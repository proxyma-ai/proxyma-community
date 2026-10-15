package ai.proxyma.core.guardrail.controller;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.guardrail.GuardrailMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * What the guardrails have been doing, and what they have been costing.
 *
 * <p>This is the half that makes monitor mode useful. A deployment can run every check without
 * applying any of it, then read here what would have been refused, how often, and how long it
 * took - which is how a similarity threshold gets chosen without experimenting on people.
 *
 * <p>Everything is in memory and resets with the process, which is stated on {@code
 * GuardrailMetrics} and repeated in the UI. The snapshot carries counts, categories and durations -
 * and, only while evidence capture is on (GuardrailEvidence), the matched spans it captured.
 *
 * <p>No {@code boundedElastic} offload on these: reading a few counters and copying a bounded
 * deque is not blocking work, and wrapping it would add a thread hand-off for nothing. The rule
 * this project follows is that the PERSISTENCE layer must not run on the event loop; this touches
 * no database.
 */
@RestController
@RequestMapping("/api/v1/guardrail/metrics")
public class GuardrailMetricsController {

    private final GuardrailMetrics metrics;
    private final boolean multiUser;

    public GuardrailMetricsController(GuardrailMetrics metrics,
                                      @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.metrics = metrics;
        this.multiUser = multiUser;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> snapshot() {
        return Mono.just(ResponseEntity.ok(metrics.snapshot()));
    }

    /** Starts a fresh window - for beginning a tuning run against a known-empty baseline. */
    @DeleteMapping
    // A Super admin's on Enterprise Server, and recorded with who did it - the window holds the
    // evidence a reset erases (GuardrailWriter).
    public Mono<ResponseEntity<Void>> reset() {
        return GuardrailWriter.of(multiUser).map(writer -> {
            if (!writer.allowed()) return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).<Void>build();
            metrics.reset();
            AuditEntry.of(AuditCategory.DATA, "guardrail-window.reset")
                    .user(writer.userId())
                    .summary("Guardrail in-memory window started fresh")
                    .record();
            return ResponseEntity.noContent().<Void>build();
        });
    }
}
