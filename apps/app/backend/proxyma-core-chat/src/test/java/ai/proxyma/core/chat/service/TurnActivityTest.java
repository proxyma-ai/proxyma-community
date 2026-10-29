package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.ApprovalInbox;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.guardrail.StreamingScrubber;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmGateway.LlmResult;
import ai.proxyma.core.provider.gateway.LlmSession;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a running turn is doing, for a page that shows it without following the stream
 * (Constellations' agent cards, 2026-10-29): the tool it is in by name, a rate limit's wait while it
 * lasts, and nothing at all once the turn has ended - a stale activity is worse than none.
 */
class TurnActivityTest {

    @Test
    @DisplayName("the latest tool still in flight is the one shown; none once it ends; nothing once the turn ends")
    void toolsInFlight() {
        TurnActivity activity = new TurnActivity();
        assertThat(activity.of("c")).isNull();
        activity.toolStarted("c", "t0", "searchSources");
        assertThat(activity.of("c")).as("no turn runs, so a call outside one is not recorded").isNull();

        Object turn = activity.begin("c");
        assertThat(activity.of("c").tool()).isNull();
        activity.toolStarted("c", "t1", "searchSources");
        activity.toolStarted("c", "t2", "jira_search");
        assertThat(activity.of("c").tool()).isEqualTo("jira_search");
        activity.toolEnded("c", "t2");
        assertThat(activity.of("c").tool()).isEqualTo("searchSources");
        activity.toolEnded("c", "t1");
        activity.toolEnded("c", "t1");
        assertThat(activity.of("c").tool()).isNull();

        activity.toolStarted("c", "t3", "readSource");
        activity.end("c", turn);
        assertThat(activity.of("c")).as("the turn's end takes its calls with it").isNull();
    }

    @Test
    @DisplayName("an earlier turn's end never removes the turn that replaced it")
    void endIsTheTurnsOwn() {
        TurnActivity activity = new TurnActivity();
        Object first = activity.begin("c");
        Object second = activity.begin("c");
        activity.end("c", first);
        assertThat(activity.of("c")).isNotNull();
        activity.end("c", second);
        assertThat(activity.of("c")).isNull();
    }

    @Test
    @DisplayName("a rate limit's wait shows while it lasts")
    void rateLimit() {
        TurnActivity activity = new TurnActivity();
        Object turn = activity.begin("c");
        activity.rateLimited("c", true);
        assertThat(activity.of("c").rateLimited()).isTrue();
        activity.rateLimited("c", false);
        assertThat(activity.of("c").rateLimited()).isFalse();
        activity.end("c", turn);
    }

    @Test
    @DisplayName("a model call says when each attempt starts, so the wait before a retry ends with it")
    void attemptsAreAnnounced() {
        AtomicInteger calls = new AtomicInteger();
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after-ms", "1");
        RuntimeException refusal = WebClientResponseException.create(429, "Error", headers,
                "{\"type\":\"error\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        LlmSession session = new LlmSession() {
            @Override public String provider() { return "Fake"; }
            @Override public String model() { return "fake-chat"; }
            @Override public boolean dedicated() { return false; }
            @Override public LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
                return Flux.defer(() -> calls.incrementAndGet() <= 2
                        ? Flux.error(refusal)
                        : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))));
            }
        };
        List<String> seen = new ArrayList<>();
        ModelCall.run(session, true, List.of(new UserMessage("go")), List.of(), () -> false,
                new StreamingScrubber(List.of()), new ModelCall.Listener() {
                    @Override public void text(String text) {}
                    @Override public void retry(int attempt, Throwable failure) {
                        seen.add(ModelCall.isRateLimited(failure) ? "rate-limited" : "retry");
                    }
                    @Override public void attempt() { seen.add("attempt"); }
                });

        assertThat(seen).containsExactly("attempt", "rate-limited", "attempt", "rate-limited", "attempt");
    }

    @Test
    @DisplayName("turnStates: an idle conversation is left out, and a write in the approvals inbox shows with no turn running")
    void turnStates() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ChatService service = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        ApprovalInbox inbox = mock(ApprovalInbox.class);
        when(inbox.hasPending("parked")).thenReturn(true);
        service.setApprovalInbox(inbox);

        Map<String, ChatService.TurnState> states = service.turnStates(List.of("idle", "parked"));

        assertThat(states).containsOnlyKeys("parked");
        assertThat(states.get("parked")).isEqualTo(new ChatService.TurnState(false, 0, null, false, true, 0));
        assertThat(service.turnStates(List.of())).isEmpty();
    }
}
