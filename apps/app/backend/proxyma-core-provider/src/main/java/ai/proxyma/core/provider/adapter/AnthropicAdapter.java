package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.provider.usage.WireUsage;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.api.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AnthropicAdapter implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final ClientHttpRequestFactory externalHttpFactory;
    private final WebClient.Builder externalWebClientBuilder;

    public AnthropicAdapter(@Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory,
                            @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        this.externalHttpFactory = externalHttpFactory;
        this.externalWebClientBuilder = externalWebClientBuilder;
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.ANTHROPIC;
    }

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        return buildChatModel(provider, model, tone, maxTokens, java.util.List.of());
    }

    /**
     * The same, with stream filters of a gateway's own on top of Anthropic's - a gateway that speaks
     * Anthropic's API with deviations of its own (LiteLlmAdapter). The native protocol passes none.
     */
    ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens,
                             java.util.List<org.springframework.web.reactive.function.client.ExchangeFilterFunction> gatewayStreamFilters) {
        if (!provider.hasApiKey()) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no API key configured");
        }
        log.debug("[Proxy] Building Anthropic ChatModel for {} on '{}' (baseUrl={})", model, provider.name(),
                provider.hasBaseUrl() ? provider.baseUrl() : "default");
        // An instance's own headers (for a relay or proxy in front of Anthropic) are attached to
        // BOTH clients: RestClient backs the non-streaming call() path, WebClient backs stream().
        // They now come from the instance rather than a per-provider settings field, so every
        // protocol gets them the same way (see ProviderClients).
        //
        // Both clients also read the usage each response states (WireUsage): Spring AI keeps a
        // stream's input and cache counts from message_start alone, and the one-hour split of
        // either kind of response not at all. And both move the conversation's cache breakpoint
        // to its last message (AnthropicCacheTail), where Spring AI spends them from the front.
        RestClient.Builder restClientBuilder = ProviderClients.applyHeaders(
                RestClient.builder().requestFactory(externalHttpFactory), provider)
                .requestInterceptor(AnthropicCacheTail.callInterceptor())
                .requestInterceptor(WireUsage.callInterceptor(WireUsage.Shape.ANTHROPIC));
        // A STREAMED CALL'S ERROR KEEPS ITS STATUS AND HEADERS (2026-10-08, 0.29.1 feedback).
        // AnthropicApi adds a status handler of its own to this builder that raises every error as
        // a bare RuntimeException("Response exception, Status: [...]"), which nothing recognized as
        // a rate limit, so a streamed Anthropic 429 was never retried and its Retry-After was lost.
        // WebClient tries its default handlers in the order they were added and the first match
        // wins, so this one - added before AnthropicApi's - raises a WebClientResponseException
        // carrying the status, the headers and the body. ModelCall reads all three. A refusal sent
        // AFTER the 200, as an error event, is raised the same way (AnthropicStreamErrors).
        WebClient.Builder webClientBuilder = ProviderClients.applyHeaders(
                externalWebClientBuilder.clone(), provider)
                .defaultStatusHandler(HttpStatusCode::isError, ClientResponse::createException)
                .filter(AnthropicCacheTail.streamFilter())
                .filter(WireUsage.streamFilter(WireUsage.Shape.ANTHROPIC))
                .filter(AnthropicStreamErrors.streamFilter());
        gatewayStreamFilters.forEach(webClientBuilder::filter);
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .apiKey(provider.apiKey())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                // A blocking call's error keeps its status and headers too (ProviderErrors).
                .responseErrorHandler(ProviderErrors.HANDLER);
        if (provider.hasBaseUrl()) apiBuilder.baseUrl(provider.baseUrl());
        if (!provider.headers().isEmpty()) {
            log.debug("[Proxy] Custom headers applied for '{}': {}", provider.name(), provider.headers().keySet());
        }
        return AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .retryTemplate(ProviderRetryTemplates.CHAT)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(required(maxTokens))
                        .build())
                .build();
    }

    // Anthropic refuses a request with no max_tokens, so "leave it to the provider" is not
    // available here. The number is the model's own ceiling whenever the models API states it
    // (modelLimits below, resolved before this is reached); this is what is left when it does not
    // - a model the API does not describe, or a relay in front of it that does not serve
    // /v1/models.
    private static int required(int maxTokens) {
        return maxTokens > 0 ? maxTokens : ChatSettings.FALLBACK_MAX_OUTPUT_TOKENS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelLimits modelLimits(ResolvedProvider provider, String model) {
        if (!provider.hasApiKey()) return ModelLimits.UNKNOWN;
        Map<String, Object> body = modelsApi(provider).get().uri(baseUrl(provider) + "/v1/models/{id}", model)
                .retrieve().body(Map.class);
        return ModelLimits.fromAnthropic(body);
    }

    @Override
    public ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                   List<ToolCallback> toolCallbacks) {
        return buildChatOptions(model, tone, maxTokens, toolCallbacks, PromptCache.FIVE_MINUTES);
    }

    @Override
    public ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                   List<ToolCallback> toolCallbacks, PromptCache cache) {
        // NO SAMPLING PARAMETERS, so the tone does not reach Claude. Anthropic's current models
        // (Opus 4.7 and later, Sonnet 5 and later, the Fable family) refuse temperature, top_p and
        // top_k with a 400, Sonnet 5.5 and Haiku 5.5 any non-default value, and older ones refuse
        // temperature and top_p together - so none is sent, to any of them.
        //
        // cacheOptions turns on Anthropic prompt caching (adds cache_control: ephemeral breakpoints
        // to the request). Anthropic caches only what a breakpoint marks, so this is what makes
        // cache read/write tokens show up at all.
        //
        // CONVERSATION_HISTORY rather than SYSTEM_AND_TOOLS: it caches the growing message prefix
        // as well as the system prompt and tool definitions, so a long conversation re-reads its own
        // history at cache rates instead of paying full price for it on every ReAct iteration. That
        // matters most for the largest things living in history - a loaded skill's instructions (see
        // ChatService.applyPickedSkills) and tool results - which the old strategy re-sent in full
        // every single call. WHERE in the history those breakpoints land is AnthropicCacheTail's:
        // Spring AI spends them from the front, which left a long turn's tail uncached.
        //
        // The strategy's own precondition is that the system prompt and tool list stay stable,
        // because changing either cascade-invalidates the whole conversation's cache. The system
        // prompt qualifies: it is resolved once at conversation start and never rewritten
        // afterwards. The tool list does still change once per skill unlock (ToolSkillGating
        // advertises a connector's tools only after its skill is read), which invalidates the
        // cache for that one call - the same invalidation SYSTEM_AND_TOOLS already took on a tool
        // change, just with a larger prefix to rebuild, and it happens a handful of times per
        // conversation at most.
        return AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(required(maxTokens))
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks)
                .cacheOptions(cacheOptions(cache))
                .build();
    }

    /**
     * The agent's Settings > Agents "Prompt cache" choice, as Spring AI cache options.
     *
     * <p>ONE HOUR IS SET ON EVERY MESSAGE TYPE, not only the system prompt. Spring AI gives tool
     * definitions the SYSTEM type's lifetime, and Anthropic requires a longer-lived breakpoint never
     * to FOLLOW a shorter one in the prompt - tools, system, then history - so a one-hour system
     * prompt behind five-minute history is valid but a mixed order is refused. All one-hour is the
     * shape that is always valid, and it is what "keep this conversation warm for an hour" means.
     * Spring AI adds the extended-TTL beta header itself when a one-hour breakpoint is present.
     *
     * <p>There is no "off" since 2026-10-07: the setting's Auto is resolved to five minutes or an
     * hour before it gets here (ChatSettings.PromptCache.forTurn), and anything that is not an hour
     * - Auto reaching here unresolved included - is five minutes.
     */
    static AnthropicCacheOptions cacheOptions(PromptCache cache) {
        AnthropicCacheOptions.Builder builder = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY);
        if (cache == PromptCache.ONE_HOUR) {
            for (MessageType type : MessageType.values()) {
                builder.messageTypeTtl(type, AnthropicCacheTtl.ONE_HOUR);
            }
        }
        return builder.build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listModels(ResolvedProvider provider) {
        if (!provider.hasApiKey()) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no API key configured");
        }
        String baseUrl = baseUrl(provider);
        log.info("[Proxy] Listing Anthropic models for '{}' from {}/v1/models", provider.name(), baseUrl);
        RestClient client = modelsApi(provider);
        // PAGED: the list answers 20 models unless asked for more, so the older ones dropped out of
        // the model picker as the catalog grew. Asked for the most a page holds, and followed.
        List<String> ids = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_MODEL_PAGES; page++) {
            String uri = baseUrl + "/v1/models?limit=1000" + (after == null ? "" : "&after_id={after}");
            Map<String, Object> body = after == null
                    ? client.get().uri(uri).retrieve().body(Map.class)
                    : client.get().uri(uri, after).retrieve().body(Map.class);
            if (body == null) break;
            List<Map<String, Object>> data = (List<Map<String, Object>>) body.getOrDefault("data", List.of());
            data.stream().map(m -> Objects.toString(m.get("id"), null)).filter(Objects::nonNull).forEach(ids::add);
            Object lastId = body.get("last_id");
            if (!Boolean.TRUE.equals(body.get("has_more")) || lastId == null || data.isEmpty()) break;
            after = lastId.toString();
        }
        return ids.stream().distinct().sorted().toList();
    }

    // A runaway "has_more" from a relay is not followed for ever.
    private static final int MAX_MODEL_PAGES = 20;

    private static String baseUrl(ResolvedProvider provider) {
        return provider.hasBaseUrl() ? provider.baseUrl() : DEFAULT_BASE_URL;
    }

    // The models API: the instance's headers, its key and the API version, on the LLM factory.
    private RestClient modelsApi(ResolvedProvider provider) {
        return ProviderClients.applyHeaders(RestClient.builder().requestFactory(externalHttpFactory), provider)
                .defaultHeader("x-api-key", provider.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

}
