package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.provider.usage.WireUsage;
import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.GatewayApi;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LiteLLM's proxy, spoken through the API its instance chose ({@link GatewayApi}).
 *
 * <p><b>Anthropic Messages</b> is Anthropic's request path, unchanged - {@link AnthropicAdapter}
 * builds it - so Claude's tool format and cache breakpoints reach the model as Anthropic defines
 * them. <b>OpenAI Chat Completions</b> is OpenAI's request path with what LiteLLM does differently:
 * it counts cached tokens OUTSIDE {@code prompt_tokens} where OpenAI counts them inside
 * ({@link WireUsage.Shape#OPENAI_CACHE_OUTSIDE}), and the models it routes to may send several tool
 * calls in one delta ({@link ParallelToolCallDeltas}).
 *
 * <p>Either way, what is LiteLLM's own:
 * <ul>
 *   <li><b>A key may not be needed</b> - a proxy started without a master key takes any.</li>
 *   <li><b>Model limits come from {@code /model/info}</b>, LiteLLM's description of each deployment
 *       ({@link ModelLimits#fromLiteLlm}). A virtual key limited to the LLM routes is refused it with
 *       a 403, which leaves the limits unknown - LlmProviderFactory remembers that and does not ask
 *       again for a while.</li>
 *   <li><b>The model list and embeddings are the OpenAI ones</b>, whichever API chat uses.</li>
 *   <li><b>One blocking call per step</b>, for a deployment that ignores stream=true once tools are
 *       present ({@link ResolvedProvider#streams()}, which the agent loops ask).</li>
 * </ul>
 */
@Component
public class LiteLlmAdapter implements LlmProviderAdapter {

    // A proxy started without a master key takes any key, and both routes refuse to build without one.
    private static final String PLACEHOLDER_KEY = "not-required";

    private final AnthropicAdapter anthropic;
    private final OpenAiRoute openAi;

    public LiteLlmAdapter(AnthropicAdapter anthropic,
                          @Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory,
                          @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        this.anthropic = anthropic;
        this.openAi = new OpenAiRoute(externalHttpFactory, externalWebClientBuilder);
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.LITELLM;
    }

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        return messages(provider)
                // Each tool call of the stream handed over whole: LiteLLM closes a block late
                // (AnthropicToolUseBlocks, 0.39.0 feedback).
                ? anthropic.buildChatModel(withKey(provider), model, tone, maxTokens,
                        List.of(AnthropicToolUseBlocks.streamFilter()))
                : openAi.buildChatModel(provider, model, tone, maxTokens);
    }

    @Override
    public ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                   List<ToolCallback> toolCallbacks) {
        // No instance to say which API: the shared default, as for any protocol.
        return LlmProviderAdapter.super.buildChatOptions(model, tone, maxTokens, toolCallbacks);
    }

    @Override
    public ToolCallingChatOptions buildChatOptions(ResolvedProvider provider, String model, Tone tone,
                                                   int maxTokens, List<ToolCallback> toolCallbacks,
                                                   PromptCache cache) {
        if (provider == null) return buildChatOptions(model, tone, maxTokens, toolCallbacks);
        return messages(provider)
                ? anthropic.buildChatOptions(model, tone, maxTokens, toolCallbacks, cache)
                : openAi.buildChatOptions(provider, model, tone, maxTokens, toolCallbacks, cache);
    }

    @Override
    public EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        return openAi.buildEmbeddingModel(provider, model);
    }

    @Override
    public List<String> listModels(ResolvedProvider provider) {
        return openAi.listModels(provider);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelLimits modelLimits(ResolvedProvider provider, String model) {
        Map<String, Object> body = openAi.rest(provider).get().uri(openAi.baseUrl(provider) + "/model/info")
                .header("Authorization", "Bearer " + openAi.apiKey(provider))
                .retrieve().body(Map.class);
        List<Map<String, Object>> data = body == null
                ? List.of() : (List<Map<String, Object>>) body.getOrDefault("data", List.of());
        return data.stream()
                .filter(m -> model.equals(Objects.toString(m.get("model_name"), null)))
                .map(ModelLimits::fromLiteLlm)
                .filter(ModelLimits::isKnown)
                .findFirst()
                .orElse(ModelLimits.UNKNOWN);
    }

    private static boolean messages(ResolvedProvider provider) {
        return provider.gatewayApi() == GatewayApi.ANTHROPIC_MESSAGES;
    }

    private static ResolvedProvider withKey(ResolvedProvider p) {
        if (p.hasApiKey()) return p;
        return new ResolvedProvider(p.id(), p.name(), p.protocol(), p.baseUrl(), PLACEHOLDER_KEY, p.headers(),
                p.nativeRerank(), p.api(), p.blockingCalls());
    }

    /** OpenAI's request path as LiteLLM serves it. Not a bean: the factory finds LiteLLM by protocol. */
    private static final class OpenAiRoute extends OpenAiShapedAdapter {

        OpenAiRoute(ClientHttpRequestFactory externalHttpFactory, WebClient.Builder externalWebClientBuilder) {
            super(externalHttpFactory, externalWebClientBuilder);
        }

        @Override
        public ProviderProtocol protocol() {
            return ProviderProtocol.LITELLM;
        }

        @Override
        protected String defaultBaseUrl() {
            return null;
        }

        @Override
        protected String apiKey(ResolvedProvider provider) {
            return provider.hasApiKey() ? provider.apiKey() : PLACEHOLDER_KEY;
        }

        @Override
        protected WireUsage.Shape usageShape() {
            return WireUsage.Shape.OPENAI_CACHE_OUTSIDE;
        }

        @Override
        protected List<ExchangeFilterFunction> streamFilters() {
            return List.of(ParallelToolCallDeltas.filter());
        }
    }
}
