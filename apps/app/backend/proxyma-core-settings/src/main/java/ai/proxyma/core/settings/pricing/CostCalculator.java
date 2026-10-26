package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.CallFacts;
import ai.proxyma.core.common.usage.TokenUsage;

import java.util.Locale;

/**
 * What one call cost under a rate card - the arithmetic, and nothing that reads a database, so it
 * can be tested on its own. ModelPricingService picks the card and calls this.
 *
 * <p>In order: the prompt's size picks the standard or the long-context rates (for the WHOLE call,
 * cache reads and writes counted in its size, as Anthropic and Google bill it); each kind of token
 * is priced at its rate; then the multipliers the response or the clock decide - the time of day
 * (DeepSeek), the service tier (flex or batch at half, priority at the provider's premium), the
 * region (Anthropic's US-only inference at 1.1x); and last the per-request fees for server-side
 * web search.
 */
public final class CostCalculator {

    /** Anthropic: a one-hour write costs 2x input where a five-minute one costs 1.25x. */
    static final double LONG_CACHE_WRITE_FACTOR = 2.0 / 1.25;

    /** Anthropic's server-side web search: $10 per 1,000 searches. */
    static final double WEB_SEARCH_USD = 10.0 / 1000;

    /** Anthropic's inference_geo "us": 1.1x on every category. */
    static final double US_ONLY_INFERENCE = 1.1;

    private CostCalculator() {}

    public static double cost(RateCard card, TokenUsage usage, CallFacts facts) {
        if (usage == null) return 0;
        double tokens = card == null ? 0 : tokenCost(card, usage);
        double multiplier = card == null ? 1 : card.schedule().multiplierAt(facts == null ? null : facts.at())
                * tierMultiplier(card, facts) * geoMultiplier(facts);
        double fees = facts == null ? 0 : facts.webSearchRequests() * WEB_SEARCH_USD;
        return tokens * multiplier + fees;
    }

    // The per-token part, in dollars.
    static double tokenCost(RateCard card, TokenUsage usage) {
        long prompt = usage.promptTokensTotal() > 0
                ? usage.promptTokensTotal()
                : usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
        boolean longContext = card.longContextTokens() > 0 && prompt > card.longContextTokens();

        double input = longContext ? given(card.longInput(), card.input()) : card.input();
        double output = longContext ? given(card.longOutput(), card.output()) : card.output();
        // A cache rate left empty means "not entered", not "free": it falls back to the input rate,
        // what those tokens cost before caching was priced at all.
        double read = orInput(longContext ? given(card.longCacheRead(), card.cacheRead()) : card.cacheRead(), input);
        double write = orInput(longContext ? given(card.longCacheWrite(), card.cacheWrite()) : card.cacheWrite(), input);
        double writeLong = write * longWriteRatio(card);

        long longWrites = Math.min(usage.longCacheWriteTokens(), usage.cacheWriteTokens());
        long shortWrites = usage.cacheWriteTokens() - longWrites;
        return (usage.uncachedInputTokens() * input
                + usage.outputTokens() * output
                + usage.cacheReadTokens() * read
                + shortWrites * write
                + longWrites * writeLong) / 1_000_000.0;
    }

    // A one-hour write against a five-minute one: the card's own ratio where both are given, else
    // Anthropic's.
    private static double longWriteRatio(RateCard card) {
        if (card.cacheWriteLong() != null && card.cacheWriteLong() > 0) {
            double shortWrite = orInput(card.cacheWrite(), card.input());
            if (shortWrite > 0) return card.cacheWriteLong() / shortWrite;
        }
        return LONG_CACHE_WRITE_FACTOR;
    }

    /**
     * The tier the response says served the call. Flex and batch are half price at OpenAI, Google
     * and Anthropic alike. Priority costs more at OpenAI (2x, 2.5x on GPT-5.5) and Google (1.8x);
     * Anthropic's priority tier is capacity bought in advance, not a per-token premium.
     */
    static double tierMultiplier(RateCard card, CallFacts facts) {
        if (facts == null || facts.serviceTier() == null) return 1;
        String tier = facts.serviceTier().trim().toLowerCase(Locale.ROOT);
        return switch (tier) {
            case "flex", "batch" -> 0.5;
            case "priority" -> {
                String protocol = facts.protocol() == null ? "" : facts.protocol();
                if (protocol.equals("ANTHROPIC")) yield 1;
                if (card.priorityMultiplier() != null) yield card.priorityMultiplier();
                yield protocol.equals("GEMINI") ? 1.8 : 2.0;
            }
            default -> 1;
        };
    }

    // Anthropic's inference_geo: "us" keeps the call in the US at 1.1x; anything else is the default.
    static double geoMultiplier(CallFacts facts) {
        return facts != null && "us".equalsIgnoreCase(facts.inferenceGeo()) ? US_ONLY_INFERENCE : 1;
    }

    private static double given(Double value, double fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static double orInput(double rate, double input) {
        return rate > 0 ? rate : input;
    }
}
