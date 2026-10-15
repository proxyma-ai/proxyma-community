package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class OllamaAdapter implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapter.class);

    // RestClientConfig.llmRestClientBuilder(): the LLM read bound and the stale-connection retry.
    // Cloned before each use, since a RestClient.Builder is mutable and the headers below are one
    // instance's.
    private final RestClient.Builder externalRestClientBuilder;

    // The STREAMING client, which OllamaApi otherwise builds itself with WebClient.builder() - and
    // a streamed chat turn then went out without the instance's custom headers, the configured
    // proxy and its noProxy list, an administrator's CA and the response timeout, while the test
    // button, the model list and embeddings (all on the RestClient) worked. Behind an
    // authenticating reverse proxy that is every chat turn failing with a 401.
    private final WebClient.Builder externalWebClientBuilder;

    // "127.0.0.1" is correct for desktop mode and any server deployment where Ollama runs in
    // the same network namespace, but wrong for a containerized deployment where Ollama runs
    // on the host instead (127.0.0.1 inside the container is the container itself). Such a
    // deployment can set this property (e.g.
    // proxyma.llm-provider.ollama.default-base-url=http://host.docker.internal:11434); none of the
    // shipped ones do. Only applies until an admin explicitly saves a base URL via Settings >
    // Providers - that stored choice always wins from then on.
    @Value("${proxyma.llm-provider.ollama.default-base-url:http://127.0.0.1:11434}")
    private String defaultBaseUrl;

    public OllamaAdapter(@Qualifier("llmRestClientBuilder") RestClient.Builder externalRestClientBuilder,
                         @Qualifier("externalWebClientBuilder") WebClient.Builder externalWebClientBuilder) {
        this.externalRestClientBuilder = externalRestClientBuilder;
        this.externalWebClientBuilder = externalWebClientBuilder;
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.OLLAMA;
    }

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        String baseUrl = baseUrl(provider);
        log.debug("[Proxy] Building Ollama ChatModel for {} on '{}' (baseUrl={})", model, provider.name(), baseUrl);
        return OllamaChatModel.builder()
                .ollamaApi(api(provider))
                .retryTemplate(ProviderRetryTemplates.CHAT)
                .defaultOptions(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(tone.getTemperature())
                        .topK(tone.getTopK())
                        .topP(tone.getTopP())
                        .numPredict(LlmProviderAdapter.ceiling(maxTokens))
                        .build())
                .build();
    }

    // /api/show is the one place Ollama says how much a model can read - its OpenAI-compatible
    // /v1/models says nothing - and is the reason this protocol is worth keeping beside that one.
    @Override
    @SuppressWarnings("unchecked")
    public ModelLimits modelLimits(ResolvedProvider provider, String model) {
        RestClient client = ProviderClients.applyHeaders(externalRestClientBuilder.clone(), provider).build();
        Map<String, Object> body = client.post().uri(baseUrl(provider) + "/api/show")
                .body(Map.of("model", model))
                .retrieve().body(Map.class);
        return ModelLimits.fromOllama(body);
    }

    @Override
    public EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        String baseUrl = baseUrl(provider);
        log.debug("[Proxy] Building Ollama EmbeddingModel for {} on '{}' (baseUrl={})",
                model, provider.name(), baseUrl);
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api(provider))
                .defaultOptions(OllamaEmbeddingOptions.builder().model(model).build())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listModels(ResolvedProvider provider) {
        String baseUrl = baseUrl(provider);
        log.info("[Proxy] Listing Ollama models for '{}' from {}/api/tags", provider.name(), baseUrl);
        RestClient client = ProviderClients.applyHeaders(externalRestClientBuilder.clone(), provider).build();
        Map<String, Object> body = client.get().uri(baseUrl + "/api/tags").retrieve().body(Map.class);
        List<Map<String, Object>> models = body == null
                ? List.of() : (List<Map<String, Object>>) body.getOrDefault("models", List.of());
        return models.stream()
                .map(m -> Objects.toString(m.get("name"), null))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    // Both clients carry the instance's headers; the streaming one also the proxy, CA and timeouts
    // RestClientConfig gives every external stream. Errors keep their status and headers
    // (ProviderErrors), so a blocking 429's Retry-After reaches ModelCall.
    OllamaApi api(ResolvedProvider provider) {
        return OllamaApi.builder()
                .baseUrl(baseUrl(provider))
                .restClientBuilder(ProviderClients.applyHeaders(externalRestClientBuilder.clone(), provider))
                .webClientBuilder(ProviderClients.applyHeaders(externalWebClientBuilder.clone(), provider))
                .responseErrorHandler(ProviderErrors.HANDLER)
                .build();
    }

    // The instance's own URL, or the deploy-time default when it has none. That default matters for
    // containerized deployments, where 127.0.0.1 inside the container is the container itself - see
    // the field's own note.
    private String baseUrl(ResolvedProvider provider) {
        return provider.hasBaseUrl() ? provider.baseUrl() : defaultBaseUrl;
    }
}
