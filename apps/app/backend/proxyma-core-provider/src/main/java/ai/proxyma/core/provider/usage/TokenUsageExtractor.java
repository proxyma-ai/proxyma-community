package ai.proxyma.core.provider.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import com.azure.ai.openai.models.CompletionsUsage;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.ai.openai.api.OpenAiApi;

// Maps Spring AI's generic ChatResponseMetadata.Usage (promptTokens/completionTokens, always
// present) plus each provider's own native usage object (Usage.getNativeUsage(), provider-shaped)
// into one common TokenUsage - specifically to recover cache read/write counts, which only exist
// on the native object since Spring AI's own Usage interface has no cache-token concept.
//
// Per-provider native shape (verified against spring-ai 1.1.7 / azure-ai-openai 1.0.0-beta.16):
// - Anthropic:        AnthropicApi.Usage - cacheReadInputTokens + cacheCreationInputTokens (both directions)
// - OpenAI:            OpenAiApi.Usage.promptTokensDetails().cachedTokens() (reads only)
// - Azure OpenAI:      com.azure.ai.openai.models.CompletionsUsage.getPromptTokensDetails().getCachedTokens() (reads only)
// - Google GenAI:      GoogleGenAiUsage itself (not nativeUsage) exposes getCachedContentTokenCount() (reads only)
// - Ollama:            no cache concept in its API - always 0
// DeepSeek, Mistral and the rest are OPENAI_COMPATIBLE, read through OpenAiApi.Usage.
// - A TokenUsage:      a chunk the gateway corrected from the wire (WireUsage), returned as it is
public final class TokenUsageExtractor {

    private TokenUsageExtractor() {}

    /**
     * The same token counts as {@link #extract(Usage)}, reached through a whole response.
     *
     * <p>The two overloads used to differ in more than convenience: this one also claimed the cost
     * the provider had reported for the call, parked under the completion id by a side channel, so
     * calling the Usage overload at a spend site silently priced from the rate table instead. The
     * per-instance cost reporting behind that is gone as of 0.1.2, so the overloads now differ only
     * in what the caller happens to be holding, and neither can be the wrong one to pick.
     */
    public static TokenUsage extract(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return TokenUsage.EMPTY;
        return extract(response.getMetadata().getUsage());
    }

    public static TokenUsage extract(Usage usage) {
        if (usage == null) return TokenUsage.EMPTY;
        // Already corrected from what the provider's response stated - see WireUsage.
        if (usage.getNativeUsage() instanceof TokenUsage stated) return stated;
        long input = nz(usage.getPromptTokens());
        long output = nz(usage.getCompletionTokens());
        long cacheRead = 0;
        long cacheWrite = 0;
        // Default: promptTokens already IS the true total (cachedTokens, when present, is a
        // subset already counted inside it) - true for every provider below except Anthropic.
        long promptTokensTotal = input;

        if (usage instanceof GoogleGenAiUsage googleUsage) {
            cacheRead = nz(googleUsage.getCachedContentTokenCount());
        } else {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof AnthropicApi.Usage anthropicUsage) {
                cacheRead = nz(anthropicUsage.cacheReadInputTokens());
                cacheWrite = nz(anthropicUsage.cacheCreationInputTokens());
                // Anthropic's input_tokens explicitly EXCLUDES both cache_read and
                // cache_creation tokens (they're separate, additive billing categories) - so
                // unlike every other provider here, the true prompt size is the sum of all
                // three, not input_tokens alone.
                promptTokensTotal = input + cacheRead + cacheWrite;
            } else if (nativeUsage instanceof OpenAiApi.Usage openAiUsage && openAiUsage.promptTokensDetails() != null) {
                cacheRead = nz(openAiUsage.promptTokensDetails().cachedTokens());
            } else if (nativeUsage instanceof CompletionsUsage azureUsage && azureUsage.getPromptTokensDetails() != null) {
                cacheRead = nz(azureUsage.getPromptTokensDetails().getCachedTokens());
            }
        }

        return new TokenUsage(input, output, cacheRead, cacheWrite, promptTokensTotal);
    }

    private static long nz(Integer i) {
        return i != null ? i : 0;
    }
}
