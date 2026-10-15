package ai.proxyma.core.system.controller;

import ai.proxyma.core.registry.service.SyncOrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ProcessingController {

    private final SyncOrchestratorService syncOrchestratorService;

    public ProcessingController(SyncOrchestratorService syncOrchestratorService) {
        this.syncOrchestratorService = syncOrchestratorService;
    }

    @PostMapping("/pause")
    public Mono<ResponseEntity<Map<String, Object>>> pause() {
        return Mono.fromCallable(() -> {
            syncOrchestratorService.pause();
            return ResponseEntity.ok(Map.<String, Object>of("paused", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/resume")
    public Mono<ResponseEntity<Map<String, Object>>> resume() {
        return Mono.fromCallable(() -> {
            syncOrchestratorService.resume();
            return ResponseEntity.ok(Map.<String, Object>of("paused", false));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // POST /sync-now/{source} went on 2026-10-14 (platform-12): nothing called it - the page syncs one
    // source through /indexing/{id}/sync - and a mistyped source name fell through to syncing every
    // source in the deployment.
}
