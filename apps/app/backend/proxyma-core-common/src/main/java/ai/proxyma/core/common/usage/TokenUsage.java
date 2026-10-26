package ai.proxyma.core.common.usage;

// Token counts for a single LLM call (or an accumulated sum across several) - input/output are
// reported by every provider, cache reads/writes only by the ones that support prompt caching
// (Anthropic has both; OpenAI/Azure OpenAI/DeepSeek report reads only; Ollama/Mistral report
// neither and stay 0). See proxyma-core-provider's TokenUsageExtractor for how each provider's
// native response is mapped into this shape.
//
// Lives in proxyma-core-common (not alongside TokenUsageExtractor in proxyma-core-provider,
// where it originated) specifically so proxyma-core-settings' ModelPricingService can depend on
// this plain data type without depending on proxyma-core-provider itself - proxyma-core-provider
// already depends on proxyma-core-settings (via proxyma-core-network), so the reverse dependency
// would close a Maven reactor cycle. TokenUsageExtractor's provider-SDK-specific extraction logic
// has no such reason to move and stays in proxyma-core-provider.
//
// promptTokensTotal is the actual total size of the prompt sent to the model for this call -
// NOT simply inputTokens, because providers disagree on what inputTokens itself already
// includes: Anthropic's input_tokens explicitly EXCLUDES cache_read/cache_creation tokens (they
// are billed and reported as separate, additive categories), while OpenAI/Azure/DeepSeek report
// cachedTokens as a SUBSET already counted inside promptTokens. TokenUsageExtractor computes
// this correctly per provider so callers that need "how big is the context right now" (see
// SmartChatMemory.recordContextTokens()) never have to know which provider they're talking to.
// inputTokens itself is left as each provider's own raw figure - callers that want a cost/usage
// breakdown (cumulative input vs. cache read vs. cache write) still want the raw split, not this
// derived total.
//
// WHAT A CALL COST travels with its counts since 2026-10-22, settled once where the call is made -
// the gateway, which alone sees the response's own figures, the tier it ran on and when. A cost the
// PROVIDER stated wins outright (the maintainer's rule: "if the provider provides cost, always use
// it"); else the rates price the counts. settledCostUsd is that sum, costSource says where it came
// from, and unsettled holds the counts nobody priced yet, which ModelPricingService prices when
// asked - null meaning "all of them", which is what every usage built by hand is. Summing a turn's
// calls keeps both halves, so a turn that mixes settled and unsettled calls prices each call once.
// (From 0.1.2 until then it carried counts only; before 0.1.2 a provider cost was read for one
// opted-in gateway.)
//
// longCacheWriteTokens is the part of cacheWriteTokens written with a ONE-HOUR lifetime, which
// Anthropic bills at 2x the input rate against 1.25x for five minutes. Spring AI drops the split
// the response states (Anthropic's usage.cache_creation, an OpenAI-route gateway's
// cache_creation_token_details), so it is read off the wire (WireUsage, proxyma-core-provider) and
// cacheLifetimeStated says it was. Where nothing stated it, the gateway session marks every write
// by the lifetime the request ASKED for (withLongCacheWrites) - which a gateway may not have
// honored: one was seen billing an hour-long request at the five-minute rate (2026-10-08).
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                          long promptTokensTotal, long longCacheWriteTokens, boolean cacheLifetimeStated,
                          double settledCostUsd, TokenUsage unsettled, CostSource costSource) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                      long promptTokensTotal) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal, 0, false);
    }

    public TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                      long promptTokensTotal, long longCacheWriteTokens) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal, longCacheWriteTokens, false);
    }

    public TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                      long promptTokensTotal, long longCacheWriteTokens, boolean cacheLifetimeStated) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal, longCacheWriteTokens,
                cacheLifetimeStated, 0, null, null);
    }

    /**
     * The same counts, with every cache write marked as one-hour - unless the response stated the
     * split itself, which is the better witness of what was written and is kept. A settled cost is
     * kept too: the gateway marks the writes BEFORE it settles, so the cost already counts them.
     */
    public TokenUsage withLongCacheWrites() {
        if (cacheLifetimeStated) return this;
        return new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                promptTokensTotal, cacheWriteTokens, false, settledCostUsd,
                unsettled == null ? null : unsettled.withLongCacheWrites(), costSource);
    }

    /** These counts with their cost settled - nothing left unpriced. */
    public TokenUsage settled(double costUsd, CostSource source) {
        return new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal,
                longCacheWriteTokens, cacheLifetimeStated, costUsd, EMPTY, source);
    }

    /** The counts no cost covers yet - all of them when nothing was settled. */
    public TokenUsage unsettledCounts() {
        return unsettled != null ? unsettled : countsOnly();
    }

    /** No tokens of any kind. */
    public boolean isEmptyCounts() {
        return inputTokens == 0 && outputTokens == 0 && cacheReadTokens == 0 && cacheWriteTokens == 0
                && promptTokensTotal == 0;
    }

    private TokenUsage countsOnly() {
        return new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal,
                longCacheWriteTokens, cacheLifetimeStated);
    }

    /**
     * Field-wise maximum - how the chunks of ONE streamed call combine. A token count never
     * decreases within a call, so the maximum is right for a running total, for a single report,
     * and for counts split across chunks (Anthropic: input and cache in the first, output in the
     * last). A sum would multiply a running total by the chunk count; the last chunk alone loses
     * whatever only the first one said.
     *
     * <p>The cost follows the same call: a cost the provider REPORTED beats any estimate (OpenRouter
     * states it in the last chunk, after chunks the gateway had priced from the counts so far);
     * otherwise the larger, since an estimate over growing counts only grows.
     */
    public TokenUsage maxWith(TokenUsage other) {
        if (other == null) return this;
        boolean reportedHere = costSource == CostSource.REPORTED;
        boolean reportedThere = other.costSource == CostSource.REPORTED;
        double cost;
        CostSource source;
        if (reportedHere != reportedThere) {
            cost = reportedHere ? settledCostUsd : other.settledCostUsd;
            source = CostSource.REPORTED;
        } else if (settledCostUsd >= other.settledCostUsd) {
            cost = settledCostUsd;
            source = costSource != null ? costSource : other.costSource;
        } else {
            cost = other.settledCostUsd;
            source = other.costSource != null ? other.costSource : costSource;
        }
        // One call is settled whole or not at all: once either chunk was priced, the call is.
        TokenUsage remaining = unsettled != null || other.unsettled != null ? EMPTY : null;
        return new TokenUsage(
                Math.max(inputTokens, other.inputTokens),
                Math.max(outputTokens, other.outputTokens),
                Math.max(cacheReadTokens, other.cacheReadTokens),
                Math.max(cacheWriteTokens, other.cacheWriteTokens),
                Math.max(promptTokensTotal, other.promptTokensTotal),
                Math.max(longCacheWriteTokens, other.longCacheWriteTokens),
                cacheLifetimeStated || other.cacheLifetimeStated,
                cost, remaining, source);
    }

    /**
     * The prompt tokens billed at the plain INPUT rate - the prompt minus whatever was read from or
     * written to a cache, each of which has its own rate.
     *
     * <p>Not {@code inputTokens}, because providers disagree on what that already holds: Anthropic's
     * input excludes both cache categories, while OpenAI, Azure, DeepSeek and Gemini count cached
     * tokens INSIDE it. Pricing inputTokens at the input rate and cacheReadTokens at the read rate
     * therefore charged every OpenAI cache hit twice, until 2026-09-24. promptTokensTotal is the
     * true prompt size for every provider (see TokenUsageExtractor), so subtracting both cache
     * counts from it is right for all of them. A usage built without a total - zero - falls back
     * to inputTokens as it stands.
     */
    public long uncachedInputTokens() {
        if (promptTokensTotal <= 0) return inputTokens;
        return Math.max(0, promptTokensTotal - cacheReadTokens - cacheWriteTokens);
    }

    /**
     * Calls summed - a turn, a month. Counts and settled costs add; so do the unsettled counts,
     * which stay null ("all of them") only while neither side was settled at all. The source is
     * the strongest of the two (a turn with one reported call says so).
     */
    public TokenUsage plus(TokenUsage other) {
        TokenUsage remaining = unsettled == null && other.unsettled == null
                ? null
                : unsettledCounts().plus(other.unsettledCounts());
        CostSource source = costSource == null ? other.costSource
                : other.costSource == null ? costSource
                : costSource.ordinal() >= other.costSource.ordinal() ? costSource : other.costSource;
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                promptTokensTotal + other.promptTokensTotal,
                longCacheWriteTokens + other.longCacheWriteTokens,
                cacheLifetimeStated || other.cacheLifetimeStated,
                settledCostUsd + other.settledCostUsd,
                remaining, source);
    }
}
