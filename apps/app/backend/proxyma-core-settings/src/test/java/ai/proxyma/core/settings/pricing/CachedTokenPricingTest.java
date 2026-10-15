package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a cached prompt costs.
 *
 * <p>Providers disagree on what "input tokens" holds. Anthropic's excludes both cache categories;
 * OpenAI, Azure, DeepSeek and Gemini count cached tokens INSIDE it. Pricing inputTokens at the
 * input rate and cacheReadTokens at the read rate therefore billed every OpenAI cache hit twice -
 * once at full price and once at the discount - so a model that cached well looked MORE expensive
 * than one that did not. Found 2026-09-24 while adding the prompt-cache setting.
 */
class CachedTokenPricingTest {

    // $1 per million input, $10 out, $0.10 cache read, $1.25 cache write: easy to read back.
    private static final double IN = 1.0, OUT = 10.0, READ = 0.10, WRITE = 1.25;

    private ModelPricingService pricing;

    @BeforeEach
    void setUp() {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing rate = new ModelPricing();
        rate.setProvider("p");
        rate.setModel("m");
        rate.setInputPricePerMillion(IN);
        rate.setOutputPricePerMillion(OUT);
        rate.setCacheReadPricePerMillion(READ);
        rate.setCacheWritePricePerMillion(WRITE);
        when(repository.findByProviderAndModel("p", "m")).thenReturn(Optional.of(rate));
        pricing = new ModelPricingService(repository);
    }

    @Test
    @DisplayName("OpenAI shape: cached tokens are inside the input count and are billed ONCE, at the read rate")
    void openAiCacheHitIsNotDoubleCharged() {
        // 1,000,000 prompt tokens of which 800,000 were a cache hit; the total equals the input.
        TokenUsage usage = new TokenUsage(1_000_000, 0, 800_000, 0, 1_000_000);

        assertEquals(200_000L, usage.uncachedInputTokens());
        assertEquals(0.2 * IN + 0.8 * READ, pricing.costUsd("p", "m", usage), 1e-9);
    }

    @Test
    @DisplayName("Anthropic shape: input already excludes both cache categories, so it is billed as it stands")
    void anthropicInputIsBilledAsReported() {
        // input 200k, read 800k, write 100k - the prompt was 1.1M, which the extractor totals.
        TokenUsage usage = new TokenUsage(200_000, 1_000_000, 800_000, 100_000, 1_100_000);

        assertEquals(200_000L, usage.uncachedInputTokens());
        assertEquals(0.2 * IN + 1.0 * OUT + 0.8 * READ + 0.1 * WRITE, pricing.costUsd("p", "m", usage), 1e-9);
    }

    @Test
    @DisplayName("a usage with no prompt total falls back to the input count as it stands")
    void noTotalFallsBackToInput() {
        TokenUsage usage = new TokenUsage(500_000, 0, 0, 0, 0);

        assertEquals(500_000L, usage.uncachedInputTokens());
        assertEquals(0.5 * IN, pricing.costUsd("p", "m", usage), 1e-9);
    }

    @Test
    @DisplayName("streamed chunks fold to the field-wise maximum, keeping what only the first chunk said")
    void streamedAnthropicUsageKeepsItsInput() {
        // message_start: input and both cache counts. message_delta: the output alone.
        TokenUsage start = new TokenUsage(1_200, 0, 9_000, 400, 10_600);
        TokenUsage delta = new TokenUsage(0, 350, 0, 0, 0);

        TokenUsage folded = TokenUsage.EMPTY.maxWith(start).maxWith(delta);

        assertEquals(new TokenUsage(1_200, 350, 9_000, 400, 10_600), folded);
    }

    @Test
    @DisplayName("a cache rate left at zero bills cached tokens at the input rate, not at nothing")
    void missingCacheRateFallsBackToInput() {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing rate = new ModelPricing();
        rate.setProvider("q");
        rate.setModel("m");
        rate.setInputPricePerMillion(IN);
        rate.setOutputPricePerMillion(OUT);
        when(repository.findByProviderAndModel("q", "m")).thenReturn(Optional.of(rate));
        ModelPricingService unpricedCache = new ModelPricingService(repository);

        // OpenAI shape: 1M prompt, 800k of it a cache hit, and no cache-read rate entered.
        TokenUsage usage = new TokenUsage(1_000_000, 0, 800_000, 0, 1_000_000);

        assertEquals(1.0 * IN, unpricedCache.costUsd("q", "m", usage), 1e-9);
    }
    @Test
    @DisplayName("one-hour cache writes cost 2x input: 1.6 times the configured five-minute write rate")
    void oneHourCacheWritesArePricedAsSuch() {
        // 1,000,000 written with a one-hour lifetime: 2x the $1 input rate, not the 1.25x rate.
        TokenUsage usage = new TokenUsage(0, 0, 0, 1_000_000, 1_000_000).withLongCacheWrites();

        assertEquals(2.0 * IN, pricing.costUsd("p", "m", usage), 1e-9);
    }

    @Test
    @DisplayName("the one-hour mark survives folding and summing, and unmarked writes stay at the five-minute rate")
    void oneHourMarkCarriesThroughAggregation() {
        TokenUsage longTurn = new TokenUsage(0, 0, 0, 500_000, 500_000).withLongCacheWrites();
        TokenUsage shortTurn = new TokenUsage(0, 0, 0, 500_000, 500_000);

        TokenUsage sum = longTurn.plus(shortTurn);
        assertEquals(500_000L, sum.longCacheWriteTokens());
        assertEquals(0.5 * 2.0 * IN + 0.5 * WRITE, pricing.costUsd("p", "m", sum), 1e-9);
        assertEquals(500_000L, longTurn.maxWith(TokenUsage.EMPTY).longCacheWriteTokens());
    }
}
