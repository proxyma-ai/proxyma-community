package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.CostSource;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.pricing.ModelPricing;
import ai.proxyma.core.settings.pricing.ModelPricingRepository;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gateway settles each call's cost ON the chunks it hands on (2026-10-22), so the chat's turn
 * totals, the per-call log and the monthly rollup read one figure - with the one-hour writes a
 * session asked for already priced as such.
 */
class GatewaySettlesCostTest {

    private static final SpendSubject USER = SpendSubject.user("u-1");

    private LlmCallLogService callLog;
    private DefaultLlmGateway gateway;
    private TokenUsage nextChunk;

    @BeforeEach
    void setUp() {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        SettingsService settings = mock(SettingsService.class);
        ChatSettings chat = new ChatSettings();
        chat.setProvider("p");
        chat.setModel("m");
        when(settings.getSettings(eq(ChatSettings.PREFIX), eq(ChatSettings.class))).thenReturn(chat);
        when(factory.providerDisplayName("p")).thenReturn("Provider");
        when(factory.resolveProvider(anyString())).thenReturn(Optional.empty());

        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> Flux.defer(() -> Flux.just(chunk(nextChunk))));
        when(factory.getChatModel()).thenReturn(model);
        // A tuned session's options are the adapter's to build.
        ai.proxyma.core.provider.adapter.LlmProviderAdapter adapter =
                mock(ai.proxyma.core.provider.adapter.LlmProviderAdapter.class);
        when(adapter.buildChatOptions(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
        when(factory.getAdapter(any())).thenReturn(adapter);

        // $1 per million in, $2 out, $1.25 for a five-minute write.
        ModelPricingRepository rates = mock(ModelPricingRepository.class);
        ModelPricing rate = new ModelPricing();
        rate.setProvider("Provider");
        rate.setModel("m");
        rate.setInputPricePerMillion(1);
        rate.setOutputPricePerMillion(2);
        rate.setCacheWritePricePerMillion(1.25);
        when(rates.findByProviderAndModel(anyString(), anyString())).thenReturn(Optional.empty());
        when(rates.findByProviderAndModel("Provider", "m")).thenReturn(Optional.of(rate));

        callLog = mock(LlmCallLogService.class);
        gateway = new DefaultLlmGateway(factory, settings, mock(AttributedSpendService.class), callLog,
                new ModelPricingService(rates));
        gateway.setSpendRecorder(Runnable::run);
    }

    private static ChatResponse chunk(TokenUsage stated) {
        DefaultUsage usage = new DefaultUsage((int) stated.inputTokens(), (int) stated.outputTokens(),
                (int) (stated.inputTokens() + stated.outputTokens()), stated);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    @Test
    @DisplayName("a streamed chunk reaches the chat with its cost settled, and the log row says where it came from")
    void chunkCarriesItsCost() {
        nextChunk = new TokenUsage(1_000_000, 500_000, 0, 0, 1_000_000);
        LlmSession session = gateway.open(LlmCaller.of(LlmRole.CHAT, USER), LlmGateway.SessionOptions.plain());

        List<ChatResponse> chunks = session.stream(List.of(new UserMessage("hi")), List.of())
                .collectList().block(Duration.ofSeconds(10));

        TokenUsage seen = TokenUsageExtractor.extract(chunks.get(0));
        assertThat(seen.settledCostUsd()).isCloseTo(2.0, within(1e-9));
        assertThat(seen.costSource()).isEqualTo(CostSource.RATE);
        verify(callLog).record(eq(USER), eq("CHAT"), eq("Provider"), eq("m"), any(),
                org.mockito.ArgumentMatchers.doubleThat(c -> Math.abs(c - 2.0) < 1e-9), eq(CostSource.RATE));
    }

    @Test
    @DisplayName("a session that asked for an hour's cache prices its writes as one-hour writes")
    void oneHourSessionPricesLongWrites() {
        nextChunk = new TokenUsage(0, 0, 0, 1_000_000, 1_000_000);
        // A TUNED session, as the agent opens - one-hour caching reaches only those.
        LlmSession session = gateway.open(LlmCaller.of(LlmRole.CHAT, USER),
                LlmGateway.SessionOptions.tuned(ChatSettings.Tone.values()[0], 1024)
                        .cached(ChatSettings.PromptCache.ONE_HOUR));

        List<ChatResponse> chunks = session.stream(List.of(new UserMessage("hi")), List.of())
                .collectList().block(Duration.ofSeconds(10));

        // 1.25 for five minutes, times 1.6 for an hour.
        assertThat(TokenUsageExtractor.extract(chunks.get(0)).settledCostUsd()).isCloseTo(2.0, within(1e-9));
    }
}
