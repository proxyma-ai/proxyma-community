package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.network.HttpTimeouts;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// Google GenAI SDK uses JVM system proxy properties (https.proxyHost, https.proxyPort,
// http.nonProxyHosts) set by RestClientConfig. No per-client proxy injection is possible.
@Component
public class GeminiAdapter implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GeminiAdapter.class);

    // The LLM read bound every other provider gets (HttpTimeouts.LLM_READ), in milliseconds - this
    // SDK's HttpOptions.timeout takes an Integer. The Google GenAI SDK builds its own HTTP client
    // rather than routing through RestClientConfig, so it needs the bound stated here. It carried
    // its own copy of the infrastructure 300 s until 2026-10-14.
    private static final int RESPONSE_TIMEOUT_MILLIS = (int) HttpTimeouts.LLM_READ.toMillis();

    private static final String API = "https://generativelanguage.googleapis.com/v1beta/models";

    private final ClientHttpRequestFactory externalHttpFactory;

    public GeminiAdapter(@Qualifier("llmClientHttpRequestFactory") ClientHttpRequestFactory externalHttpFactory) {
        this.externalHttpFactory = externalHttpFactory;
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.GEMINI;
    }

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        log.debug("[Proxy] Building Google GenAI ChatModel for {} on '{}' (uses JVM system proxy properties)",
                model, provider.name());
        Client client = client(provider);
        // Wrapped, not returned bare: Gemini is the one protocol here whose tool results must be
        // JSON objects rather than strings, and two of this codebase's tools return raw text by
        // design. See GeminiJsonToolResponses for the failure that leaves and why the adapter is
        // where it is fixed. And its errors are raised with their status (SdkStatusChatModel), so a
        // Gemini 429 or 503 is waited out like any other provider's.
        return new GeminiJsonToolResponses(new SdkStatusChatModel(GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .retryTemplate(ProviderRetryTemplates.CHAT)
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .model(model)
                        .temperature(tone.getTemperature())
                        .topK(tone.getTopK())
                        .topP(tone.getTopP())
                        .maxOutputTokens(LlmProviderAdapter.ceiling(maxTokens))
                        .build())
                .build()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelLimits modelLimits(ResolvedProvider provider, String model) {
        if (!provider.hasApiKey()) return ModelLimits.UNKNOWN;
        Map<String, Object> body = modelsApi(provider).get().uri(API + "/{model}", bare(model))
                .retrieve().body(Map.class);
        return ModelLimits.fromGemini(body);
    }

    /**
     * Google's `embedContent`, reached with the same API key as the chat path above.
     *
     * <p>Task type is deliberately NOT set. Spring AI 1.1.7 defaults it to RETRIEVAL_DOCUMENT and
     * then never sends it - {@code GoogleGenAiTextEmbeddingModel.call()} copies only `dimensions`
     * into the SDK's `EmbedContentConfig` - so setting it here would be a property nothing
     * enforces. It would also be the wrong property to set: queries and documents both reach this
     * model through the single EmbeddingModel bean in EmbeddingModelConfig, with nothing to tell
     * them apart, so the asymmetric RETRIEVAL_QUERY/RETRIEVAL_DOCUMENT pair Google recommends for
     * retrieval is not expressible from here at all.
     *
     * <p>Dimensions are left alone too, so a model returns its native width - 3072 for
     * gemini-embedding-001, against 768 or 1024 for the models most deployments run today.
     * PgVectorStoreService indexes anything up to 4000 (halfvec above 2000), so that is supported,
     * but it is a different width: changing the embedding model still means a reindex, exactly as
     * it does between any other two models.
     */
    @Override
    public EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        log.debug("[Proxy] Building Google GenAI EmbeddingModel for {} on '{}'", model, provider.name());
        // Built from a client we made rather than from apiKey(), because the builder's own branch
        // creates a Client with no timeout, and its else-branch asserts a Vertex AI project id that
        // a provider instance here never has - this product configures Gemini by API key only.
        GoogleGenAiEmbeddingConnectionDetails connection = GoogleGenAiEmbeddingConnectionDetails.builder()
                .genAiClient(client(provider))
                .build();
        return new GoogleGenAiTextEmbeddingModel(connection,
                GoogleGenAiTextEmbeddingOptions.builder().model(model).build(),
                ProviderRetryTemplates.EMBEDDING);
    }

    // Google's own client builder takes neither a base URL nor extra headers, so an instance that
    // sets either is not honored - unlike every other protocol. Reaching Gemini through a gateway
    // therefore means configuring that gateway under its own protocol (LiteLLM, OpenRouter) or as
    // OPENAI_COMPATIBLE, which is what those gateways expose anyway.
    private Client client(ResolvedProvider provider) {
        if (provider.hasBaseUrl() || !provider.headers().isEmpty()) {
            log.warn("[Proxy] Provider '{}' sets a base URL or custom headers; the Gemini client "
                    + "supports neither and they are ignored", provider.name());
        }
        return Client.builder()
                .apiKey(provider.apiKey())
                .httpOptions(HttpOptions.builder().timeout(RESPONSE_TIMEOUT_MILLIS).build())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listModels(ResolvedProvider provider) {
        if (!provider.hasApiKey()) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no API key configured");
        }
        log.info("[Proxy] Listing Google GenAI models for '{}'", provider.name());
        Map<String, Object> body = modelsApi(provider).get().uri(API + "?pageSize=200")
                .retrieve().body(Map.class);
        List<Map<String, Object>> models = body == null
                ? List.of() : (List<Map<String, Object>>) body.getOrDefault("models", List.of());
        return models.stream()
                .map(m -> Objects.toString(m.get("name"), null))
                .filter(Objects::nonNull)
                .map(GeminiAdapter::bare)
                .sorted()
                .toList();
    }

    // The key in Google's x-goog-api-key header, as the GenAI SDK sends it for chat. It was a ?key=
    // query parameter, which put it in the request line - where a TLS-inspecting proxy, a client's
    // debug log or any message that prints the URI can read it.
    private RestClient modelsApi(ResolvedProvider provider) {
        return RestClient.builder().requestFactory(externalHttpFactory)
                .defaultHeader("x-goog-api-key", provider.apiKey())
                .build();
    }

    private static String bare(String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }
}
