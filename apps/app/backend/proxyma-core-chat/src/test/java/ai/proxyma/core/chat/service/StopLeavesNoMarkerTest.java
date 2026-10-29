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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * chat-agent-09: a Stop that finds no turn running is kept only for a turn that is on its way.
 *
 * <p>It was kept whenever no turn was found - including the moment after a turn ENDED, a Stop
 * clicked as its last event arrived - and the next turn of that conversation within five minutes
 * started already stopped: the user's next message was answered "Chat was stopped." and never
 * reached the model. Deleting a conversation stopped it the same way and left one such marker per
 * deleted conversation for the life of the process.
 */
class StopLeavesNoMarkerTest {

    private static final String CONVERSATION = "conv-1";

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    @Test
    @DisplayName("a Stop with no turn running or on its way leaves nothing for the next turn")
    void stopAfterTheTurnEndedIsNothing() {
        chatService.stop(CONVERSATION);
        assertThat(chatService.stopWaiting(CONVERSATION)).isFalse();
    }

    @Test
    @DisplayName("a Stop for a turn that was started and has not registered is kept for it")
    void stopForATurnOnItsWayIsKept() {
        chatService.recordTurnStart(CONVERSATION, "local", null);
        chatService.stop(CONVERSATION);
        assertThat(chatService.stopWaiting(CONVERSATION)).isTrue();
    }

    @Test
    @DisplayName("deleting a conversation leaves no Stop behind")
    void deletingLeavesNoMarker() {
        chatService.deleteConversation(CONVERSATION);
        assertThat(chatService.stopWaiting(CONVERSATION)).isFalse();
    }
}
