package ai.proxyma.core.update;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;
import java.util.Map;

// Not on Enterprise Server - see UpdateService.
@RestController
@RequestMapping("/api/v1")
@Profile("!server")
public class UpdateController {

    private final UpdateService updateService;
    private final UpdateHistory updateHistory;

    public UpdateController(UpdateService updateService, UpdateHistory updateHistory) {
        this.updateService = updateService;
        this.updateHistory = updateHistory;
    }

    @GetMapping("/update/status")
    public Mono<ResponseEntity<Map<String, Object>>> getStatus() {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of(
                "status",           updateService.getStatus().name(),
                "availableVersion", updateService.getAvailableVersion() != null
                        ? updateService.getAvailableVersion() : "",
                "currentVersion",   updateService.getCurrentVersion(),
                // Empty rather than absent, both of them: the page renders "never" and "no error"
                // from an empty string, and a missing key would have to be told apart from a null
                // one in the client for no gain.
                "lastCheckAt",      updateService.getLastCheckAt() != null
                        ? updateService.getLastCheckAt().toString() : "",
                "lastError",        updateService.getLastError() != null
                        ? updateService.getLastError() : "",
                // Whether this platform can install over itself at all. The page reads this rather
                // than sniffing the user agent: Linux and macOS download the new release instead,
                // and a disabled Apply button with no explanation is worse than not offering one.
                "canApplyInPlace",  updateService.canApplyInPlace(),
                "installKind",      updateService.getInstallKind(),
                // Progress of a DOWNLOADING status: bytes so far, the size the server announced (0 if
                // it did not), and whether it is still transferring or already verifying/unpacking.
                // Sent always rather than only while downloading, so the shape never changes.
                "downloadedBytes",    updateService.getDownloadedBytes(),
                "downloadTotalBytes", updateService.getDownloadTotalBytes(),
                "downloadStage",      updateService.getDownloadStage().name()
        )));
    }

    /**
     * A check the user asked for.
     *
     * <p>On boundedElastic because it makes an outbound HTTPS request and this is a WebFlux
     * controller - blocking the event loop on GitHub's latency would stall every other request in
     * the process, which on desktop is the entire application.
     */
    @PostMapping("/update/check")
    public Mono<ResponseEntity<Void>> checkNow() {
        return Mono.fromRunnable(updateService::checkNow)
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok().<Void>build());
    }

    /**
     * Start downloading what the last check found - for an install with auto-update off. Returns as
     * soon as the download has STARTED; its progress and outcome are read from /update/status.
     */
    @PostMapping("/update/download")
    public Mono<ResponseEntity<Void>> download() {
        return Mono.fromRunnable(updateService::downloadNow)
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok().<Void>build());
    }

    /** Stop the running download. 409 when there is none, so a late click is not an error toast. */
    @PostMapping("/update/cancel")
    public Mono<ResponseEntity<Void>> cancel() {
        return Mono.fromCallable(() -> {
            try {
                updateService.cancelDownload();
                return ResponseEntity.ok().<Void>build();
            } catch (IllegalStateException e) {
                return ResponseEntity.status(409).<Void>build();
            }
        });
    }

    @GetMapping("/update/history")
    public Mono<ResponseEntity<List<UpdateHistory.Entry>>> history() {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateHistory.recent(50)));
    }

    /** Forget the update history. A file on disk, so it runs off the event loop. */
    @DeleteMapping("/update/history")
    public Mono<ResponseEntity<Void>> clearHistory(ServerWebExchange exchange) {
        // "" when there is no principal (Desktop, Home Server), which the audit log files as the local user.
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("")
                .flatMap(uid -> Mono.fromCallable(() -> {
                    updateHistory.clear();
                    AuditEntry.of(AuditCategory.DATA, "update-history.cleared")
                            .user(uid)
                            .summary("Update history cleared")
                            .record();
                    return ResponseEntity.noContent().<Void>build();
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/update/apply")
    public Mono<ResponseEntity<Void>> applyUpdate() {
        return Mono.fromRunnable(updateService::applyAndRestart)
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok().<Void>build());
    }
}
