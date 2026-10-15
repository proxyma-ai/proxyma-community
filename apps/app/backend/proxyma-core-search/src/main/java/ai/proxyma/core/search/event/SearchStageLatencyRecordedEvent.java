package ai.proxyma.core.search.event;

// Fired once per SearchService.search() call with per-stage latency data - what System >
// Performance reports. Published on every search, with no toggle (proxyma-core-search cannot
// depend on proxyma-core-evaluation - evaluation depends on search, never the reverse - so this
// crosses the module boundary via a plain Spring application event, same pattern as
// ai.proxyma.core.common.spend.LlmSpendRecordedEvent). Listened to by proxyma-core-evaluation's
// EvaluationMetricsListener and persisted to eval_search_stage_metrics. Covers both standalone
// /api/v1/search requests and chat-driven searches, since both paths call this same
// SearchService.search(). Carries queryLength, never the raw query text - the metrics are
// anonymous by construction, with no exception for any admin role, so no field here may hold
// user-authored content.
public record SearchStageLatencyRecordedEvent(
        int queryLength,
        int variantCount,
        long expansionMs,
        long denseMs,
        long bm25Ms,
        long rerankMs,
        long totalMs,
        int resultCount,
        // Whether the knowledge-graph channel ran, and what it cost - so searches with it can be
        // set beside searches without (GraphSettings.searchChannel).
        boolean graphChannel,
        long graphMs) {
}
