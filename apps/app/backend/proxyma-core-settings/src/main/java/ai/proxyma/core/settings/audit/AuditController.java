package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Logs page: Settings > Logs on Desktop and Home Server, Administration > Logs on Enterprise
 * Server (named Audit Logs until 2026-10-14). The LLM call log beside it is
 * {@code /api/v1/usage/calls}.
 *
 * <p>WHO. Desktop and Home Server: the one local user, who may also clear a tab. Enterprise Server:
 * an administrator, who reads everyone's entries and clears nothing - retention is the only thing
 * that removes a row there, since a log its readers can empty records nothing they would not want
 * it to. SecurityConfig refuses anyone else before this is reached; the check below is the second
 * lock on the same door.
 *
 * <p>A TOOL ENTRY'S DETAIL - the call's arguments and its result - is a Super admin's on Enterprise
 * Server (settings-core-11). A search tool's result is passages from its caller's own private
 * sources, which nothing else lets an Admin read: sources are their owner's, and cross-user
 * conversation content is Super admin only (Evaluation). An Admin still sees every row, its summary
 * and timing, without the detail.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;
    /** Durations the percentiles are taken from - the newest this many in the window. */
    private static final int LATENCY_SAMPLE = 5_000;

    /** The page's tabs, each a set of categories. LLM calls are not here; they have a table of their own. */
    static final Map<String, List<String>> TABS = Map.of(
            "TOOL", List.of(AuditCategory.TOOL),
            "GUARDRAIL", List.of(AuditCategory.GUARDRAIL),
            "DIAGNOSTICS", List.of(AuditCategory.DIAGNOSTICS),
            "ACTIVITY", AuditCategory.ACTIVITY);

    static final Map<String, String> SORTABLE = Map.of(
            "time", "occurredAt",
            "category", "category",
            "action", "action",
            "outcome", "outcome",
            "target", "target",
            "actor", "actorName",
            "duration", "durationMs");

    private final AuditEventRepository repository;
    private final AuditWriter writer;
    private final ObjectMapper json;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public AuditController(AuditEventRepository repository, AuditWriter writer, ObjectMapper json) {
        this.repository = repository;
        this.writer = writer;
        this.json = json;
    }

    @GetMapping("/events")
    public Mono<ResponseEntity<Map<String, Object>>> events(
            @RequestParam(defaultValue = "ACTIVITY") String tab,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir) {
        List<String> categories = categories(tab, category);
        if (categories == null) return Mono.just(badTab());
        return allowed().flatMap(ok -> !ok ? Mono.just(forbidden()) : Mono.fromCallable(() -> {
            // Whatever is still queued is written first, so an entry made a moment ago - the
            // setting just saved, the tool call just finished - is on the page that is asked next.
            writer.flush();
            Window w = window(days);
            Page<AuditEvent> found = repository.search(w.from(), w.to(), categories,
                    blankToNull(action), blankToNull(outcome), blankToNull(target), blankToNull(actor),
                    blankToNull(conversation), likePattern(q),
                    PageRequest.of(Math.max(0, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size)),
                            order(sort, dir)));
            List<Map<String, Object>> items = new ArrayList<>(found.getNumberOfElements());
            for (AuditEvent e : found.getContent()) items.add(toMap(e, false));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowDays", w.days());
            out.put("items", items);
            out.put("page", found.getNumber());
            out.put("size", found.getSize());
            out.put("totalItems", found.getTotalElements());
            out.put("totalPages", found.getTotalPages());
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/events/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> event(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return allowed().flatMap(ok -> !ok ? Mono.just(forbidden()) : seesToolDetail().flatMap(full ->
                Mono.fromCallable(() -> repository.findById(uuid)
                        .map(e -> ResponseEntity.ok(toMap(e, full || !isTool(e))))
                        .orElseGet(() -> ResponseEntity.notFound().build()))
                .subscribeOn(Schedulers.boundedElastic())));
    }

    // Whether this caller reads a tool call's arguments and result - see the class note.
    private Mono<Boolean> seesToolDetail() {
        return ai.proxyma.core.settings.access.CallerRoles.isSuperAdmin(multiUser);
    }

    private static boolean isTool(AuditEvent e) {
        return AuditCategory.TOOL.equals(e.getCategory());
    }

    /**
     * The tab's entries as a file, under the same filters and order the page shows them in - CSV
     * (one row per entry, the detail as JSON in its last column) or JSON (each entry whole). At most
     * {@link LogExport#MAX_ROWS}; see there. Recorded under Activity: who took a copy of a log, and
     * which, is itself worth knowing.
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(defaultValue = "ACTIVITY") String tab,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) String format) {
        List<String> categories = categories(tab, category);
        if (categories == null) return Mono.just(ResponseEntity.badRequest().build());
        String fmt = LogExport.format(format);
        return allowed().flatMap(ok -> !ok ? Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build())
                : seesToolDetail().flatMap(full -> currentUser().flatMap(who -> Mono.fromCallable(() -> {
            writer.flush();
            Window w = window(days);
            List<AuditEvent> rows = new ArrayList<>();
            boolean truncated = false;
            for (int page = 0; ; page++) {
                Page<AuditEvent> found = repository.search(w.from(), w.to(), categories,
                        blankToNull(action), blankToNull(outcome), blankToNull(target), blankToNull(actor),
                        blankToNull(conversation), likePattern(q), PageRequest.of(page, 500, order(sort, dir)));
                for (AuditEvent e : found.getContent()) {
                    if (rows.size() == LogExport.MAX_ROWS) { truncated = true; break; }
                    rows.add(e);
                }
                if (truncated || !found.hasNext()) break;
            }
            String body;
            if ("json".equals(fmt)) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("exportedAt", Instant.now().toString());
                doc.put("tab", tab.toUpperCase(Locale.ROOT));
                doc.put("windowDays", w.days());
                doc.put("truncated", truncated);
                List<Map<String, Object>> entries = new ArrayList<>(rows.size());
                for (AuditEvent e : rows) entries.add(toMap(e, full || !isTool(e)));
                doc.put("entries", entries);
                body = json.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            } else {
                List<List<Object>> cells = new ArrayList<>(rows.size());
                for (AuditEvent e : rows) {
                    cells.add(java.util.Arrays.asList(e.getOccurredAt().toString(), e.getCategory(), e.getAction(),
                            e.getOutcome(), LogExport.who(e.getActorKind(), e.getActorId(), e.getActorName()),
                            e.getActorId(), e.getTarget(), e.getConversationId(), e.getSummary(), e.getDurationMs(),
                            full || !isTool(e) ? e.getDetail() : null));
                }
                body = LogExport.csv(List.of("Time (UTC)", "Category", "Action", "Outcome", "Who", "Who (id)",
                        "Target", "Conversation", "Summary", "Duration (ms)", "Detail"), cells);
            }
            AuditEntry.of(AuditCategory.DATA, "audit.exported")
                    .user(who.orElse(null))
                    .target(tab.toUpperCase(Locale.ROOT))
                    .summary("Log downloaded: " + tab.toLowerCase(Locale.ROOT) + ", " + rows.size()
                            + " entries as " + fmt.toUpperCase(Locale.ROOT))
                    .detail("entries", rows.size())
                    .detail("format", fmt)
                    .detail("windowDays", w.days())
                    .detail("truncated", truncated)
                    .record();
            return LogExport.file("proxyma-audit-" + tab.toLowerCase(Locale.ROOT), fmt, body, truncated);
        }).subscribeOn(Schedulers.boundedElastic()))));
    }

    /**
     * The tab's summary for the window: how many, how they ended, what they were about, how long
     * they took, and - for an administrator - who. Also the filters' choices, so a filter never
     * offers an action the window does not contain.
     */
    @GetMapping("/summary")
    public Mono<ResponseEntity<Map<String, Object>>> summary(
            @RequestParam(defaultValue = "ACTIVITY") String tab,
            @RequestParam(defaultValue = "30") int days) {
        List<String> categories = categories(tab, null);
        if (categories == null) return Mono.just(badTab());
        return allowed().flatMap(ok -> !ok ? Mono.just(forbidden()) : Mono.fromCallable(() -> {
            writer.flush();
            Window w = window(days);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowDays", w.days());
            out.put("multiUser", multiUser);
            out.put("canClear", !multiUser);

            long total = 0;
            Map<String, Long> byOutcome = new LinkedHashMap<>();
            for (Object[] r : repository.countByOutcome(w.from(), w.to(), categories)) {
                long n = asLong(r[1]);
                byOutcome.put((String) r[0], n);
                total += n;
            }
            out.put("total", total);
            out.put("byOutcome", byOutcome);

            List<Map<String, Object>> byAction = new ArrayList<>();
            for (Object[] r : repository.countByAction(w.from(), w.to(), categories)) {
                byAction.add(Map.of("category", r[0], "action", r[1], "count", asLong(r[2])));
            }
            out.put("byAction", byAction);

            List<Map<String, Object>> byTarget = new ArrayList<>();
            for (Object[] r : repository.countByTarget(w.from(), w.to(), categories, PageRequest.of(0, 100))) {
                long n = asLong(r[1]);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("target", r[0]);
                row.put("count", n);
                row.put("failed", asLong(r[2]));
                row.put("avgMs", n == 0 ? 0 : asLong(r[3]) / n);
                byTarget.add(row);
            }
            out.put("byTarget", byTarget);

            if (multiUser) {
                List<Map<String, Object>> byActor = new ArrayList<>();
                for (Object[] r : repository.countByActor(w.from(), w.to(), categories, PageRequest.of(0, 50))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("actorKind", r[0]);
                    row.put("actorId", r[1]);
                    row.put("actorName", r[2]);
                    row.put("count", asLong(r[3]));
                    byActor.add(row);
                }
                out.put("byActor", byActor);
            }

            out.put("latency", latency(repository.recentDurations(w.from(), w.to(), categories,
                    PageRequest.of(0, LATENCY_SAMPLE))));
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Clears one tab, on Desktop and Home Server. Recorded as the first entry after it, under
     * Activity - clearing a log is itself something the log should say happened.
     */
    @DeleteMapping("/events")
    public Mono<ResponseEntity<Map<String, Object>>> clear(@RequestParam String tab) {
        List<String> categories = categories(tab, null);
        if (categories == null) return Mono.just(badTab());
        if (multiUser) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.<String, Object>of(
                    "error", "Logs on Enterprise Server are removed by retention only.")));
        }
        return Mono.fromCallable(() -> {
            writer.flush();
            int deleted = repository.deleteByCategories(categories);
            AuditEntry.of(AuditCategory.DATA, "audit.cleared")
                    .user(null)
                    .target(tab.toUpperCase(Locale.ROOT))
                    .summary("Log cleared: " + tab.toLowerCase(Locale.ROOT) + " (" + deleted + " entries)")
                    .detail("entries", deleted)
                    .record();
            return ResponseEntity.ok(Map.<String, Object>of("deleted", deleted));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // -- helpers ----------------------------------------------------------------------------------

    /** The tab's categories, narrowed to one where asked and where it belongs to the tab; null for an unknown tab. */
    static List<String> categories(String tab, String category) {
        List<String> all = tab == null ? null : TABS.get(tab.toUpperCase(Locale.ROOT));
        if (all == null) return null;
        if (category == null || category.isBlank()) return all;
        String c = category.toUpperCase(Locale.ROOT);
        return all.contains(c) ? List.of(c) : all;
    }

    record Window(int days, Instant from, Instant to) {}

    static Window window(int days) {
        int d = Math.max(1, Math.min(365, days));
        return new Window(d, Instant.now().minus(Duration.ofDays(d)), Instant.now().plus(Duration.ofMinutes(1)));
    }

    static Sort order(String sort, String dir) {
        String property = sort == null ? null : SORTABLE.get(sort);
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "occurredAt");
        if (property == null) return newestFirst;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if ("occurredAt".equals(property)) return Sort.by(direction, "occurredAt");
        return Sort.by(direction, property).and(newestFirst);
    }

    /** The search box as an escaped, lower-cased LIKE pattern - as LlmCallsController builds it. */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) return null;
        String text = q.strip().toLowerCase(Locale.ROOT);
        if (text.length() > 200) text = text.substring(0, 200);
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static Map<String, Object> latency(List<Long> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", samples.size());
        if (samples.isEmpty()) return out;
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        out.put("p50", sorted[(int) Math.floor(0.50 * (sorted.length - 1))]);
        out.put("p95", sorted[(int) Math.floor(0.95 * (sorted.length - 1))]);
        out.put("max", sorted[sorted.length - 1]);
        return out;
    }

    private Map<String, Object> toMap(AuditEvent e, boolean withDetail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId() == null ? null : e.getId().toString());
        m.put("at", e.getOccurredAt() == null ? null : e.getOccurredAt().toString());
        m.put("category", e.getCategory());
        m.put("action", e.getAction());
        m.put("outcome", e.getOutcome());
        m.put("actorKind", e.getActorKind());
        m.put("actorId", e.getActorId());
        m.put("actorName", e.getActorName());
        m.put("target", e.getTarget());
        m.put("conversationId", e.getConversationId());
        m.put("summary", e.getSummary());
        m.put("durationMs", e.getDurationMs());
        if (!withDetail && AuditCategory.TOOL.equals(e.getCategory()) && e.getDetail() != null) {
            m.put("detailWithheld", true);
        }
        if (withDetail) {
            Object detail = null;
            if (e.getDetail() != null) {
                try {
                    detail = json.readValue(e.getDetail(), Object.class);
                } catch (Exception ignored) {
                    detail = Map.of("text", e.getDetail());
                }
            }
            m.put("detail", detail);
        }
        return m;
    }

    private Mono<Boolean> allowed() {
        return ai.proxyma.core.settings.access.CallerRoles.isAdmin(multiUser);
    }

    /** Who is asking, by id - empty on Desktop and Home Server, where nobody signs in. */
    private Mono<java.util.Optional<String>> currentUser() {
        if (!multiUser) return Mono.just(java.util.Optional.empty());
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> java.util.Optional.ofNullable(auth.getName()))
                .defaultIfEmpty(java.util.Optional.empty());
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Administrators only."));
    }

    private static ResponseEntity<Map<String, Object>> badTab() {
        return ResponseEntity.badRequest().body(Map.of("error", "Unknown tab."));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
