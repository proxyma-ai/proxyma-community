package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A DRAFT FOR A COMMENT ALREADY ANSWERED LEAVES THE WAITING LIST (2026-11-04, the maintainer: "Many comments I
 * already replied but still asked to approve"). The person's waiting requests of a tool a provider checks are
 * asked about when a page lists them - at most once every five minutes a person, a draft being edited left for
 * approval - and one done another way is WITHDRAWN with the provider's reason, its agent not woken. Approving
 * asks once more, for all the chosen requests in one check: one done meanwhile posts nothing and is withdrawn,
 * the others go; a check that cannot answer leaves the approval as the person made it.
 */
class ApprovalDoneElsewhereTest {

    static final String REPLY = "replyToYouTubeComment";
    static final String ANSWERED = "Already answered on YouTube.";
    static final String GONE = "The comment is gone.";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T08:00:00Z"));
    final List<String> sent = new ArrayList<>();
    /** What the world says now: comment id to the reason. */
    final Map<String, String> world = new HashMap<>();
    /** Each check the provider was asked for: the comment ids, in order. */
    final List<List<String>> checks = new CopyOnWriteArrayList<>();
    volatile boolean failing;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> List.of(),
                "decide", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !r.isPending()) return 0;
                    r.setStatus((String) args[1]);
                    r.setDecidedAt((Instant) args[2]);
                    r.setDecidedBy((String) args[3]);
                    r.setNote((String) args[4]);
                    return 1;
                },
                "edited", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    r.setArguments((String) args[1]);
                    r.setDraftArguments((String) args[2]);
                    r.setContext((String) args[3]);
                    return 1;
                },
                "claimRetry", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !ToolApprovalRequest.RAN_HELD.equals(r.getRunOutcome())) return 0;
                    r.setRunOutcome(ToolApprovalRequest.RAN_RETRYING);
                    return 1;
                }));
        ApplicationEventPublisher events = e -> { };
        told = InMemoryRepositories.of(AgentInboxRepository.class);
        AgentInbox inbox = new AgentInbox(told, events);
        ChatService chat = mock(ChatService.class);
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            sent.add(JSON.readTree(calls.get(0).arguments()).get("commentId").asText());
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, "{\"success\":true}"))).build();
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
        approvals.setContexts(ApprovalContexts.of(List.of(new Replies()), Duration.ofSeconds(5)));
    }

    /** The YouTube reply's provider in miniature, answering from {@link #world}. */
    final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            return Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.text("comment", "Sheet music please"),
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
            if (failing) throw new IllegalStateException("YouTube is down");
            checks.add(waiting.stream().map(w -> String.valueOf(w.arguments().get("commentId"))).toList());
            Map<String, String> out = new HashMap<>();
            for (Waiting w : waiting) {
                String reason = world.get(String.valueOf(w.arguments().get("commentId")));
                if (reason != null) out.put(w.id(), reason);
            }
            return out;
        }
    }

    String park(String commentId) {
        try {
            return JSON.readTree(approvals.park("conv-writer", "u-1", new ToolCall("call-" + UUID.randomUUID(), "function",
                    REPLY, "{\"commentId\":\"" + commentId + "\",\"text\":\"Cam on ban\"}"))).get("requestId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    void later(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    @DisplayName("listing the waiting drafts: one answered on YouTube and one deleted are withdrawn with the reason, the rest stay, and no agent is woken")
    void answeredAndGoneAreWithdrawn() {
        String a = park("c1");
        String b = park("c2");
        String c = park("c3");
        world.put("c1", ANSWERED);
        world.put("c3", GONE);

        approvals.recheckLater("u-1");

        assertThat(checks).as("one check for the three").singleElement()
                .satisfies(ids -> assertThat(ids).containsExactlyInAnyOrder("c1", "c2", "c3"));
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(a).getNote()).isEqualTo(ANSWERED);
        assertThat(row(a).getDecidedBy()).as("nobody decided it").isNull();
        assertThat(row(b).isPending()).isTrue();
        assertThat(row(c).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(c).getNote()).isEqualTo(GONE);
        assertThat(told.findAll()).as("nothing is left for the agent to do: it is not woken").isEmpty();
        assertThat(approvals.forUser("u-1", ApprovalInbox.Show.DECIDED, 0, 20).getContent())
                .extracting(ApprovalInbox.View::id).containsExactlyInAnyOrder(a, c);
        assertThat(approvals.hasPending("conv-writer")).as("c2 still waits").isTrue();
    }

    @Test
    @DisplayName("a person's drafts are checked at most once every five minutes, and another person's never with theirs")
    void throttled() {
        park("c1");
        approvals.recheckLater("u-1");
        assertThat(checks).hasSize(1);

        world.put("c1", ANSWERED);
        later(Duration.ofMinutes(4));
        approvals.recheckLater("u-1");
        approvals.recheckWaiting("u-1");
        assertThat(checks).as("within the five minutes").hasSize(1);
        assertThat(rows.findAll()).allMatch(ToolApprovalRequest::isPending);

        approvals.recheckLater("u-2");
        assertThat(checks).as("u-2 has nothing waiting: nothing to ask").hasSize(1);

        later(ApprovalInbox.RECHECK_EVERY);
        assertThat(approvals.recheckWaiting("u-1")).isEqualTo(1);
        assertThat(checks).hasSize(2);
        assertThat(rows.findAll()).noneMatch(ToolApprovalRequest::isPending);
    }

    @Test
    @DisplayName("a draft the person is editing is left by the background check, and checked when they approve it")
    void heldDraftCheckedAtApproval() {
        String a = park("c1");
        world.put("c1", ANSWERED);
        approvals.holdEditing(row(a), "u-1");

        approvals.recheckLater("u-1");
        assertThat(row(a).isPending()).isTrue();

        ApprovalInbox.Decided out = approvals.decideAll(List.of(row(a)), ApprovalInbox.Decision.APPROVE, null, "u-1",
                Map.of(a, Map.of("text", "Cam on ban nhieu")));
        assertThat(out.withdrawn()).isEqualTo(1);
        assertThat(sent).as("never a second reply").isEmpty();
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(approvals.beingEdited(row(a).getId())).isFalse();
    }

    @Test
    @DisplayName("approving several: one check for all; the one answered meanwhile posts nothing and is withdrawn, the others are posted")
    void approvalChecksFirst() {
        String a = park("c1");
        String b = park("c2");
        String c = park("c3");
        world.put("c2", ANSWERED);

        ApprovalInbox.Decided out = approvals.decideAll(List.of(row(a), row(b), row(c)), ApprovalInbox.Decision.APPROVE,
                null, "u-1", Map.of());

        assertThat(checks).singleElement().satisfies(ids -> assertThat(ids).containsExactly("c1", "c2", "c3"));
        assertThat(out).isEqualTo(new ApprovalInbox.Decided(2, 0, 1, List.of(ANSWERED)));
        assertThat(sent).containsExactlyInAnyOrder("c1", "c3");
        assertThat(row(b).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(b).getNote()).isEqualTo(ANSWERED);
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
    }

    @Test
    @DisplayName("a denial asks YouTube nothing, and an approval whose check fails goes as the person decided")
    void denialAndFailedCheck() {
        String a = park("c1");
        world.put("c1", ANSWERED);
        ApprovalInbox.Decided denied = approvals.decideAll(List.of(row(a)), ApprovalInbox.Decision.DENY, null, "u-1", Map.of());
        assertThat(denied).isEqualTo(new ApprovalInbox.Decided(1, 0, 0, List.of()));
        assertThat(checks).isEmpty();

        String b = park("c2");
        failing = true;
        ApprovalInbox.Decided approved = approvals.decideAll(List.of(row(b)), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());
        assertThat(approved.decided()).isEqualTo(1);
        assertThat(sent).containsExactly("c2");
    }

    @Test
    @DisplayName("a draft the server approves by itself is asked about first: one the channel answered meanwhile is withdrawn and nothing posts (2026-11-11 review)")
    void serverApprovalChecksFirst() {
        String a = park("c1");
        String b = park("c2");
        world.put("c1", ANSWERED);

        assertThat(approvals.approveWaitingAutomatically(row(a), ApprovalInbox.AUTO_PROJECT)).isFalse();
        assertThat(approvals.approveWaitingAutomatically(row(b), ApprovalInbox.AUTO_PROJECT)).isTrue();

        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(a).getNote()).isEqualTo(ANSWERED);
        assertThat(row(b).getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(row(b).getDecidedBy()).isEqualTo(ApprovalInbox.AUTO_PROJECT);
        assertThat(sent).as("only the unanswered one posts").containsExactly("c2");
    }

    @Test
    @DisplayName("an approved write a restart cut short: a reply is held for a retry that asks YouTube first; another write fails, never made blind (2026-11-11 review)")
    void cutShortByARestart() {
        String reply = park("c1");
        String reply2 = park("c2");
        ToolApprovalRequest other = new ToolApprovalRequest();
        other.setConversationId("conv-writer");
        other.setRootConversationId("conv-writer");
        other.setUserId("u-1");
        other.setToolName("createJiraIssue");
        other.setArguments("{}");
        other.setStatus(ToolApprovalRequest.APPROVED);
        other.setDecidedAt(now.get());
        rows.save(other);
        for (String id : List.of(reply, reply2)) {
            ToolApprovalRequest r = row(id);
            r.setStatus(ToolApprovalRequest.APPROVED);
            r.setDecidedAt(now.get());
            rows.save(r);
        }
        ToolApprovalRequest old = new ToolApprovalRequest();
        old.setConversationId("conv-writer");
        old.setToolName(REPLY);
        old.setArguments("{\"commentId\":\"c9\",\"text\":\"x\"}");
        old.setStatus(ToolApprovalRequest.APPROVED);
        old.setDecidedAt(now.get().minus(Duration.ofDays(30)));
        rows.save(old);
        world.put("c1", ANSWERED);

        assertThat(approvals.recoverCutShort(now.get())).isEqualTo(3);
        assertThat(row(reply).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD);
        assertThat(rows.findById(other.getId()).orElseThrow().getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_FAILED);
        assertThat(rows.findById(old.getId()).orElseThrow().getRunOutcome()).as("history is left alone").isNull();

        approvals.retryHeld();
        assertThat(row(reply).getStatus()).as("the channel answered it: withdrawn, not posted twice").isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(sent).containsExactly("c2");
        assertThat(row(reply2).getRunOutcome()).isEqualTo(ToolApprovalRequest.RAN_DONE);
    }
}
