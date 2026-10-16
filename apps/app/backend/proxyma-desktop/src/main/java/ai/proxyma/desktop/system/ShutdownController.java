package ai.proxyma.desktop.system;

import ai.proxyma.core.system.util.ProcessExit;
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

    private final ConfigurableApplicationContext context;

    public ShutdownController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * Closes the context, then ends the process - and ends it either way, through ProcessExit.
     *
     * Exit code 0, never 3: the launcher treats 3 as "start me again" and would loop straight back
     * into starting the application the user just asked to close.
     *
     * THIS HAD A BOUNDED CLOSE OF ITS OWN, written after a quit on 2026-09-16 left a process that
     * never ended - the UI said goodbye, the window closed, and the next launch was refused by the
     * single-instance lock the dead-looking process still held. ProcessExit was later made from it
     * for the restarts, and since the 0.36.0 feedback this path uses it too, so a quit and a restart
     * close the same way: the context closed on a thread of its own, the live threads logged and
     * the process halted if that overruns.
     */
    @PostMapping("/quit")
    public Mono<ResponseEntity<Void>> quit() {
        return Mono.fromRunnable(() -> ProcessExit.exitShortly(context, 0, "Quit requested from the UI"))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
