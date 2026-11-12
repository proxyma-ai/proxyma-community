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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE PERSON'S OWN WORDS (2026-11-02, the maintainer's review of the YouTube assistant): a reply waiting
 * for approval can be edited before it is approved, and then what runs is the person's text with every
 * other argument as the agent wrote it; the agent is told its draft and what was sent, so it can learn;
 * an edit the request does not offer is refused before anything is decided; a revised draft for the
 * same comment replaces the one still waiting; and "decided" never lists a request still waiting.
 */
class ApprovalEditTest {

    static final String CONV = "conv-run";
    static final String REPLY = "replyToYouTubeComment";

    ToolApprovalRequestRepository rows;
    AgentInbox inbox;
    ChatService chat;
    ApprovalInbox approvals;
    List<Object> published = new ArrayList<>();

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
        AgentInboxRepository inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "markDelivered", (store, args) -> {
                    int n = 0;
                    for (Object id : (Collection<?>) args[0]) {
                        ai.proxyma.core.chat.model.AgentInboxEntry e = (ai.proxyma.core.chat.model.AgentInboxEntry) store.get(id);
                        if (e != null && e.getDeliveredAt() == null) {
                            e.setDeliveredAt((Instant) args[1]);
                            n++;
                        }
                    }
                    return n;
                }));
        ApplicationEventPublisher events = published::add;
        inbox = new AgentInbox(inboxRows, events);
        chat = mock(ChatService.class);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(InMemoryRepositories.of(AgentNodeRepository.class), inbox,
                InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings, mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
        approvals.setEvents(events);
        approvals.setContexts(ApprovalContexts.of(List.of(new Replies()), Duration.ofSeconds(5)));
    }

    /** The YouTube reply's provider in miniature: the comment and the reply, which the person may edit. */
    static final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            return Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.link("viewer", "Minh", "https://www.youtube.com/channel/UCfan2"),
                    Field.quote("comment", "Sheet music please"),
                    Field.editable("reply", String.valueOf(args.get("text")), "text"))));
        }

        @Override
        public Optional<String> subjectOf(String tool, Map<String, Object> args) {
            return REPLY.equals(tool) ? Optional.of("youtube-comment:" + args.get("commentId")) : Optional.empty();
        }
    }

    String park(String conversationId, String commentId, String text) {
        approvals.park(conversationId, "u-1", new ToolCall("call-" + UUID.randomUUID(), "function", REPLY,
                "{\"commentId\":\"" + commentId + "\",\"text\":\"" + text + "\"}"));
        return rows.findAll().stream().filter(r -> r.getArguments().contains(text)).findFirst().orElseThrow().getId().toString();
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    AtomicReference<String> ran() {
        AtomicReference<String> args = new AtomicReference<>();
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            args.set(calls.get(0).arguments());
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, "{\"commentId\":\"c3\",\"replyId\":\"r9\"}"))).build();
        });
        return args;
    }

    @Test
    @DisplayName("approved with an edit, the person's text runs with every other argument as written, and the agent reads both")
    void editedApprovalRunsThePersonsText() {
        String id = park(CONV, "c3", "Cam on ban nhieu hen");
        AtomicReference<String> ran = ran();

        assertThat(approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, null, "u-1",
                Map.of("text", "Cam on em nhieu nhe"))).isTrue();

        assertThat(ran.get()).contains("\"commentId\":\"c3\"").contains("Cam on em nhieu nhe").doesNotContain("ban nhieu hen");
        ToolApprovalRequest r = row(id);
        assertThat(r.getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(r.getArguments()).contains("Cam on em nhieu nhe");
        assertThat(r.getDraftArguments()).as("the agent's own draft is kept beside it").contains("Cam on ban nhieu hen");
        assertThat(r.getContext()).as("the page shows what was sent").contains("Cam on em nhieu nhe");

        String told = inbox.takeWhere(CONV, t -> true).get(0).text();
        assertThat(told).contains("after EDITING it").contains("Your draft (text): \"Cam on ban nhieu hen\"")
                .contains("What the person sent instead: \"Cam on em nhieu nhe\"").contains(ApprovalInbox.LESSON_KEPT)
                .as("nothing to record: the server keeps the decision").doesNotContain("Record the lesson");

        ApprovalDecidedEvent e = published.stream().filter(ApprovalDecidedEvent.class::isInstance)
                .map(ApprovalDecidedEvent.class::cast).findFirst().orElseThrow();
        assertThat(e.edited()).isTrue();
        assertThat(e.succeeded()).isTrue();
        assertThat(e.outcome()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(e.draftArguments()).contains("Cam on ban nhieu hen");
        assertThat(e.arguments()).contains("Cam on em nhieu nhe");
    }

    @Test
    @DisplayName("approved as written, nothing is marked edited and the agent hears its draft went as it was")
    void unchangedApprovalIsNoEdit() {
        String id = park(CONV, "c3", "Thank you so much");
        ran();
        approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of("text", "  Thank you so much "));
        assertThat(row(id).getDraftArguments()).isNull();
        assertThat(inbox.takeWhere(CONV, t -> true).get(0).text()).contains("sent your draft as written");
        assertThat(published.stream().filter(ApprovalDecidedEvent.class::isInstance).map(ApprovalDecidedEvent.class::cast)
                .findFirst().orElseThrow().edited()).isFalse();
    }

    @Test
    @DisplayName("an edit the request does not offer - another argument, an empty text - is refused and nothing is decided")
    void editsOnlyWhatIsOffered() {
        String id = park(CONV, "c3", "Thanks");
        assertThatThrownBy(() -> approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, null, "u-1",
                Map.of("commentId", "someone-else"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, null, "u-1",
                Map.of("text", "   "))).isInstanceOf(IllegalArgumentException.class);
        assertThat(row(id).isPending()).isTrue();
        verify(chat, never()).callTools(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("a denial with edits ignores them, and the agent is told the refusal is kept as a lesson")
    void denialIgnoresEdits() {
        String id = park(CONV, "c3", "Thanks");
        assertThat(approvals.decide(row(id), ApprovalInbox.Decision.DENY, "Too cold - call her chi", "u-1",
                Map.of("commentId", "x"))).isTrue();
        assertThat(inbox.takeWhere(CONV, t -> true).get(0).text()).contains("Too cold")
                .contains("refused this draft").contains(ApprovalInbox.LESSON_KEPT);
    }

    @Test
    @DisplayName("a revised draft for the same comment replaces the one still waiting: one waits, with the new text and asker")
    void revisedDraftReplacesTheWaitingOne() {
        String first = park(CONV, "c3", "Cam on ban");
        String second = park("conv-chat", "c3", "Cam on em nhe");

        assertThat(second).isNotEqualTo(first);
        assertThat(rows.findAll().stream().filter(ToolApprovalRequest::isPending)).hasSize(1);
        assertThat(row(first).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
        ToolApprovalRequest r = row(second);
        assertThat(r.getArguments()).contains("Cam on em nhe");
        assertThat(r.getConversationId()).isEqualTo("conv-chat");
        assertThat(r.getContext()).contains("Cam on em nhe");
        assertThat(approvals.hasPending("conv-chat")).isTrue();
        assertThat(approvals.hasPending(CONV)).as("the first asker no longer waits on it").isFalse();

        park(CONV, "c4", "Another comment");
        assertThat(rows.findAll().stream().filter(ToolApprovalRequest::isPending)).as("another comment is another request")
                .hasSize(2);
    }

    @Test
    @DisplayName("a revised draft after the first was decided is a new request")
    void decidedIsNotReplaced() {
        String first = park(CONV, "c3", "Cam on ban");
        approvals.decide(row(first), ApprovalInbox.Decision.DENY, null, "u-1");
        String second = park(CONV, "c3", "Cam on em nhe");
        assertThat(second).isNotEqualTo(first);
        assertThat(row(first).getArguments()).contains("Cam on ban");
    }

    @Test
    @DisplayName("Decided lists decided requests only, Waiting only waiting ones")
    void decidedListsNoWaitingRequest() {
        String decided = park(CONV, "c3", "One");
        park(CONV, "c4", "Two");
        approvals.decide(row(decided), ApprovalInbox.Decision.DENY, null, "u-1");

        assertThat(approvals.forTrees(List.of(CONV), ApprovalInbox.Show.DECIDED, 0, 20).getContent())
                .extracting(ApprovalInbox.View::status).containsExactly(ToolApprovalRequest.DENIED);
        assertThat(approvals.forTrees(List.of(CONV), ApprovalInbox.Show.PENDING, 0, 20).getContent())
                .extracting(ApprovalInbox.View::status).containsExactly(ToolApprovalRequest.PENDING);
        assertThat(approvals.forTrees(List.of(CONV), ApprovalInbox.Show.ALL, 0, 20).getContent()).hasSize(2);
    }

    @Test
    @DisplayName("a context's editable field survives being stored and read back, and only a plain argument name is kept")
    void editableFieldRoundTrips() {
        ApprovalContext c = new ApprovalContext("t", List.of(Field.editable("reply", "Hi", "text"),
                new Field("note", "x", null, ApprovalContext.QUOTE, "not an argument!")));
        ApprovalContext read = ApprovalContext.fromJson(c.toJson()).orElseThrow();
        assertThat(read.editable()).containsOnlyKeys("text");
        assertThat(read.fields().get(1).argument()).isNull();
    }
}
