package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.event.ApprovalOutcomeNotedEvent;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * APPROVAL OUTCOMES DO NOT WAKE THE DRAFTING AGENT (2026-11-04, the maintainer's log: seventeen approved replies woke
 * the Reply Writer's long conversation "for what reached its inbox", and it spent five model calls - 22k output tokens
 * over 80k cached input each - rewriting notes that then went stale). Where a module says its agent has nothing to do
 * about an outcome (ChatModule.approvalOutcomeQuiet - a saved Constellations agent's reply), a decided batch posts
 * NOTHING to the agent's inbox, so no turn starts: each outcome is written into the conversation as a note its next
 * turn reads (ChatService.noteForNextTurn, pinned in NoteWithoutTurnTest), and whatever waited on the request hears so
 * (ApprovalOutcomeNotedEvent). Without the module's say, or for a deployed agent still owing its report, it wakes as before.
 */
class QuietApprovalOutcomeTest {

    static final String REPLY = "replyToYouTubeComment";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    AgentNodeRepository nodes;
    AgentInbox inbox;
    ApprovalInbox approvals;
    ChatService chat;
    final List<Object> published = new ArrayList<>();

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
        ApplicationEventPublisher events = published::add;
        told = InMemoryRepositories.of(AgentInboxRepository.class);
        inbox = new AgentInbox(told, events);
        chat = mock(ChatService.class);
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, "{\"success\":true,\"replyId\":\"r1\"}"))).build();
        });
        when(chat.noteForNextTurn(anyString(), anyString())).thenReturn(true);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        when(chats.getIfAvailable()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        nodes = InMemoryRepositories.of(AgentNodeRepository.class);
        AgentTree tree = new AgentTree(nodes, inbox, InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings,
                mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
        approvals.setEvents(events);
        approvals.setContexts(ApprovalContexts.of(List.of(new Replies()), Duration.ofSeconds(5)));
    }

    static final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            return REPLY.equals(tool) ? Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.editable("reply", String.valueOf(args.get("text")), "text")))) : Optional.empty();
        }
    }

    ToolApprovalRequest park(String conversationId, String commentId) {
        try {
            String id = JSON.readTree(approvals.park(conversationId, "u-1", new ToolCall("call-" + UUID.randomUUID(), "function",
                    REPLY, "{\"commentId\":\"" + commentId + "\",\"text\":\"Cam on ban\"}"))).get("requestId").asText();
            return rows.findById(UUID.fromString(id)).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a decided batch for a saved agent posts nothing to its inbox, so no turn starts; each outcome is a note for its next turn")
    void aDecidedBatchStartsNoTurn() {
        when(chat.approvalOutcomeQuiet(eq("conv-writer"), eq(REPLY), anyBoolean())).thenReturn(true);
        List<ToolApprovalRequest> batch = List.of(park("conv-writer", "c1"), park("conv-writer", "c2"), park("conv-writer", "c3"));
        assertThat(approvals.hasPending("conv-writer")).isTrue();

        approvals.decideAll(batch.subList(0, 2), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());
        approvals.decideAll(batch.subList(2, 3), ApprovalInbox.Decision.DENY, "Too formal", "u-1", Map.of());

        // Nothing reached the inbox: the wake (AgentInboxPostedEvent) never fired.
        assertThat(told.findAll()).isEmpty();
        assertThat(inbox.hasPending("conv-writer")).isFalse();
        assertThat(published).noneMatch(e -> e instanceof AgentInboxPostedEvent);
        assertThat(approvals.hasPending("conv-writer")).as("nothing waits: a run owed its reply may settle").isFalse();
        // Each outcome is written in, inbox-shaped, for the next turn to read.
        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(chat, times(3)).noteForNextTurn(eq("conv-writer"), notes.capture());
        assertThat(notes.getAllValues()).allMatch(AgentInbox::isInboxMessage);
        assertThat(notes.getAllValues().get(0)).startsWith("[Approval granted: " + REPLY + "]");
        assertThat(notes.getAllValues().get(2)).startsWith("[Approval denied: " + REPLY + "]").contains("Too formal");
        assertThat(published).filteredOn(e -> e instanceof ApprovalOutcomeNotedEvent).hasSize(3)
                .allMatch(e -> ((ApprovalOutcomeNotedEvent) e).conversationId().equals("conv-writer"));
    }

    @Test
    @DisplayName("without a module's say the outcome wakes its agent as before, and a conversation that is gone is posted to as well")
    void otherwiseItWakes() {
        ToolApprovalRequest r = park("conv-chat", "c1");
        approvals.decideAll(List.of(r), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());
        assertThat(told.findAll()).hasSize(1);
        assertThat(published).anyMatch(e -> e instanceof AgentInboxPostedEvent);
        verify(chat, never()).noteForNextTurn(anyString(), anyString());

        // Quiet, but the note could not be written: it is posted instead, never lost.
        when(chat.approvalOutcomeQuiet(eq("conv-gone"), eq(REPLY), anyBoolean())).thenReturn(true);
        when(chat.noteForNextTurn(eq("conv-gone"), anyString())).thenReturn(false);
        ToolApprovalRequest g = park("conv-gone", "c2");
        approvals.decideAll(List.of(g), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());
        assertThat(told.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("a deployed agent still owing its report is woken even where the module says quiet: its report waits on the decision")
    void aReportOwedStillWakes() {
        when(chat.approvalOutcomeQuiet(anyString(), anyString(), anyBoolean())).thenReturn(true);
        AgentNode child = new AgentNode();
        child.setConversationId("conv-child");
        child.setRootConversationId("conv-writer");
        child.setParentConversationId("conv-writer");
        child.setTitle("Helper");
        child.setReportOwed(true);
        nodes.save(child);

        ToolApprovalRequest r = park("conv-child", "c1");
        approvals.decideAll(List.of(r), ApprovalInbox.Decision.APPROVE, null, "u-1", Map.of());

        assertThat(told.findAll()).hasSize(1);
        verify(chat, never()).noteForNextTurn(anyString(), anyString());
    }
}
