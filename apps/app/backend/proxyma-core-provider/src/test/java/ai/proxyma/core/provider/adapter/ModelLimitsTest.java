package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.provider.gateway.DefaultLlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent's output ceiling and context window are the model's own until somebody sets them.
 *
 * <p>They were fixed numbers - 8k and 128k, then 64k and 200k - and a fixed number is wrong for
 * most models: a ceiling above the model's is a request the provider refuses, and a window larger
 * than the model's overflows before anything is condensed. What each provider's answer is taken
 * to mean, and which of setting, model and fallback wins, is what these pin.
 */
class ModelLimitsTest {

    // -- What each provider's answer is taken to mean ---------------------------------------------

    @Test
    @DisplayName("Anthropic states both, and either may be missing")
    void anthropic() {
        assertThat(ModelLimits.fromAnthropic(Map.of("id", "m", "max_input_tokens", 200000, "max_tokens", 64000)))
                .isEqualTo(new ModelLimits(200000, 64000));
        // "number or null" in its own reference, and 0 in its own example: neither is a limit.
        assertThat(ModelLimits.fromAnthropic(Map.of("id", "m", "max_input_tokens", 0)).isKnown()).isFalse();
        assertThat(ModelLimits.fromAnthropic(null)).isEqualTo(ModelLimits.UNKNOWN);
    }

    @Test
    @DisplayName("Gemini states both")
    void gemini() {
        assertThat(ModelLimits.fromGemini(Map.of("inputTokenLimit", 1048576, "outputTokenLimit", 65536)))
                .isEqualTo(new ModelLimits(1048576, 65536));
    }

    @Test
    @DisplayName("Ollama: the model file's num_ctx when it sets one, else the trained length - and no output ceiling")
    void ollama() {
        Map<String, Object> info = Map.of("general.architecture", "gemma3", "gemma3.context_length", 131072);

        assertThat(ModelLimits.fromOllama(Map.of("model_info", info)))
                .isEqualTo(new ModelLimits(131072, null));
        // What the server loads the model with wins over what the model could be given.
        assertThat(ModelLimits.fromOllama(Map.of("model_info", info,
                "parameters", "temperature 0.7\nnum_ctx                        8192\nstop \"<end>\"")))
                .isEqualTo(new ModelLimits(8192, null));
        assertThat(ModelLimits.fromOllama(Map.of("details", Map.of("family", "gemma3"))).isKnown()).isFalse();
    }

    @Test
    @DisplayName("an OpenAI-compatible service is read by the field it uses, and OpenAI's own answer is unknown")
    void openAiCompatible() {
        // OpenAI itself: an id and an owner.
        assertThat(ModelLimits.fromOpenAiCompatible(Map.of("id", "gpt-x", "object", "model", "owned_by", "openai"))
                .isKnown()).isFalse();
        // Groq.
        assertThat(ModelLimits.fromOpenAiCompatible(Map.of("id", "m", "context_window", 131072,
                "max_completion_tokens", 8192)))
                .isEqualTo(new ModelLimits(131072, 8192));
        // vLLM states the window only. Its number is NOT an output ceiling: asked for that much
        // output, vLLM refuses any request that also has a prompt.
        assertThat(ModelLimits.fromOpenAiCompatible(Map.of("id", "m", "max_model_len", 32768)))
                .isEqualTo(new ModelLimits(32768, null));
        // llama.cpp.
        assertThat(ModelLimits.fromOpenAiCompatible(Map.of("id", "m", "meta", Map.of("n_ctx_train", 8192))))
                .isEqualTo(new ModelLimits(8192, null));
        // A number written as text is still the number; text that is not one is nothing.
        assertThat(ModelLimits.fromOpenAiCompatible(Map.of("id", "m", "context_length", "4096", "max_output_tokens", "many")))
                .isEqualTo(new ModelLimits(4096, null));
    }

    @Test
    @DisplayName("OpenRouter and LiteLLM are read by their own fields")
    void gateways() {
        assertThat(ModelLimits.fromOpenRouter(Map.of("id", "a/b", "context_length", 262144,
                "top_provider", Map.of("max_completion_tokens", 32768))))
                .isEqualTo(new ModelLimits(262144, 32768));
        // LiteLLM's /model/info, one deployment: the window as max_input_tokens, the ceiling as
        // max_output_tokens - or max_tokens, its older name for it.
        assertThat(ModelLimits.fromLiteLlm(Map.of("model_name", "claude-haiku-4-5",
                "model_info", Map.of("max_input_tokens", 200000, "max_output_tokens", 64000, "max_tokens", 8192))))
                .isEqualTo(new ModelLimits(200000, 64000));
        assertThat(ModelLimits.fromLiteLlm(Map.of("model_name", "m",
                "model_info", Map.of("max_input_tokens", 128000, "max_tokens", 4096))))
                .isEqualTo(new ModelLimits(128000, 4096));
        assertThat(ModelLimits.fromLiteLlm(Map.of("model_name", "m")).isKnown()).isFalse();
    }

    // -- Asked once, and what an unset ceiling becomes --------------------------------------------

    private static final String PROVIDER = UUID.randomUUID().toString();

    private static ResolvedProvider provider(ProviderProtocol protocol) {
        return new ResolvedProvider(UUID.fromString(PROVIDER), "Provider", protocol, "http://host", "key", Map.of(), false);
    }

    private static LlmProviderFactory factory(LlmProviderAdapter adapter) {
        // Read before the stubbing below: Mockito cannot have one mock answered inside another's.
        ResolvedProvider resolved = provider(adapter.protocol());
        ProviderInstanceService instances = mock(ProviderInstanceService.class);
        when(instances.resolve(PROVIDER)).thenReturn(Optional.of(resolved));
        when(instances.resolve("gone")).thenReturn(Optional.empty());
        return new LlmProviderFactory(mock(SettingsService.class), instances, List.of(adapter));
    }

    private static LlmProviderAdapter adapter(ProviderProtocol protocol) {
        LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
        when(adapter.protocol()).thenReturn(protocol);
        return adapter;
    }

    @Test
    @DisplayName("a model's limits are asked for once, and a ceiling nobody set becomes the model's own")
    void askedOnceAndUsed() {
        LlmProviderAdapter adapter = adapter(ProviderProtocol.ANTHROPIC);
        when(adapter.modelLimits(any(), eq("big"))).thenReturn(new ModelLimits(200000, 64000));
        LlmProviderFactory factory = factory(adapter);

        assertThat(factory.outputCeiling(PROVIDER, "big", ChatSettings.FROM_MODEL)).isEqualTo(64000);
        assertThat(factory.modelLimits(PROVIDER, "big").contextWindow()).isEqualTo(200000);
        // What somebody set is what is sent, whatever the model states.
        assertThat(factory.outputCeiling(PROVIDER, "big", 8192)).isEqualTo(8192);

        verify(adapter, times(1)).modelLimits(any(), anyString());
    }

    @Test
    @DisplayName("a provider that cannot be asked is unknown, never an error - and the ceiling is then left to it")
    void aFailureIsUnknown() {
        LlmProviderAdapter adapter = adapter(ProviderProtocol.OPENAI_COMPATIBLE);
        when(adapter.modelLimits(any(), anyString())).thenThrow(new IllegalStateException("connection refused"));
        LlmProviderFactory factory = factory(adapter);

        assertThat(factory.modelLimits(PROVIDER, "m")).isEqualTo(ModelLimits.UNKNOWN);
        // Zero is what an adapter reads as "send no ceiling".
        assertThat(factory.outputCeiling(PROVIDER, "m", ChatSettings.FROM_MODEL)).isZero();
        assertThat(LlmProviderAdapter.ceiling(0)).isNull();
        assertThat(LlmProviderAdapter.ceiling(8192)).isEqualTo(8192);
        // Remembered too, so a provider that is down is not asked again on every model call.
        factory.modelLimits(PROVIDER, "m");
        verify(adapter, times(1)).modelLimits(any(), anyString());

        assertThat(factory.modelLimits("gone", "m")).isEqualTo(ModelLimits.UNKNOWN);
        assertThat(factory.modelLimits(PROVIDER, " ")).isEqualTo(ModelLimits.UNKNOWN);
    }

    // -- The context window: what was set, else the model's, else a fallback ------------------------

    private static final class Deployment {
        final ChatSettings chat = new ChatSettings();
        final SubAgentSettings sub = new SubAgentSettings();
        final LlmProviderFactory factory = mock(LlmProviderFactory.class);
        final DefaultLlmGateway gateway;

        Deployment() {
            chat.setProvider("agent-provider");
            chat.setModel("agent-model");
            SettingsService settings = mock(SettingsService.class);
            when(settings.getSettings(ChatSettings.PREFIX, ChatSettings.class)).thenReturn(chat);
            when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(sub);
            when(factory.getChatModel()).thenReturn(mock(ChatModel.class));
            when(factory.resolveProvider(anyString())).thenReturn(Optional.empty());
            when(factory.modelLimits(anyString(), anyString())).thenReturn(ModelLimits.UNKNOWN);
            gateway = new DefaultLlmGateway(factory, settings,
                    mock(AttributedSpendService.class), mock(LlmCallLogService.class), mock(ModelPricingService.class));
        }
    }

    @Test
    @DisplayName("the agent's window is what Settings says, else what its model states, else the fallback")
    void theAgentsWindow() {
        Deployment d = new Deployment();
        assertThat(d.gateway.contextWindow(LlmRole.CHAT)).isEqualTo(ChatSettings.FALLBACK_CONTEXT_WINDOW);

        when(d.factory.modelLimits("agent-provider", "agent-model")).thenReturn(new ModelLimits(1000000, 64000));
        assertThat(d.gateway.contextWindow(LlmRole.CHAT)).isEqualTo(1000000);

        d.chat.setMemoryMaxTokens(128000);
        assertThat(d.gateway.contextWindow(LlmRole.CHAT)).isEqualTo(128000);
    }

    @Test
    @DisplayName("a sub-agent with a model of its own is measured against THAT model, unless a window was set")
    void aSubAgentsWindow() {
        Deployment d = new Deployment();
        when(d.factory.modelLimits("agent-provider", "agent-model")).thenReturn(new ModelLimits(1000000, 64000));
        when(d.factory.modelLimits("sub-provider", "small-model")).thenReturn(new ModelLimits(32768, 8192));

        // No model of its own: it runs on the agent's.
        assertThat(d.gateway.contextWindow(LlmRole.SUB_AGENT)).isEqualTo(1000000);

        d.sub.setProvider("sub-provider");
        d.sub.setModel("small-model");
        when(d.factory.getSubAgentChatModel()).thenReturn(mock(ChatModel.class));
        assertThat(d.gateway.contextWindow(LlmRole.SUB_AGENT)).isEqualTo(32768);

        // The agent's own setting is the sub-agents' too, until they are given one.
        d.chat.setMemoryMaxTokens(200000);
        assertThat(d.gateway.contextWindow(LlmRole.SUB_AGENT)).isEqualTo(200000);
        d.sub.setContextMaxTokens(128000);
        assertThat(d.gateway.contextWindow(LlmRole.SUB_AGENT)).isEqualTo(128000);
    }
}
