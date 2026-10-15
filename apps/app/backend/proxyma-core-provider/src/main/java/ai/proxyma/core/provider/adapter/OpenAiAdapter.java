package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.provider.ProviderProtocol;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI's own API, exactly as OpenAI documents it: a key on every request, usage asked for on a
 * stream, cached tokens counted inside {@code prompt_tokens}, and no limits stated on a model -
 * OpenAI publishes none through its API, so the agent runs on what Settings says or the assumed
 * window. No rerank endpoint, no extra request field, no repair of a stream OpenAI does not send.
 * Its reasoning models are sent what OpenAI documents for them: no temperature or top_p, and the
 * ceiling as max_completion_tokens.
 *
 * <p>Every server that speaks OpenAI's shape differently is {@link OpenAiCompatibleAdapter},
 * {@link OpenRouterAdapter} or LiteLLM's OpenAI route ({@link LiteLlmAdapter}).
 */
@Component
public class OpenAiAdapter extends OpenAiShapedAdapter {

    public OpenAiAdapter(@Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory,
                         @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        super(externalHttpFactory, externalWebClientBuilder);
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.OPENAI;
    }

    // o-series and gpt-5: no sampling parameters, and max_completion_tokens - see ReasoningModels.
    @Override
    protected boolean reasoningModel(String model) {
        return ReasoningModels.isReasoning(model);
    }
}
