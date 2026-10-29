package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.ApprovalDecision;
import ai.proxyma.core.chat.domain.EnableDecision;
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

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-49: a tool decision is resolved only for the conversation that asked for it.
 *
 * <p>POST /chat/conversations/{id}/tool-approval checked that the caller could open the path's
 * conversation, and then resolved the pending decision by its call id ALONE. So anyone who could
 * open a conversation of their own - a visitor included - could approve a write waiting in someone
 * else's turn by posting its call id under their own conversation id. The call id travels in the
 * SSE stream, which SEC-42 also leaked, so the two together were a complete path.
 */
class ToolDecisionBindingTest {

    private static final String OWNER_CONV = "conv-owner";
    private static final String OTHER_CONV = "conv-other";

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
    @DisplayName("an approval posted under another conversation's id does not resolve the pending write")
    void approvalFromAnotherConversationIsIgnored() {
        CompletableFuture<ApprovalDecision> pending = chatService.registerPendingApproval(OWNER_CONV, "call-1");

        chatService.resolveApproval(OTHER_CONV, "call-1", "ACCEPT_ONCE");

        assertThat(pending).isNotDone();
    }

    @Test
    @DisplayName("the conversation that asked can still resolve its own approval")
    void approvalFromItsOwnConversationResolves() {
        CompletableFuture<ApprovalDecision> pending = chatService.registerPendingApproval(OWNER_CONV, "call-1");

        chatService.resolveApproval(OWNER_CONV, "call-1", "ACCEPT_ONCE");

        assertThat(pending).isCompletedWithValue(ApprovalDecision.ACCEPT_ONCE);
    }

    @Test
    @DisplayName("Accept All in one conversation answers only that conversation's waiting calls")
    void acceptAllDoesNotReachAnotherConversation() {
        CompletableFuture<ApprovalDecision> mine = chatService.registerPendingApproval(OTHER_CONV, "call-mine");
        CompletableFuture<ApprovalDecision> sibling = chatService.registerPendingApproval(OTHER_CONV, "call-sibling");
        CompletableFuture<ApprovalDecision> theirs = chatService.registerPendingApproval(OWNER_CONV, "call-theirs");

        chatService.resolveApproval(OTHER_CONV, "call-mine", "ACCEPT_ALL");

        assertThat(mine).isCompletedWithValue(ApprovalDecision.ACCEPT_ALL);
        assertThat(sibling).isCompletedWithValue(ApprovalDecision.ACCEPT_ONCE);
        assertThat(theirs).isNotDone();
    }

    @Test
    @DisplayName("an enable decision posted under another conversation's id does not switch writes on")
    void enableDecisionFromAnotherConversationIsIgnored() {
        CompletableFuture<EnableDecision> pending = chatService.registerPendingEnableDecision(OWNER_CONV, "call-2");

        chatService.resolveEnableDecision(OTHER_CONV, "call-2", "ENABLED");

        assertThat(pending).isNotDone();

        chatService.resolveEnableDecision(OWNER_CONV, "call-2", "ENABLED");

        assertThat(pending).isCompletedWithValue(EnableDecision.ENABLED);
    }

    @Test
    @DisplayName("an askUser answer reaches the model as the call's result, and only from its own conversation")
    void questionAnswerIsBoundToItsConversation() {
        CompletableFuture<String> pending = chatService.registerPendingQuestion(OWNER_CONV, "call-q");

        chatService.resolveQuestion(OTHER_CONV, "call-q",
                java.util.List.of(java.util.Map.of("question", "Which format?", "answer", "PDF")), false);
        assertThat(pending).isNotDone();

        chatService.resolveQuestion(OWNER_CONV, "call-q",
                java.util.List.of(java.util.Map.of("question", "Which format?", "answer", "PDF")), false);
        assertThat(pending.join()).contains("\"success\":true").contains("Which format?").contains("PDF");
    }

    @Test
    @DisplayName("several picks reach the model as a list, not as text joined by commas")
    void severalPicksReachTheModelAsAList() {
        CompletableFuture<String> pending = chatService.registerPendingQuestion(OWNER_CONV, "call-m");

        chatService.resolveQuestion(OWNER_CONV, "call-m", java.util.List.of(java.util.Map.of(
                "question", "Which sources?", "answer", java.util.List.of("Confluence", "Files, local"))), false);

        assertThat(pending.join()).contains("\"answer\":[\"Confluence\",\"Files, local\"]");
    }

    @Test
    @DisplayName("a skipped question tells the model to go on with its best judgment")
    void skippedQuestionSaysSo() {
        CompletableFuture<String> pending = chatService.registerPendingQuestion(OWNER_CONV, "call-s");

        chatService.resolveQuestion(OWNER_CONV, "call-s", java.util.List.of(), true);

        assertThat(pending.join()).contains("\"success\":false").contains("chose not to answer");
    }
}
