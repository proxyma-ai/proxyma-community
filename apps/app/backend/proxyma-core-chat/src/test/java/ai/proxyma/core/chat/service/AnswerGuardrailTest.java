package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailDecision;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.guardrail.GuardrailStage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.search.domain.SearchContext;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * chat-agent-12: an answer to the assistant's question is the user's own words reaching the model,
 * and it was the one way past the INPUT guardrail - a credential typed into it went to the provider
 * and into the stored history. chat-agent-23: an input check made the user and the conversation
 * known to the guardrail's audit entry, which read them off SearchContext and found nothing.
 */
class AnswerGuardrailTest {

    private static final String CONV = "conv-1";

    private ChatService chatService;
    private GuardrailService guardrail;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        guardrail = mock(GuardrailService.class);
        when(guardrail.enabled(GuardrailStage.INPUT)).thenReturn(true);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(SubAgentService.class), mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), guardrail);
    }

    @Test
    @DisplayName("an answer the input rules withhold never reaches the model")
    void withheldAnswer() {
        when(guardrail.checkInput(anyString(), anyString())).thenAnswer(i -> {
            String text = i.getArgument(0);
            return text.contains("sk-") ? GuardrailDecision.block(text, List.of()) : GuardrailDecision.allow(text);
        });
        CompletableFuture<String> pending = chatService.registerPendingQuestion(CONV, "call-q");

        chatService.resolveQuestion(CONV, "call-q", List.of(
                Map.of("question", "Which key?", "answer", "sk-live-1234567890")), false);

        assertThat(pending.join()).contains("\"success\":false").contains("withheld").doesNotContain("sk-live");
    }

    @Test
    @DisplayName("an answer they redact reaches the model redacted, a pick of several included")
    void redactedAnswer() {
        when(guardrail.checkInput(anyString(), anyString())).thenAnswer(i -> {
            String text = i.getArgument(0);
            return text.contains("secret") ? GuardrailDecision.redact("[redacted]", List.of())
                    : GuardrailDecision.allow(text);
        });
        CompletableFuture<String> pending = chatService.registerPendingQuestion(CONV, "call-q");

        chatService.resolveQuestion(CONV, "call-q", List.of(
                Map.of("question", "Which?", "answer", List.of("Confluence", "my secret"))), false);

        assertThat(pending.join()).contains("\"answer\":[\"Confluence\",\"[redacted]\"]").doesNotContain("my secret");
    }

    @Test
    @DisplayName("the check is made with the user and the conversation on SearchContext, and leaves the thread as it was")
    void checkKnowsWhoAndWhere() {
        AtomicReference<String> sawConversation = new AtomicReference<>();
        when(guardrail.checkInput(anyString(), anyString())).thenAnswer(i -> {
            sawConversation.set(SearchContext.getConversationId());
            return GuardrailDecision.allow(i.getArgument(0));
        });
        CompletableFuture<String> pending = chatService.registerPendingQuestion(CONV, "call-q");

        chatService.resolveQuestion(CONV, "call-q", List.of(Map.of("question", "Which?", "answer", "PDF")), false);

        assertThat(pending.join()).contains("PDF");
        assertThat(sawConversation.get()).isEqualTo(CONV);
        assertThat(SearchContext.getConversationId()).isNull();
    }
}
