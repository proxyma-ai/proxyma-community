package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.dto.CostCenterSettings;
import ai.proxyma.core.settings.audit.LogExport;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * The per-call detail view: every model call, and the same window grouped by provider, by model and
 * by service.
 *
 * <p>ONE ENDPOINT, for the reason UsagesController gives: the page draws one picture, and totals
 * assembled from four round trips disagree with each other the moment a call lands between two of
 * them. But TWO HALVES since 2026-10-07 ({@code include}): the summary - the chart and the
 * breakdowns - describes the period whatever the log's filters say, so the page asks for it once
 * per period, and asks for the LOG alone as the reader filters, sorts and scrolls. Every filter
 * change re-ran four aggregations and read every call in the period for the chart, and on an
 * install with a year of embedding batches the table took seconds to follow a filter
 * (maintainer's feedback). Without {@code include} both halves come back, as they always did.
 *
 * <p>In proxyma-core-settings so it exists in BOTH editions - the maintainer's requirement is that
 * usage and cost detail is visible everywhere, not only where there are accounts to attribute it
 * to. Desktop has no security filter chain at all, so {@code multiUser} is what tells the two
 * apart; see SettingsController, which documents that bypass at length.
 *
 * <h2>Who sees whose calls</h2>
 *
 * <ul>
 *   <li><b>Desktop</b> - one user, no accounts: everything, including the SYSTEM rows that ingest
 *       and background jobs produce.</li>
 *   <li><b>Server, admin</b> - everything, plus the by-subject breakdown that says who spent it.</li>
 *   <li><b>Server, anybody else</b> - their OWN calls only. The subject filter is derived from the
 *       authenticated principal here and never read from a query parameter, so asking for someone
 *       else's id returns your own rows rather than theirs.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/usage/calls")
public class LlmCallsController {

    /** A page big enough to scroll and small enough that nobody can ask for the whole table. */
    private static final int MAX_PAGE_SIZE = 200;

    /** From this window up the chart has one column per MONTH - 365 day-columns are unreadable. */
    static final int MONTHLY_FROM_DAYS = 180;

    private final LlmCallRepository repository;
    private final ModelPricingService pricing;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public LlmCallsController(LlmCallRepository repository, ModelPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    // Both setter-injected and optional, so the controller built by hand in tests still works:
    // without the first the user/platform split falls back to the default (chat alone is the
    // user's), without the second - desktop, which has no accounts - a subject reads as its id.
    private CostCenterService costCenters;
    private UserSpendPort userSpendPort;

    @Autowired(required = false)
    void setCostCenters(CostCenterService costCenters) {
        this.costCenters = costCenters;
    }

    @Autowired(required = false)
    void setUserSpendPort(UserSpendPort userSpendPort) {
        this.userSpendPort = userSpendPort;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> calls(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String tz,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) String include) {

        boolean wantLog = !"summary".equals(include);
        boolean wantSummary = !"log".equals(include);
        return viewer().flatMap(viewer -> Mono.fromCallable(() -> {
            int window = Math.max(1, Math.min(365, days));
            Instant from = Instant.now().minus(Duration.ofDays(window));
            Instant to = Instant.now().plus(Duration.ofMinutes(1));   // a call recorded mid-request
            String subjectId = viewer.seesEveryone() ? null : viewer.subjectId();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowDays", window);
            out.put("scope", viewer.seesEveryone() ? "all" : "mine");
            // Asked once per provider/model pair, not once per row - a page is up to 200 rows and
            // almost all of them share two or three models.
            Map<String, Boolean> priced = new HashMap<>();

            if (wantLog) {
                Page<LlmCall> found = repository.search(from, to, subjectId,
                        blankToNull(role), blankToNull(provider), blankToNull(model), likePattern(q),
                        PageRequest.of(Math.max(0, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size)),
                                order(sort, dir)));

                List<Map<String, Object>> items = new ArrayList<>(found.getNumberOfElements());
                for (LlmCall c : found.getContent()) {
                    items.add(toMap(c, isPriced(priced, c.getProvider(), c.getModel())));
                }
                out.put("items", items);
                out.put("page", found.getNumber());
                out.put("size", found.getSize());
                out.put("totalItems", found.getTotalElements());
                out.put("totalPages", found.getTotalPages());
            }
            if (!wantSummary) return ResponseEntity.ok(out);
            out.put("canClear", viewer.mayClear());

            // The breakdowns and the chart ignore the log's filters and its search: they describe
            // the whole window, and since 2026-09-24 the page shows them above the log as a summary
            // rather than as a way to narrow it. They are also where the log's filter menus take
            // their choices from, so a filter can never hide the other choices it offers.
            out.put("byProvider", group(repository.totalsByProvider(from, to, subjectId)));
            out.put("byModel", groupModels(repository.totalsByModel(from, to, subjectId), priced));
            out.put("byService", group(repository.totalsByRole(from, to, subjectId)));
            boolean monthly = window >= MONTHLY_FROM_DAYS;
            out.put("seriesUnit", monthly ? "month" : "day");
            List<Map<String, Object>> series = series(repository.costPoints(from, to, subjectId), from,
                    Instant.now(), zone(tz), monthly, this::chargedToUser);
            out.put("series", series);
            // The window's user/platform split, SUMMED FROM THE SERIES rather than queried again, so
            // the tiles above the chart cannot disagree with the columns in it.
            double userUsd = 0;
            double totalUsd = 0;
            for (Map<String, Object> b : series) {
                userUsd += asDouble(b.get("userUsd"));
                totalUsd += asDouble(b.get("costUsd"));
            }
            out.put("userUsd", userUsd);
            out.put("platformUsd", totalUsd - userUsd);
            if (viewer.seesEveryone()) {
                out.put("bySubject", groupSubjects(repository.totalsBySubject(from, to)));
                // Each role's cost center, defaults included, for the Administration page's By
                // service table - sent here because an ADMIN may see it but not read the setting.
                out.put("costCenters", costCenters != null ? costCenters.byRole()
                        : new CostCenterSettings().byRole());
            }
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The log as a file, under the same scope, filters and order as the page - see LogExport for the
     * row limit and the CSV rules. Recorded under Activity, as an audit log's download is.
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) String format) {
        String fmt = LogExport.format(format);
        return viewer().flatMap(viewer -> Mono.fromCallable(() -> {
            int window = Math.max(1, Math.min(365, days));
            Instant from = Instant.now().minus(Duration.ofDays(window));
            Instant to = Instant.now().plus(Duration.ofMinutes(1));
            String subjectId = viewer.seesEveryone() ? null : viewer.subjectId();
            List<LlmCall> rows = new ArrayList<>();
            boolean truncated = false;
            for (int page = 0; ; page++) {
                Page<LlmCall> found = repository.search(from, to, subjectId, blankToNull(role),
                        blankToNull(provider), blankToNull(model), likePattern(q),
                        PageRequest.of(page, 500, order(sort, dir)));
                for (LlmCall c : found.getContent()) {
                    if (rows.size() == LogExport.MAX_ROWS) { truncated = true; break; }
                    rows.add(c);
                }
                if (truncated || !found.hasNext()) break;
            }
            Map<String, Boolean> priced = new HashMap<>();
            Map<String, String> names = multiUser && userSpendPort != null && viewer.seesEveryone()
                    ? userSpendPort.displayNames(rows.stream()
                            .filter(c -> c.getSubjectKind() == SpendSubject.Kind.USER)
                            .map(LlmCall::getSubjectId).distinct().toList())
                    : Map.of();
            String body;
            if ("json".equals(fmt)) {
                List<Map<String, Object>> items = new ArrayList<>(rows.size());
                for (LlmCall c : rows) {
                    Map<String, Object> m = new LinkedHashMap<>(toMap(c, isPriced(priced, c.getProvider(), c.getModel())));
                    if (names.containsKey(c.getSubjectId())) m.put("subjectName", names.get(c.getSubjectId()));
                    items.add(m);
                }
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("exportedAt", Instant.now().toString());
                doc.put("windowDays", window);
                doc.put("scope", viewer.seesEveryone() ? "all" : "mine");
                doc.put("truncated", truncated);
                doc.put("calls", items);
                // Every value here is a string, a number or a boolean already, so a plain mapper writes
                // it as the page reads it - the controller is built by hand in tests, with no bean.
                body = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            } else {
                List<List<Object>> cells = new ArrayList<>(rows.size());
                for (LlmCall c : rows) {
                    cells.add(java.util.Arrays.asList(c.getRecordedAt().toString(), c.getRole(), c.getProvider(),
                            c.getModel(), LogExport.who(c.getSubjectKind().name(), c.getSubjectId(), names.get(c.getSubjectId())),
                            c.getSubjectKind().name(),
                            c.getInputTokens(), c.getOutputTokens(), c.getCacheWriteTokens(), c.getCacheReadTokens(),
                            c.getCostUsd(), isPriced(priced, c.getProvider(), c.getModel())));
                }
                body = LogExport.csv(List.of("Time (UTC)", "Service", "Provider", "Model", "Who", "Who (kind)",
                        "Input tokens", "Output tokens", "Cache write tokens", "Cache hit tokens", "Cost (USD)",
                        "Priced"), cells);
            }
            AuditEntry.of(AuditCategory.DATA, "llm-calls.exported")
                    .user(multiUser ? viewer.subjectId() : null)
                    .summary("LLM call log downloaded: " + rows.size() + " calls as " + fmt.toUpperCase(java.util.Locale.ROOT))
                    .detail("calls", rows.size())
                    .detail("format", fmt)
                    .detail("windowDays", window)
                    .detail("truncated", truncated)
                    .record();
            return LogExport.file("proxyma-llm-calls", fmt, body, truncated);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Clears the log: every recorded call, in every period (maintainer's feedback, 2026-10-07 -
     * "there is no way to clear the logs").
     *
     * <p>WHO: on Desktop and Home Server the one local user, whose calls these all are. On
     * Enterprise Server only a Super admin, and then for everyone - this is the deployment's record
     * of what it spent, and an ordinary user clearing their own would also be clearing what the
     * monthly spending limit counts. The public sandbox refuses it before it gets here
     * (SandboxGuardFilter: its Super admin writes nothing).
     *
     * <p>The spending limit counts this table (GlobalQuotaService), so a clear starts the month's
     * count again; the page says so before it asks. The monthly rollups are another table and stay.
     */
    @DeleteMapping
    public Mono<ResponseEntity<Map<String, Object>>> clear() {
        return viewer().flatMap(viewer -> {
            if (!viewer.mayClear()) {
                return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.<String, Object>of("error", "Only a Super admin can clear the call log.")));
            }
            return Mono.fromCallable(() -> {
                        int deleted = repository.deleteAllCalls();
                        AuditEntry.of(AuditCategory.DATA, "llm-calls.cleared")
                                .user(viewer.subjectId())
                                .summary("LLM call log cleared (" + deleted + " calls) - the spending limit's count starts again")
                                .detail("calls", deleted)
                                .record();
                        return ResponseEntity.ok(Map.<String, Object>of("deleted", deleted));
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * The log's sortable columns, by the names the page sends - and nothing else. A sort is a
     * property path handed to the query, so a client-chosen string is never passed through: an
     * unknown one sorts newest first, as the log always has. Every order ends on the time, newest
     * first, so rows that tie (a hundred embedding batches at $0) keep a stable order across pages.
     */
    static final Map<String, String> SORTABLE = Map.of(
            "time", "recordedAt",
            "service", "role",
            "model", "model",
            "provider", "provider",
            "in", "inputTokens",
            "out", "outputTokens",
            "cacheWrite", "cacheWriteTokens",
            "cacheRead", "cacheReadTokens",
            "cost", "costUsd");

    static Sort order(String sort, String dir) {
        String property = sort == null ? null : SORTABLE.get(sort);
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "recordedAt");
        if (property == null) return newestFirst;
        if ("recordedAt".equals(property)) {
            return "asc".equalsIgnoreCase(dir) ? Sort.by(Sort.Direction.ASC, "recordedAt") : newestFirst;
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort primary = Sort.by(direction, property);
        // A model name alone is ambiguous across providers; the provider breaks the tie first.
        if ("model".equals(property)) primary = primary.and(Sort.by(direction, "provider"));
        return primary.and(newestFirst);
    }

    /**
     * The search box's text as a case-insensitive LIKE pattern, or null for no search. Its own
     * {@code %} and {@code _} are escaped (with backslash, the query's ESCAPE character), so a model
     * called "gpt_4" matches itself rather than "gpt-4" too. Capped, since it reaches the database.
     */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) return null;
        String text = q.strip().toLowerCase(java.util.Locale.ROOT);
        if (text.length() > 200) text = text.substring(0, 200);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private boolean isPriced(Map<String, Boolean> seen, String provider, String model) {
        return seen.computeIfAbsent(provider + '\u0000' + model, k -> pricing.hasPricing(provider, model));
    }

    /**
     * Cost per calendar day (or month) in the READER's zone, with every bucket present from the
     * start of the window to {@code now} - an empty day is a zero column rather than a gap the chart
     * closes up, which would make three busy days a week apart look consecutive.
     */
    static List<Map<String, Object>> series(List<Object[]> points, Instant from, Instant now,
                                            ZoneId zone, boolean monthly) {
        return series(points, from, now, zone, monthly, null);
    }

    /**
     * As above, with each bucket's cost also split into {@code userUsd} - what was charged to a
     * user - when a point carries its [subjectKind, role] and {@code userPays} says so. The rest is
     * the platform's.
     */
    static List<Map<String, Object>> series(List<Object[]> points, Instant from, Instant now,
                                            ZoneId zone, boolean monthly,
                                            BiPredicate<Object, Object> userPays) {
        TreeMap<String, double[]> buckets = new TreeMap<>();   // key -> [calls, cost, userCost]
        LocalDate first = from.atZone(zone).toLocalDate();
        LocalDate last = now.atZone(zone).toLocalDate();
        if (monthly) {
            for (YearMonth m = YearMonth.from(first); !m.isAfter(YearMonth.from(last)); m = m.plusMonths(1)) {
                buckets.put(m.toString(), new double[3]);
            }
        } else {
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                buckets.put(d.toString(), new double[3]);
            }
        }
        for (Object[] p : points) {
            if (!(p[0] instanceof Instant at)) continue;
            LocalDate day = at.atZone(zone).toLocalDate();
            double[] b = buckets.get(monthly ? YearMonth.from(day).toString() : day.toString());
            if (b == null) continue;   // the one-minute grace past "now" can land on tomorrow
            b[0] += 1;
            b[1] += asDouble(p[1]);
            if (userPays != null && p.length >= 4 && userPays.test(p[2], p[3])) b[2] += asDouble(p[1]);
        }
        List<Map<String, Object>> out = new ArrayList<>(buckets.size());
        buckets.forEach((key, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", key);
            m.put("calls", (long) b[0]);
            m.put("costUsd", b[1]);
            m.put("userUsd", b[2]);
            out.add(m);
        });
        return out;
    }

    /** The reader's zone, falling back to UTC for anything missing or unrecognized. */
    static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static Map<String, Object> toMap(LlmCall c, boolean priced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId() == null ? null : c.getId().toString());
        m.put("recordedAt", c.getRecordedAt().toString());
        m.put("service", c.getRole());
        m.put("provider", c.getProvider());
        m.put("model", c.getModel());
        m.put("subjectKind", c.getSubjectKind().name());
        m.put("subjectId", c.getSubjectId());
        m.put("inputTokens", c.getInputTokens());
        m.put("outputTokens", c.getOutputTokens());
        m.put("cacheReadTokens", c.getCacheReadTokens());
        m.put("cacheWriteTokens", c.getCacheWriteTokens());
        m.put("costUsd", c.getCostUsd());
        // REPORTED (the provider's own figure), RATE, LIST (the built-in list price) or NONE; null
        // on a row written before the source was kept.
        m.put("costSource", c.getCostSource());
        m.put("longCacheWriteTokens", c.getLongCacheWriteTokens());
        // HIT, FIRST, EXPIRED, CHANGED_TOOLS / CHANGED_SYSTEM / CHANGED_MESSAGES (the prompt no longer
        // started with the one before), NOT_KEPT (same prompt, within its lifetime, nothing read);
        // null for no cache activity or an older row.
        m.put("cacheOutcome", c.getCacheOutcome());
        m.put("cacheWarmUntil", c.getCacheWarmUntil() == null ? null : c.getCacheWarmUntil().toString());
        // Whether the model has a rate at all. A costUsd of 0 means either "priced at zero" or
        // "never priced", and only the second is something the reader can fix.
        m.put("priced", priced || "REPORTED".equals(c.getCostSource()) || "LIST".equals(c.getCostSource()));
        return m;
    }

    private static List<Map<String, Object>> group(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(groupRow(String.valueOf(r[0]), r, 1));
        return out;
    }

    private List<Map<String, Object>> groupModels(List<Object[]> rows, Map<String, Boolean> priced) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String provider = String.valueOf(r[0]);
            String model = String.valueOf(r[1]);
            Map<String, Object> m = groupRow(provider + " / " + model, r, 2);
            // The pair apart as well, for the log's model filter, which narrows on both.
            m.put("provider", provider);
            m.put("model", model);
            m.put("priced", isPriced(priced, provider, model));
            out.add(m);
        }
        return out;
    }

    /** Reads [calls, in, out, cacheRead, cacheWrite, cost] starting at column {@code at}. */
    private static Map<String, Object> groupRow(String key, Object[] r, int at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("calls", asLong(r[at]));
        m.put("inputTokens", asLong(r[at + 1]));
        m.put("outputTokens", asLong(r[at + 2]));
        m.put("cacheReadTokens", asLong(r[at + 3]));
        m.put("cacheWriteTokens", asLong(r[at + 4]));
        m.put("costUsd", asDouble(r[at + 5]));
        return m;
    }

    /**
     * Whether a call was charged to the user it was made for: a person behind it AND a role whose
     * cost center is the user. The same split GlobalQuotaService.status() makes, so the page's
     * "charged to users" is the figure the quota counts.
     */
    private boolean chargedToUser(Object kind, Object role) {
        if (kind == SpendSubject.Kind.SYSTEM || "SYSTEM".equals(String.valueOf(kind))) return false;
        String r = role == null ? null : role.toString();
        return costCenters != null ? costCenters.chargedToUser(r) : "CHAT".equals(r);
    }

    private List<Map<String, Object>> groupSubjects(List<Object[]> rows) {
        Map<String, String> names = displayNames(rows);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String kind = String.valueOf(r[0]);
            String id = String.valueOf(r[1]);
            m.put("subjectKind", kind);
            m.put("subjectId", id);
            // A display name for a USER that still has an account. Absent otherwise - a deleted
            // user's spend still happened and still renders, under its id.
            String name = "USER".equals(kind) ? names.get(id) : null;
            if (name != null) m.put("label", name);
            m.put("calls", asLong(r[2]));
            m.put("inputTokens", asLong(r[3]));
            m.put("outputTokens", asLong(r[4]));
            m.put("costUsd", asDouble(r[5]));
            out.add(m);
        }
        return out;
    }

    /** Names for the USER subjects, in one query - see UserSpendPort.displayNames. */
    private Map<String, String> displayNames(List<Object[]> rows) {
        if (userSpendPort == null) return Map.of();
        try {
            List<String> ids = new ArrayList<>();
            for (Object[] r : rows) {
                if ("USER".equals(String.valueOf(r[0]))) ids.add(String.valueOf(r[1]));
            }
            return ids.isEmpty() ? Map.of() : userSpendPort.displayNames(ids);
        } catch (Exception e) {
            // A name is decoration; the money is not. Rendering ids beats rendering nothing.
            return Map.of();
        }
    }

    private static long asLong(Object v) { return v instanceof Number n ? n.longValue() : 0L; }

    private static double asDouble(Object v) { return v instanceof Number n ? n.doubleValue() : 0.0; }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Who is asking, whether they may see anyone but themselves, and whether they may clear the log. */
    private record Viewer(String subjectId, boolean seesEveryone, boolean mayClear) {}

    private Mono<Viewer> viewer() {
        // Desktop: no accounts, no security filter chain, and SYSTEM rows are this user's own
        // ingest. Anything else would show an empty page on the edition where the whole cost is
        // the operator's.
        if (!multiUser) return Mono.just(new Viewer(null, true, true));
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> {
                    List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
                    boolean superAdmin = roles.contains("ROLE_SUPER_ADMIN");
                    boolean admin = superAdmin || roles.contains("ROLE_ADMIN");
                    return new Viewer(auth.getName(), admin, superAdmin);
                })
                // No authentication on a multi-user deployment: an anonymous visitor sees the
                // anonymous subject's own calls, never everyone's.
                .defaultIfEmpty(new Viewer(SpendSubject.ANONYMOUS_ID, false, false));
    }
}
