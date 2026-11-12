package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every model call is filed under the conversation its caller names (2026-11-04): the per-call log
 * is how one agent's calls are told from another's - every agent is a conversation of its own on the
 * one turn loop - so a team's hourly pass can be measured by agent. A call made for no conversation
 * is filed under none, never under the last one seen on the thread.
 */
class GatewayConversationTest {

    private static final SpendSubject USER = SpendSubject.user("u-1");

    private LlmCallLogService callLog;
    private DefaultLlmGateway gateway;

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
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> Flux.defer(() -> Flux.just(chunk())));
        when(model.call(any(Prompt.class))).thenAnswer(inv -> chunk());
        when(factory.getChatModel()).thenReturn(model);

        ModelPricingRepository rates = mock(ModelPricingRepository.class);
        when(rates.findByProviderAndModel(anyString(), anyString())).thenReturn(Optional.empty());
        callLog = mock(LlmCallLogService.class);
        gateway = new DefaultLlmGateway(factory, settings, mock(AttributedSpendService.class), callLog,
                new ModelPricingService(rates));
        gateway.setSpendRecorder(Runnable::run);
    }

    private static ChatResponse chunk() {
        TokenUsage stated = new TokenUsage(100, 10, 0, 0, 100);
        DefaultUsage usage = new DefaultUsage(100, 10, 110, stated);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    @Test
    @DisplayName("a session opened for a conversation files its streamed and its blocking calls under it")
    void sessionCallsCarryTheConversation() {
        LlmSession session = gateway.open(LlmCaller.of(LlmRole.CHAT, USER).inConversation("conv-7"),
                LlmGateway.SessionOptions.plain());

        session.stream(List.of(new UserMessage("hi")), List.of()).collectList().block(Duration.ofSeconds(10));
        session.chat(List.of(new UserMessage("hi")), List.of());

        verify(callLog, org.mockito.Mockito.times(2)).record(eq(USER), eq("CHAT"), eq("Provider"), eq("m"), any(),
                anyDouble(), any(), any(), any(), eq("conv-7"));
    }

    @Test
    @DisplayName("a one-off call names its conversation too")
    void oneOffCallCarriesTheConversation() {
        gateway.chat(LlmCaller.of(LlmRole.TITLE, USER).inConversation("conv-8"), List.of(new UserMessage("hi")));

        verify(callLog).record(eq(USER), eq("TITLE"), eq("Provider"), eq("m"), any(),
                anyDouble(), any(), any(), any(), eq("conv-8"));
    }

    @Test
    @DisplayName("a call made for no conversation is filed under none")
    void noConversationIsNull() {
        gateway.chat(LlmCaller.of(LlmRole.CHAT, USER), List.of(new UserMessage("hi")));

        verify(callLog).record(eq(USER), eq("CHAT"), eq("Provider"), eq("m"), any(),
                anyDouble(), any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("a blank conversation id is no conversation")
    void blankIsNone() {
        assertThat(LlmCaller.of(LlmRole.CHAT, USER).inConversation("  ").conversationId()).isNull();
        assertThat(LlmCaller.of(LlmRole.CHAT, USER).conversationId()).isNull();
    }
}
