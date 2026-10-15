package ai.proxyma.core.system.controller;

import ai.proxyma.core.registry.service.SyncOrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final SyncOrchestratorService syncOrchestratorService;

    public StatusController(SyncOrchestratorService syncOrchestratorService) {
        this.syncOrchestratorService = syncOrchestratorService;
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getStatus() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(Map.<String, Object>of("paused", syncOrchestratorService.isPaused())))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
