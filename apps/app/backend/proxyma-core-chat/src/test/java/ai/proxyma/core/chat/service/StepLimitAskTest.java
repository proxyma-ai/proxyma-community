package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A turn that has used its steps asks whether to go on, and only "Continue" means yes.
 *
 * <p>It used to end there: the last step was told to answer with what it had, and a job that
 * needed sixty steps stopped at fifty. Since 2026-10-03 the reader is asked - and everything
 * that is not an explicit yes leaves the turn to end as it always did.
 */
class StepLimitAskTest {

    private static final String CONVERSATION = "conv-1";

    private ChatService chatService;
    private final List<ChatEvent> published = new ArrayList<>();
    /** What the reader does when the question appears. */
    private Consumer<ChatEvent> reader = question -> { };

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doAnswer(call -> {
            if (call.getArgument(0) instanceof ChatEvent event) {
                published.add(event);
                if (ChatEvent.Type.USER_QUESTION.value().equals(event.streamType())) reader.accept(event);
            }
            return null;
        }).when(events).publishEvent(any(Object.class));
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                events, mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    private void answers(String answer) {
        reader = question -> chatService.resolveQuestion(CONVERSATION, question.callId(),
                List.of(Map.of("question", "Let it continue?", "answer", answer)), false);
    }

    private List<String> types() {
        return published.stream().map(ChatEvent::streamType).toList();
    }

    @Test
    @DisplayName("Continue lets the turn go on, and the question is closed behind it")
    void continueGoesOn() {
        answers("Continue");

        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, new AtomicBoolean())).isTrue();

        // Asked once, then closed by an event of its own - no tool result will ever arrive for it.
        assertThat(types()).containsExactly("user-question", "prompt-closed");
        assertThat(published.get(1).callId()).isEqualTo(published.get(0).callId());
    }

    @Test
    @DisplayName("the question says how many steps were used and how many more a yes gives")
    void theQuestionCarriesItsNumbers() throws Exception {
        answers("Continue");
        chatService.askToContinue(CONVERSATION, 50, 20, new AtomicBoolean());

        JsonNode asked = new ObjectMapper().readTree(published.get(0).data());
        assertThat(asked.path("stepLimit").asInt()).isEqualTo(50);
        assertThat(asked.path("moreSteps").asInt()).isEqualTo(20);
        // Written out as an ordinary question too, so anything that shows one can show this.
        JsonNode question = asked.path("questions").get(0);
        assertThat(question.path("question").asText()).contains("50 steps");
        assertThat(question.path("options").get(0).path("label").asText()).isEqualTo(ChatService.CONTINUE_ANSWER);
        assertThat(published.get(0).toolName()).isEqualTo(ChatService.CONTINUE_PROMPT);
    }

    @Test
    @DisplayName("Stop here, a skip, or words of the reader's own all end the turn as it always ended")
    void anythingElseStops() {
        answers("Stop here");
        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, new AtomicBoolean())).isFalse();

        reader = question -> chatService.resolveQuestion(CONVERSATION, question.callId(), List.of(), true);
        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, new AtomicBoolean())).isFalse();

        answers("maybe, if it is quick");
        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, new AtomicBoolean())).isFalse();
    }

    @Test
    @DisplayName("Stop pressed while the question is open ends the wait, and the question is still closed")
    void aStoppedTurnDoesNotWait() {
        AtomicBoolean abort = new AtomicBoolean(true);

        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, abort)).isFalse();
        assertThat(types()).containsExactly("user-question", "prompt-closed");
    }

    @Test
    @DisplayName("another conversation cannot answer it")
    void anAnswerIsBoundToItsConversation() {
        AtomicBoolean abort = new AtomicBoolean();
        reader = question -> {
            chatService.resolveQuestion("conv-2", question.callId(),
                    List.of(Map.of("question", "Let it continue?", "answer", "Continue")), false);
            // Nothing answered it, so the wait would run its thirty minutes: end it here.
            abort.set(true);
        };

        assertThat(chatService.askToContinue(CONVERSATION, 50, 50, abort)).isFalse();
    }

    @Test
    @DisplayName("only a successful result holding the word Continue counts")
    void whatCountsAsYes() {
        assertThat(ChatService.wantsToContinue("{\"success\":true,\"answers\":[{\"question\":\"q\",\"answer\":\"Continue\"}]}")).isTrue();
        assertThat(ChatService.wantsToContinue("{\"success\":true,\"answers\":[{\"question\":\"q\",\"answer\":\" continue \"}]}")).isTrue();
        assertThat(ChatService.wantsToContinue("{\"success\":false,\"error\":\"The user chose not to answer.\"}")).isFalse();
        assertThat(ChatService.wantsToContinue("{\"success\":true,\"answers\":[]}")).isFalse();
        assertThat(ChatService.wantsToContinue("not json")).isFalse();
        assertThat(ChatService.wantsToContinue(null)).isFalse();
    }
}
