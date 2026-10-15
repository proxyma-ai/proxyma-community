package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Admin-only CRUD over per provider+model $ pricing rows, consumed by ModelPricingService for
// per-conversation cost estimation (see that class's javadoc). ADMIN+SUPER_ADMIN-gated on every
// endpoint, same as every other system-config settings prefix (see SettingsController) - see
// ChatSystemPromptController for the reference pattern this duplicates (no shared helper for
// this check exists anywhere in the codebase yet).
@RestController
@RequestMapping("/api/v1/admin/model-pricing")
public class ModelPricingController {

    private final ModelPricingService modelPricingService;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public ModelPricingController(ModelPricingService modelPricingService) {
        this.modelPricingService = modelPricingService;
    }

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> list() {
        return isAdmin().flatMap(allowed -> allowed
                ? Mono.fromCallable(modelPricingService::listAll)
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(rows -> ResponseEntity.ok(rows.stream().map(ModelPricingController::toMap).toList()))
                : Mono.just(ResponseEntity.status(403).build()));
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
                                                            ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> isSuperAdmin().flatMap(allowed -> allowed
                ? Mono.fromCallable(() -> {
                        String provider = name(body, "provider");
                        String model = name(body, "model");
                        double input = numberOf(body, "inputPricePerMillion");
                        double output = numberOf(body, "outputPricePerMillion");
                        double cacheRead = numberOf(body, "cacheReadPricePerMillion");
                        double cacheWrite = numberOf(body, "cacheWritePricePerMillion");
                        ModelPricing saved = modelPricingService.create(provider, model, input, output, cacheRead, cacheWrite);
                        audit("pricing.changed", saved, uid);
                        return saved;
                    }).subscribeOn(Schedulers.boundedElastic())
                        .map(pricing -> ResponseEntity.ok(toMap(pricing)))
                : Mono.just(ResponseEntity.status(403).build())));
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable UUID id, @RequestBody Map<String, Object> body,
                                                            ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> isSuperAdmin().flatMap(allowed -> allowed
                ? Mono.fromCallable(() -> {
                        double input = numberOf(body, "inputPricePerMillion");
                        double output = numberOf(body, "outputPricePerMillion");
                        double cacheRead = numberOf(body, "cacheReadPricePerMillion");
                        double cacheWrite = numberOf(body, "cacheWritePricePerMillion");
                        var updated = modelPricingService.update(id, input, output, cacheRead, cacheWrite);
                        updated.ifPresent(pricing -> audit("pricing.changed", pricing, uid));
                        return updated;
                    }).subscribeOn(Schedulers.boundedElastic())
                        .map(result -> result.map(pricing -> ResponseEntity.ok(toMap(pricing)))
                                .orElse(ResponseEntity.notFound().build()))
                : Mono.just(ResponseEntity.status(403).build())));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id, ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> isSuperAdmin().flatMap(allowed -> allowed
                ? Mono.fromCallable(() -> {
                            // Read first: the entry names the provider and model, which the id alone does not.
                            ModelPricing row = modelPricingService.get(id).orElse(null);
                            boolean deleted = modelPricingService.delete(id);
                            if (deleted && row != null) {
                                AuditEntry.of(AuditCategory.CONFIG, "pricing.removed")
                                        .user(uid)
                                        .target(row.getProvider() + " / " + row.getModel())
                                        .summary("Model price removed: " + row.getProvider() + " / " + row.getModel())
                                        .record();
                            }
                            return deleted;
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(deleted -> deleted
                                ? ResponseEntity.noContent().<Void>build()
                                : ResponseEntity.notFound().<Void>build())
                : Mono.just(ResponseEntity.status(403).<Void>build())));
    }

    // A rate decides what every later call costs against a spending limit, so each change is kept.
    private static void audit(String action, ModelPricing p, String uid) {
        AuditEntry.of(AuditCategory.CONFIG, action)
                .user(uid)
                .target(p.getProvider() + " / " + p.getModel())
                .summary("Model price set: " + p.getProvider() + " / " + p.getModel())
                .detail("inputPerMillion", p.getInputPricePerMillion())
                .detail("outputPerMillion", p.getOutputPricePerMillion())
                .detail("cacheReadPerMillion", p.getCacheReadPricePerMillion())
                .detail("cacheWritePerMillion", p.getCacheWritePricePerMillion())
                .record();
    }

    // "" when there is no principal (Desktop, Home Server), which the audit log files as the local user.
    private static Mono<String> userId(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }

    // A rate is a finite number of dollars, never below zero (settings-core-26): "NaN", "Infinity" and
    // negatives parsed as rates, and a NaN or negative spend defeats every budget comparison it
    // reaches. Refused as a bad request that names the field.
    static double numberOf(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        double value;
        try {
            // Jackson deserializes a JSON number into Map<String,Object> as Double, not
            // Integer/Long - never cast directly, always go through Number.
            value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number.");
        }
        if (!Double.isFinite(value) || value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be zero or more.");
        }
        return value;
    }

    // A provider and a model are named; String.valueOf(null) stored the word "null".
    static String name(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required.");
        }
        return String.valueOf(raw).trim();
    }

    private static Map<String, Object> toMap(ModelPricing p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId().toString());
        m.put("provider", p.getProvider());
        m.put("model", p.getModel());
        m.put("inputPricePerMillion", p.getInputPricePerMillion());
        m.put("outputPricePerMillion", p.getOutputPricePerMillion());
        m.put("cacheReadPricePerMillion", p.getCacheReadPricePerMillion());
        m.put("cacheWritePricePerMillion", p.getCacheWritePricePerMillion());
        m.put("createdAt", p.getCreatedAt().toString());
        m.put("updatedAt", p.getUpdatedAt().toString());
        return m;
    }

    // Per-model $ pricing is deployment-wide system configuration (the Cost Settings tab), so it
    // follows the same split SettingsController applies to every other system-config surface: an
    // ADMIN reads it - they need it to interpret the spend figures on the Administration page -
    // and only a SUPER_ADMIN changes it. Editing a rate here silently rewrites what every user's
    // usage costs against their quota, which is not a per-user administrative act.
    private Mono<Boolean> isAdmin() {
        return ai.proxyma.core.settings.access.CallerRoles.isAdmin(multiUser);
    }

    private Mono<Boolean> isSuperAdmin() {
        return ai.proxyma.core.settings.access.CallerRoles.isSuperAdmin(multiUser);
    }
}
