package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.CallFacts;
import ai.proxyma.core.common.usage.CostSource;
import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A call's cost is settled once, where the call is made (2026-10-22). The maintainer's rule: "In
 * all cases, if the provider provide cost, always use it." Otherwise the rate entered for the
 * model, else its list price where the protocol serves it, else nothing.
 */
class SettledCostTest {

    private ModelPricingService pricing;

    @BeforeEach
    void setUp() {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        when(repository.findByProviderAndModel(anyString(), anyString())).thenReturn(Optional.empty());
        ModelPricing rate = new ModelPricing();
        rate.setProvider("Mine");
        rate.setModel("claude-opus-5-5");
        rate.setInputPricePerMillion(1);
        rate.setOutputPricePerMillion(1);
        when(repository.findByProviderAndModel("Mine", "claude-opus-5-5")).thenReturn(Optional.of(rate));
        pricing = new ModelPricingService(repository);
    }

    private static CallFacts facts(Double reported, String protocol) {
        return new CallFacts(reported, null, null, 0, protocol, Instant.parse("2026-10-22T12:00:00Z"));
    }

    private static final TokenUsage MILLION_IN = new TokenUsage(1_000_000, 0, 0, 0, 1_000_000);

    @Test
    @DisplayName("a cost the provider stated wins over the rate, the list and every multiplier")
    void reportedCostWins() {
        TokenUsage settled = pricing.settle("Mine", "claude-opus-5-5", MILLION_IN, facts(0.1234, "OPENROUTER"));
        assertThat(settled.settledCostUsd()).isEqualTo(0.1234);
        assertThat(settled.costSource()).isEqualTo(CostSource.REPORTED);
        assertThat(pricing.costUsd("Mine", "claude-opus-5-5", settled)).isEqualTo(0.1234);
    }

    @Test
    @DisplayName("an entered rate beats the list price; with no rate, the list prices it where the protocol serves the model")
    void rateThenListThenNothing() {
        assertThat(pricing.settle("Mine", "claude-opus-5-5", MILLION_IN, facts(null, "ANTHROPIC")))
                .satisfies(u -> {
                    assertThat(u.settledCostUsd()).isCloseTo(1.0, within(1e-9));
                    assertThat(u.costSource()).isEqualTo(CostSource.RATE);
                });
        assertThat(pricing.settle("Anthropic", "claude-opus-5-5", MILLION_IN, facts(null, "ANTHROPIC")))
                .satisfies(u -> {
                    assertThat(u.settledCostUsd()).isCloseTo(4.0, within(1e-9));
                    assertThat(u.costSource()).isEqualTo(CostSource.LIST);
                });
        // The same name on your own server is not Anthropic's price.
        assertThat(pricing.settle("Local", "claude-opus-5-5", MILLION_IN, facts(null, "OLLAMA")))
                .satisfies(u -> {
                    assertThat(u.settledCostUsd()).isZero();
                    assertThat(u.costSource()).isEqualTo(CostSource.NONE);
                });
    }

    @Test
    @DisplayName("a turn that sums settled and unsettled calls prices each call once")
    void mixedSumPricesEachCallOnce() {
        TokenUsage reported = pricing.settle("Mine", "claude-opus-5-5", MILLION_IN, facts(0.5, "LITELLM"));
        TokenUsage byHand = new TokenUsage(2_000_000, 0, 0, 0, 2_000_000);

        TokenUsage turn = reported.plus(byHand);
        assertThat(turn.inputTokens()).isEqualTo(3_000_000);
        // 0.5 reported for the first call, plus the second at the entered $1/M.
        assertThat(pricing.costUsd("Mine", "claude-opus-5-5", turn)).isCloseTo(2.5, within(1e-9));
        assertThat(pricing.price("Mine", "claude-opus-5-5", turn).source()).isEqualTo(CostSource.REPORTED);
        // Nothing settled at all prices the whole as it always did.
        assertThat(pricing.costUsd("Mine", "claude-opus-5-5", byHand.plus(byHand))).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("within one streamed call, a reported cost replaces the estimates of the chunks before it")
    void reportedBeatsEarlierEstimates() {
        TokenUsage early = pricing.settle("Mine", "claude-opus-5-5",
                new TokenUsage(5_000_000, 0, 0, 0, 5_000_000), facts(null, "OPENROUTER"));
        TokenUsage last = pricing.settle("Mine", "claude-opus-5-5",
                new TokenUsage(5_000_000, 100, 0, 0, 5_000_000), facts(0.01, "OPENROUTER"));

        TokenUsage call = TokenUsage.EMPTY.maxWith(early).maxWith(last);
        assertThat(early.settledCostUsd()).isGreaterThan(1);
        assertThat(call.settledCostUsd()).isEqualTo(0.01);
        assertThat(call.costSource()).isEqualTo(CostSource.REPORTED);
        // The same in the other order.
        assertThat(TokenUsage.EMPTY.maxWith(last).maxWith(early).settledCostUsd()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("marking the writes as one-hour after settling keeps the settled cost")
    void longWriteMarkKeepsTheCost() {
        TokenUsage settled = pricing.settle("Mine", "claude-opus-5-5", MILLION_IN, facts(0.2, "OPENROUTER"));
        assertThat(settled.withLongCacheWrites().settledCostUsd()).isEqualTo(0.2);
        assertThat(pricing.costUsd("Mine", "claude-opus-5-5", settled.withLongCacheWrites())).isEqualTo(0.2);
    }
}
