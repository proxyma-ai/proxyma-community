package ai.proxyma.core.settings.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in list prices: which model ids a list entry answers to, and through which protocols
 * a list price may stand in for a rate nobody entered.
 */
class ListPricesTest {

    private final ListPrices list = ListPrices.builtIn();

    @Test
    @DisplayName("the list loads, dated, with a price for each vendor's current models")
    void loads() {
        assertThat(list.asOf()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(list.find("ANTHROPIC", "claude-opus-5-5")).get()
                .satisfies(e -> assertThat(e.rates().input()).isEqualTo(4.0));
        assertThat(list.find("OPENAI", "gpt-4o")).isPresent();
        assertThat(list.find("GEMINI", "gemini-2.5-pro")).isPresent();
    }

    @Test
    @DisplayName("an id matches itself, a dated snapshot of it and a Vertex version - and nothing that merely starts like it")
    void idMatching() {
        assertThat(ListPrices.matches("claude-haiku-4-5-20251001", "claude-haiku-4-5")).isTrue();
        assertThat(ListPrices.matches("gpt-4o-2024-08-06", "gpt-4o")).isTrue();
        assertThat(ListPrices.matches("claude-opus-4-5@20251101", "claude-opus-4-5")).isTrue();
        assertThat(ListPrices.matches("gpt-5.5", "gpt-5")).isFalse();
        assertThat(ListPrices.matches("gpt-4o-mini", "gpt-4o")).isFalse();
        assertThat(ListPrices.matches("gpt-5-mini", "gpt-5")).isFalse();

        assertThat(list.find("OPENAI", "gpt-4o-mini")).get()
                .satisfies(e -> assertThat(e.rates().input()).isEqualTo(0.15));
        assertThat(list.find("GEMINI", "gemini-2.5-flash-lite")).get()
                .satisfies(e -> assertThat(e.rates().input()).isEqualTo(0.1));
    }

    @Test
    @DisplayName("a list price stands in only where the vendor serves the model - never for a model on your own hardware")
    void onlyWhereTheVendorServesIt() {
        assertThat(list.find("OLLAMA", "gpt-4o")).isEmpty();
        assertThat(list.find(null, "gpt-4o")).isEmpty();
        assertThat(list.find("ANTHROPIC", "gpt-4o")).isEmpty();
        // An OpenAI-compatible server is DeepSeek's API, or somebody's own server.
        assertThat(list.find("OPENAI_COMPATIBLE", "deepseek-flash")).isPresent();
        assertThat(list.find("OPENAI_COMPATIBLE", "gpt-4o")).isEmpty();
        assertThat(list.find("OPENAI_COMPATIBLE", "deepseek-ai/deepseek-flash")).isEmpty();
        // A reseller names the vendor first.
        assertThat(list.find("OPENROUTER", "anthropic/claude-sonnet-5-5")).isPresent();
        assertThat(list.find("LITELLM", "claude-sonnet-5-5")).isPresent();
        assertThat(list.find("AZURE_OPENAI", "gpt-4.1")).isPresent();
    }
}
