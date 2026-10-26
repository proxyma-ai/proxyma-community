package ai.proxyma.core.provider.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini bills its thinking at the output rate and states it apart from the answer
 * (thoughtsTokenCount beside candidatesTokenCount); Spring AI's completion count is the answer
 * alone, so a call that thought was priced without its thinking until 2026-10-22.
 */
class GeminiThinkingTokensTest {

    @Test
    @DisplayName("thinking counts as output, a tool's prompt as input, the cached part stays a read")
    void thinkingIsOutput() {
        // prompt 10,000 (4,000 of it cached), answer 300, thinking 1,200, a tool's prompt 500.
        GoogleGenAiUsage gemini = new GoogleGenAiUsage(10_000, 300, 12_000, 1_200, 4_000, 500,
                List.of(), List.of(), List.of(), List.of(), null, null);

        TokenUsage usage = TokenUsageExtractor.extract(gemini);

        assertThat(usage.outputTokens()).isEqualTo(1_500);
        assertThat(usage.inputTokens()).isEqualTo(10_500);
        assertThat(usage.promptTokensTotal()).isEqualTo(10_500);
        assertThat(usage.cacheReadTokens()).isEqualTo(4_000);
        assertThat(usage.uncachedInputTokens()).isEqualTo(6_500);
    }

    @Test
    @DisplayName("a model that does not think reads as it always did")
    void noThinking() {
        GoogleGenAiUsage gemini = new GoogleGenAiUsage(800, 200, 1_000, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null);

        TokenUsage usage = TokenUsageExtractor.extract(gemini);

        assertThat(usage.outputTokens()).isEqualTo(200);
        assertThat(usage.inputTokens()).isEqualTo(800);
    }
}
