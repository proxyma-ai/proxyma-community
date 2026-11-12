package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ONE PROJECT, ONE CHANNEL (2026-11-05, the maintainer: a team meant for @camamtieudao had drafts waiting for comments
 * on @lesyhau's videos - it began on that channel's connection and was switched - and its lead could neither count
 * them nor withdraw them). A reply for a comment of another channel than the one its conversation posts as is refused
 * before it is kept; a waiting one is withdrawn as "Made for another channel." by the check, at once after a change
 * whatever the throttle; approving one posts nothing; and an agent lists the project's waiting drafts - all of them,
 * paged, with the inbox's total, only its person's - and withdraws several at once, by request or by comment.
 */
class ApprovalChannelTest {

    static final String REPLY = "replyToYouTubeComment";
    static final String OTHER = "Made for another channel.";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T08:00:00Z"));
    final List<String> sent = new ArrayList<>();
    /** The comments on the channel the conversation posts as NOW; any other is another channel's. */
    final Set<String> ours = ConcurrentHashMap.newKeySet();
    final AtomicInteger checks = new AtomicInteger();

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

    /** The YouTube reply's provider in miniature: a comment not in {@link #ours} is another channel's. */
    final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            return Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.link("replyAs", "Cam Am Tieu Dao", "https://www.youtube.com/@camamtieudao"),
                    Field.editable("reply", String.valueOf(args.get("text")), "text"))));
        }

        @Override
        public Optional<String> subjectOf(String tool, Map<String, Object> args) {
            return REPLY.equals(tool) ? Optional.of("youtube-comment:" + args.get("commentId")) : Optional.empty();
        }

        @Override
        public Optional<String> subjectNamed(String reference) {
            return reference.matches("[A-Za-z0-9_.-]+") ? Optional.of("youtube-comment:" + reference) : Optional.empty();
        }

        @Override
        public Optional<String> refusal(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool) || ours.contains(String.valueOf(args.get("commentId")))) return Optional.empty();
            return Optional.of("That comment is under a video of another YouTube channel.");
        }

        @Override
        public boolean settles(String tool) {
            return REPLY.equals(tool);
        }

        @Override
        public Map<String, String> settled(String tool, List<Waiting> waiting, String userId) {
            checks.incrementAndGet();
            Map<String, String> out = new HashMap<>();
            for (Waiting w : waiting) {
                if (!ours.contains(String.valueOf(w.arguments().get("commentId")))) out.put(w.id(), OTHER);
            }
            return out;
        }
    }

    JsonNode park(String conversation, String user, String commentId) {
        try {
            return JSON.readTree(approvals.park(conversation, user, new ToolCall("call-" + UUID.randomUUID(), "function",
                    REPLY, "{\"commentId\":\"" + commentId + "\",\"text\":\"Cam on ban\"}")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String kept(String conversation, String user, String commentId) {
        ours.add(commentId);
        return park(conversation, user, commentId).get("requestId").asText();
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    @Test
    @DisplayName("a reply for a comment of another channel is refused at the call, in words the agent can act on, and nothing is kept")
    void anotherChannelsCommentIsRefused() {
        JsonNode answer = park("conv-writer", "u-1", "lesyhau-c1");
        assertThat(answer.get("status").asText()).isEqualTo("REFUSED");
        assertThat(answer.get("error").asText()).startsWith("That comment is under a video of another YouTube channel.")
                .endsWith("Nothing was kept or changed.");
        assertThat(answer.has("requestId")).isFalse();
        assertThat(rows.findAll()).isEmpty();
        assertThat(approvals.hasPending("conv-writer")).isFalse();

        // Approved automatically (the project's switch): refused the same, no row, nothing made.
        ApprovalInbox.AutoApproval auto = approvals.approveAutomatically("conv-writer", "u-1", new ToolCall("call-a",
                "function", REPLY, "{\"commentId\":\"lesyhau-c2\",\"text\":\"Hi\"}"), ApprovalInbox.AUTO_PROJECT);
        assertThat(auto.proceed()).isFalse();
        assertThat(auto.refusal()).contains("REFUSED");
        assertThat(rows.findAll()).isEmpty();

        // The channel's own comment is kept as ever.
        assertThat(kept("conv-writer", "u-1", "cam-c1")).isNotBlank();
        assertThat(rows.findAll()).singleElement().satisfies(r -> assertThat(r.isPending()).isTrue());
    }

    @Test
    @DisplayName("after a channel change every waiting draft for the old channel is withdrawn at once, whatever the throttle, with the reason, and nobody woken")
    void channelChangeWithdrawsTheOldChannelsDrafts() {
        String a = kept("conv-writer", "u-1", "lesyhau-c1");
        String b = kept("conv-writer", "u-1", "lesyhau-c2");
        String c = kept("conv-writer", "u-1", "cam-c1");
        assertThat(approvals.recheckWaiting("u-1")).as("all on the channel still").isZero();

        // The project moves to camamtieudao: lesyhau's comments are no longer this team's.
        ours.removeAll(Set.of("lesyhau-c1", "lesyhau-c2"));
        assertThat(approvals.recheckWaiting("u-1")).as("throttled: checked a moment ago").isZero();
        AtomicInteger heard = new AtomicInteger(-2);
        approvals.recheckAfterChange("u-1", heard::set);

        assertThat(heard.get()).isEqualTo(2);
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(a).getNote()).isEqualTo(OTHER);
        assertThat(row(a).getDecidedBy()).as("nobody decided it").isNull();
        assertThat(row(b).getNote()).isEqualTo(OTHER);
        assertThat(row(c).isPending()).isTrue();
        assertThat(told.findAll()).as("nothing is left for the agent to do").isEmpty();
    }

    @Test
    @DisplayName("approving a draft whose channel no longer matches posts nothing and says why")
    void approvingAMismatchPostsNothing() {
        String a = kept("conv-writer", "u-1", "lesyhau-c1");
        String b = kept("conv-writer", "u-1", "cam-c1");
        ours.remove("lesyhau-c1");

        ApprovalInbox.Decided out = approvals.decideAll(List.of(row(a), row(b)), ApprovalInbox.Decision.APPROVE, null,
                "u-1", Map.of());

        assertThat(out).isEqualTo(new ApprovalInbox.Decided(1, 0, 1, List.of(OTHER)));
        assertThat(sent).containsExactly("cam-c1");
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(a).getNote()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("the project's waiting drafts: every one, paged, with the inbox's total - only the turn's person's, only the project's")
    void listedWithTheTotal() {
        for (int i = 0; i < 7; i++) kept("conv-writer", "u-1", "cam-c" + i);
        // Kept rows are stamped with the wall clock: the scout's is made the newest by hand.
        row(kept("conv-scout", "u-1", "cam-s1")).setRequestedAt(Instant.now().plusSeconds(3600));
        kept("conv-writer", "u-2", "cam-other-person");
        kept("conv-elsewhere", "u-1", "cam-elsewhere");
        List<String> project = List.of("conv-writer", "conv-scout");

        ApprovalInbox.WaitingPage first = approvals.waitingIn(project, "u-1", 0, 5);
        ApprovalInbox.WaitingPage second = approvals.waitingIn(project, "u-1", 1, 5);

        assertThat(first.total()).isEqualTo(8);
        assertThat(first.items()).hasSize(5);
        assertThat(second.items()).hasSize(3);
        List<String> all = new ArrayList<>();
        first.items().forEach(w -> all.add(w.view().id()));
        second.items().forEach(w -> all.add(w.view().id()));
        assertThat(all).doesNotHaveDuplicates().hasSize(8);
        assertThat(first.items().get(0).view().conversationId()).as("newest first").isEqualTo("conv-scout");
        assertThat(all).extracting(id -> row(id).getUserId()).containsOnly("u-1");
        assertThat(approvals.waitingIn(List.of(), "u-1", 0, 5).total()).isZero();
    }

    @Test
    @DisplayName("withdrawing several at once: by request id and by comment id, only the person's and the project's, each drafting agent told")
    void withdrawnSeveralAtOnce() {
        String a = kept("conv-writer", "u-1", "lesyhau-c1");
        String b = kept("conv-writer", "u-1", "lesyhau-c2");
        kept("conv-writer", "u-1", "lesyhau-c3");
        String keep = kept("conv-writer", "u-1", "cam-c1");
        String others = kept("conv-writer", "u-2", "lesyhau-c4");
        String elsewhere = kept("conv-elsewhere", "u-1", "lesyhau-c5");
        List<String> project = List.of("conv-writer");

        ApprovalInbox.Withdrawals out = approvals.withdrawAll(List.of(a, b, "lesyhau-c3", others, "lesyhau-c5", "nope", a),
                "u-1", project, "conv-lead", "Made for another channel.");

        assertThat(out.withdrawn()).isEqualTo(3);
        assertThat(out.notWithdrawn()).hasSize(3);
        assertThat(row(a).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(a).getNote()).isEqualTo("Made for another channel.");
        assertThat(rows.findAll()).filteredOn(r -> "youtube-comment:lesyhau-c3".equals(r.getSubject()))
                .singleElement().satisfies(r -> assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN));
        assertThat(row(keep).isPending()).isTrue();
        assertThat(row(others).isPending()).as("another person's").isTrue();
        assertThat(row(elsewhere).isPending()).as("another project's").isTrue();
        // The writer is told once per draft withdrawn by its lead, which is another conversation.
        assertThat(told.findAll()).extracting(AgentInboxEntry::getConversationId).containsOnly("conv-writer").hasSize(3);

        ApprovalInbox.Withdrawals many = approvals.withdrawAll(java.util.Collections.nCopies(ApprovalInbox.WITHDRAW_MAX + 5, "x"),
                "u-1", project, "conv-lead", null);
        assertThat(many.notWithdrawn()).last().asString().startsWith("Only " + ApprovalInbox.WITHDRAW_MAX + " at a time");
    }
}
