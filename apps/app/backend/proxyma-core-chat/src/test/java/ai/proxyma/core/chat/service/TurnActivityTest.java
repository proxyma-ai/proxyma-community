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
        long[] now = {0};
        TurnActivity activity = new TurnActivity(() -> now[0]);
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
        now[0] += TurnActivity.LINGER_MS;
        assertThat(activity.of("c").tool()).isNull();

        activity.toolStarted("c", "t3", "readSource");
        activity.end("c", turn);
        assertThat(activity.of("c")).as("the turn's end takes its calls with it").isNull();
    }

    @Test
    @DisplayName("a call's subject: the keys a page names it by, short and scrubbed, gone when the call ends")
    void subject() {
        long[] now = {0};
        TurnActivity activity = new TurnActivity(() -> now[0]);
        Object turn = activity.begin("c");
        activity.toolStarted("c", "t1", "searchIndexedSources",
                "{\"query\":\"dizi keys\",\"limit\":5,\"content\":\"never this\"}");
        assertThat(activity.of("c").subject()).isEqualTo("{\"query\":\"dizi keys\"}");
        activity.toolEnded("c", "t1");
        now[0] += TurnActivity.LINGER_MS;
        assertThat(activity.of("c").subject()).as("no call in flight, no subject").isNull();

        // A file is its NAME, never the path to it; an absolute path in a value is scrubbed.
        activity.toolStarted("c", "t2", "read", "{\"path\":\"C:\\\\Users\\\\hau\\\\guides\\\\Bay loi hay gap.md\"}");
        assertThat(activity.of("c").subject()).isEqualTo("{\"fileName\":\"Bay loi hay gap.md\"}");
        activity.toolStarted("c", "t3", "webSearch", "{\"query\":\"see /home/hau/secret/notes.txt\"}");
        assertThat(activity.of("c").subject()).doesNotContain("/home/hau");
        activity.end("c", turn);
        activity.begin("c");

        // A REST passthrough keeps its API address; an id says nothing unless it is remember's.
        assertThat(TurnActivity.subjectOf("callJiraApi", "{\"method\":\"GET\",\"path\":\"/rest/api/2/issue/SAI-1\"}"))
                .isEqualTo("{\"method\":\"GET\",\"path\":\"/rest/api/2/issue/SAI-1\"}");
        // A workspace file keeps its address in the project, which the page reads to say what the file
        // holds and never shows; no bare name is made of it (2026-11-05).
        assertThat(TurnActivity.subjectOf("readWorkspaceFile", "{\"path\":\"memory/channel.md\"}"))
                .isEqualTo("{\"path\":\"memory/channel.md\"}");
        assertThat(TurnActivity.subjectOf("getThing", "{\"id\":\"3f2a\"}")).isNull();
        assertThat(TurnActivity.subjectOf("remember", "{\"id\":\"3f2a\",\"fact\":\"likes D5\"}"))
                .isEqualTo("{\"fact\":\"likes D5\",\"id\":\"3f2a\"}");

        // Bounded: a long value is cut, a huge call is not read, and something that is not JSON is nothing.
        String longQuery = "x".repeat(5000);
        assertThat(TurnActivity.subjectOf("webSearch", "{\"query\":\"" + longQuery + "\"}").length())
                .isLessThanOrEqualTo(TurnActivity.SUBJECT_MAX);
        assertThat(TurnActivity.subjectOf("saveToSource", "{\"title\":\"a\",\"content\":\"" + "y".repeat(70_000) + "\"}"))
                .isNull();
        assertThat(TurnActivity.subjectOf("webSearch", "not json")).isNull();
        assertThat(TurnActivity.subjectOf("listYouTubeComments", "{\"videoId\":\"dQw4w9WgXcQ\",\"limit\":20}"))
                .as("a bare id is no subject").isNull();
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
    @DisplayName("a call that ended is still named for a while, so a call answered between two looks is seen (2026-11-11)")
    void endedCallLingers() {
        long[] now = {1_000};
        TurnActivity activity = new TurnActivity(() -> now[0]);
        activity.begin("c");
        activity.toolStarted("c", "t1", "listYouTubeChannelReplies", "{\"query\":\"flute\"}");
        now[0] += 3;
        activity.toolEnded("c", "t1");
        assertThat(activity.of("c").tool()).as("answered in 3ms, still named").isEqualTo("listYouTubeChannelReplies");
        assertThat(activity.of("c").subject()).isEqualTo("{\"query\":\"flute\"}");
        now[0] += TurnActivity.LINGER_MS - 1;
        assertThat(activity.of("c").tool()).isEqualTo("listYouTubeChannelReplies");
        now[0] += 1;
        assertThat(activity.of("c").tool()).as("then the turn is thinking again").isNull();

        activity.toolStarted("c", "t2", "getYouTubeChannelStats");
        activity.toolEnded("c", "t2");
        activity.toolStarted("c", "t3", "listYouTubeVideos");
        assertThat(activity.of("c").tool()).as("a call in flight comes before one that ended").isEqualTo("listYouTubeVideos");
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
