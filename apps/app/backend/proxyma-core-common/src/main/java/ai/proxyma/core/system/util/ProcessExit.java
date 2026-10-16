package ai.proxyma.core.system.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends this process a moment from now - and ends it either way.
 *
 * <p>ONE COPY OF A RESTART THAT WAS WRITTEN FOUR TIMES (platform-10): the restart endpoint, the
 * server resets, Apply update and Desktop's reset each started a thread that slept and called
 * {@code System.exit(3)}. {@code System.exit} runs the shutdown hooks, Spring's closes the context,
 * and one bean that blocks in {@code @PreDestroy} then leaves a process that never ends - observed
 * twice on 2026-09-16 on the quit path (ShutdownController), which is why that path closes the
 * context on a thread of its own with a time limit and halts when it runs over. Every restart gets
 * the same protection here.
 *
 * <p>EVERY EXIT, since the 0.36.0 feedback. Apply update, Desktop's reset and the tray's Quit still
 * called {@code System.exit} bare, and the UI's Quit had a copy of this class of its own; all of
 * them come through here now, so they close the same way and are bounded the same way.
 *
 * <p>Exit code 3 means "start me again": the Desktop launcher restarts on it, and the server's
 * container restarts on any exit ({@code restart: unless-stopped}).
 */
public final class ProcessExit {

    private static final Logger log = LoggerFactory.getLogger(ProcessExit.class);

    /** The launcher's and the container's "start me again". */
    public static final int RESTART = 3;

    // Long enough for the response that announced it to reach the browser.
    private static final long GRACE_MILLIS = 500;
    // More than a graceful shutdown takes, and less than a person waits before deciding it hung.
    private static final long CLOSE_TIMEOUT_MILLIS = 15_000;
    // What the closes registered with beforeHalt may take, together, before the halt.
    private static final long BEFORE_HALT_MILLIS = 3_000;

    // WHAT A HALT WOULD OTHERWISE LEAVE UNCLOSED, since the 0.36.0 feedback. Desktop's H2 databases
    // no longer close themselves in H2's own shutdown hook (DB_CLOSE_ON_EXIT=FALSE - it closed them
    // under work still running), so they close when Spring closes their pools; and a halt is exactly
    // the case where Spring did not get that far. A halted H2 loses the writes of its last second
    // and is recovered on the next open. Keyed by name, so a context started again replaces its own.
    private static final Map<String, Runnable> BEFORE_HALT = new ConcurrentHashMap<>();

    // How the process ends - replaced only by a test, which cannot let the JVM go.
    interface Jvm {
        void exit(int code);
        void halt(int code);
    }

    private static final Jvm REAL = new Jvm() {
        @Override public void exit(int code) { System.exit(code); }
        @Override public void halt(int code) { Runtime.getRuntime().halt(code); }
    };

    private ProcessExit() {}

    /** Restarts shortly, on a thread of its own; returns at once. */
    public static void restartShortly(ConfigurableApplicationContext context, String why) {
        exitShortly(context, RESTART, why);
    }

    /** Ends the process with {@code code} shortly, on a thread of its own; returns at once. */
    public static void exitShortly(ConfigurableApplicationContext context, int code, String why) {
        log.info("[ProcessExit] {} - exiting with {} shortly", why, code);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeAndExit(context, code, CLOSE_TIMEOUT_MILLIS, REAL);
        }, "process-exit");
        t.setDaemon(false);
        t.start();
    }

    /**
     * Registers what must be closed before a halt, under {@code name} (a second registration under
     * the same name replaces the first). It runs only when the context's close overran and the
     * process is about to be halted, on a thread of its own, and is given a few seconds at most - so
     * it must be safe to run beside a close that is still under way, and must not need Spring.
     */
    public static void beforeHalt(String name, Runnable close) {
        BEFORE_HALT.put(name, close);
    }

    // The close on its own thread, waited for a bounded time; a halt if it overruns, since
    // System.exit would block in the very hook that is stuck.
    static void closeAndExit(ConfigurableApplicationContext context, int code, long timeoutMillis, Jvm jvm) {
        Thread closer = new Thread(() -> {
            if (context != null) SpringApplication.exit(context, () -> code);
            jvm.exit(code);
        }, "process-exit-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!closer.isAlive()) return;
        // WITHOUT THE THREADS A HALT HIDES ITS OWN CAUSE: the 2026-09-16 hang left a log that stopped
        // mid-shutdown and said nothing about why.
        log.warn("[ProcessExit] shutdown did not finish within {}ms - halting. Threads still running:{}",
                timeoutMillis, liveThreads());
        runBeforeHalt(BEFORE_HALT_MILLIS);
        jvm.halt(code);
    }

    // Every registered close at once, each on a daemon thread, all waited for until one deadline.
    static void runBeforeHalt(long budgetMillis) {
        List<Thread> running = new ArrayList<>();
        BEFORE_HALT.forEach((name, close) -> {
            Thread t = new Thread(() -> {
                try {
                    close.run();
                } catch (RuntimeException e) {
                    log.warn("[ProcessExit] '{}' could not be closed before the halt: {}", name, e.getMessage());
                }
            }, "process-exit-" + name);
            t.setDaemon(true);
            t.start();
            running.add(t);
        });
        long deadline = System.currentTimeMillis() + budgetMillis;
        for (Thread t : running) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                t.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // Test seam: what is registered now.
    static void clearBeforeHalt() {
        BEFORE_HALT.clear();
    }

    /**
     * The threads still alive when a shutdown overruns, by name and state and the frame each is in.
     * Daemon threads are skipped: they cannot hold the JVM open, so listing them buries the ones
     * that can under the pools - except the closer itself, which is daemon and is the one stuck.
     */
    static String liveThreads() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t == Thread.currentThread()) continue;
            if (t.isDaemon() && !"process-exit-close".equals(t.getName())) continue;
            sb.append("\n    ").append(t.getName()).append(' ').append(t.getState());
            StackTraceElement[] stack = e.getValue();
            for (int i = 0; i < Math.min(stack.length, 4); i++) sb.append("\n        at ").append(stack[i]);
        }
        return sb.isEmpty() ? " (none - the blocker is not a thread this can see)" : sb.toString();
    }
}
