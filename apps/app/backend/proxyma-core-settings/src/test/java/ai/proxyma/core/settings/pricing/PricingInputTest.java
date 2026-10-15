package ai.proxyma.core.settings.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * settings-core-26: a rate is what every later call is priced at against a spending limit, so a
 * NaN, an infinity or a negative one - all accepted, the strings parsed as rates - defeats the
 * budget it reaches; and a missing provider was stored as the word "null".
 */
class PricingInputTest {

    @Test
    @DisplayName("a rate is a finite number, zero or more")
    void ratesAreFiniteAndNonNegative() {
        assertThat(ModelPricingController.numberOf(Map.of("input", 2.5), "input")).isEqualTo(2.5);
        assertThat(ModelPricingController.numberOf(Map.of("input", "0"), "input")).isZero();
        for (Object bad : new Object[] {"NaN", "Infinity", -1, "-0.5", "cheap", Double.NaN}) {
            assertThatThrownBy(() -> ModelPricingController.numberOf(Map.of("input", bad), "input"))
                    .as(String.valueOf(bad)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> ModelPricingController.numberOf(Map.of(), "input"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("a provider and a model are named - absent or blank is refused, not stored as \"null\"")
    void namesAreRequired() {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", null);
        body.put("model", "  ");
        assertThatThrownBy(() -> ModelPricingController.name(body, "provider")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ModelPricingController.name(body, "model")).isInstanceOf(ResponseStatusException.class);
        assertThat(ModelPricingController.name(Map.of("model", " gpt-x "), "model")).isEqualTo("gpt-x");
    }
}
