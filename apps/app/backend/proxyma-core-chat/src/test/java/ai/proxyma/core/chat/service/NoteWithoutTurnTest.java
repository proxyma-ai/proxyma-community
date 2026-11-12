package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WORDS AN AGENT WROTE ELSEWHERE, WRITTEN INTO A CONVERSATION WITH NO TURN (ChatService.writeNote, since
 * 2026-11-03: a Constellations lead's tellPerson reaches the person's lead chat from a scheduled run).
 * The note is inbox-shaped mail of the kind TOLD, so the transcript draws it as the agent's words for the
 * person; NOTHING ANSWERS IT - no turn starts and no model is called for words the agent wrote itself;
 * a conversation that does not exist yet is made with its system prompt; and while a turn or a
 * condensation holds the memory it waits for that to end, never landing between a tool call and its result.
 */
class NoteWithoutTurnTest {

    private static final String CONVERSATION = "conv-lead-chat";
    private static final String NOTE = AgentInbox.render(List.of(new AgentInbox.Taken(null, AgentInbox.Kind.TOLD, null,
            "(from your scheduled run)", "Needs your decision: 8 items in shared/for-the-person.md.", Instant.now())));

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SystemPromptService prompts = mock(SystemPromptService.class);

    private ChatService chatService(SmartChatMemory memory) {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        return new ChatService(gateway, mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                events, mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), prompts, mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    private List<ChatEvent> published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        List<ChatEvent> out = new ArrayList<>();
        for (Object o : captor.getAllValues()) if (o instanceof ChatEvent e) out.add(e);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Message> written(SmartChatMemory memory) {
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(memory, org.mockito.Mockito.atLeast(0)).add(eq(CONVERSATION), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("an idle conversation takes the note at once, as the agent's mail, and no turn or model call follows")
    void idleTakesItAtOnce() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.tryLockMemory(CONVERSATION)).thenReturn(true);
        when(memory.lastMessageId(CONVERSATION)).thenReturn(41L);
        ChatService chat = chatService(memory);
        AtomicInteger told = new AtomicInteger();

        chat.writeNote(CONVERSATION, "user-1", "constellation", NOTE, "Needs your decision", told::incrementAndGet);

        assertThat(written(memory)).singleElement().satisfies(m -> {
            assertThat(m).isInstanceOf(UserMessage.class);
            assertThat(m.getText()).startsWith("[You told the person (from your scheduled run)]\nNeeds your decision");
        });
        verify(memory).ensureConversation(CONVERSATION, "Needs your decision", "user-1", null, null, "constellation");
        verify(memory).recordExchange(CONVERSATION);
        verify(memory).unlockMemory(CONVERSATION);
        assertThat(told.get()).isEqualTo(1);
        assertThat(chat.notesWaiting(CONVERSATION)).isZero();

        // The page following it is told, with the words; nothing starts a turn.
        List<ChatEvent> sent = published();
        assertThat(sent).singleElement().satisfies(e -> {
            assertThat(e.streamType()).isEqualTo(ChatEvent.Type.AGENT_MAIL.value());
            assertThat(e.data()).contains("\"kind\":\"told\"").contains("Needs your decision: 8 items");
        });
        assertThat(chat.isActive(CONVERSATION)).isFalse();
        assertThat(chat.isBusy(CONVERSATION)).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("a conversation that does not exist yet is made, its system prompt first and the note after it")
    void aNewConversationGetsItsPromptFirst() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.tryLockMemory(CONVERSATION)).thenReturn(true);
        when(memory.lastMessageId(CONVERSATION)).thenReturn(null);
        when(prompts.read()).thenReturn("You are {{PRODUCT_NAME}}.");
        ChatService chat = chatService(memory);

        chat.writeNote(CONVERSATION, null, "constellation", NOTE, "Needs your decision", null);

        List<Message> rows = written(memory);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(rows.get(1).getText()).startsWith("[You told the person");
        // Desktop's one person: the null owner every conversation there has.
        verify(memory).ensureConversation(CONVERSATION, "Needs your decision", null, null, null, "constellation");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("while something holds the memory the note waits, and is written once that ends - never twice")
    void waitsForWhatHoldsTheMemory() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.tryLockMemory(CONVERSATION)).thenReturn(false);
        when(memory.lastMessageId(CONVERSATION)).thenReturn(41L);
        ChatService chat = chatService(memory);
        AtomicInteger told = new AtomicInteger();

        chat.writeNote(CONVERSATION, "user-1", "constellation", NOTE, "Needs your decision", told::incrementAndGet);

        verify(memory, never()).add(anyString(), anyList());
        verify(memory, never()).unlockMemory(anyString());
        assertThat(chat.notesWaiting(CONVERSATION)).isEqualTo(1);
        assertThat(told.get()).isZero();

        // A manual condensation held it; its end writes what waited.
        when(memory.tryLockMemory(CONVERSATION)).thenReturn(true);
        when(memory.forceCondense(CONVERSATION)).thenReturn(true);
        chat.condenseNow(CONVERSATION);

        assertThat(written(memory)).singleElement().satisfies(m -> assertThat(m.getText()).startsWith("[You told the person"));
        assertThat(chat.notesWaiting(CONVERSATION)).isZero();
        assertThat(told.get()).isEqualTo(1);
        chat.condenseNow(CONVERSATION);
        assertThat(written(memory)).hasSize(1);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("an approval outcome its agent need not act on is written into its existing conversation for the next turn, under the conversation's own owner, and starts none")
    void anApprovalOutcomeIsANoteForTheNextTurn() {
        // 2026-11-04: seventeen approved replies woke the Reply Writer for five model calls of bookkeeping
        // (ApprovalInbox, QuietApprovalOutcomeTest). The outcome is mail of the kind APPROVAL, read at the next turn.
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.tryLockMemory(CONVERSATION)).thenReturn(true);
        when(memory.lastMessageId(CONVERSATION)).thenReturn(41L);
        ai.proxyma.core.chat.model.Conversation c = new ai.proxyma.core.chat.model.Conversation();
        c.setUserId("user-1");
        c.setSurface("constellation");
        when(memory.findConversation(CONVERSATION)).thenReturn(java.util.Optional.of(c));
        ChatService chat = chatService(memory);
        String outcome = AgentInbox.render(List.of(new AgentInbox.Taken(null, AgentInbox.Kind.APPROVAL, null,
                "granted: replyToYouTubeComment", "A person approved replyToYouTubeComment and it ran.", Instant.now())));

        assertThat(chat.noteForNextTurn(CONVERSATION, outcome)).isTrue();

        assertThat(written(memory)).singleElement().satisfies(m ->
                assertThat(m.getText()).startsWith("[Approval granted: replyToYouTubeComment]\nA person approved"));
        verify(memory).ensureConversation(CONVERSATION, outcome, "user-1", null, null, "constellation");
        assertThat(chat.isActive(CONVERSATION)).isFalse();
        verifyNoInteractions(gateway);

        // A conversation that is gone gets nothing, and the caller posts it instead.
        when(memory.findConversation("conv-gone")).thenReturn(java.util.Optional.empty());
        assertThat(chat.noteForNextTurn("conv-gone", outcome)).isFalse();
        verify(memory, never()).add(eq("conv-gone"), anyList());
    }

    @Test
    @DisplayName("nothing is written for an empty note or a conversation id that could be a path")
    void refusesNothingToWrite() {
        SmartChatMemory memory = mock(SmartChatMemory.class);
        when(memory.tryLockMemory(any())).thenReturn(true);
        ChatService chat = chatService(memory);
        chat.writeNote(CONVERSATION, "user-1", "constellation", "  ", null, null);
        verify(memory, never()).ensureConversation(any(), any(), any(), isNull(), isNull(), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                chat.writeNote("../etc", "user-1", "constellation", NOTE, null, null)).isInstanceOf(RuntimeException.class);
    }
}
