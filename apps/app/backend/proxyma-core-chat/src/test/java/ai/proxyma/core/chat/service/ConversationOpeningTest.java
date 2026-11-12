package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conversation nobody typed into is titled from what its first message SAYS, never from the header
 * written for the model (ConversationOpening, 2026-11-05: a team's run read "[Scheduled run, ...]" in its
 * list, then a title the model wrote in English). The kind is stored for the page to word; the title keeps
 * only the words that are data, and is empty rather than "New conversation" when there are none.
 */
class ConversationOpeningTest {

    @TempDir
    Path conversationsDir;

    // A module that writes "[Run, ...]" openings, relays a person's words under "[Relayed]", and fails on "[Throws".
    private static final List<ChatModule> MODULE = List.of(new ChatModule() {
        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
        @Override public Set<String> ownToolNames() { return Set.of(); }
        @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
        @Override public Optional<String> systemContext(String conversationId, String userId) { return Optional.empty(); }
        @Override public boolean licensed() { return true; }

        @Override
        public Optional<Shown> shownOpening(String content) {
            if (content.startsWith("[Run, ")) {
                int nl = content.indexOf('\n');
                return Optional.of(Shown.asMail("run_schedule", nl < 0 ? null : content.substring(nl + 1)));
            }
            if (content.startsWith("[Note]")) return Optional.of(Shown.asMail("lead_note", null));
            if (content.startsWith("[Relayed]\n")) return Optional.of(Shown.asPerson(content.substring(10)));
            if (content.startsWith("[Throws")) throw new IllegalStateException("a module that fails costs only itself");
            return Optional.empty();
        }
    });

    @Test
    @DisplayName("a module's run is its kind and its task, with no model call and the composer's markers off")
    void moduleRun() {
        var o = ConversationOpening.of("[Run, 2026-11-05 09:00 UTC]\nCheck the new comments.\nAnd draft replies."
                + ChatService.skillMentionBlock(List.of("YouTube")), MODULE);
        assertThat(o.kind()).isEqualTo("run_schedule");
        assertThat(o.title()).isEqualTo("Check the new comments.");
        assertThat(o.nameFrom()).isNull();

        var note = ConversationOpening.of("[Note] 3 approved.", MODULE);
        assertThat(note.kind()).isEqualTo("lead_note");
        assertThat(note.title()).isNull();
    }

    @Test
    @DisplayName("an agent's mail that opens a conversation is its kind and its sender")
    void inboxMail() {
        var o = ConversationOpening.of("[" + AgentInbox.Kind.REPORT.heading() + " Price check]\nAll done.", List.of());
        assertThat(o.kind()).isEqualTo("report");
        assertThat(o.title()).isEqualTo("Price check");
        assertThat(o.nameFrom()).isNull();
    }

    @Test
    @DisplayName("a person's words under a header are named from those words; anything else as before")
    void personAndPlain() {
        var relayed = ConversationOpening.of("[Relayed]\nWhat did the team find?", MODULE);
        assertThat(relayed.kind()).isNull();
        assertThat(relayed.title()).isEqualTo("What did the team find?");
        assertThat(relayed.nameFrom()).isEqualTo("What did the team find?");

        var plain = ConversationOpening.of("Summarize the release notes", MODULE);
        assertThat(plain.kind()).isNull();
        assertThat(plain.nameFrom()).isEqualTo("Summarize the release notes");

        var failing = ConversationOpening.of("[Throws]\nkept", MODULE);
        assertThat(failing.kind()).as("a module that throws is passed over").isNull();
        assertThat(failing.nameFrom()).isEqualTo("[Throws]\nkept");
    }

    @Test
    @DisplayName("the kind is stored with the conversation, and a kind with no words leaves the title empty")
    void stored() {
        ConversationRepository repo = mock(ConversationRepository.class);
        when(repo.findById("run-1")).thenReturn(Optional.empty());
        when(repo.findById("note-1")).thenReturn(Optional.empty());
        when(repo.findById("chat-1")).thenReturn(Optional.empty());
        SmartChatMemory memory = memory(repo);

        memory.ensureConversation("run-1", "Check the new comments.", "alice", null, null, "constellation", "run_schedule");
        memory.ensureConversation("note-1", null, "alice", null, null, "constellation", "lead_note");
        memory.ensureConversation("chat-1", "hello", "alice", null, null, null);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(repo, org.mockito.Mockito.times(3)).save(saved.capture());
        List<Conversation> rows = saved.getAllValues();
        assertThat(rows.get(0).getTitleKind()).isEqualTo("run_schedule");
        assertThat(rows.get(0).getTitle()).isEqualTo("Check the new comments.");
        assertThat(rows.get(1).getTitleKind()).isEqualTo("lead_note");
        assertThat(rows.get(1).getTitle()).as("never the English \"New conversation\"").isNull();
        assertThat(rows.get(2).getTitleKind()).isNull();
        assertThat(rows.get(2).getTitle()).isEqualTo("hello");
    }

    private SmartChatMemory memory(ConversationRepository conversations) {
        ChatMessageRepository rows = mock(ChatMessageRepository.class);
        return new SmartChatMemory(new JpaChatMemoryRepository(rows, new ObjectMapper()), conversations, rows,
                mock(LlmGateway.class), mock(SettingsService.class), mock(ModelPricingService.class),
                mock(ChatSpendService.class), mock(ApplicationEventPublisher.class), mock(ChatEventSink.class),
                conversationsDir.toString(), new ObjectMapper(), true);
    }
}
