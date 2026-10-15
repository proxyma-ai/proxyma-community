package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A message typed while "condense now" runs is not handed to a turn - there is none. It was
 * accepted, because a condensation holds the same busy flag a turn does, and then nothing took it:
 * the page sent it as a turn of its own, and that turn found the stale copy at its first step. The
 * model got the message twice and the page moved a bubble it had already placed.
 */
class InjectDuringCondenseTest {

    private static final String CONVERSATION = "conv-1";

    private SmartChatMemory memory;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        memory = mock(SmartChatMemory.class);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(SubAgentService.class), mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    @Test
    @DisplayName("a message typed during a manual condensation is refused, so the page sends it itself")
    void aCondensationTakesNoMessage() {
        AtomicReference<ChatService.InjectResult> during = new AtomicReference<>();
        AtomicReference<Boolean> busy = new AtomicReference<>();
        // The condensation itself is where the message is typed.
        when(memory.forceCondense(CONVERSATION)).thenAnswer(call -> {
            busy.set(chatService.isActive(CONVERSATION));
            during.set(chatService.inject(CONVERSATION, "search more widely", "q-1", null));
            return true;
        });

        assertThat(chatService.condenseNow(CONVERSATION)).isTrue();

        // Still busy for a rewind or a second condense - only the typed message is turned away.
        assertThat(busy.get()).isTrue();
        assertThat(during.get().outcome()).isEqualTo(ChatService.InjectOutcome.NOT_RUNNING);
        assertThat(chatService.waitingInjections(CONVERSATION)).isZero();
    }

    @Test
    @DisplayName("with nothing running, a typed message is refused and nothing is left waiting")
    void nothingRunningTakesNoMessage() {
        assertThat(chatService.inject(CONVERSATION, "hello", "q-1", null).outcome())
                .isEqualTo(ChatService.InjectOutcome.NOT_RUNNING);
        assertThat(chatService.waitingInjections(CONVERSATION)).isZero();
    }
}
