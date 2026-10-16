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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a chat stream's first event calls "busy" (ChatService.isBusy, since 2026-10-16): anything
 * still to publish into the conversation's stream. A page that holds every event and is told
 * nothing is busy loads the conversation again rather than wait - so a turn on its way, a
 * condensation and background sub-agents must all count, or a page would reload under work that
 * is about to speak. See ChatStreamOpenTest for the event itself.
 */
class ConversationBusyTest {

    private static final String CONVERSATION = "conv-1";

    private static ChatService realChatService(SmartChatMemory memory, SubAgentService subAgents) {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        return new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                subAgents, mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    @Test
    @DisplayName("busy: nothing for a conversation nothing runs in")
    void idleIsNotBusy() {
        ChatService service = realChatService(mock(SmartChatMemory.class), mock(SubAgentService.class));
        assertThat(service.isBusy(CONVERSATION)).isFalse();
    }

    @Test
    @DisplayName("busy: a turn accepted and not registered yet - the page's stream may open before it does")
    void aDispatchedTurnIsBusy() {
        ChatService service = realChatService(mock(SmartChatMemory.class), mock(SubAgentService.class));
        service.recordTurnStart(CONVERSATION, "user:alice", null);
        assertThat(service.isActive(CONVERSATION)).isFalse();
        assertThat(service.isBusy(CONVERSATION)).isTrue();
    }

    @Test
    @DisplayName("busy: a manual condensation while it runs, and not after")
    void aCondensationIsBusy() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        ChatService[] holder = new ChatService[1];
        AtomicBoolean during = new AtomicBoolean();
        when(memory.forceCondense(CONVERSATION)).thenAnswer(call -> {
            during.set(holder[0].isBusy(CONVERSATION));
            return true;
        });
        holder[0] = realChatService(memory, mock(SubAgentService.class));

        assertThat(holder[0].condenseNow(CONVERSATION)).isTrue();

        assertThat(during.get()).isTrue();
        assertThat(holder[0].isBusy(CONVERSATION)).isFalse();
    }

    @Test
    @DisplayName("busy: a condensation summarizing, whatever started it - GET .../status says so too")
    void aCondensationSummarizingIsBusy() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.isCondensingNow(CONVERSATION)).thenReturn(true);
        assertThat(realChatService(memory, mock(SubAgentService.class)).isBusy(CONVERSATION)).isTrue();
    }

    @Test
    @DisplayName("busy: sub-agents at work in the background, or a report still owed")
    void backgroundWorkIsBusy() {
        SubAgentService running = mock(SubAgentService.class);
        when(running.hasRunning(CONVERSATION)).thenReturn(true);
        assertThat(realChatService(mock(SmartChatMemory.class), running).isBusy(CONVERSATION)).isTrue();

        SubAgentService owed = mock(SubAgentService.class);
        when(owed.hasUndelivered(CONVERSATION)).thenReturn(true);
        assertThat(realChatService(mock(SmartChatMemory.class), owed).isBusy(CONVERSATION)).isTrue();
    }
}
