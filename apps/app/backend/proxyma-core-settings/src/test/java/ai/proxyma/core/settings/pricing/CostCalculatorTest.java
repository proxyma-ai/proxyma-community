package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.CallFacts;
import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * What a call costs under a rate card (2026-10-22, the maintainer's request: cost from what the
 * response states - hits, misses, writes - with five-minute and one-hour writes priced apart, and
 * the rest of what a provider bills by). Prices are each vendor's list prices of that day.
 */
class CostCalculatorTest {

    private static final double M = 1_000_000;

    // Claude Opus 5.5: $4 in, $20 out, $0.20 read, $5 five-minute write, $8 one-hour write.
    private static final RateCard OPUS = new RateCard(4, 20, 0.2, 5, 8.0, 0, null, null, null, null,
            PricingSchedule.NONE, null);

    private static CallFacts facts(String tier, String geo, long searches, String protocol, Instant at) {
        return new CallFacts(null, tier, geo, searches, protocol, at);
    }

    @Test
    @DisplayName("each kind of token at its own rate, a one-hour write at the one-hour rate")
    void eachKindAtItsRate() {
        // Anthropic shape: input 1,000 uncached, 200,000 read, 10,000 written of which 8,000 for an hour.
        TokenUsage usage = new TokenUsage(1_000, 2_000, 200_000, 10_000, 211_000, 8_000, true);
        double expected = (1_000 * 4 + 2_000 * 20 + 200_000 * 0.2 + 2_000 * 5 + 8_000 * 8) / M;
        assertThat(CostCalculator.cost(OPUS, usage, null)).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("a one-hour rate not given is 1.6 times the five-minute one, Anthropic's ratio")
    void oneHourDefaultsToAnthropicRatio() {
        RateCard card = new RateCard(3, 15, 0.3, 3.75, null, 0, null, null, null, null, PricingSchedule.NONE, null);
        TokenUsage usage = new TokenUsage(0, 0, 0, 1_000_000, 1_000_000, 1_000_000, true);
        assertThat(CostCalculator.cost(card, usage, null)).isCloseTo(6.0, within(1e-9));
    }

    @Test
    @DisplayName("a cache rate left empty is the input rate, never free")
    void emptyCacheRateIsInput() {
        RateCard card = new RateCard(2, 8, 0, 0, null, 0, null, null, null, null, PricingSchedule.NONE, null);
        TokenUsage usage = new TokenUsage(1_000_000, 0, 600_000, 0, 1_000_000);
        assertThat(CostCalculator.cost(card, usage, null)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("past the long-context threshold the WHOLE call is at the long rates, cache counted in its size")
    void longContextPricesTheWholeCall() {
        // Claude Haiku 5.5: above 100K every rate is 5x.
        RateCard haiku = ListPrices.builtIn().find("ANTHROPIC", "claude-haiku-5-5").orElseThrow().rates();
        TokenUsage small = new TokenUsage(10_000, 1_000, 80_000, 0, 90_000);
        TokenUsage large = new TokenUsage(10_000, 1_000, 100_000, 0, 110_000);

        assertThat(CostCalculator.cost(haiku, small, null))
                .isCloseTo((10_000 * 0.1 + 1_000 * 0.5 + 80_000 * 0.01) / M, within(1e-12));
        assertThat(CostCalculator.cost(haiku, large, null))
                .isCloseTo((10_000 * 0.5 + 1_000 * 2.5 + 100_000 * 0.05) / M, within(1e-12));
    }

    @Test
    @DisplayName("the tier the response names: flex and batch at half, priority at the provider's premium")
    void serviceTier() {
        TokenUsage usage = new TokenUsage(1_000_000, 0, 0, 0, 1_000_000);
        RateCard plain = new RateCard(1, 1, 0, 0, null, 0, null, null, null, null, PricingSchedule.NONE, null);
        RateCard gpt55 = ListPrices.builtIn().find("OPENAI", "gpt-5.5").orElseThrow().rates();

        assertThat(CostCalculator.cost(plain, usage, facts("flex", null, 0, "OPENAI", null))).isCloseTo(0.5, within(1e-9));
        assertThat(CostCalculator.cost(plain, usage, facts("batch", null, 0, "ANTHROPIC", null))).isCloseTo(0.5, within(1e-9));
        assertThat(CostCalculator.cost(plain, usage, facts("priority", null, 0, "OPENAI", null))).isCloseTo(2.0, within(1e-9));
        assertThat(CostCalculator.cost(plain, usage, facts("priority", null, 0, "GEMINI", null))).isCloseTo(1.8, within(1e-9));
        // Anthropic's priority tier is capacity bought in advance, not a per-token premium.
        assertThat(CostCalculator.cost(plain, usage, facts("priority", null, 0, "ANTHROPIC", null))).isCloseTo(1.0, within(1e-9));
        assertThat(CostCalculator.cost(plain, usage, facts("default", null, 0, "OPENAI", null))).isCloseTo(1.0, within(1e-9));
        // GPT-5.5's own premium is 2.5x (a prompt under its 272K long-context threshold).
        TokenUsage short100k = new TokenUsage(100_000, 0, 0, 0, 100_000);
        assertThat(CostCalculator.cost(gpt55, short100k, facts("priority", null, 0, "OPENAI", null))).isCloseTo(1.25, within(1e-9));
        // Above it, the whole call is at the long rate ($10/M) before the premium.
        assertThat(CostCalculator.cost(gpt55, usage, facts(null, null, 0, "OPENAI", null))).isCloseTo(10.0, within(1e-9));
    }

    @Test
    @DisplayName("Anthropic's US-only inference is 1.1x, and each web search is $0.01 on top")
    void regionAndSearches() {
        TokenUsage usage = new TokenUsage(1_000_000, 0, 0, 0, 1_000_000);
        assertThat(CostCalculator.cost(OPUS, usage, facts(null, "us", 0, "ANTHROPIC", null))).isCloseTo(4.4, within(1e-9));
        assertThat(CostCalculator.cost(OPUS, usage, facts(null, "global", 3, "ANTHROPIC", null))).isCloseTo(4.03, within(1e-9));
        // No rate at all: the searches the response stated are still a cost.
        assertThat(CostCalculator.cost(null, usage, facts(null, null, 2, "ANTHROPIC", null))).isCloseTo(0.02, within(1e-9));
    }

    @Test
    @DisplayName("DeepSeek: the rates are the peak price, and outside peak hours every rate is half")
    void deepSeekOffPeak() {
        RateCard flash = ListPrices.builtIn().find("OPENAI_COMPATIBLE", "deepseek-flash").orElseThrow().rates();
        TokenUsage usage = new TokenUsage(1_000_000, 0, 0, 0, 1_000_000);
        Instant mondayPeak = Instant.parse("2026-10-19T02:30:00Z");
        Instant mondayNoon = Instant.parse("2026-10-19T12:00:00Z");
        Instant saturdayPeakHour = Instant.parse("2026-10-24T02:30:00Z");

        assertThat(CostCalculator.cost(flash, usage, facts(null, null, 0, "OPENAI_COMPATIBLE", mondayPeak))).isCloseTo(0.3, within(1e-9));
        assertThat(CostCalculator.cost(flash, usage, facts(null, null, 0, "OPENAI_COMPATIBLE", mondayNoon))).isCloseTo(0.15, within(1e-9));
        assertThat(CostCalculator.cost(flash, usage, facts(null, null, 0, "OPENAI_COMPATIBLE", saturdayPeakHour))).isCloseTo(0.15, within(1e-9));
    }
}
