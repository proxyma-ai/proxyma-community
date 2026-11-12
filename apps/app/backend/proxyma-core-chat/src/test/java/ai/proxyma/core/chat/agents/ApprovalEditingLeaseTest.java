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
 * THE AGENTS REVISE ONLY WHAT THE PERSON IS NOT EDITING (2026-11-03, the maintainer: "It would be nice if the
 * agent could know whenever the user is editing the comment, so it will revise only the comments that are not
 * actively being edited by the user"). A person editing a waiting draft holds it: only they can, a renewal
 * extends it, a release or two minutes without one ends it. While it is held, an agent's revision of it is
 * refused and the person's draft kept, and so is its withdrawal; the waiting list marks it. And when an edit
 * and a revision cross anyway, the person's text is what is sent.
 */
class ApprovalEditingLeaseTest {

    static final String REPLY = "replyToYouTubeComment";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    ChatService chat;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T08:00:00Z"));
    final List<String> sent = new ArrayList<>();

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
                }));
        ApplicationEventPublisher events = e -> { };
        AgentInbox inbox = new AgentInbox(InMemoryRepositories.of(AgentInboxRepository.class), events);
        chat = mock(ChatService.class);
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            sent.add(JSON.readTree(calls.get(0).arguments()).get("text").asText());
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

    /** The YouTube reply's provider in miniature. */
    static final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            return Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.link("viewer", "Minh", "https://www.youtube.com/channel/UCfan2"),
                    new Field("comment", "Sheet music please", "https://www.youtube.com/watch?v=v9&lc=" + args.get("commentId"),
                            ApprovalContext.QUOTE),
                    Field.editable("reply", String.valueOf(args.get("text")), "text"))));
        }

        @Override
        public boolean describes(String tool) {
            return REPLY.equals(tool);
        }

        @Override
        public Optional<String> subjectOf(String tool, Map<String, Object> args) {
            return REPLY.equals(tool) ? Optional.of("youtube-comment:" + args.get("commentId")) : Optional.empty();
        }
    }

    JsonNode park(String conversationId, String commentId, String text) {
        try {
            return JSON.readTree(approvals.park(conversationId, "u-1", new ToolCall("call-" + UUID.randomUUID(), "function",
                    REPLY, "{\"commentId\":\"" + commentId + "\",\"text\":\"" + text + "\"}")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    List<ToolApprovalRequest> waiting() {
        return rows.findAll().stream().filter(ToolApprovalRequest::isPending).toList();
    }

    void later(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    @DisplayName("a hold is the request's own person's: renewed it lasts, released or two minutes unrenewed it ends, and a decided request takes none")
    void theHold() {
        String id = park("conv-writer", "c3", "Cam on ban").get("requestId").asText();
        assertThat(approvals.holdEditing(row(id), "u-2")).as("another person's").isEqualTo(ApprovalInbox.Hold.NOT_FOUND);
        assertThat(approvals.beingEdited(UUID.fromString(id))).isFalse();

        assertThat(approvals.holdEditing(row(id), "u-1")).isEqualTo(ApprovalInbox.Hold.HELD);
        assertThat(approvals.beingEdited(UUID.fromString(id))).isTrue();
        later(Duration.ofSeconds(90));
        approvals.holdEditing(row(id), "u-1");
        later(Duration.ofSeconds(90));
        assertThat(approvals.beingEdited(UUID.fromString(id))).as("renewed 90 s ago").isTrue();
        later(ApprovalInbox.EDIT_LEASE);
        assertThat(approvals.beingEdited(UUID.fromString(id))).as("lapsed").isFalse();

        approvals.holdEditing(row(id), "u-1");
        assertThat(approvals.releaseEditing(row(id), "u-2")).as("only its holder releases it").isFalse();
        assertThat(approvals.beingEdited(UUID.fromString(id))).isTrue();
        assertThat(approvals.releaseEditing(row(id), "u-1")).isTrue();
        assertThat(approvals.beingEdited(UUID.fromString(id))).isFalse();

        approvals.holdEditing(row(id), "u-1");
        assertThat(approvals.decide(row(id), ApprovalInbox.Decision.DENY, null, "u-1")).isTrue();
        assertThat(approvals.beingEdited(UUID.fromString(id))).as("a decision ends it").isFalse();
        assertThat(approvals.holdEditing(row(id), "u-1")).isEqualTo(ApprovalInbox.Hold.NOT_WAITING);
    }

    @Test
    @DisplayName("a revision of a draft the person is editing is refused and their draft kept; once the hold ends, it replaces it")
    void revisionOfAHeldDraftIsRefused() {
        String id = park("conv-writer", "c3", "Cam on ban").get("requestId").asText();
        approvals.holdEditing(row(id), "u-1");

        JsonNode refused = park("conv-writer", "c3", "Cam on em nhieu");
        assertThat(refused.get("success").asBoolean()).isFalse();
        assertThat(refused.get("status").asText()).isEqualTo("BEING_EDITED");
        assertThat(refused.get("error").asText()).isEqualTo(ApprovalInbox.EDITING);
        assertThat(waiting()).singleElement().satisfies(r -> {
            assertThat(r.getId().toString()).isEqualTo(id);
            assertThat(r.getArguments()).contains("Cam on ban");
        });
        assertThat(rows.findAll()).as("nothing kept for the refused revision").hasSize(1);

        // Another comment's draft is not held: revised as before.
        String other = park("conv-writer", "c4", "Hi").get("requestId").asText();
        JsonNode revised = park("conv-writer", "c4", "Hello");
        assertThat(revised.get("replaced").get(0).asText()).isEqualTo(other);

        approvals.releaseEditing(row(id), "u-1");
        JsonNode after = park("conv-writer", "c3", "Cam on em nhieu");
        assertThat(after.get("replaced").get(0).asText()).isEqualTo(id);
        assertThat(row(id).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
    }

    @Test
    @DisplayName("a withdrawal of a draft the person is editing is refused and the draft kept")
    void withdrawalOfAHeldDraftIsRefused() {
        String id = park("conv-writer", "c3", "Cam on ban").get("requestId").asText();
        approvals.holdEditing(row(id), "u-1");
        ApprovalInbox.Withdrawal w = approvals.withdraw(id, "u-1", null, "conv-lead", "Not needed");
        assertThat(w.withdrawn()).isFalse();
        assertThat(w.message()).isEqualTo(ApprovalInbox.EDITING);
        assertThat(row(id).isPending()).isTrue();

        later(ApprovalInbox.EDIT_LEASE.plusSeconds(1));
        assertThat(approvals.withdraw(id, "u-1", null, "conv-lead", "Not needed").withdrawn()).as("once it lapsed").isTrue();
    }

    @Test
    @DisplayName("the waiting list is one person's drafts of the tool, newest first, each marked while being edited")
    void waitingMarksBeingEdited() {
        String a = park("conv-writer", "c1", "One").get("requestId").asText();
        later(Duration.ofSeconds(1));
        String b = park("conv-writer", "c2", "Two").get("requestId").asText();
        approvals.park("conv-other", "u-2", new ToolCall("x", "function", REPLY, "{\"commentId\":\"c9\",\"text\":\"Theirs\"}"));
        approvals.holdEditing(row(a), "u-1");

        List<ApprovalInbox.Waiting> list = approvals.waiting("u-1", REPLY, 20);
        assertThat(list).extracting(w -> w.view().id()).containsExactlyInAnyOrder(a, b);
        assertThat(list).filteredOn(ApprovalInbox.Waiting::beingEdited).extracting(w -> w.view().id()).containsExactly(a);
        assertThat(approvals.waiting("u-1", "someOtherTool", 20)).isEmpty();
    }

    @Test
    @DisplayName("an edit and a revision that crossed: approving the replaced draft with the edit sends the person's text, on the draft now waiting")
    void thePersonsTextWins() {
        String mine = park("conv-writer", "c3", "Cam on ban").get("requestId").asText();
        // The person opened the editor, then their hold lapsed (a laptop asleep), and an agent revised the draft.
        approvals.holdEditing(row(mine), "u-1");
        later(ApprovalInbox.EDIT_LEASE.plusSeconds(1));
        String revision = park("conv-writer", "c3", "Cam on em, chuc em vui").get("requestId").asText();
        assertThat(row(mine).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
        assertThat(approvals.successor(row(mine))).hasValueSatisfying(r -> assertThat(r.getId().toString()).isEqualTo(revision));

        // Without an edit, approving the replaced draft approves nothing: the person read words that are gone.
        assertThat(approvals.decide(row(mine), ApprovalInbox.Decision.APPROVE, null, "u-1")).isFalse();
        assertThat(row(revision).isPending()).isTrue();

        assertThat(approvals.decide(row(mine), ApprovalInbox.Decision.APPROVE, null, "u-1",
                Map.of("text", "Cam on ban nhieu, sheet o camamtieudao.com"))).isTrue();
        assertThat(sent).containsExactly("Cam on ban nhieu, sheet o camamtieudao.com");
        assertThat(row(revision).getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(row(revision).getArguments()).contains("Cam on ban nhieu, sheet o camamtieudao.com");
        assertThat(row(revision).getDraftArguments()).as("the agent's revision kept as its draft").contains("Cam on em, chuc em vui");
        assertThat(row(mine).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
    }
}
