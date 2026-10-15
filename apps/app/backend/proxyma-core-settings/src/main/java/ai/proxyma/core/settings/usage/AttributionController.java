package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the deployment spent, and on whose behalf.
 *
 * <p>Three questions, one table: the deployment's total, a breakdown per payer (every account, plus
 * the single row all anonymous visitors share), and a breakdown per role. The per-role split is
 * what makes an include/exclude policy possible at all - "this user's cost, not counting the
 * indexing they triggered" is a decision the reader makes, not one frozen into where the row was
 * written.
 */
@RestController
@RequestMapping("/api/v1/usage/attribution")
public class AttributionController {

    private final AttributedSpendService service;

    public AttributionController(AttributedSpendService service) {
        this.service = service;
    }

    /** Everything, for one month - defaults to this one. */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> overview(@RequestParam(required = false) String month) {
        return Mono.fromCallable(() -> {
            String m = month == null || month.isBlank() ? service.currentMonth() : month;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month", m);
            out.put("totalUsd", service.totalCost(m));
            // Called out separately as well as appearing in `subjects`, because "what are guests
            // costing us" is a question asked on its own and answering it should not require the
            // reader to know that anonymous spend is filed under one reserved id.
            out.put("anonymousUsd", service.anonymousCost(m));
            out.put("subjects", service.bySubject(m).stream().map(s -> Map.<String, Object>of(
                    "kind", s.kind().name(),
                    "id", s.id(),
                    "costUsd", s.costUsd(),
                    "inputTokens", s.inputTokens(),
                    "outputTokens", s.outputTokens(),
                    "calls", s.calls())).toList());
            out.put("roles", roles(service.byRole(m)));
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** One payer's month, split by role - the shape an include/exclude policy is applied to. */
    @GetMapping("/{kind}/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> subject(@PathVariable String kind,
                                                              @PathVariable String id,
                                                              @RequestParam(required = false) String month) {
        return Mono.fromCallable(() -> {
            SpendSubject.Kind k;
            try {
                k = SpendSubject.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Map<String, Object>>build();
            }
            String m = month == null || month.isBlank() ? service.currentMonth() : month;
            List<AttributedSpendService.RoleTotal> byRole =
                    service.forSubject(m, new SpendSubject(k, id));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month", m);
            out.put("kind", k.name());
            out.put("id", id);
            out.put("totalUsd", byRole.stream().mapToDouble(AttributedSpendService.RoleTotal::costUsd).sum());
            out.put("roles", roles(byRole));
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static List<Map<String, Object>> roles(List<AttributedSpendService.RoleTotal> totals) {
        return totals.stream().map(r -> Map.<String, Object>of(
                "role", r.role(),
                "costUsd", r.costUsd(),
                "inputTokens", r.inputTokens(),
                "outputTokens", r.outputTokens(),
                "calls", r.calls())).toList();
    }
}
