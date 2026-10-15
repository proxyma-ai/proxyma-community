package ai.proxyma.desktop.system;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Reset endpoints for the desktop edition.
 *
 * Deliberately the same paths and the same confirmation phrases as the server's
 * SystemResetController - {@code /api/v1/system/reset} and {@code /api/v1/system/reset/settings} -
 * so the frontend calls one API in both editions and systemApi needs no branch. The two
 * controllers never coexist: the server one is in proxyma-core-system-admin, which only builds
 * under the server Maven profile, and this one is in proxyma-desktop.
 *
 * There is no {@code /reset/users} here. Desktop has no accounts, so it would be an endpoint whose
 * only honest implementation is to do nothing.
 *
 * No role check, because there are no roles: a desktop install is one local user with no auth, and
 * the confirmation phrase is what stands between a stray request and a wipe. That phrase is checked
 * here as well as in the UI, so a request that arrives from anywhere else still has to mean it.
 */
@RestController
@RequestMapping("/api/v1/system")
public class DesktopResetController {

    private static final String CONFIRMATION_PHRASE = "RESET EVERYTHING";
    private static final String SETTINGS_CONFIRMATION_PHRASE = "RESET SETTINGS";

    @FunctionalInterface
    private interface ResetOp {
        void run() throws Exception;
    }

    private final DesktopResetService resetService;

    public DesktopResetController(DesktopResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/reset")
    public Mono<ResponseEntity<Map<String, String>>> reset(@RequestBody Map<String, String> body) {
        return runReset(body, CONFIRMATION_PHRASE, resetService::resetEverything);
    }

    @PostMapping("/reset/settings")
    public Mono<ResponseEntity<Map<String, String>>> resetSettings(@RequestBody Map<String, String> body) {
        return runReset(body, SETTINGS_CONFIRMATION_PHRASE, resetService::resetSettings);
    }

    private Mono<ResponseEntity<Map<String, String>>> runReset(Map<String, String> body,
                                                               String confirmationPhrase, ResetOp op) {
        if (!confirmationPhrase.equals(body.get("confirmation"))) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "Confirmation phrase does not match")));
        }
        // The reset schedules its own exit, so this response has to be written first - it is the
        // client's only signal that the request was accepted, since the connection dies moments
        // later and the frontend then waits for the app to come back.
        return Mono.fromCallable(() -> {
            try {
                op.run();
                return ResponseEntity.ok(Map.of("status", "resetting"));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of(
                        "error", e.getMessage() != null ? e.getMessage() : "Reset failed"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
