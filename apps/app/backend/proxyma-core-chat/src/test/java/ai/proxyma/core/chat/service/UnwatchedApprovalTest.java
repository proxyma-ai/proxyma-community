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
import org.mockito.Mockito;
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
        when(inbox.approveAutomatically(anyString(), any(), any(), anyString()))
                .thenReturn(new ApprovalInbox.AutoApproval(java.util.UUID.randomUUID(), null));
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(WRITE)).isEqualTo(ChatService.READ_ONLY_REFUSAL);
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        assertThat(decide(WRITE)).isNull();
        verify(inbox, never()).park(anyString(), any(), any());
        // Watched, Auto asks nothing of the inbox at all.
        chatService.setPermissionMode("conv-person", PermissionMode.AUTO);
        assertThat(chatService.awaitApprovalIfNeeded("conv-person", WRITE, WRITE.id(), new AtomicBoolean(false))).isNull();
        verify(inbox, never()).approveAutomatically(eq("conv-person"), any(), any(), anyString());
    }

    @Test
    @DisplayName("an agent's Auto where nobody watches still leaves a row: kept in the inbox, approved as the agent's Auto, run in the turn")
    void agentAutoLeavesARow() {
        when(inbox.approveAutomatically(anyString(), any(), any(), anyString()))
                .thenReturn(new ApprovalInbox.AutoApproval(java.util.UUID.randomUUID(), null));
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        assertThat(decide(WRITE)).as("let through, to run in the turn").isNull();
        verify(inbox).approveAutomatically(eq(CONV), any(), eq(WRITE), eq(ApprovalInbox.AUTO_AGENT));
        verify(inbox, never()).park(anyString(), any(), any());
    }

    @Test
    @DisplayName("a project that approves its writes: Ask where nobody watches is approved at once, as the project's - and what is not to be made is answered")
    void projectAutoApproves() {
        when(module.autoApproves(CONV)).thenReturn(true);
        when(inbox.approveAutomatically(anyString(), any(), any(), anyString()))
                .thenReturn(new ApprovalInbox.AutoApproval(java.util.UUID.randomUUID(), null));
        assertThat(decide(WRITE)).isNull();
        verify(inbox).approveAutomatically(eq(CONV), any(), eq(WRITE), eq(ApprovalInbox.AUTO_PROJECT));
        verify(inbox, never()).park(anyString(), any(), any());

        when(inbox.approveAutomatically(anyString(), any(), any(), anyString()))
                .thenReturn(new ApprovalInbox.AutoApproval(null, "{\"status\":\"NOT_MADE\"}"));
        assertThat(decide(WRITE)).contains("NOT_MADE");

        // Read-only still refuses, before anything is kept.
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(WRITE)).isEqualTo(ChatService.READ_ONLY_REFUSAL);
        verify(inbox, Mockito.times(2)).approveAutomatically(anyString(), any(), any(), anyString());
        // And the write switches still decide whether it may happen at all.
        chatService.setPermissionMode(CONV, PermissionMode.ASK);
        when(guard.checkWriteAllowed(any(), any())).thenReturn("Write is off for this connection.");
        assertThat(decide(WRITE)).contains("Write is off for this connection.");
        verify(inbox, Mockito.times(2)).approveAutomatically(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("through the turn: an automatically approved call runs once and its result - or its failure - is recorded on its request")
    void finishedWithItsResult() {
        ToolCallback create = tool("createJiraIssue");
        when(create.call(anyString())).thenReturn("{\"success\":true,\"key\":\"PRX-1\"}");
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[]{create});
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.offloadIfLarge(anyString(), anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(3));
        ChatService chat = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), guard, memory,
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        chat.setApprovalInbox(inbox);
        chat.setChatModules(List.of(module));
        when(module.autoApproves(CONV)).thenReturn(true);
        java.util.UUID request = java.util.UUID.randomUUID();
        when(inbox.approveAutomatically(anyString(), any(), any(), anyString()))
                .thenReturn(new ApprovalInbox.AutoApproval(request, null));
        when(inbox.finishAutomatic(eq(request), any())).thenAnswer(i -> i.getArgument(1));

        chat.callTools(CONV, List.of(WRITE), new AtomicBoolean());
        verify(create, Mockito.times(1)).call(anyString());
        verify(inbox).finishAutomatic(request, "{\"success\":true,\"key\":\"PRX-1\"}");

        // A tool that throws is recorded as failed, not left as being made.
        when(create.call(anyString())).thenThrow(new IllegalStateException("Jira is down"));
        chat.callTools(CONV, List.of(new ToolCall("call-2", "function", "createJiraIssue", "{}")), new AtomicBoolean());
        verify(inbox).finishAutomatic(eq(request), org.mockito.ArgumentMatchers.contains("Jira is down"));
    }

    @Test
    @DisplayName("the inbox's own run of an approved call in an Auto conversation is let through, never kept a second time")
    void approvedCallInAuto() {
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        ToolCall approved = new ToolCall(ApprovalInbox.CALL_PREFIX + "r-1", "function", "createJiraIssue", "{}");
        when(inbox.consumeApproved(approved.id())).thenReturn(true);
        assertThat(decide(approved)).isNull();
        verify(inbox, never()).approveAutomatically(anyString(), any(), any(), anyString());
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

    static ToolCallback tool(String name) {
        org.springframework.ai.tool.definition.ToolDefinition definition =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(cb -> cb.getToolDefinition().name()).toList();
    }

    @Test
    @DisplayName("askUser is not offered where nobody watches - it could only be answered 'nobody is watching'")
    void noQuestionOffered() {
        List<ToolCallback> offered = List.of(tool("searchIndexedSources"), tool("askUser"), tool("createJiraIssue"));

        assertThat(names(chatService.withoutQuestionsWhereUnwatched(CONV, offered)))
                .containsExactly("searchIndexedSources", "createJiraIssue");
        // Watched, or another conversation: offered as ever.
        assertThat(names(chatService.withoutQuestionsWhereUnwatched("conv-person", offered))).contains("askUser");
        when(module.parksApprovals(CONV)).thenReturn(false);
        assertThat(names(chatService.withoutQuestionsWhereUnwatched(CONV, offered))).contains("askUser");
    }

    @Test
    @DisplayName("a write waiting in the inbox is work outstanding: the conversation's report waits for it")
    void waitingIsOutstanding() {
        when(inbox.hasPending(CONV)).thenReturn(true);
        assertThat(chatService.hasBackgroundWork(CONV)).isTrue();
    }
}
