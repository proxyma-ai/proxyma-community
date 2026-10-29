package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentInboxEntry;
import ai.proxyma.core.chat.model.AgentNode;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE APPROVALS INBOX: a write asked for where nobody watches is kept, not waited on; a person
 * decides it once - approved, it runs exactly once under the id it was given and the agent reads its
 * result; denied, the agent reads the denial and the note; undecided for a week, it expires and the
 * agent is told. Until then the conversation has work outstanding, so its report waits.
 */
class ApprovalInboxTest {

    static final String CONV = "conv-run";

    ToolApprovalRequestRepository rows;
    AgentInboxRepository inboxRows;
    AgentNodeRepository nodes;
    AgentInbox inbox;
    ChatService chat;
    ApprovalInbox approvals;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> store.values().stream()
                        .map(o -> (ToolApprovalRequest) o).filter(ToolApprovalRequest::isPending)
                        .map(ToolApprovalRequest::getConversationId).distinct().toList(),
                "decide", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !r.isPending()) return 0;
                    r.setStatus((String) args[1]);
                    r.setDecidedAt((Instant) args[2]);
                    r.setDecidedBy((String) args[3]);
                    r.setNote((String) args[4]);
                    return 1;
                },
                "findByStatusAndRequestedAtBefore", (store, args) -> store.values().stream()
                        .map(o -> (ToolApprovalRequest) o)
                        .filter(r -> r.getStatus().equals(args[0]) && r.getRequestedAt().isBefore((Instant) args[1]))
                        .toList()));
        inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "markDelivered", (store, args) -> {
                    int n = 0;
                    for (Object id : (Collection<?>) args[0]) {
                        AgentInboxEntry e = (AgentInboxEntry) store.get(id);
                        if (e != null && e.getDeliveredAt() == null) {
                            e.setDeliveredAt((Instant) args[1]);
                            n++;
                        }
                    }
                    return n;
                }));
        nodes = InMemoryRepositories.of(AgentNodeRepository.class);
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;
        inbox = new AgentInbox(inboxRows, events);
        chat = mock(ChatService.class);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(nodes, inbox, InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings,
                mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
    }

    String park(String conversationId) {
        String out = approvals.park(conversationId, "u-1", new ToolCall("call-1", "function", "createJiraIssue",
                "{\"summary\":\"Rotate the key\"}"));
        assertThat(out).contains("WAITING_FOR_APPROVAL").contains("Nothing was changed");
        return rows.findAll().stream().filter(r -> r.getConversationId().equals(conversationId))
                .reduce((a, b) -> b).orElseThrow().getId().toString();
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    List<AgentInbox.Taken> mail() {
        return inbox.takeWhere(CONV, t -> true);
    }

    @Test
    @DisplayName("a parked write is kept with its tree's root and the asking agent's title, and leaves the conversation owing it")
    void parks() {
        AgentNode n = new AgentNode();
        n.setConversationId("conv-child");
        n.setParentConversationId(CONV);
        n.setRootConversationId(CONV);
        n.setDepth(1);
        n.setTitle("Draft the tickets");
        nodes.save(n);
        String id = park("conv-child");
        ToolApprovalRequest r = row(id);
        assertThat(r.getRootConversationId()).isEqualTo(CONV);
        assertThat(r.getAgentTitle()).isEqualTo("Draft the tickets");
        assertThat(r.getUserId()).isEqualTo("u-1");
        assertThat(r.isPending()).isTrue();
        assertThat(approvals.hasPending("conv-child")).isTrue();
        verify(chat, never()).callTools(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("approved, the call runs once under its own id - which the chat lets through once - and the agent reads the result")
    void approveRuns() {
        String id = park(CONV);
        List<Boolean> letThrough = new ArrayList<>();
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            String callId = calls.get(0).id();
            letThrough.add(approvals.consumeApproved(callId));
            letThrough.add(approvals.consumeApproved(callId));
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(callId, "createJiraIssue",
                    "{\"success\":true,\"key\":\"OPS-7\"}"))).build();
        });

        assertThat(approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, "Go ahead", "u-1")).isTrue();

        assertThat(letThrough).containsExactly(true, false);
        assertThat(row(id).getStatus()).isEqualTo(ToolApprovalRequest.APPROVED);
        assertThat(row(id).getResult()).contains("OPS-7");
        List<AgentInbox.Taken> got = mail();
        assertThat(got).hasSize(1);
        assertThat(got.get(0).kind()).isEqualTo(AgentInbox.Kind.APPROVAL);
        assertThat(got.get(0).fromTitle()).isEqualTo("granted: createJiraIssue");
        assertThat(got.get(0).text()).contains("OPS-7").contains("Go ahead");
        assertThat(approvals.hasPending(CONV)).isFalse();
    }

    @Test
    @DisplayName("denied, nothing runs and the agent reads the denial with the person's note")
    void denyTells() {
        String id = park(CONV);
        assertThat(approvals.decide(row(id), ApprovalInbox.Decision.DENY, "Not this sprint", "u-1")).isTrue();
        verify(chat, never()).callTools(anyString(), anyList(), any());
        List<AgentInbox.Taken> got = mail();
        assertThat(got.get(0).fromTitle()).isEqualTo("denied: createJiraIssue");
        assertThat(got.get(0).text()).contains("Nothing was changed").contains("Not this sprint");
        assertThat(approvals.hasPending(CONV)).isFalse();
    }

    @Test
    @DisplayName("a request is decided once: a second decision finds it taken and runs nothing")
    void decidedOnce() {
        String id = park(CONV);
        ToolApprovalRequest r = row(id);
        assertThat(approvals.decide(r, ApprovalInbox.Decision.DENY, null, "u-1")).isTrue();
        assertThat(approvals.decide(r, ApprovalInbox.Decision.APPROVE, null, "u-1")).isFalse();
        verify(chat, never()).callTools(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("the conversation keeps owing while any of its requests waits")
    void owesUntilAllDecided() {
        String first = park(CONV);
        park(CONV);
        approvals.decide(row(first), ApprovalInbox.Decision.DENY, null, "u-1");
        assertThat(approvals.hasPending(CONV)).isTrue();
    }

    @Test
    @DisplayName("undecided for a week, a request expires and the agent is told nothing was changed")
    void expires() {
        String id = park(CONV);
        row(id).setRequestedAt(Instant.now().minus(8, ChronoUnit.DAYS));
        String fresh = park("conv-other");
        approvals.expire();
        assertThat(row(id).getStatus()).isEqualTo(ToolApprovalRequest.EXPIRED);
        assertThat(row(fresh).isPending()).isTrue();
        assertThat(mail().get(0).fromTitle()).isEqualTo("expired: createJiraIssue");
    }

    @Test
    @DisplayName("a deleted conversation takes its requests with it")
    void deletedWithConversation() {
        park(CONV);
        approvals.onConversationsDeleted(new ConversationsDeletedEvent(List.of(CONV)));
        assertThat(rows.findAll()).isEmpty();
        assertThat(approvals.hasPending(CONV)).isFalse();
    }

    @Test
    @DisplayName("a decision is approve or deny, in any case, and nothing else")
    void decisionWords() {
        assertThat(ApprovalInbox.parseDecision("approve")).contains(ApprovalInbox.Decision.APPROVE);
        assertThat(ApprovalInbox.parseDecision("DENY")).contains(ApprovalInbox.Decision.DENY);
        assertThat(ApprovalInbox.parseDecision("accept_all")).isEmpty();
        assertThat(ApprovalInbox.parseDecision(null)).isEmpty();
    }
}
