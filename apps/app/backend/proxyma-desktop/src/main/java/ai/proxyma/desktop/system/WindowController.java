package ai.proxyma.desktop.system;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/desktop/window} - opens Proxyma's window, or brings it forward.
 *
 * <p>For ProxymaLauncher started a second time - from the Start menu, a shortcut - while Proxyma
 * runs. It used to start the window itself with the bare address, which since the access code
 * (AccessGate) would have opened onto the access screen; asking the running app means the window is
 * opened by the one process that holds the code. Behind the access code like every other call: the
 * launcher sends the install's token.
 */
@RestController
@RequestMapping("/api/v1/desktop")
public class WindowController {

    private final DesktopIntegrationService integration;

    public WindowController(DesktopIntegrationService integration) {
        this.integration = integration;
    }

    @PostMapping("/window")
    public ResponseEntity<Void> open() {
        integration.openWindow();
        return ResponseEntity.accepted().build();
    }
}
