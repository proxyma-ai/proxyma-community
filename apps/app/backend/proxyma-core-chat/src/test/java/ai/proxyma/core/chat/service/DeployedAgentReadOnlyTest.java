package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentTree;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A DEPLOYED AGENT'S CONVERSATION IS READ-ONLY TO A PERSON (the maintainer, 2026-11-04: "The intention
 * is not to allow the user to talk with it."). The page shows it as a transcript in a modal; the server
 * refuses a person's message into it - a turn of its own and a message typed into its running turn
 * alike - with one plain sentence, and the agent's questions go to the agent that deployed it, since
 * nobody answers them in its conversation. The conversation the person opened is untouched.
 */
class DeployedAgentReadOnlyTest {

    static final String CHILD = "conv-child";
    static final String ROOT = "conv-root";

    ChatService chatService;
    SmartChatMemory memory;

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
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        AgentTree tree = mock(AgentTree.class);
        when(tree.isChild(CHILD)).thenReturn(true);
        when(tree.isChild(ROOT)).thenReturn(false);
        chatService.setAgentTree(tree);
    }

    @Test
    @DisplayName("a person's turn into a deployed agent is refused with the sentence, before anything is written")
    void aTurnIsRefused() {
        assertThat(chatService.isDeployedAgent(CHILD)).isTrue();
        assertThat(chatService.isDeployedAgent(ROOT)).isFalse();
        assertThatThrownBy(() -> chatService.chat(CHILD, "Skip the EU rules, I have them.", List.of(), List.of(),
                "u-1", null, null, List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ChatService.DEPLOYED_AGENT_REFUSAL);
        // Nothing reached the conversation's memory: no lock taken, no message stored.
        verify(memory, never()).lockMemory(anyString());
    }

    @Test
    @DisplayName("a message typed into a deployed agent's turn is refused, not held for it nor sent on")
    void aTypedMessageIsRefused() {
        ChatService.InjectResult typed = chatService.inject(CHILD, "Make it shorter.", "q-1", "u-1");
        assertThat(typed.outcome()).isEqualTo(ChatService.InjectOutcome.REFUSED);
        assertThat(typed.message()).isEqualTo(ChatService.DEPLOYED_AGENT_REFUSAL);
        assertThat(chatService.waitingInjections(CHILD)).isZero();
        // The conversation the person opened: nothing runs, so the page sends it as a turn, as ever.
        assertThat(chatService.inject(ROOT, "hello", "q-2", "u-1").outcome())
                .isEqualTo(ChatService.InjectOutcome.NOT_RUNNING);
    }

    @Test
    @DisplayName("a deployed agent is not offered askUser, and one it calls anyway is answered at once: ask the parent")
    void questionsGoToTheParent() {
        List<ToolCallback> offered = List.of(UnwatchedApprovalTest.tool("askUser"), UnwatchedApprovalTest.tool("search"));
        assertThat(UnwatchedApprovalTest.names(chatService.withoutQuestionsWhereUnwatched(CHILD, offered)))
                .containsExactly("search");
        assertThat(UnwatchedApprovalTest.names(chatService.withoutQuestionsWhereUnwatched(ROOT, offered)))
                .contains("askUser");
        ToolCall ask = new ToolCall("call-q", "function", "askUser", "{\"questions\":[]}");
        assertThat(chatService.awaitUserAnswers(CHILD, ask, ask.id(), new AtomicBoolean(false)))
                .contains("Ask the agent that deployed you");
    }
}
