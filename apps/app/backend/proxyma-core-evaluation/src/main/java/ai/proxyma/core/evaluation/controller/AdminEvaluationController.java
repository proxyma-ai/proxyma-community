package ai.proxyma.core.evaluation.controller;

import ai.proxyma.core.evaluation.repository.ChatTurnMetricRepository;
import ai.proxyma.core.evaluation.repository.SearchStageMetricRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The latency figures System > Performance shows - chat turns and searches, counted and averaged
// over a window - and the same figures as a download. Aggregates only: nothing here is per
// conversation, per user or per message, and nothing here ever was since the conversation-content
// capture was removed (2026-08-08).
//
// Administrators only. The blanket "/api/v1/admin/**" -> hasAnyRole(SUPER_ADMIN, ADMIN) matcher in
// SecurityConfig already says so, and guard() below checks the role claim again in code - the same
// pattern AdminUserController uses under /admin/**. It allowed SUPER_ADMIN alone, so an ADMIN, who
// is shown the page, was refused every time; and it also required the retired evaluation.enabled
// toggle, which nothing sets any more, so the page never loaded for anybody. (Desktop mode has no
// roles and no JWTs at all, so the role check collapses to true there - see callerIsAdministrator().)
@RestController
@RequestMapping("/api/v1/admin/evaluation")
public class AdminEvaluationController {

    private final ChatTurnMetricRepository chatTurnMetricRepository;
    private final SearchStageMetricRepository searchStageMetricRepository;
    private final ObjectMapper objectMapper;

    public AdminEvaluationController(ChatTurnMetricRepository chatTurnMetricRepository,
                                     SearchStageMetricRepository searchStageMetricRepository,
                                     ObjectMapper objectMapper) {
        this.chatTurnMetricRepository = chatTurnMetricRepository;
        this.searchStageMetricRepository = searchStageMetricRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/overview")
    public Mono<ResponseEntity<Map<String, Object>>> overview(
            @RequestParam(defaultValue = "7d") String since, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            ResponseEntity<Map<String, Object>> guard = guard(exchange);
            if (guard != null) return guard;
            return ResponseEntity.ok(buildOverview(resolveSince(since), since));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Aggregate-only export - the overview tiles as a file download, nothing per-conversation or
    // per-message. First CSV export anywhere in this codebase, so hand-rolled rather than
    // following an existing convention.
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(defaultValue = "7d") String since,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            ResponseEntity<byte[]> guard = guard(exchange);
            if (guard != null) return guard;

            Map<String, Object> overview = buildOverview(resolveSince(since), since);

            if ("csv".equalsIgnoreCase(format)) {
                byte[] csv = buildCsv(overview).getBytes(StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evaluation-export.csv\"")
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .body(csv);
            }
            byte[] json = objectMapper.writeValueAsBytes(Map.of("overview", overview));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evaluation-export.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Returns null when the caller may proceed; otherwise the 403 to return as-is. Generic so
    // each endpoint above can assign it directly to its own response-body type without a cast.
    private <T> ResponseEntity<T> guard(ServerWebExchange exchange) {
        return callerIsAdministrator(exchange) ? null : ResponseEntity.status(403).build();
    }

    // Desktop mode has no roles, no sign-in and no SecurityWebFilterChain at all (proxyma-core-auth
    // is server-profile-only), so no request there ever carries a JWT and a role check would 403
    // every caller with no bypass. Desktop is single-user by design and its only user IS the
    // administrator, so the role check collapses to true - exactly the same proxyma.multi-user
    // carve-out SettingsController's isSuperAdmin() makes, and for the same reason.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    private boolean callerIsAdministrator(ServerWebExchange exchange) {
        if (!multiUser) return true;
        JWTClaimsSet claims = exchange.getAttribute(USER_CLAIMS_KEY);
        if (claims == null) return false;
        try { return ADMIN_ROLES.contains(claims.getStringClaim("role")); }
        catch (Exception e) { return false; }
    }

    // Both must match proxyma-core-auth exactly (JwtAuthFilter.USER_CLAIMS_KEY and the Role
    // names) - inlined rather than importing those types so this module has no compile-time
    // dependency on the server-profile-only auth module, only a wire-level contract. Same
    // technique, and same reason, as FileController.USER_CLAIMS_KEY in proxyma-core-registry.
    private static final String USER_CLAIMS_KEY = "PROXYMA_USER_CLAIMS";
    private static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "ADMIN");

    private Instant resolveSince(String since) {
        Duration window = switch (since) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            default -> Duration.ofDays(7); // "7d" and any unrecognized value
        };
        return Instant.now().minus(window);
    }

    // Two queries, one per table - it was seventeen, three of them over chat's own tables for
    // figures the page never showed, read through chat's repositories from this module.
    private Map<String, Object> buildOverview(Instant since, String sinceLabel) {
        Object[] chat = firstRow(chatTurnMetricRepository.summarySince(since), 5);
        Object[] search = firstRow(searchStageMetricRepository.summarySince(since), 10);

        long chatTurnCount = longOf(chat[0]);
        long chatErrorCount = longOf(chat[1]);
        double errorRate = chatTurnCount > 0 ? (double) chatErrorCount / chatTurnCount : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("since", sinceLabel);
        body.put("chatTurnCount", chatTurnCount);
        body.put("avgTimeToFirstTokenMs", doubleOf(chat[2]));
        body.put("avgTotalDurationMs", doubleOf(chat[3]));
        body.put("errorRate", errorRate);
        body.put("avgRetryCount", doubleOf(chat[4]));
        body.put("searchCount", longOf(search[0]));
        body.put("avgExpansionMs", doubleOf(search[1]));
        body.put("avgDenseMs", doubleOf(search[2]));
        body.put("avgBm25Ms", doubleOf(search[3]));
        body.put("avgRerankMs", doubleOf(search[4]));
        body.put("avgSearchTotalMs", doubleOf(search[5]));
        body.put("graphSearchCount", longOf(search[6]));
        body.put("avgGraphMs", doubleOf(search[7]));
        body.put("avgSearchTotalMsWithGraph", doubleOf(search[8]));
        body.put("avgSearchTotalMsWithoutGraph", doubleOf(search[9]));
        return body;
    }

    // An aggregate over no rows is still one row; an empty list only if the driver says otherwise.
    static Object[] firstRow(List<Object[]> rows, int width) {
        return rows == null || rows.isEmpty() || rows.get(0) == null ? new Object[width] : rows.get(0);
    }

    static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    static double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String buildCsv(Map<String, Object> overview) {
        StringBuilder sb = new StringBuilder();
        sb.append("section,key,value\n");
        for (Map.Entry<String, Object> e : overview.entrySet()) {
            sb.append("overview,").append(csvField(e.getKey())).append(',')
                    .append(csvField(String.valueOf(e.getValue()))).append('\n');
        }
        return sb.toString();
    }

    // RFC 4180-style quoting: only wraps a field in quotes (doubling any embedded quotes) when it
    // actually contains a comma/quote/newline, so the common case stays readable unquoted.
    private static String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
