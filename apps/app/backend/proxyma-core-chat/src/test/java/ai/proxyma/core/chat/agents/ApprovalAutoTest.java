package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.event.ApprovalDecidedEvent;
import ai.proxyma.core.chat.model.AgentInboxEntry;
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
import java.util.ArrayList;
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
 * AUTO-APPROVE (2026-11-05, the maintainer: "It should also support auto approve so the agents won't need to wait for
 * the user approval"). A write approved by the server is still a ROW, decided APPROVED by {@link ApprovalInbox#AUTO_PROJECT}
 * or {@link ApprovalInbox#AUTO_AGENT} and never waited on, and it takes what a person's approval takes before and after
 * the write: a comment answered or deleted meanwhile is withdrawn and nothing is made; a draft the person is editing
 * wins; a waiting draft for the same comment is replaced; a second write for a comment already approved is not made;
 * a spent quota HOLDS it for the server's retry; a failure is FAILED. Its decision is announced as automatic.
 */
class ApprovalAutoTest {

    static final String REPLY = "replyToYouTubeComment";
    static final String QUOTA = "{\"success\":false,\"error\":\"YouTube's daily quota for the connection's Google Cloud "
            + "project is spent. Nothing was posted; try again tomorrow.\"}";
    static final String POSTED = "{\"success\":true,\"replyId\":\"r1\",\"link\":\"https://www.youtube.com/watch?v=v&lc=r1\"}";
    static final String FAILED = "{\"success\":false,\"error\":\"The comment cannot be replied to.\"}";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T10:00:00Z"));
    /** The calls the server made itself (a held write's retry), by comment. */
    final List<String> retried = new ArrayList<>();
    final Map<String, String> world = new HashMap<>();
    final List<ApprovalDecidedEvent> decided = new ArrayList<>();
    int settledChecks;

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
                },
                // The JPQL's own rule: approved and not FAILED - made, being made or held.
                "countApprovedNotFailed", (store, args) -> store.values().stream().map(o -> (ToolApprovalRequest) o)
                        .filter(r -> args[0].equals(r.getToolName()) && args[1].equals(r.getSubject())
                                && args[2].equals(r.getUserId()) && ToolApprovalRequest.APPROVED.equals(r.getStatus())
                                && !ToolApprovalRequest.RAN_FAILED.equals(r.getRunOutcome()))
                        .count()));
        ApplicationEventPublisher events = e -> {
            if (e instanceof ApprovalDecidedEvent d) decided.add(d);
        };
        told = InMemoryRepositories.of(AgentInboxRepository.class);
        AgentInbox inbox = new AgentInbox(told, events);
        ChatService chat = mock(ChatService.class);
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            retried.add(JSON.readTree(calls.get(0).arguments()).get("commentId").asText());
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, POSTED))).build();
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

    /** The YouTube reply's provider in miniature. */
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
            settledChecks++;
            Map<String, String> out = new HashMap<>();
            for (Waiting w : waiting) {
                String reason = world.get(String.valueOf(w.arguments().get("commentId")));
                if (reason != null) out.put(w.id(), reason);
            }
            return out;
        }

        @Override
        public Optional<Instant> heldUntil(String tool, String result, Instant at) {
            return REPLY.equals(tool) && result.contains("daily quota")
                    ? Optional.of(at.truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1)).plus(Duration.ofMinutes(5)))
                    : Optional.empty();
        }
    }

    static ToolCall reply(String commentId, String text) {
        return new ToolCall("call-" + UUID.randomUUID(), "function", REPLY,
                "{\"commentId\":\"" + commentId + "\",\"text\":\"" + text + "\"}");
    }

    /** What the turn does: ask, and - let through - run the call with this answer and finish it. */
    String write(String commentId, String by, String answer) {
        ApprovalInbox.AutoApproval a = approvals.approveAutomatically("conv-writer", "u-1", reply(commentId, "Cam on ban"), by);
        if (!a.proceed()) return a.refusal();
        return approvals.finishAutomatic(a.requestId(), answer);
    }

    ToolApprovalRequest only() {
        return rows.findAll().stream().map(o -> (ToolApprovalRequest) (Object) o).reduce((a, b) -> {
            throw new AssertionError("more than one request");
        }).orElseThrow();
    }

    List<ToolApprovalRequest> all() {
        return rows.findAll().stream().map(o -> (ToolApprovalRequest) (Object) o).toList();
    }

    @Test
    @DisplayName("an automatic approval is a row decided APPROVED by the server, made in the turn, never waited on, announced as automatic")
    void approvedAndMade() {
        String read = write("c1", ApprovalInbox.AUTO_PROJECT, POSTED);

        assertThat(read).as("the agent reads the call's own result").isEqualTo(POSTED);
        ToolApprovalRequest r = only();
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getDecidedBy()).isEqualTo(ApprovalInbox.AUTO_PROJECT);
        assertThat(ApprovalInbox.automatic(r.getDecidedBy())).isTrue();
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_DONE);
        assertThat(r.getContext()).as("linked to where the reply went").contains("https://www.youtube.com/watch?v=v&lc=r1");
        assertThat(approvals.hasPending("conv-writer")).as("nothing waits on it").isFalse();
        assertThat(told.findAll()).as("the agent read its result in its turn: no message").isEmpty();
        assertThat(decided).singleElement().satisfies(e -> {
            assertThat(e.automatic()).isTrue();
            assertThat(e.succeeded()).isTrue();
            assertThat(e.outcome()).isEqualTo(ToolApprovalRequest.APPROVED);
        });
        // Listed as decided, said to be approved automatically - and never the decider's raw value.
        assertThat(approvals.forUser("u-1", ApprovalInbox.Show.DECIDED, 0, 20).getContent()).singleElement()
                .satisfies(v -> assertThat(v.autoApproved()).isEqualTo("PROJECT"));
        assertThat(approvals.forUser("u-1", ApprovalInbox.Show.PENDING, 0, 20).getContent()).isEmpty();
    }

    @Test
    @DisplayName("the agent's Auto mode leaves the same row, marked as the agent's")
    void agentAuto() {
        write("c1", ApprovalInbox.AUTO_AGENT, POSTED);
        assertThat(only().getDecidedBy()).isEqualTo(ApprovalInbox.AUTO_AGENT);
        assertThat(approvals.forUser("u-1", ApprovalInbox.Show.DECIDED, 0, 20).getContent()).singleElement()
                .satisfies(v -> assertThat(v.autoApproved()).isEqualTo("AGENT"));
    }

    @Test
    @DisplayName("a comment answered or deleted meanwhile is asked about first: WITHDRAWN with the reason, nothing made")
    void doneElsewhereIsWithdrawn() {
        world.put("c1", "Already answered on YouTube.");
        ApprovalInbox.AutoApproval a = approvals.approveAutomatically("conv-writer", "u-1", reply("c1", "Hi"),
                ApprovalInbox.AUTO_PROJECT);

        assertThat(a.proceed()).as("the call is not let through").isFalse();
        assertThat(a.refusal()).contains("NOT_MADE", "Already answered on YouTube.");
        assertThat(settledChecks).isEqualTo(1);
        ToolApprovalRequest r = only();
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(r.getNote()).isEqualTo("Already answered on YouTube.");
        assertThat(decided).as("nothing was decided to be made").isEmpty();
    }

    @Test
    @DisplayName("a spent quota HOLDS it as a person's approval is held: the agent is told, the server posts it after the reset")
    void quotaHolds() {
        String read = write("c1", ApprovalInbox.AUTO_PROJECT, QUOTA);

        assertThat(read).contains("\"status\":\"HELD\"", "The server makes it itself after");
        ToolApprovalRequest r = only();
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
        assertThat(r.getRetryAt()).isNotNull();
        assertThat(decided).singleElement().satisfies(e -> {
            assertThat(e.held()).isTrue();
            assertThat(e.automatic()).isTrue();
        });

        now.set(r.getRetryAt().plusSeconds(60));
        approvals.retryHeld();
        r = only();
        assertThat(retried).containsExactly("c1");
        assertThat(r.getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_DONE);
        assertThat(r.getRunAttempts()).isEqualTo(2);
        assertThat(decided).last().satisfies(e -> {
            assertThat(e.succeeded()).isTrue();
            assertThat(e.automatic()).as("still the server's approval at its retry").isTrue();
        });
        assertThat(told.findAll()).as("the agent is told when it was made").singleElement()
                .satisfies(e -> assertThat(((AgentInboxEntry) e).getText()).contains("was made now"));
    }

    @Test
    @DisplayName("a held write answered meanwhile is withdrawn at its retry, never posted")
    void heldThenAnsweredIsWithdrawn() {
        write("c1", ApprovalInbox.AUTO_PROJECT, QUOTA);
        world.put("c1", "Already answered on YouTube.");
        now.set(only().getRetryAt().plusSeconds(60));
        approvals.retryHeld();
        assertThat(retried).isEmpty();
        assertThat(only().getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
    }

    @Test
    @DisplayName("a failed call is FAILED on its row, the agent reading the failure in its turn")
    void failureIsFailed() {
        String read = write("c1", ApprovalInbox.AUTO_PROJECT, FAILED);
        assertThat(read).isEqualTo(FAILED);
        assertThat(only().getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_FAILED);
        assertThat(decided).singleElement().satisfies(e -> {
            assertThat(e.succeeded()).isFalse();
            assertThat(e.held()).isFalse();
            assertThat(e.automatic()).isTrue();
        });
    }

    @Test
    @DisplayName("a second automatic reply to a comment already approved is not made; after a failure it is")
    void notTwice() {
        write("c1", ApprovalInbox.AUTO_PROJECT, POSTED);
        String second = write("c1", ApprovalInbox.AUTO_PROJECT, POSTED);
        assertThat(second).contains("NOT_MADE", ApprovalInbox.ALREADY_APPROVED);
        assertThat(all()).extracting(ToolApprovalRequest::getStatus)
                .containsExactlyInAnyOrder(ToolApprovalRequest.APPROVED, ToolApprovalRequest.WITHDRAWN);

        write("c2", ApprovalInbox.AUTO_PROJECT, FAILED);
        assertThat(write("c2", ApprovalInbox.AUTO_PROJECT, POSTED)).as("the failed one is no reply").isEqualTo(POSTED);
    }

    @Test
    @DisplayName("a draft waiting for the same comment is replaced; one the person is editing wins and nothing is kept")
    void waitingDrafts() throws Exception {
        String waiting = JSON.readTree(approvals.park("conv-writer", "u-1", reply("c1", "Old draft"))).get("requestId").asText();
        approvals.holdEditing(rows.findById(UUID.fromString(waiting)).orElseThrow(), "u-1");
        String refused = write("c1", ApprovalInbox.AUTO_PROJECT, POSTED);
        assertThat(refused).contains("BEING_EDITED");
        assertThat(all()).hasSize(1);

        approvals.releaseEditing(rows.findById(UUID.fromString(waiting)).orElseThrow(), "u-1");
        assertThat(write("c1", ApprovalInbox.AUTO_PROJECT, POSTED)).isEqualTo(POSTED);
        assertThat(rows.findById(UUID.fromString(waiting)).orElseThrow().getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
        assertThat(all()).filteredOn(r -> ToolApprovalRequest.APPROVED.equals(r.getStatus())).hasSize(1);
    }

    @Test
    @DisplayName("a write no provider describes is kept and approved too, with nothing to ask the world")
    void anyWrite() {
        ApprovalInbox.AutoApproval a = approvals.approveAutomatically("conv-writer", "u-1",
                new ToolCall("call-j", "function", "createJiraIssue", "{\"summary\":\"Ship it\"}"), ApprovalInbox.AUTO_AGENT);
        assertThat(a.proceed()).isTrue();
        approvals.finishAutomatic(a.requestId(), "{\"success\":true,\"key\":\"PRX-1\"}");
        assertThat(only().getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_DONE);
        assertThat(settledChecks).isZero();
    }
}
