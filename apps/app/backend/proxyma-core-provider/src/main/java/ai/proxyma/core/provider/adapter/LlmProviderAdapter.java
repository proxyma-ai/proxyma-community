package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Speaks ONE wire protocol. Not one vendor.
 *
 * Every method takes the {@link ResolvedProvider} to talk to, so an adapter is a stateless
 * singleton that holds no configuration of its own. That inversion is the whole refactor: adapters
 * used to read their own settings prefix, which is why a vendor could only exist if someone wrote a
 * class for it, and why two accounts on one vendor were inexpressible.
 *
 * <p>It also deletes three mechanisms that only existed to work around that design:
 * <ul>
 *   <li>{@code withEndpoint}/{@code supportsPerRoleEndpoint} - per-role base URL overrides, added so
 *       one vLLM "provider" could serve roles living on different ports. A port is now just another
 *       instance, so the override, its copy-constructors and the @Autowired trap they needed are
 *       gone.
 *   <li>{@code getProviderName} - identity is the protocol now, and the NAME belongs to the user.
 *   <li>{@code isApiKeyConfigured}/{@code isHttpProbeable}/{@code probeUrl} - all questions about a
 *       configured instance, answerable from {@link ResolvedProvider} without asking the adapter.
 * </ul>
 */
public interface LlmProviderAdapter {

    /** The protocol this adapter implements. One adapter per constant, resolved by the factory. */
    ProviderProtocol protocol();

    ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens);

    /**
     * Throws where this protocol has no embedding endpoint (Anthropic) or the adapter implements
     * none yet - the same contract as {@link #listModels} and {@link #rerank} below, and the same
     * exception type, so ProviderConnectivityService classifies all three as `not_supported`.
     *
     * <p>It used to return null and the dropdowns used to filter on a declared capability so this
     * could not be reached. Nothing filters now (see {@link ProviderProtocol}), so this IS the
     * mechanism that tells an operator the pairing they chose cannot work, and it has to say which
     * protocol and which role rather than returning something a caller can mistake for a model.
     */
    default EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        throw new UnsupportedOperationException(
                "Embedding not supported for protocol: " + protocol());
    }

    /**
     * The output ceiling to put in a request: the number, or NOTHING when there is none to send.
     *
     * <p>{@code maxTokens} of zero or less means nobody set a ceiling and the provider stated none
     * for this model (see {@link #modelLimits}). The request then carries no ceiling at all and
     * the provider applies its own - which is the one value that cannot be refused. A protocol
     * that REQUIRES the field (Anthropic) cannot use this and falls back to a number instead.
     */
    static Integer ceiling(int maxTokens) {
        return maxTokens > 0 ? maxTokens : null;
    }

    /**
     * What this provider says the model can take in and give back - {@link ModelLimits#UNKNOWN}
     * where the protocol has no way to ask (Azure) or the service does not say (OpenAI itself).
     * May throw on a failed request; LlmProviderFactory turns that into UNKNOWN and remembers it.
     */
    default ModelLimits modelLimits(ResolvedProvider provider, String model) {
        return ModelLimits.UNKNOWN;
    }

    // Per-request options, as opposed to the ChatModel's defaultOptions built above. Takes no
    // provider: nothing here depends on where the model lives.
    default ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                     List<ToolCallback> toolCallbacks) {
        return ToolCallingChatOptions.builder()
                .model(model)
                .maxTokens(ceiling(maxTokens))
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks)
                .temperature(tone.getTemperature())
                .topK(tone.getTopK())
                .topP(tone.getTopP())
                .build();
    }

    /**
     * The same, with a prompt-cache lifetime. Only a protocol that takes an explicit cache
     * instruction overrides this - Anthropic here, and OpenRouter and LiteLLM in the overload
     * below. Everything else caches on its own terms (OpenAI and Gemini automatically) or
     * not at all, so the default ignores the lifetime rather than pretending to honor it.
     * {@code cache} may be null, meaning the protocol's default.
     */
    default ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                     List<ToolCallback> toolCallbacks, PromptCache cache) {
        return buildChatOptions(model, tone, maxTokens, toolCallbacks);
    }

    /**
     * The same, for one provider instance - what the gateway calls. Only a protocol whose request
     * depends on the INSTANCE overrides it (LiteLLM: the API the instance chose; the OpenAI-shaped
     * ones: the fields their server adds); every other protocol decides from the lifetime alone.
     */
    default ToolCallingChatOptions buildChatOptions(ResolvedProvider provider, String model, Tone tone,
                                                     int maxTokens, List<ToolCallback> toolCallbacks,
                                                     PromptCache cache) {
        return buildChatOptions(model, tone, maxTokens, toolCallbacks, cache);
    }

    /**
     * The models this instance currently serves, for the Settings page's model pickers. Throws
     * UnsupportedOperationException where the protocol has no listing endpoint (Azure deployment
     * names need the management API, not the inference key), or any exception on a failed request -
     * the caller turns both into "could not load models".
     */
    default List<String> listModels(ResolvedProvider provider) {
        throw new UnsupportedOperationException(
                "Model listing not supported for protocol: " + protocol());
    }

    /**
     * Cross-encoder rerank via the instance's own endpoint. Only attempted when the instance has it
     * turned on ({@link ResolvedProvider#nativeRerank()}) - it is a vLLM/Cohere extension that most
     * OpenAI-compatible services answer with a 404, so it cannot be inferred from the protocol.
     * Returned order/length is provider-defined.
     *
     * <p>IT RETURNS THE USAGE TOO, since 2026-09-16. This call is billed like any other and used to
     * return scores alone, so the one ledger in the product never saw it - see
     * DefaultLlmGateway.recordSpend, whose own comment claimed the opposite for months.
     */
    default RerankResponse rerank(ResolvedProvider provider, String query, List<String> documents,
                                  String model) {
        throw new UnsupportedOperationException("Native rerank not supported by protocol: " + protocol());
    }

    /** `index` is the position of the scored document in the `documents` list passed to rerank(). */
    record RerankResult(int index, double score) {}

    /**
     * What a rerank endpoint answered, and what it says that cost.
     *
     * <p>{@code usage} is {@link TokenUsage#EMPTY} when the service reports none - several report
     * only scores. The call is still recorded in that case: a row with no tokens says "this
     * happened and the provider did not say what it cost", which is the truth and is countable.
     * Silence said nothing happened at all.
     */
    record RerankResponse(List<RerankResult> results, TokenUsage usage) {}
}
