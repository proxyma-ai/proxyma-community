package ai.proxyma.core.system.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

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
            closeAndExit(context, code);
        }, "process-exit");
        t.setDaemon(false);
        t.start();
    }

    // The close on its own thread, waited for a bounded time; a halt if it overruns, since
    // System.exit would block in the very hook that is stuck.
    static void closeAndExit(ConfigurableApplicationContext context, int code) {
        Thread closer = new Thread(() -> {
            if (context != null) SpringApplication.exit(context, () -> code);
            System.exit(code);
        }, "process-exit-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(CLOSE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!closer.isAlive()) return;
        log.warn("[ProcessExit] shutdown did not finish within {}ms - halting", CLOSE_TIMEOUT_MILLIS);
        Runtime.getRuntime().halt(code);
    }
}
