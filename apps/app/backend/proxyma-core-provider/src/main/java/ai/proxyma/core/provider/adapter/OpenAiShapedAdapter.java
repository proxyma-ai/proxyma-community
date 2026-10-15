package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.provider.usage.WireUsage;
import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI's {@code /v1/chat/completions}, {@code /v1/embeddings} and {@code /v1/models} - the request
 * path every protocol speaking OpenAI's shape shares: native OpenAI, OpenAI-compatible servers,
 * OpenRouter, and LiteLLM's OpenAI route.
 *
 * <p>What THIS class does is what OpenAI documents, and nothing else. Each subclass adds what its own
 * server does differently through the hooks below - a key it does not need, a field it adds to the
 * request, a stream it sends in a shape Spring AI cannot read, usage it counts its own way, limits
 * under its own field names - so a deviation one server needs never runs against another
 * (ProviderProtocol, "native protocols first").
 */
abstract class OpenAiShapedAdapter implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiShapedAdapter.class);

    static final String OPENAI_BASE_URL = "https://api.openai.com";

    protected final ClientHttpRequestFactory externalHttpFactory;
    protected final WebClient.Builder externalWebClientBuilder;

    protected OpenAiShapedAdapter(ClientHttpRequestFactory externalHttpFactory,
                                  WebClient.Builder externalWebClientBuilder) {
        this.externalHttpFactory = externalHttpFactory;
        this.externalWebClientBuilder = externalWebClientBuilder;
    }

    // -- What a server does differently ----------------------------------------------------------

    /** The host a blank base URL means; null where there is none and a base URL is required. */
    protected String defaultBaseUrl() {
        return OPENAI_BASE_URL;
    }

    /** The key to send. OpenAI's API refuses a request without one, so by default there must be one. */
    protected String apiKey(ResolvedProvider provider) {
        if (!provider.hasApiKey()) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no API key configured");
        }
        return provider.apiKey();
    }

    /** How the usage a response states is read - see WireUsage. */
    protected WireUsage.Shape usageShape() {
        return WireUsage.Shape.OPENAI;
    }

    /** Filters over a streamed response, for a server whose stream Spring AI cannot read as sent. */
    protected List<ExchangeFilterFunction> streamFilters() {
        return List.of();
    }

    /**
     * Fields this server takes at the top level of a chat request beyond OpenAI's, for a call with
     * this prompt-cache lifetime - null for none. OpenAI's own API refuses a field it does not know.
     */
    protected Map<String, Object> extraBody(ResolvedProvider provider, PromptCache cache) {
        return null;
    }

    /** What one entry of the model list says about a model's limits. OpenAI's states none. */
    protected ModelLimits limitsOf(Map<String, Object> model) {
        return ModelLimits.UNKNOWN;
    }

    /** Whether to ask the model list for limits at all. */
    protected boolean statesLimits() {
        return false;
    }

    /**
     * Whether this model refuses sampling parameters and max_tokens - OpenAI's own reasoning
     * models (ReasoningModels). Only native OpenAI says yes; a gateway or an OpenAI-compatible
     * server applies its own rules to these fields.
     */
    protected boolean reasoningModel(String model) {
        return false;
    }

    // The tone's sampling and the output ceiling, in the form this model accepts. A reasoning model
    // takes no temperature or top_p and its ceiling as max_completion_tokens; the defaults and the
    // per-call options must agree on that, since Spring AI merges one into the other.
    private OpenAiChatOptions.Builder sampling(OpenAiChatOptions.Builder builder, String model, Tone tone,
                                               int maxTokens) {
        if (reasoningModel(model)) {
            return builder.maxCompletionTokens(LlmProviderAdapter.ceiling(maxTokens));
        }
        return builder
                .temperature(tone.getTemperature())
                .topP(tone.getTopP())
                .maxTokens(LlmProviderAdapter.ceiling(maxTokens));
    }

    // -- The shared path --------------------------------------------------------------------------

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        log.debug("[Proxy] Building {} ChatModel for {} on '{}' (baseUrl={})",
                protocol().displayName(), model, provider.name(), baseUrl(provider));
        return OpenAiChatModel.builder()
                .openAiApi(api(provider))
                .retryTemplate(ProviderRetryTemplates.CHAT)
                .defaultOptions(sampling(OpenAiChatOptions.builder().model(model), model, tone, maxTokens)
                        // Covers every caller that streams with GENERIC options rather than the
                        // ones buildChatOptions() below returns - SmartChatMemory.condense() is
                        // the live example. Spring AI merges unset runtime fields from these
                        // defaults, so the request still carries stream_options.
                        .streamUsage(true)
                        // And a server's extra fields, which the same generic callers would
                        // otherwise go without. A call's own options replace them with the
                        // turn's (buildChatOptions below); Spring AI merges the two maps, the
                        // call's key winning.
                        .extraBody(extraBody(provider, PromptCache.FIVE_MINUTES))
                        .build())
                .build();
    }

    /**
     * Chat options differing from the shared default in exactly one way: {@code streamUsage(true)}.
     *
     * <p>Without it an OpenAI-shaped server sends NO usage block on a streamed response - the
     * `usage` key is omitted from every SSE chunk unless the request asks for it with
     * {@code stream_options: {"include_usage": true}}, and Spring AI's {@code setStreamUsage} is
     * an opt-in setter with no default. Streaming is the normal path, and what it produced was zero
     * tokens on every turn.
     *
     * <p>Four things failed silently on that, which is why this is one line and a long comment:
     * cost recorded $0; the per-user monthly quota never advanced, so quota gates could not bite;
     * the Usages page stayed empty; and - least obvious - Conversation.contextTokens was written as
     * 0 after every iteration, so {@code SmartChatMemory.get()}'s {@code contextTokens > 0} guard
     * was never true and CONDENSATION NEVER RAN. A conversation grew until the provider itself
     * rejected it.
     *
     * <p>Returned as {@link OpenAiChatOptions} (which implements {@link ToolCallingChatOptions})
     * rather than relying on the merge with defaultOptions above, so the request carries the flag
     * outright instead of depending on how Spring AI folds runtime options into defaults.
     *
     * <p>Anthropic, Gemini and Ollama report usage on a stream natively and need no equivalent -
     * this is a quirk of the OpenAI wire protocol, not a gap in the others.
     */
    @Override
    public ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                   List<ToolCallback> toolCallbacks) {
        return sampling(OpenAiChatOptions.builder().model(model), model, tone, maxTokens)
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks)
                .streamUsage(true)
                .build();
    }

    /** The options for one call to one instance, with the server's extra fields for this lifetime. */
    @Override
    public ToolCallingChatOptions buildChatOptions(ResolvedProvider provider, String model, Tone tone,
                                                   int maxTokens, List<ToolCallback> toolCallbacks,
                                                   PromptCache cache) {
        OpenAiChatOptions options = (OpenAiChatOptions) buildChatOptions(model, tone, maxTokens, toolCallbacks);
        Map<String, Object> extra = provider == null ? null : extraBody(provider, cache);
        if (extra != null) options.setExtraBody(extra);
        return options;
    }

    @Override
    public ModelLimits modelLimits(ResolvedProvider provider, String model) {
        if (!statesLimits()) return ModelLimits.UNKNOWN;
        return models(provider).stream()
                .filter(m -> model.equals(Objects.toString(m.get("id"), null)))
                .findFirst()
                .map(this::limitsOf)
                .orElse(ModelLimits.UNKNOWN);
    }

    @Override
    public EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        log.debug("[Proxy] Building {} EmbeddingModel for {} on '{}'", protocol().displayName(), model, provider.name());
        return new OpenAiEmbeddingModel(api(provider),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build(),
                ProviderRetryTemplates.EMBEDDING);
    }

    @Override
    public List<String> listModels(ResolvedProvider provider) {
        log.info("[Proxy] Listing models for '{}' from {}/v1/models", provider.name(), baseUrl(provider));
        return models(provider).stream()
                .map(m -> Objects.toString(m.get("id"), null))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    /** GET /v1/models, every entry as the server sent it. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> models(ResolvedProvider provider) {
        Map<String, Object> body = rest(provider).get().uri(baseUrl(provider) + "/v1/models")
                .header("Authorization", "Bearer " + apiKey(provider))
                .retrieve().body(Map.class);
        return body == null ? List.of() : (List<Map<String, Object>>) body.getOrDefault("data", List.of());
    }

    protected RestClient rest(ResolvedProvider provider) {
        return ProviderClients.applyHeaders(RestClient.builder().requestFactory(externalHttpFactory), provider).build();
    }

    protected String baseUrl(ResolvedProvider provider) {
        if (provider.hasBaseUrl()) return provider.baseUrl();
        String fallback = defaultBaseUrl();
        if (fallback == null) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no base URL configured");
        }
        return fallback;
    }

    protected OpenAiApi api(ResolvedProvider provider) {
        // Both clients read the usage each response states (WireUsage), in this server's way.
        RestClient.Builder rest = ProviderClients.applyHeaders(
                RestClient.builder().requestFactory(externalHttpFactory), provider)
                .requestInterceptor(WireUsage.callInterceptor(usageShape()));
        WebClient.Builder web = ProviderClients.applyHeaders(externalWebClientBuilder.clone(), provider)
                .filter(WireUsage.streamFilter(usageShape()));
        for (ExchangeFilterFunction filter : streamFilters()) web.filter(filter);

        return OpenAiApi.builder()
                .apiKey(apiKey(provider))
                .baseUrl(baseUrl(provider))
                .restClientBuilder(rest)
                .webClientBuilder(web)
                .responseErrorHandler(ProviderErrors.HANDLER)
                .build();
    }
}
