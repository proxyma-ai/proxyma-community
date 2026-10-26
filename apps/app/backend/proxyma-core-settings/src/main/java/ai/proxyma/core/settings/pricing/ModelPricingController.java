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

    /**
     * The built-in list price of a model, for the form that adds a rate: 404 when the list does not
     * know it. Every vendor is searched - the form already names the model the operator means.
     */
    @GetMapping("/list-price")
    public Mono<ResponseEntity<Map<String, Object>>> listPrice(@RequestParam String model) {
        return isAdmin().map(allowed -> {
            if (!allowed) return ResponseEntity.status(403).<Map<String, Object>>build();
            return modelPricingService.listPrices().findAnyVendor(model)
                    .map(entry -> ResponseEntity.ok(listPriceMap(entry, modelPricingService.listPrices().asOf())))
                    .orElse(ResponseEntity.notFound().build());
        });
    }

    private static Map<String, Object> listPriceMap(ListPrices.Entry entry, String asOf) {
        RateCard r = entry.rates();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendor", entry.vendor());
        m.put("asOf", asOf);
        m.put("inputPricePerMillion", r.input());
        m.put("outputPricePerMillion", r.output());
        m.put("cacheReadPricePerMillion", r.cacheRead());
        m.put("cacheWritePricePerMillion", r.cacheWrite());
        m.put("cacheWriteLongPricePerMillion", r.cacheWriteLong());
        m.put("longContextThresholdTokens", r.longContextTokens() > 0 ? r.longContextTokens() : null);
        m.put("longInputPricePerMillion", r.longInput());
        m.put("longOutputPricePerMillion", r.longOutput());
        m.put("longCacheReadPricePerMillion", r.longCacheRead());
        m.put("longCacheWritePricePerMillion", r.longCacheWrite());
        m.put("pricingSchedule", r.schedule() == PricingSchedule.NONE ? null : r.schedule().name());
        return m;
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
                                                            ServerWebExchange exchange) {
        return userId(exchange).flatMap(uid -> isSuperAdmin().flatMap(allowed -> allowed
                ? Mono.fromCallable(() -> {
                        String provider = name(body, "provider");
                        String model = name(body, "model");
                        ModelPricing saved = modelPricingService.save(provider, model, rates(body));
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
                        var updated = modelPricingService.update(id, rates(body));
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
                .detail("cacheWriteOneHourPerMillion", p.getCacheWriteLongPricePerMillion())
                .detail("longContextThresholdTokens", p.getLongContextThresholdTokens())
                .detail("longInputPerMillion", p.getLongInputPricePerMillion())
                .detail("longOutputPerMillion", p.getLongOutputPricePerMillion())
                .detail("pricingSchedule", p.getPricingSchedule())
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

    // Every rate a row carries. The four basic ones are required, as they always were; the rest are
    // optional, so a client that does not know them (an older page, a script) leaves them unset.
    static ModelPricingService.RateInput rates(Map<String, Object> body) {
        Double threshold = optionalNumber(body, "longContextThresholdTokens");
        Object schedule = body.get("pricingSchedule");
        return new ModelPricingService.RateInput(
                numberOf(body, "inputPricePerMillion"),
                numberOf(body, "outputPricePerMillion"),
                numberOf(body, "cacheReadPricePerMillion"),
                numberOf(body, "cacheWritePricePerMillion"),
                optionalNumber(body, "cacheWriteLongPricePerMillion"),
                threshold == null ? null : (long) Math.floor(threshold),
                optionalNumber(body, "longInputPricePerMillion"),
                optionalNumber(body, "longOutputPricePerMillion"),
                optionalNumber(body, "longCacheReadPricePerMillion"),
                optionalNumber(body, "longCacheWritePricePerMillion"),
                schedule == null ? null : String.valueOf(schedule));
    }

    // An optional rate: absent, null or blank is "not given"; anything else is checked as numberOf does.
    static Double optionalNumber(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        return numberOf(body, key);
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
        m.put("cacheWriteLongPricePerMillion", p.getCacheWriteLongPricePerMillion());
        m.put("longContextThresholdTokens", p.getLongContextThresholdTokens());
        m.put("longInputPricePerMillion", p.getLongInputPricePerMillion());
        m.put("longOutputPricePerMillion", p.getLongOutputPricePerMillion());
        m.put("longCacheReadPricePerMillion", p.getLongCacheReadPricePerMillion());
        m.put("longCacheWritePricePerMillion", p.getLongCacheWritePricePerMillion());
        m.put("pricingSchedule", p.getPricingSchedule());
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
