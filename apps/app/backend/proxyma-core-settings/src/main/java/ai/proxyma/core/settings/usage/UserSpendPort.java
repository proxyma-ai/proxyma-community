package ai.proxyma.core.settings.usage;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads total user-attributed LLM spend, so the global quota can add it to platform spend.
 *
 * A PORT because the user ledger lives in proxyma-core-auth, which is above this module in the
 * dependency graph (auth depends on settings, never the reverse) and only exists under the server
 * Maven profile at all. The same shape as proxyma-core-connector's UserQuotaPort, for the same
 * reason.
 *
 * Consumers take it as {@code @Autowired(required = false)}: there is no implementation in desktop
 * mode, where there are no user accounts and every cost is by definition the platform's. A null
 * port therefore means "user spend is zero", which is correct rather than a degraded reading.
 */
public interface UserSpendPort {

    /** Total spend charged to users since an instant, in USD. */
    double totalUserSpendSince(Instant since);

    /**
     * Spend per calendar day since an instant, for the cost-over-time view. Days with no spend may
     * be absent - the caller fills gaps, because a sparse series is cheaper to produce than a dense
     * one and only the reader cares about the difference.
     */
    List<DailySpend> dailyUserSpendSince(Instant since);

    /** The costliest users since an instant, highest first. */
    List<UserSpend> topUsersSince(Instant since, int limit);

    /**
     * Display names for user ids, for anything that has ids and needs to show people.
     *
     * <p>A batch call rather than one per id: the attributed-spend view resolves every payer on
     * screen at once, and ten users is ten round trips otherwise - the same reason topUsersSince
     * already resolves its own names in one query. Ids with no account are simply absent from the
     * result; a deleted user's spend is still real and still has to render.
     */
    Map<String, String> displayNames(Collection<String> userIds);

    record DailySpend(LocalDate day, double costUsd) {}

    record UserSpend(String userId, String displayName, double costUsd, long calls) {}
}
