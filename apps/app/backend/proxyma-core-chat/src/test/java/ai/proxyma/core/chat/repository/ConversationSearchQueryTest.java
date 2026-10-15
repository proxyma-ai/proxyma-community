package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.service.ConversationSearchService;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * searchConversations' queries, run as written - the JPQL is read off the repositories' own
 * {@code @Query} annotations, so this cannot drift from what the app runs. What they must get
 * right: only the caller's conversations on a multi-user edition, never the one being searched
 * from, only what the user and the assistant SAID (no tool rows, no condensation summaries), case
 * ignored, and a '%' or '_' in the search taken literally.
 */
class ConversationSearchQueryTest {

    private static SessionFactory sessions;

    @BeforeAll
    static void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(Conversation.class)
                .addAnnotatedClass(ChatMessage.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:conv-search;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        conversation("a1", "alice", "Kafka retention settings");
        conversation("a2", "alice", "Lunch");
        conversation("b1", "bob", "Kafka for Bob");
        message("a1", MessageRole.USER, "What RETENTION did we pick for the kafka topic?", false);
        message("a1", MessageRole.ASSISTANT, "We picked 7 days of retention.", false);
        message("a1", MessageRole.TOOL, "retention tool output", false);
        message("a1", MessageRole.ASSISTANT, "Summary: retention discussed", true);
        message("a2", MessageRole.USER, "A 50% discount on lunch", false);
        message("a2", MessageRole.USER, "500 people came", false);
        message("b1", MessageRole.USER, "Bob's kafka retention question", false);
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    void aUserFindsOnlyTheirOwnWordsAndNotTheCurrentConversation() throws Exception {
        List<ChatMessage> alice = run(ChatMessageRepository.class, "searchContentOfUser", ChatMessage.class,
                Map.of("userId", "alice", "exclude", "none", "pattern", "%retention%"));
        assertEquals(2, alice.size(), "the user's and the assistant's words only - no tool row, no summary");
        assertTrue(alice.stream().allMatch(m -> m.getConversationId().equals("a1")));

        List<ChatMessage> excluded = run(ChatMessageRepository.class, "searchContentOfUser", ChatMessage.class,
                Map.of("userId", "alice", "exclude", "a1", "pattern", "%retention%"));
        assertTrue(excluded.isEmpty(), "the conversation searched from is already in context");
    }

    @Test
    void aSingleUserEditionSearchesEveryConversation() throws Exception {
        List<ChatMessage> all = run(ChatMessageRepository.class, "searchContent", ChatMessage.class,
                Map.of("exclude", "none", "pattern", "%kafka%"));
        assertEquals(2, all.size());
    }

    @Test
    void wildcardsInTheSearchAreLiteral() throws Exception {
        String pattern = "%" + escape("50%") + "%";
        List<ChatMessage> hits = run(ChatMessageRepository.class, "searchContentOfUser", ChatMessage.class,
                Map.of("userId", "alice", "exclude", "none", "pattern", pattern));
        assertEquals(1, hits.size(), "'50%' must not match '500'");
        assertTrue(hits.get(0).getContent().contains("50% discount"));
    }

    @Test
    void titlesAreSearchedWithTheSameScope() throws Exception {
        List<Conversation> alice = run(ConversationRepository.class, "searchTitlesOfUser", Conversation.class,
                Map.of("userId", "alice", "exclude", "none", "pattern", "%kafka%"));
        assertEquals(List.of("a1"), alice.stream().map(Conversation::getId).toList());
        List<Conversation> all = run(ConversationRepository.class, "searchTitles", Conversation.class,
                Map.of("exclude", "a1", "pattern", "%kafka%"));
        assertEquals(List.of("b1"), all.stream().map(Conversation::getId).toList());
    }

    private static String escape(String term) throws Exception {
        Method m = ConversationSearchService.class.getDeclaredMethod("escape", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, term);
    }

    private static <T> List<T> run(Class<?> repository, String method, Class<T> type, Map<String, Object> params)
            throws Exception {
        Method m = null;
        for (Method candidate : repository.getMethods()) {
            if (candidate.getName().equals(method)) m = candidate;
        }
        assertTrue(m != null && m.getParameterTypes()[m.getParameterCount() - 1] == Pageable.class, method);
        String jpql = m.getAnnotation(Query.class).value();
        return sessions.fromTransaction(s -> {
            var q = s.createSelectionQuery(jpql, type);
            // One term: its pattern in every slot, as the service fills the slots a short query leaves.
            params.forEach((name, value) -> {
                if (!name.equals("pattern")) q.setParameter(name, value);
                else for (int i = 1; i <= 6; i++) q.setParameter("p" + i, value);
            });
            return q.setMaxResults(200).getResultList();
        });
    }

    private static void conversation(String id, String user, String title) {
        sessions.inTransaction(s -> {
            Conversation c = new Conversation();
            c.setId(id);
            c.setUserId(user);
            c.setTitle(title);
            c.setCreatedAt(Instant.now());
            c.setUpdatedAt(Instant.now());
            s.persist(c);
        });
    }

    private static void message(String conversation, MessageRole role, String content, boolean summary) {
        sessions.inTransaction(s -> {
            ChatMessage m = new ChatMessage();
            m.setConversationId(conversation);
            m.setRole(role);
            m.setContent(content);
            m.setSummary(summary);
            m.setCreatedAt(Instant.now());
            s.persist(m);
        });
    }
}
