package ai.proxyma.core.system.activity;

import java.time.LocalDate;
import java.util.Map;

// In-memory per-day request/unique-user counter, written by proxyma-core-network's
// ActivityTrackingFilter on every request and drained once/day by proxyma-core-system-admin's
// ActivityFlushService into a persisted DailyActivityStat row. Lives in proxyma-core-common (the
// one module both of those already depend on) so neither needs a new edge added to the module
// dependency graph - network cannot depend on system-admin, and system-admin depending on
// network for one tiny interface would be a strange direction for what it actually needs.
//
// Also accumulates per-user per-feature usage (request count + wall-clock duration), written by
// the same filter (feature = CHAT / MCP / API:<segment>) and by the tool-callback decorator in
// proxyma-core-tools (feature = TOOL:<toolName>), drained every few minutes by
// ActivityFlushService into user_feature_activity rows. Key cardinality is bounded (users x a
// fixed set of feature names), so the map cannot grow unboundedly between drains.
public interface ActivityCounterStore {

    // userId is null for anonymous requests - still counted toward totalRequests, never toward
    // uniqueActiveUsers.
    void recordRequest(String userId);

    // Removes and returns the counts accumulated for the given date. Idempotent in the sense that
    // a second drain() for the same already-drained date returns zeros, not an error.
    DailyCounts drain(LocalDate date);

    record DailyCounts(long totalRequests, int uniqueActiveUsers) {}

    // Anonymous usage (userId == null) is not feature-tracked - per-feature stats exist to
    // profile a known user's behavior (admin analytics + the personality agent), which has no
    // meaning without an identity.
    void recordFeature(String userId, String feature, long durationMs);

    // Removes and returns everything accumulated since the last drain.
    Map<FeatureKey, FeatureUsage> drainFeatureUsage();

    record FeatureKey(String userId, String feature) {}

    record FeatureUsage(long requestCount, long totalDurationMs) {}
}
