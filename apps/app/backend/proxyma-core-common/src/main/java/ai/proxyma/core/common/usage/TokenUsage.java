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
// This record carries COUNTS ONLY. It briefly also carried a provider-reported cost, read off
// gateways that account in money; that was removed in 0.1.2 along with the per-instance opt-in that
// turned it on. Cost is now always the configured rate times these counts - one basis for every
// figure the product shows. See ModelPricingService.costUsd().
//
// longCacheWriteTokens is the part of cacheWriteTokens written with a ONE-HOUR lifetime, which
// Anthropic bills at 2x the input rate against 1.25x for five minutes. Spring AI drops the split
// the response states (Anthropic's usage.cache_creation, an OpenAI-route gateway's
// cache_creation_token_details), so it is read off the wire (WireUsage, proxyma-core-provider) and
// cacheLifetimeStated says it was. Where nothing stated it, the gateway session marks every write
// by the lifetime the request ASKED for (withLongCacheWrites) - which a gateway may not have
// honored: one was seen billing an hour-long request at the five-minute rate (2026-10-08).
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                          long promptTokensTotal, long longCacheWriteTokens, boolean cacheLifetimeStated) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                      long promptTokensTotal) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal, 0, false);
    }

    public TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                      long promptTokensTotal, long longCacheWriteTokens) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, promptTokensTotal, longCacheWriteTokens, false);
    }

    /**
     * The same counts, with every cache write marked as one-hour - unless the response stated the
     * split itself, which is the better witness of what was written and is kept.
     */
    public TokenUsage withLongCacheWrites() {
        if (cacheLifetimeStated) return this;
        return new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                promptTokensTotal, cacheWriteTokens, false);
    }

    /**
     * Field-wise maximum - how the chunks of ONE streamed call combine. A token count never
     * decreases within a call, so the maximum is right for a running total, for a single report,
     * and for counts split across chunks (Anthropic: input and cache in the first, output in the
     * last). A sum would multiply a running total by the chunk count; the last chunk alone loses
     * whatever only the first one said.
     */
    public TokenUsage maxWith(TokenUsage other) {
        if (other == null) return this;
        return new TokenUsage(
                Math.max(inputTokens, other.inputTokens),
                Math.max(outputTokens, other.outputTokens),
                Math.max(cacheReadTokens, other.cacheReadTokens),
                Math.max(cacheWriteTokens, other.cacheWriteTokens),
                Math.max(promptTokensTotal, other.promptTokensTotal),
                Math.max(longCacheWriteTokens, other.longCacheWriteTokens),
                cacheLifetimeStated || other.cacheLifetimeStated);
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

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                promptTokensTotal + other.promptTokensTotal,
                longCacheWriteTokens + other.longCacheWriteTokens,
                cacheLifetimeStated || other.cacheLifetimeStated);
    }
}
