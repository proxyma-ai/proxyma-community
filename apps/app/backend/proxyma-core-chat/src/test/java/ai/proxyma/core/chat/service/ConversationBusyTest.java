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
 * condensation and agents it deployed must all count, or a page would reload under work that
 * is about to speak. See ChatStreamOpenTest for the event itself.
 */
class ConversationBusyTest {

    private static final String CONVERSATION = "conv-1";

    private static ChatService realChatService(SmartChatMemory memory) {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        return new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    @Test
    @DisplayName("busy: nothing for a conversation nothing runs in")
    void idleIsNotBusy() {
        ChatService service = realChatService(mock(SmartChatMemory.class));
        assertThat(service.isBusy(CONVERSATION)).isFalse();
    }

    @Test
    @DisplayName("busy: a turn accepted and not registered yet - the page's stream may open before it does")
    void aDispatchedTurnIsBusy() {
        ChatService service = realChatService(mock(SmartChatMemory.class));
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
        holder[0] = realChatService(memory);

        assertThat(holder[0].condenseNow(CONVERSATION)).isTrue();

        assertThat(during.get()).isTrue();
        assertThat(holder[0].isBusy(CONVERSATION)).isFalse();
    }

    @Test
    @DisplayName("busy: a condensation summarizing, whatever started it - GET .../status says so too")
    void aCondensationSummarizingIsBusy() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.isCondensingNow(CONVERSATION)).thenReturn(true);
        assertThat(realChatService(memory).isBusy(CONVERSATION)).isTrue();
    }

    @Test
    @DisplayName("busy: agents it deployed at work, a background job, or mail still owed")
    void backgroundWorkIsBusy() {
        ChatService service = realChatService(mock(SmartChatMemory.class));
        ai.proxyma.core.chat.agents.AgentTree tree = mock(ai.proxyma.core.chat.agents.AgentTree.class);
        service.setAgentTree(tree);
        when(tree.hasBusyChildren(CONVERSATION)).thenReturn(true);
        assertThat(service.isBusy(CONVERSATION)).isTrue();

        ChatService jobs = realChatService(mock(SmartChatMemory.class));
        ai.proxyma.core.chat.agents.BackgroundJobs background = mock(ai.proxyma.core.chat.agents.BackgroundJobs.class);
        jobs.setBackgroundJobs(background);
        when(background.hasRunning(CONVERSATION)).thenReturn(true);
        assertThat(jobs.isBusy(CONVERSATION)).isTrue();

        ChatService mail = realChatService(mock(SmartChatMemory.class));
        ai.proxyma.core.chat.agents.AgentInbox inbox = mock(ai.proxyma.core.chat.agents.AgentInbox.class);
        mail.setAgentInbox(inbox);
        when(inbox.hasPending(CONVERSATION)).thenReturn(true);
        assertThat(mail.isBusy(CONVERSATION)).isTrue();
    }
}
