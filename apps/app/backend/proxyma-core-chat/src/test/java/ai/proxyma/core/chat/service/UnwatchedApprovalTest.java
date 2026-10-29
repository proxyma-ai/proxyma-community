package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.ApprovalInbox;
import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.module.ChatModule;
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
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WHERE NOBODY WATCHES, NOTHING WAITS (review item 14: an unattended Ask held a worker for half an
 * hour per write). A conversation a module runs for a schedule or another agent keeps its writes in
 * the approvals inbox and goes on; it answers its own questions; it ends at its step limit rather than
 * asking. Read-only still refuses, Auto still goes ahead, and an approval from the inbox is let
 * through exactly for the call it names.
 */
class UnwatchedApprovalTest {

    static final String CONV = "conv-run";
    static final ToolCall WRITE = new ToolCall("call-1", "function", "createJiraIssue", "{}");

    McpGuardService guard;
    ChatService chatService;
    ApprovalInbox inbox;
    ChatModule module;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        guard = mock(McpGuardService.class);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), guard, mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        inbox = mock(ApprovalInbox.class);
        when(inbox.park(anyString(), any(), any())).thenReturn("{\"status\":\"WAITING_FOR_APPROVAL\"}");
        chatService.setApprovalInbox(inbox);
        module = mock(ChatModule.class);
        when(module.parksApprovals(CONV)).thenReturn(true);
        chatService.setChatModules(List.of(module));
    }

    // NOT aborted: a call that reached the old pause would wait half an hour, and fail the test's time.
    String decide(ToolCall call) {
        return chatService.awaitApprovalIfNeeded(CONV, call, call.id(), new AtomicBoolean(false));
    }

    @Test
    @DisplayName("Ask in an unwatched conversation keeps the write in the inbox and returns at once")
    void parks() {
        assertThat(decide(WRITE)).contains("WAITING_FOR_APPROVAL");
        verify(inbox).park(eq(CONV), any(), eq(WRITE));
    }

    @Test
    @DisplayName("a watched conversation is not parked: it still asks in the turn")
    void watchedStillAsks() {
        when(module.parksApprovals(CONV)).thenReturn(false);
        String out = chatService.awaitApprovalIfNeeded(CONV, WRITE, WRITE.id(), new AtomicBoolean(true));
        assertThat(out).contains(ChatService.STOPPED_BEFORE_ANSWER);
        verify(inbox, never()).park(anyString(), any(), any());
    }

    @Test
    @DisplayName("Read-only refuses and Auto goes ahead, unwatched or not - the inbox changes only the wait")
    void modesUnchanged() {
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(WRITE)).isEqualTo(ChatService.READ_ONLY_REFUSAL);
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        assertThat(decide(WRITE)).isNull();
        verify(inbox, never()).park(anyString(), any(), any());
    }

    @Test
    @DisplayName("the call an approval names runs without parking again; the switches still decide whether it may")
    void approvedCallRuns() {
        ToolCall approved = new ToolCall(ApprovalInbox.CALL_PREFIX + "r-1", "function", "createJiraIssue", "{}");
        when(inbox.consumeApproved(approved.id())).thenReturn(true);
        assertThat(decide(approved)).isNull();

        when(guard.checkWriteAllowed(any(), any())).thenReturn("Write is off for this connection.");
        assertThat(decide(approved)).contains("Write is off for this connection.");
    }

    @Test
    @DisplayName("an unwatched conversation's question is answered at once, and its step limit asks nobody")
    void questionsDoNotWait() {
        ToolCall ask = new ToolCall("call-q", "function", "askUser", "{\"questions\":[]}");
        assertThat(chatService.awaitUserAnswers(CONV, ask, ask.id(), new AtomicBoolean(false)))
                .contains("Nobody is watching");
        assertThat(chatService.askToContinue(CONV, 30, 10, new AtomicBoolean(false))).isFalse();
    }

    @Test
    @DisplayName("a write waiting in the inbox is work outstanding: the conversation's report waits for it")
    void waitingIsOutstanding() {
        when(inbox.hasPending(CONV)).thenReturn(true);
        assertThat(chatService.hasBackgroundWork(CONV)).isTrue();
    }
}
