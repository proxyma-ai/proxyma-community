package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.network.HttpTimeouts;
import ai.proxyma.core.network.RestClientConfig;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.ProxyOptions;
import com.azure.core.util.HttpClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;

@Component
public class AzureOpenAiAdapter implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiAdapter.class);

    // The LLM read bound every other provider gets - the Azure SDK builds its own HTTP client rather
    // than routing through RestClientConfig, so it needs its own explicit bound or a stalled
    // endpoint hangs on nothing but ChatService.react()'s outer safety net. It carried its own copy
    // of the infrastructure 300 s until 2026-10-14.
    private static final Duration RESPONSE_TIMEOUT = HttpTimeouts.LLM_READ;

    private final RestClientConfig restClientConfig;

    public AzureOpenAiAdapter(RestClientConfig restClientConfig) {
        this.restClientConfig = restClientConfig;
    }

    @Override
    public ProviderProtocol protocol() {
        return ProviderProtocol.AZURE_OPENAI;
    }

    @Override
    public ChatModel buildChatModel(ResolvedProvider provider, String model, Tone tone, int maxTokens) {
        log.debug("[Proxy] Building Azure OpenAI ChatModel for deployment {} on '{}' (endpoint={})",
                model, provider.name(), provider.baseUrl());
        // Errors are raised with their status (SdkStatusChatModel), so a 429 is waited out by
        // ModelCall like any other provider's rather than failing the turn after the SDK's own tries.
        return new SdkStatusChatModel(AzureOpenAiChatModel.builder()
                .openAIClientBuilder(buildClient(provider))
                .defaultOptions(sampling(AzureOpenAiChatOptions.builder().deploymentName(model), model, tone, maxTokens)
                        .build())
                .build());
    }

    // No topK: AzureOpenAiChatOptions has no setter for it, and forwarding one fails Spring AI's
    // runtime-options merge with NotWritablePropertyException.
    @Override
    public ToolCallingChatOptions buildChatOptions(String model, Tone tone, int maxTokens,
                                                   List<ToolCallback> toolCallbacks) {
        AzureOpenAiChatOptions options = sampling(AzureOpenAiChatOptions.builder().deploymentName(model),
                model, tone, maxTokens).build();
        options.setInternalToolExecutionEnabled(false);
        options.setToolCallbacks(toolCallbacks);
        return options;
    }

    // A deployment of an OpenAI reasoning model takes no temperature or top_p and its ceiling as
    // max_completion_tokens (ReasoningModels) - recognized by its deployment name, which by
    // convention carries the model's.
    private static AzureOpenAiChatOptions.Builder sampling(AzureOpenAiChatOptions.Builder builder, String model,
                                                           Tone tone, int maxTokens) {
        if (ReasoningModels.isReasoning(model)) {
            return builder.maxCompletionTokens(LlmProviderAdapter.ceiling(maxTokens));
        }
        return builder
                .temperature(tone.getTemperature())
                .topP(tone.getTopP())
                .maxTokens(LlmProviderAdapter.ceiling(maxTokens));
    }

    @Override
    public EmbeddingModel buildEmbeddingModel(ResolvedProvider provider, String model) {
        log.debug("[Proxy] Building Azure OpenAI EmbeddingModel for deployment {} on '{}' (endpoint={})",
                model, provider.name(), provider.baseUrl());
        return new AzureOpenAiEmbeddingModel(
                buildClient(provider).buildClient(),
                MetadataMode.EMBED,
                AzureOpenAiEmbeddingOptions.builder().deploymentName(model).build());
    }

    // Custom headers are not applied here: this SDK builds its own HTTP pipeline rather than taking
    // a Spring client builder, so ProviderClients cannot reach it. An Azure deployment needing extra
    // headers has to go through a gateway, configured under that gateway's own protocol.
    private OpenAIClientBuilder buildClient(ResolvedProvider provider) {
        // The same sentence OpenAI and Anthropic give: an empty credential was every call failing
        // with a 401 that said nothing about the missing key.
        if (!provider.hasApiKey()) {
            throw new IllegalStateException("Provider '" + provider.name() + "' has no API key configured");
        }
        OpenAIClientBuilder builder = new OpenAIClientBuilder()
                .credential(new AzureKeyCredential(provider.apiKey()))
                .endpoint(provider.baseUrl());

        HttpClientOptions clientOptions = new HttpClientOptions().setResponseTimeout(RESPONSE_TIMEOUT);

        // The proxy for THIS endpoint: none when the noProxy list covers it, the same decision every
        // other client makes. The SDK takes one proxy and no bypass list, so the endpoint listed in
        // Settings > Network (a private endpoint, a loopback relay) was always sent to the proxy.
        Proxy javaProxy = restClientConfig.proxyFor(hostOf(provider.baseUrl()));
        if (javaProxy.type() != Proxy.Type.DIRECT) {
            InetSocketAddress addr = (InetSocketAddress) javaProxy.address();
            ProxyOptions proxyOptions = new ProxyOptions(
                    ProxyOptions.Type.HTTP,
                    new InetSocketAddress(addr.getHostString(), addr.getPort()));
            clientOptions.setProxyOptions(proxyOptions);
            log.debug("[Proxy] Azure OpenAI client using proxy {}:{}",
                    addr.getHostString(), addr.getPort());
        } else {
            log.debug("[Proxy] Azure OpenAI client: no proxy");
        }
        builder.clientOptions(clientOptions);

        return builder;
    }

    private static String hostOf(String url) {
        try {
            return url == null ? null : java.net.URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
