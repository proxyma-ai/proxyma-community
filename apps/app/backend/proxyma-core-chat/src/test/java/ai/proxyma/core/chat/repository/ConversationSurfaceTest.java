package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.domain.ConversationSurface;
import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conversation another page holds (a SURFACE - the key-account panel's {@code key-account})
 * stays out of the chat page: the history list, and the assistant's search of earlier
 * conversations, see only conversations with no surface unless asked for one. The surface is taken
 * from the message that CREATES a conversation and never changed by a later one, so nothing a
 * request says can move a conversation into or out of the chat's history.
 *
 * <p>A page that keeps its own record of which conversations are its (the key-account panel's
 * bindings) lists them by id instead, whatever surface each was started under.
 *
 * <p>The list and search queries are run as written - the JPQL is read off the repositories' own
 * {@code @Query} annotations, against H2, as ConversationSearchQueryTest does.
 */
class ConversationSurfaceTest {

    private static SessionFactory sessions;

    @TempDir
    static Path conversationsDir;

    @BeforeAll
    static void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(Conversation.class)
                .addAnnotatedClass(ChatMessage.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:conv-surface;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        conversation("chat-1", "alice", "Kafka retention", null);
        conversation("ka-1", "alice", "Kafka account review", "key-account");
        conversation("chat-2", "bob", "Kafka for Bob", null);
        conversation("ka-2", "alice", "Contoso review", "key-account-0b7c");
        message("chat-1", "What kafka retention did we pick?");
        message("ka-1", "Kafka spend at the account");
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    @DisplayName("a surface is a short lower-case token; absent or blank is the ordinary chat")
    void normalize() {
        assertNull(ConversationSurface.normalize(null));
        assertNull(ConversationSurface.normalize("  "));
        assertEquals("key-account", ConversationSurface.normalize(" Key-Account "));
        assertThrows(IllegalArgumentException.class, () -> ConversationSurface.normalize("key account"));
        assertThrows(IllegalArgumentException.class, () -> ConversationSurface.normalize("-key"));
        assertThrows(IllegalArgumentException.class, () -> ConversationSurface.normalize("k".repeat(65)));
    }

    @Test
    @DisplayName("the history list is the chat page's conversations, or one surface's when asked")
    void historyList() throws Exception {
        assertEquals(List.of("chat-1"), ids(run(ConversationRepository.class, "pageOfChatOfUser",
                Map.of("userId", "alice"))));
        assertEquals(List.of("ka-1"), ids(run(ConversationRepository.class, "pageOfSurfaceOfUser",
                Map.of("userId", "alice", "surface", "key-account"))));
        assertEquals(List.of("chat-1", "chat-2"), ids(run(ConversationRepository.class, "pageOfChat", Map.of()))
                .stream().sorted().toList());
        assertEquals(List.of("ka-1"), ids(run(ConversationRepository.class, "pageOfSurface",
                Map.of("surface", "key-account"))));
    }

    @Test
    @DisplayName("a page that records its own conversations lists them by id, whatever their surface, and only its user's")
    void listedByIdWhateverTheSurface() throws Exception {
        List<String> ids = List.of("chat-1", "ka-1", "ka-2", "chat-2", "never-started");
        assertEquals(List.of("chat-1", "ka-1", "ka-2"), ids(run(ConversationRepository.class, "pageAmongOfUser",
                Map.of("userId", "alice", "ids", ids))).stream().sorted().toList());
        assertEquals(List.of("chat-2"), ids(run(ConversationRepository.class, "pageAmongOfUser",
                Map.of("userId", "bob", "ids", ids))));
        assertEquals(List.of(), ids(run(ConversationRepository.class, "pageAmongOfUser",
                Map.of("userId", "alice", "ids", List.of("chat-2")))));
    }

    @Test
    @DisplayName("the assistant's search of earlier conversations does not reach one another page holds")
    void searchLeavesSurfacesOut() throws Exception {
        assertEquals(List.of("chat-1"), ids(run(ConversationRepository.class, "searchTitlesOfUser",
                Map.of("userId", "alice", "exclude", "none", "pattern", "%kafka%"))));
        assertEquals(List.of("chat-1", "chat-2"), ids(run(ConversationRepository.class, "searchTitles",
                Map.of("exclude", "none", "pattern", "%kafka%"))).stream().sorted().toList());

        List<ChatMessage> ofUser = run(ChatMessageRepository.class, "searchContentOfUser",
                Map.of("userId", "alice", "exclude", "none", "pattern", "%kafka%"));
        assertEquals(List.of("chat-1"), ofUser.stream().map(ChatMessage::getConversationId).toList());
        List<ChatMessage> all = run(ChatMessageRepository.class, "searchContent",
                Map.of("exclude", "none", "pattern", "%kafka%"));
        assertEquals(List.of("chat-1"), all.stream().map(ChatMessage::getConversationId).toList());
    }

    @Test
    @DisplayName("the message that creates a conversation records its surface")
    void firstMessageRecordsTheSurface() {
        ConversationRepository repo = mock(ConversationRepository.class);
        when(repo.findById("new-1")).thenReturn(Optional.empty());

        assertTrue(memory(repo).ensureConversation("new-1", "hello", "alice", null, null, "key-account"));

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(repo).save(saved.capture());
        assertEquals("key-account", saved.getValue().getSurface());
    }

    @Test
    @DisplayName("a later message cannot change it - an ordinary conversation stays one")
    void laterMessageChangesNothing() {
        Conversation existing = new Conversation();
        existing.setId("chat-9");
        existing.setUserId("alice");
        ConversationRepository repo = mock(ConversationRepository.class);
        when(repo.findById("chat-9")).thenReturn(Optional.of(existing));

        assertEquals(false, memory(repo).ensureConversation("chat-9", "again", "alice", null, null, "key-account"));

        assertNull(existing.getSurface());
        verify(repo, never()).save(any());
    }

    private static SmartChatMemory memory(ConversationRepository conversations) {
        ChatMessageRepository rows = mock(ChatMessageRepository.class);
        return new SmartChatMemory(new JpaChatMemoryRepository(rows, new ObjectMapper()), conversations, rows,
                mock(LlmGateway.class), mock(SettingsService.class), mock(ModelPricingService.class),
                mock(ChatSpendService.class), mock(ApplicationEventPublisher.class), mock(ChatEventSink.class),
                conversationsDir.toString(), new ObjectMapper(), true);
    }

    private static List<String> ids(List<?> rows) {
        return rows.stream().map(r -> ((Conversation) r).getId()).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> run(Class<?> repository, String method, Map<String, Object> params) throws Exception {
        Method m = null;
        for (Method candidate : repository.getMethods()) {
            if (candidate.getName().equals(method)) m = candidate;
        }
        assertTrue(m != null && m.getParameterTypes()[m.getParameterCount() - 1] == Pageable.class, method);
        String jpql = m.getAnnotation(Query.class).value();
        return sessions.fromTransaction(s -> {
            var q = s.createSelectionQuery(jpql, Object.class);
            // A search's one term fills every pattern slot, as the service does for a short query.
            params.forEach((name, value) -> {
                if (!name.equals("pattern")) q.setParameter(name, value);
                else for (int i = 1; i <= 6; i++) q.setParameter("p" + i, value);
            });
            return (List<T>) q.setMaxResults(200).getResultList();
        });
    }

    private static void conversation(String id, String user, String title, String surface) {
        sessions.inTransaction(s -> {
            Conversation c = new Conversation();
            c.setId(id);
            c.setUserId(user);
            c.setTitle(title);
            c.setSurface(surface);
            c.setCreatedAt(Instant.now());
            c.setUpdatedAt(Instant.now());
            s.persist(c);
        });
    }

    private static void message(String conversation, String content) {
        sessions.inTransaction(s -> {
            ChatMessage m = new ChatMessage();
            m.setConversationId(conversation);
            m.setRole(MessageRole.USER);
            m.setContent(content);
            m.setSummary(false);
            m.setCreatedAt(Instant.now());
            s.persist(m);
        });
    }
}
