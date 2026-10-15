package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter: OpenAI's shape, with what OpenRouter adds to it.
 *
 * <ul>
 *   <li><b>A top-level {@code cache_control}</b> on every chat request, with the turn's lifetime.
 *       OpenRouter caches Claude, Gemini and Qwen prompts only when asked, and applies a top-level
 *       instruction to the last cacheable block, moving it forward as the conversation grows - one
 *       breakpoint at the end of the prompt, the shape AnthropicCacheTail builds by hand for
 *       Anthropic. It was a per-instance switch on the OpenAI protocol until 2026-10-13, because
 *       OpenAI's own API refuses the field; it is OpenRouter's, so OpenRouter always sends it.</li>
 *   <li><b>{@code reasoning}</b> for what a reasoning model thinks ({@link ReasoningFieldAlias}),
 *       and several tool calls in one delta from the models it routes to
 *       ({@link ParallelToolCallDeltas}).</li>
 *   <li><b>Limits in its model list</b> - {@link ModelLimits#fromOpenRouter}.</li>
 * </ul>
 */
@Component
public class OpenRouterAdapter extends OpenAiShapedAdapter {

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api";

    public OpenRouterAdapter(@Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory,
                             @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        super(externalHttpFactory, externalWebClientBuilder);
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.OPENROUTER;
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    protected List<ExchangeFilterFunction> streamFilters() {
        return List.of(ReasoningFieldAlias.filter(), ParallelToolCallDeltas.filter());
    }

    @Override
    protected Map<String, Object> extraBody(ResolvedProvider provider, PromptCache cache) {
        return cacheInstruction(cache);
    }

    @Override
    protected boolean statesLimits() {
        return true;
    }

    @Override
    protected ModelLimits limitsOf(Map<String, Object> model) {
        return ModelLimits.fromOpenRouter(model);
    }

    /**
     * {@code cache_control} as OpenRouter takes it: ephemeral, five minutes unless a {@code ttl} of an
     * hour is given. The setting's Auto is resolved to one of the two before it gets here, and
     * anything that is not an hour is five minutes.
     *
     * <p>TOP-LEVEL, NOT PER MESSAGE: Spring AI 1.1.7's message records have no field for a per-block
     * marker, and its {@code extraBody} is written into the request's top level (a
     * {@code @JsonAnyGetter}), so this needs no rewrite of the request body.
     */
    static Map<String, Object> cacheInstruction(PromptCache cache) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("type", "ephemeral");
        if (cache == PromptCache.ONE_HOUR) control.put("ttl", "1h");
        return Map.of("cache_control", control);
    }
}
