package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.event.ApprovalDecidedEvent;
import ai.proxyma.core.chat.model.ToolApprovalRequest;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.repository.ToolApprovalRequestRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QUOTA-HELD APPROVED WRITES POST THEMSELVES LATER (2026-11-04, the maintainer: sixteen approved replies "not posted
 * because YouTube's quota is exhausted, they need re-posting after the reset" - and nothing re-posted them). An approved
 * write refused for a limit that lifts is not FAILED: it stays APPROVED, HELD until the provider's time, and the server
 * tries it again once due - one at a time, a second refusal holding it again and the rest of that person's due writes
 * with it, a comment answered meanwhile withdrawn rather than answered twice, and past its tries or 48 hours, FAILED
 * with the reason.
 */
class ApprovalHoldTest {

    static final String REPLY = "replyToYouTubeComment";
    static final String QUOTA = "{\"success\":false,\"error\":\"YouTube's daily quota for the connection's Google Cloud "
            + "project is spent. Nothing was posted; try again tomorrow.\"}";
    static final String POSTED = "{\"success\":true,\"replyId\":\"r1\",\"link\":\"https://www.youtube.com/watch?v=v&lc=r1\"}";
    static final String ANSWERED = "Already answered on YouTube.";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T18:14:46Z"));
    /** What each call answers, in order; POSTED once they are used up. */
    final Deque<String> answers = new ArrayDeque<>();
    final List<String> sent = new ArrayList<>();
    final Map<String, String> world = new HashMap<>();
    final List<ApprovalDecidedEvent> decided = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> List.of(),
                "decide", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !r.isPending()) return 0;
                    r.setStatus((String) args[1]);
                    r.setDecidedAt(now.get());
                    r.setDecidedBy((String) args[3]);
                    r.setNote((String) args[4]);
                    return 1;
                },
                "claimRetry", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !ToolApprovalRequest.RAN_HELD.equals(r.getRunOutcome())) return 0;
                    r.setRunOutcome(ToolApprovalRequest.RAN_RETRYING);
                    return 1;
                }));
        ApplicationEventPublisher events = e -> {
            if (e instanceof ApprovalDecidedEvent d) decided.add(d);
        };
        told = InMemoryRepositories.of(AgentInboxRepository.class);
        AgentInbox inbox = new AgentInbox(told, events);
        ChatService chat = mock(ChatService.class);
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            sent.add(JSON.readTree(calls.get(0).arguments()).get("commentId").asText());
            String answer = answers.isEmpty() ? POSTED : answers.poll();
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, answer))).build();
        });
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(InMemoryRepositories.of(AgentNodeRepository.class), inbox,
                InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings, mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
        approvals.setClockForTest(now::get);
        approvals.setEvents(events);
        approvals.setContexts(ApprovalContexts.of(List.of(new Replies()), Duration.ofSeconds(5)));
    }

    /** The next reset in this test's world: the next midnight UTC, five minutes on. */
    static Instant resetAfter(Instant t) {
        return t.truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1)).plus(Duration.ofMinutes(5));
    }

    /** The YouTube reply's provider in miniature: its quota refusal waits for the next reset. */
    final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            return Optional.of(new ApprovalContext("YouTube", List.of(Field.text("comment", "Sheet music please"),
                    Field.editable("reply", String.valueOf(args.get("text")), "text"))));
        }

        @Override
        public Optional<String> subjectOf(String tool, Map<String, Object> args) {
            return REPLY.equals(tool) ? Optional.of("youtube-comment:" + args.get("commentId")) : Optional.empty();
        }

        @Override
        public boolean settles(String tool) {
            return REPLY.equals(tool);
        }

        @Override
        public Map<String, String> settled(String tool, List<Waiting> waiting, String userId) {
            Map<String, String> out = new HashMap<>();
            for (Waiting w : waiting) {
                String reason = world.get(String.valueOf(w.arguments().get("commentId")));
                if (reason != null) out.put(w.id(), reason);
            }
            return out;
        }

        @Override
        public Optional<Instant> heldUntil(String tool, String result, Instant at) {
            return REPLY.equals(tool) && result.contains("daily quota") ? Optional.of(resetAfter(at)) : Optional.empty();
        }
    }

    String approve(String commentId) {
        try {
            String id = JSON.readTree(approvals.park("conv-writer", "u-1", new ToolCall("call-" + UUID.randomUUID(), "function",
                    REPLY, "{\"commentId\":\"" + commentId + "\",\"text\":\"Cam on ban\"}"))).get("requestId").asText();
            approvals.decideAll(List.of(row(id)), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    void at(Instant t) {
        now.set(t);
    }

    @Test
    @DisplayName("a reply YouTube refuses for its daily quota is held, still approved, until the reset - not failed - and the page and the agent say so")
    void quotaRefusalHolds() {
        answers.add(QUOTA);
        String a = approve("c1");

        ToolApprovalRequest r = row(a);
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
        assertThat(r.getRetryAt()).isEqualTo(resetAfter(now.get()));
        assertThat(r.getRunAttempts()).isEqualTo(1);
        assertThat(sent).containsExactly("c1");
        // The Decided card reads it as held, with its retry.
        assertThat(approvals.forUser("u-1", ApprovalInbox.Show.DECIDED, 0, 20).getContent()).singleElement()
                .satisfies(v -> {
                    assertThat(v.runOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
                    assertThat(v.retryAt()).isEqualTo(resetAfter(now.get()));
                });
        // Held, not a lesson and not a failure.
        assertThat(decided).singleElement().satisfies(e -> {
            assertThat(e.held()).isTrue();
            assertThat(e.succeeded()).isFalse();
        });
        assertThat(told.findAll()).singleElement().satisfies(e ->
                assertThat(((ai.proxyma.core.chat.model.AgentInboxEntry) e).getText()).contains("Nothing was made yet",
                        "The server makes it itself after"));
    }

    @Test
    @DisplayName("once due it is posted, once: nothing before the reset, one call after it, and nothing again")
    void retryPostsOnce() {
        answers.add(QUOTA);
        String a = approve("c1");
        Instant reset = row(a).getRetryAt();

        approvals.retryHeld();
        assertThat(sent).as("not due yet").hasSize(1);

        at(reset.plusSeconds(60));
        approvals.retryHeld();
        ToolApprovalRequest r = row(a);
        assertThat(sent).containsExactly("c1", "c1");
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_DONE);
        assertThat(r.getRetryAt()).isNull();
        assertThat(r.getRanAt()).isEqualTo(now.get());
        assertThat(r.getRunAttempts()).isEqualTo(2);
        assertThat(decided).last().satisfies(e -> {
            assertThat(e.succeeded()).isTrue();
            assertThat(e.held()).isFalse();
        });

        at(now.get().plus(Duration.ofHours(1)));
        approvals.retryHeld();
        assertThat(sent).as("made: never again").hasSize(2);
    }

    @Test
    @DisplayName("a second refusal holds it again until the next reset, and holds the person's other due replies with it without asking")
    void secondRefusalHoldsAgain() {
        answers.add(QUOTA);
        answers.add(QUOTA);
        String a = approve("c1");
        String b = approve("c2");
        Instant reset = row(a).getRetryAt();
        assertThat(row(b).getRetryAt()).isEqualTo(reset);

        at(reset.plusSeconds(60));
        answers.add(QUOTA);
        approvals.retryHeld();

        Instant next = resetAfter(now.get());
        assertThat(sent).as("one call in the pass: the quota is still spent").containsExactly("c1", "c2", "c1");
        assertThat(row(a).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
        assertThat(row(a).getRetryAt()).isEqualTo(next);
        assertThat(row(a).getRunAttempts()).isEqualTo(2);
        assertThat(row(b).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
        assertThat(row(b).getRetryAt()).as("held with it, not tried").isEqualTo(next);
        assertThat(row(b).getRunAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("past 48 hours from its approval it fails with the reason, and is tried no more")
    void boundFails() {
        answers.add(QUOTA);
        String a = approve("c1");
        // Approved almost two days ago: the next reset is past the 48 hours.
        row(a).setDecidedAt(row(a).getRetryAt().minus(ApprovalInbox.RETRY_WINDOW).plus(Duration.ofHours(1)));

        at(row(a).getRetryAt().plusSeconds(60));
        answers.add(QUOTA);
        approvals.retryHeld();

        ToolApprovalRequest r = row(a);
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_FAILED);
        assertThat(r.getRetryAt()).isNull();
        assertThat(ApprovalContexts.errorOf(r.getResult())).hasValueSatisfying(e ->
                assertThat(e).contains("daily quota", "Still refused after 2 tries over 48 hours"));
        assertThat(decided).last().satisfies(e -> assertThat(e.succeeded()).isFalse());

        at(now.get().plus(Duration.ofDays(2)));
        approvals.retryHeld();
        assertThat(sent).hasSize(2);
    }

    @Test
    @DisplayName("a comment answered on YouTube while its reply was held is withdrawn at the retry, never answered twice")
    void answeredMeanwhileIsWithdrawn() {
        answers.add(QUOTA);
        String a = approve("c1");
        world.put("c1", ANSWERED);

        at(row(a).getRetryAt().plusSeconds(60));
        approvals.retryHeld();

        ToolApprovalRequest r = row(a);
        assertThat(sent).as("no second reply").containsExactly("c1");
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(r.getNote()).isEqualTo(ANSWERED);
        assertThat(r.getRunOutcome()).isNull();
        assertThat(r.getRetryAt()).isNull();
    }

    @Test
    @DisplayName("an approved reply held for the quota is taken: listed as drafted, and a new draft for its comment - parked, retrying or automatic - is refused, nothing kept, the held one never replaced")
    void heldIsNotAskedAgain() throws Exception {
        // 2026-11-05, the maintainer: "I approved, but youtube API reach limits. Those approved replies should be queued
        // to retry, not to ask for approval again."
        answers.add(QUOTA);
        String a = approve("c1");
        long kept = rows.count();

        assertThat(approvals.waitingSubjects("u-1", List.of("youtube-comment:c1", "youtube-comment:c2")))
                .containsExactly("youtube-comment:c1");
        assertThat(approvals.heldSubjects("u-1", List.of("youtube-comment:c1"))).containsExactly("youtube-comment:c1");
        assertThat(approvals.heldSubjects("someone-else", List.of("youtube-comment:c1"))).isEmpty();

        var answer = JSON.readTree(approvals.park("conv-writer", "u-1", new ToolCall("call-2", "function", REPLY,
                "{\"commentId\":\"c1\",\"text\":\"Cam on ban nhieu\"}")));
        assertThat(answer.get("status").asText()).isEqualTo("ALREADY_APPROVED");
        assertThat(answer.get("requestId").asText()).isEqualTo(a);
        assertThat(answer.get("error").asText()).contains("Already approved", "daily limit resets", "no new approval");
        assertThat(rows.count()).as("nothing kept").isEqualTo(kept);
        assertThat(row(a).getStatus()).as("never replaced").isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(row(a).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);

        ApprovalInbox.AutoApproval auto = approvals.approveAutomatically("conv-writer", "u-1", new ToolCall("call-3",
                "function", REPLY, "{\"commentId\":\"c1\",\"text\":\"Hi\"}"), ApprovalInbox.AUTO_PROJECT);
        assertThat(auto.proceed()).isFalse();
        assertThat(auto.refusal()).contains("ALREADY_APPROVED");
        assertThat(rows.count()).isEqualTo(kept);

        // Being retried counts the same; a different comment is asked as ever.
        row(a).setRunOutcome(ToolApprovalRequest.RAN_RETRYING);
        assertThat(approvals.alreadyHeld(REPLY, "{\"commentId\":\"c1\",\"text\":\"x\"}", "u-1")).isPresent();
        assertThat(approvals.alreadyHeld(REPLY, "{\"commentId\":\"c2\",\"text\":\"x\"}", "u-1")).isEmpty();
        // Once made, nothing holds the subject.
        row(a).setRunOutcome(ToolApprovalRequest.RAN_DONE);
        assertThat(approvals.alreadyHeld(REPLY, "{\"commentId\":\"c1\",\"text\":\"x\"}", "u-1")).isEmpty();
    }

    @Test
    @DisplayName("a refusal that waiting does not cure fails at once, as before")
    void otherFailuresFail() {
        answers.add("{\"success\":false,\"error\":\"YouTube refused the reply: comments are turned off on that video.\"}");
        String a = approve("c1");
        assertThat(row(a).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_FAILED);
        assertThat(row(a).getRetryAt()).isNull();
        assertThat(decided).singleElement().satisfies(e -> assertThat(e.held()).isFalse());
    }
}
