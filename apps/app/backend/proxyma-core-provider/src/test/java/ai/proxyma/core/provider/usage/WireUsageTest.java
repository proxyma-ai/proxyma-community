package ai.proxyma.core.provider.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two usage shapes as WireUsage reads them - the numbers are the probe's own (0.29.2 feedback).
 */
class WireUsageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static TokenUsage anthropic(String usage) throws Exception {
        return WireUsage.fromAnthropic(JSON.readTree(usage));
    }

    private static TokenUsage openAi(String usage) throws Exception {
        return WireUsage.fromOpenAi(JSON.readTree(usage));
    }

    private static TokenUsage liteLlmOpenAiRoute(String usage) throws Exception {
        return WireUsage.fromOpenAiCacheOutside(JSON.readTree(usage));
    }

    @Test
    @DisplayName("Anthropic: the prompt is input plus both cache counts, and a missing key is zero")
    void anthropicSums() throws Exception {
        TokenUsage u = anthropic("{\"input_tokens\":24,\"output_tokens\":9,\"cache_read_input_tokens\":10842}");
        assertThat(u.promptTokensTotal()).isEqualTo(10866);
        assertThat(u.cacheWriteTokens()).isZero();
        assertThat(u.uncachedInputTokens()).isEqualTo(24);
        assertThat(u.cacheLifetimeStated()).isFalse();
    }

    @Test
    @DisplayName("Anthropic: cache_creation states the one-hour part of the writes")
    void anthropicLifetime() throws Exception {
        TokenUsage u = anthropic("{\"input_tokens\":0,\"output_tokens\":5,\"cache_creation_input_tokens\":10825,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":2,\"ephemeral_1h_input_tokens\":10823}}");
        assertThat(u.longCacheWriteTokens()).isEqualTo(10823);
        assertThat(u.cacheLifetimeStated()).isTrue();
    }

    @Test
    @DisplayName("OpenAI itself: cached tokens are inside prompt_tokens, and stay read that way")
    void openAiCountsCacheInside() throws Exception {
        TokenUsage u = openAi("{\"prompt_tokens\":2006,\"completion_tokens\":300,\"total_tokens\":2306,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1920}}");
        assertThat(u.promptTokensTotal()).isEqualTo(2006);
        assertThat(u.uncachedInputTokens()).isEqualTo(86);
    }

    @Test
    @DisplayName("LiteLLM's OpenAI route counting the cache outside prompt_tokens is told apart by its total")
    void gatewayCountsCacheOutside() throws Exception {
        String usage = "{\"completion_tokens\":19,\"prompt_tokens\":10,\"total_tokens\":10856,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":10827,\"cache_creation_tokens\":10827,"
                + "\"cache_creation_token_details\":{\"ephemeral_5m_input_tokens\":10827,\"ephemeral_1h_input_tokens\":0}}}";
        // OpenAI's own reading never takes the cache for outside the prompt - that is LiteLLM's.
        assertThat(openAi(usage).promptTokensTotal()).isEqualTo(10);
        TokenUsage write = liteLlmOpenAiRoute("{\"completion_tokens\":19,\"prompt_tokens\":10,\"total_tokens\":10856,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":10827,\"cache_creation_tokens\":10827,"
                + "\"cache_creation_token_details\":{\"ephemeral_5m_input_tokens\":10827,\"ephemeral_1h_input_tokens\":0}}}");
        assertThat(write.cacheWriteTokens()).isEqualTo(10827);
        assertThat(write.promptTokensTotal()).isEqualTo(10837);
        assertThat(write.uncachedInputTokens()).isEqualTo(10);
        assertThat(write.cacheLifetimeStated()).isTrue();
        assertThat(write.longCacheWriteTokens()).isZero();
    }

    @Test
    @DisplayName("a total that differs for another reason is not taken for a cache outside the prompt")
    void unrelatedExcessIsIgnored() throws Exception {
        // Reasoning counted apart: total exceeds prompt + completion, but not by the cache.
        TokenUsage u = liteLlmOpenAiRoute("{\"prompt_tokens\":500,\"completion_tokens\":40,\"total_tokens\":900,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":256}}");
        assertThat(u.promptTokensTotal()).isEqualTo(500);
        assertThat(u.uncachedInputTokens()).isEqualTo(244);
    }

    @Test
    @DisplayName("an event line is read only when it is a data line carrying a usage")
    void eventLines() {
        assertThat(WireUsage.usageOfEvent("event: message_delta", WireUsage.Shape.ANTHROPIC)).isEqualTo(TokenUsage.EMPTY);
        assertThat(WireUsage.usageOfEvent("data: [DONE]", WireUsage.Shape.OPENAI)).isEqualTo(TokenUsage.EMPTY);
        assertThat(WireUsage.usageOfEvent("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}",
                WireUsage.Shape.ANTHROPIC).inputTokens()).isEqualTo(7);
        assertThat(WireUsage.usageOfEvent("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5,\"cache_read_input_tokens\":10823}}",
                WireUsage.Shape.ANTHROPIC).cacheReadTokens()).isEqualTo(10823);
    }

    @Test
    @DisplayName("a usage the response stated is not re-marked by the lifetime the request asked for")
    void statedLifetimeWins() {
        TokenUsage stated = new TokenUsage(0, 5, 0, 100, 100, 0, true);
        assertThat(stated.withLongCacheWrites().longCacheWriteTokens()).isZero();
        TokenUsage unstated = new TokenUsage(0, 5, 0, 100, 100);
        assertThat(unstated.withLongCacheWrites().longCacheWriteTokens()).isEqualTo(100);
    }
}
