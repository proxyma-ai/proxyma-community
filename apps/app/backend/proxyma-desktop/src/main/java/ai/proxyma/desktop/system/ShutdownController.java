package ai.proxyma.desktop.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Lets the user quit Proxyma from the UI.
 *
 * WHY THIS EXISTS. The only way to stop a desktop install used to be the system tray's Exit item,
 * and AWT's SystemTray is not supported on GNOME or under Wayland - {@code SystemTray.isSupported()}
 * returns false and DesktopIntegrationService quietly installs nothing. That left a Linux user with
 * a running application, a browser tab, and no offered way to stop either. The tray is still
 * installed wherever it works; this is the way out that does not depend on it.
 *
 * It is equally the answer on Windows to the tray failing to install for any other reason, which
 * was always possible and simply had no fallback.
 *
 * DESKTOP ONLY, and deliberately in proxyma-desktop rather than a proxyma-core-* module - the same
 * boundary LocalPathController observes. An endpoint that halts the process with no role check -
 * only the install's access code, like every call here (AccessGate) - is exactly right for a
 * single-user application the user is sitting in front of, and exactly wrong for a shared server:
 * proxyma-server does not build this class at all, so it cannot be exposed there by a
 * configuration mistake.
 */
@RestController
@RequestMapping("/api/v1/desktop")
public class ShutdownController {

    private static final Logger log = LoggerFactory.getLogger(ShutdownController.class);

    // Long enough for the 202 to reach the browser and be rendered, short enough that the user does
    // not wonder whether the click registered.
    private static final long GRACE_MILLIS = 400;

    /**
     * How long the whole context close may take before the process is halted anyway.
     *
     * Comfortably more than the 5s graceful-shutdown phase configured in
     * application-desktop.properties plus a Hikari drain and ChromaDB's own stop, and well under
     * what a person will sit through before deciding the application has frozen. The point is not
     * to pick the perfect number - it is that SOME number exists, where before there was none.
     */
    private static final long CLOSE_TIMEOUT_MILLIS = 15_000;

    private final ConfigurableApplicationContext context;

    public ShutdownController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/quit")
    public Mono<ResponseEntity<Void>> quit() {
        return Mono.fromRunnable(this::shutdownShortly)
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    private void shutdownShortly() {
        log.info("[Desktop] Quit requested from the UI - shutting down");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeAndExit();
        }, "desktop-quit");
        t.setDaemon(false);
        t.start();
    }

    /**
     * Closes the context, then ends the process - and ends it either way.
     *
     * THIS USED TO BE TWO STATEMENTS AND IT COULD LEAVE A ZOMBIE. {@code SpringApplication.exit}
     * closes the context synchronously with no bound of its own, so a single bean that blocks in
     * {@code @PreDestroy} means the {@code System.exit} on the next line is never reached. What the
     * user sees then is the worst version of this: the UI says goodbye, the window closes, the
     * process stays, and the NEXT launch is refused by the single-instance lock the dead-looking
     * app is still holding. The only way out is Task Manager. Observed on 2026-09-16, twice.
     *
     * So the close runs on its own thread and this one waits a bounded time for it. If it overruns
     * we halt rather than exit: {@code System.exit} runs shutdown hooks, and Spring's own hook
     * closes the very context that is already stuck, so exit would block in the same place. Halting
     * skips every hook - which is why the close is attempted first and given time to do its work.
     *
     * A hard stop loses whatever the stuck bean had not finished. That is a real cost and it is the
     * lesser one: ChromaDB is stopped by the launcher's own watchdog if we do not stop it here, and
     * an index is rebuilt from source by design, while a process that cannot be quit or restarted
     * has no recovery a normal user will find.
     */
    private void closeAndExit() {
        Thread closer = new Thread(() -> {
            // Closing explicitly is what lets ChromaStoreService's @PreDestroy stop the managed
            // ChromaDB child before the process goes away.
            // Exit code 0, never 3. The launcher treats 3 as "an update was staged, restart me" and
            // would loop straight back into starting the application the user just asked to close.
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }, "desktop-quit-close");
        closer.setDaemon(true);
        closer.start();

        try {
            closer.join(CLOSE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!closer.isAlive()) return;   // the close finished and System.exit is on its way

        log.warn("[Desktop] Shutdown did not finish within {}ms - halting. Threads still running:{}",
                CLOSE_TIMEOUT_MILLIS, liveThreads());
        Runtime.getRuntime().halt(0);
    }

    /**
     * The threads still alive when a shutdown overruns, for the log line above.
     *
     * WITHOUT THIS A HALT HIDES ITS OWN CAUSE. The evidence left by the 2026-09-16 hang was a log
     * that stopped mid-shutdown and said nothing at all about why, which is the same evidence a
     * silent halt would leave every time from now on - a bug fixed into invisibility rather than
     * fixed. Names and states are enough to point at the bean on the next occurrence.
     *
     * Daemon threads are skipped: they cannot hold the JVM open, so listing them buries the ones
     * that can under the pools.
     */
    private static String liveThreads() {
        StringBuilder sb = new StringBuilder();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isDaemon() || t == Thread.currentThread()) continue;
            sb.append("\n    ").append(t.getName()).append(' ').append(t.getState());
            StackTraceElement[] stack = t.getStackTrace();
            if (stack.length > 0) sb.append(" at ").append(stack[0]);
        }
        return sb.isEmpty() ? " (none - the blocker is not a thread this can see)" : sb.toString();
    }
}
