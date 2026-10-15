package ai.proxyma.core.settings.usage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything the Administration > Usages page reads.
 *
 * One endpoint rather than five, because the page draws one picture: what has this deployment spent,
 * on what, on whom, and where is it heading. Five round trips to assemble one answer would also make
 * the totals inconsistent with each other whenever a call landed between two of them.
 *
 * Lives in proxyma-core-settings so it exists in BOTH editions. Desktop has no user accounts, so
 * UserSpendPort is absent there and every user figure is legitimately zero - the page then reads as
 * "all of this is platform cost", which on a single-user desktop install is exactly true.
 */
@RestController
@RequestMapping("/api/v1/usages")
public class UsagesController {

    private final AttributedSpendService attributedSpend;
    private final PlatformSpendService platformSpendService;
    private final PlatformSpendRepository platformSpendRepository;
    private final GlobalQuotaService globalQuotaService;
    private final UserSpendPort userSpendPort;

    public UsagesController(AttributedSpendService attributedSpend,
                            PlatformSpendService platformSpendService,
                            PlatformSpendRepository platformSpendRepository,
                            GlobalQuotaService globalQuotaService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            UserSpendPort userSpendPort) {
        this.attributedSpend = attributedSpend;
        this.platformSpendService = platformSpendService;
        this.platformSpendRepository = platformSpendRepository;
        this.globalQuotaService = globalQuotaService;
        this.userSpendPort = userSpendPort;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> overview(
            @RequestParam(defaultValue = "30") int days) {
        return Mono.fromCallable(() -> {
            int window = Math.max(1, Math.min(365, days));
            Instant since = Instant.now().minus(Duration.ofDays(window));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowDays", window);

            // Quota status comes from the quota's OWN window (the configured reset day), not this
            // page's. Showing "spent $40 of $50" against a 7-day view when the quota month started
            // three weeks ago would be a different number every time the range changed.
            GlobalQuotaService.GlobalQuotaStatus quota = globalQuotaService.status();
            out.put("quota", Map.of(
                    "enabled", quota.enabled(),
                    "limitUsd", quota.limitUsd(),
                    "userUsd", quota.userUsd(),
                    "platformUsd", quota.platformUsd(),
                    "totalUsd", quota.totalUsd(),
                    "exhausted", quota.exhausted()));

            double platformTotal = platformSpendService.totalSince(since);
            double userTotal = userSpendPort != null ? userSpendPort.totalUserSpendSince(since) : 0;
            out.put("platformUsd", platformTotal);
            out.put("userUsd", userTotal);
            out.put("totalUsd", platformTotal + userTotal);

            // Per platform activity - embedding vs OCR vs memory digest. This is the breakdown that
            // answers "is indexing what costs us money", which it usually is.
            List<Map<String, Object>> services = new ArrayList<>();
            platformSpendService.totalsByServiceSince(since).forEach((svc, t) -> services.add(Map.of(
                    "service", svc.name(),
                    "costUsd", t.costUsd(),
                    "inputTokens", t.inputTokens(),
                    "outputTokens", t.outputTokens(),
                    "calls", t.calls())));
            services.sort((a, b) -> Double.compare((double) b.get("costUsd"), (double) a.get("costUsd")));
            out.put("services", services);

            out.put("users", userSpendPort != null
                    ? userSpendPort.topUsersSince(since, 10).stream().map(u -> Map.<String, Object>of(
                            "userId", u.userId(),
                            "displayName", u.displayName() != null ? u.displayName() : u.userId(),
                            "costUsd", u.costUsd(),
                            "calls", u.calls())).toList()
                    : List.of());

            out.put("daily", dailySeries(since, window));

            // WHO IT WAS FOR, for the current calendar month.
            //
            // A different time basis from everything above, deliberately and visibly. The window
            // figures come from chat_spend_events, which is PRUNED AT 8 DAYS because its real job
            // is the rolling quota windows - so "users, 30d" has always been able to report only
            // the last eight of those thirty days, silently. The attributed ledger is a monthly
            // rollup with no such hole, so the answer it gives is a month's, and the client labels
            // it as one rather than pretending the two ranges are the same.
            String month = attributedSpend.currentMonth();
            List<AttributedSpendService.SubjectTotal> subjects = attributedSpend.bySubject(month);
            Map<String, String> names = resolveNames(subjects);

            Map<String, Object> attribution = new LinkedHashMap<>();
            attribution.put("month", month);
            attribution.put("totalUsd", attributedSpend.totalCost(month));
            attribution.put("anonymousUsd", attributedSpend.anonymousCost(month));
            attribution.put("subjects", subjects.stream().map(t -> Map.<String, Object>of(
                    "kind", t.kind().name(),
                    "id", t.id(),
                    // An id that resolves to no account still renders: a deleted user's spend
                    // happened, and dropping the row would quietly shrink the total.
                    "label", names.getOrDefault(t.id(), defaultLabel(t)),
                    "costUsd", t.costUsd(),
                    "calls", t.calls(),
                    "tokens", t.inputTokens() + t.outputTokens())).toList());
            attribution.put("roles", attributedSpend.byRole(month).stream().map(r -> Map.<String, Object>of(
                    "role", r.role(),
                    "costUsd", r.costUsd(),
                    "calls", r.calls(),
                    "tokens", r.inputTokens() + r.outputTokens())).toList());
            out.put("attribution", attribution);
            return ResponseEntity.ok(out);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** Names for the USER subjects on screen, in one query - see UserSpendPort.displayNames. */
    private Map<String, String> resolveNames(List<AttributedSpendService.SubjectTotal> subjects) {
        if (userSpendPort == null) return Map.of();
        try {
            return userSpendPort.displayNames(subjects.stream()
                    .filter(t -> t.kind() == ai.proxyma.core.common.spend.SpendSubject.Kind.USER)
                    .map(AttributedSpendService.SubjectTotal::id)
                    .toList());
        } catch (Exception e) {
            // A name is decoration; the money is not. Rendering ids beats rendering nothing.
            return Map.of();
        }
    }

    /** What to call a payer with no account behind it. */
    private static String defaultLabel(AttributedSpendService.SubjectTotal t) {
        return switch (t.kind()) {
            case ANONYMOUS -> "Signed-out visitors";
            case SYSTEM -> "Background work";
            // A user id with no account left - deleted since the spend was recorded.
            case USER -> "Removed account";
        };
    }

    /**
     * One row per day in the window, user and platform separated, with ZERO-FILLED GAPS.
     *
     * The gaps matter. A sparse series drawn as a line implies spending was continuous between two
     * points that are actually a fortnight apart, which makes a quiet period look like a steady one
     * and flatters any trend drawn through it.
     */
    private List<Map<String, Object>> dailySeries(Instant since, int windowDays) {
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        LocalDate start = since.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(d, new double[]{0, 0});
        }

        try {
            for (Object[] row : platformSpendRepository.dailyCostSince(since)) {
                LocalDate day = toLocalDate(row[0]);
                if (day != null && byDay.containsKey(day)) {
                    byDay.get(day)[1] = ((Number) row[1]).doubleValue();
                }
            }
        } catch (Exception ignored) {
            // A chart with one series missing still says something; a 500 says nothing.
        }

        if (userSpendPort != null) {
            try {
                for (UserSpendPort.DailySpend d : userSpendPort.dailyUserSpendSince(since)) {
                    if (byDay.containsKey(d.day())) byDay.get(d.day())[0] = d.costUsd();
                }
            } catch (Exception ignored) {
                // As above.
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(byDay.size());
        byDay.forEach((day, v) -> out.add(Map.of(
                "day", day.toString(), "userUsd", v[0], "platformUsd", v[1])));
        return out;
    }

    // The JPA `cast(x as date)` result type differs by provider - java.sql.Date on PostgreSQL,
    // LocalDate on H2 - and desktop and server run different ones.
    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        return null;
    }
}
