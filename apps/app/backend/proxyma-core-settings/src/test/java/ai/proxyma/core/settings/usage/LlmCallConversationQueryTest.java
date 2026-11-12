package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The per-call log's conversation filter and its grouping by conversation, run as written on H2 - the
 * JPQL is read off LlmCallRepository's own {@code @Query} annotations, so this cannot drift from what
 * the app runs. Pins that the filter takes one conversation's calls and that the grouping keeps the
 * calls made for no conversation together as one row, with each group's totals and its first and last
 * call - and that neither reads past the subject it is given.
 */
class LlmCallConversationQueryTest {

    private static SessionFactory sessions;
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @BeforeAll
    static void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(LlmCall.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:llm-call-conv;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        call("u-1", "conv-a", 0, 0.10);
        call("u-1", "conv-a", 60, 0.20);
        call("u-1", "conv-b", 120, 0.05);
        call("u-1", null, 30, 0.01);
        call("u-2", "conv-c", 10, 9.00);
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    private static void call(String subject, String conversation, int seconds, double cost) {
        sessions.inTransaction(s -> {
            LlmCall c = new LlmCall();
            c.setRecordedAt(T0.plusSeconds(seconds));
            c.setSubjectKind(SpendSubject.Kind.USER);
            c.setSubjectId(subject);
            c.setRole("CHAT");
            c.setProvider("Anthropic");
            c.setModel("claude-haiku-5");
            c.setInputTokens(100);
            c.setOutputTokens(10);
            c.setCacheReadTokens(50);
            c.setCacheWriteTokens(5);
            c.setCostUsd(cost);
            c.setConversationId(conversation);
            s.persist(c);
        });
    }

    private static Map<String, Object> params(String subjectId, String conversationId) {
        Map<String, Object> p = new HashMap<>();
        p.put("from", T0.minusSeconds(3600));
        p.put("to", T0.plusSeconds(3600));
        p.put("subjectId", subjectId);
        p.put("role", null);
        p.put("provider", null);
        p.put("model", null);
        p.put("text", null);
        p.put("conversationId", conversationId);
        return p;
    }

    private static <T> List<T> run(String method, Class<T> type, Map<String, Object> params) {
        Method m = null;
        for (Method candidate : LlmCallRepository.class.getMethods()) {
            if (candidate.getName().equals(method)) m = candidate;
        }
        String jpql = m.getAnnotation(Query.class).value();
        return sessions.fromTransaction(s -> {
            var q = s.createSelectionQuery(jpql, type);
            params.forEach(q::setParameter);
            return q.getResultList();
        });
    }

    @Test
    @DisplayName("the filter takes one conversation's calls, within the subject given")
    void filterByConversation() {
        assertEquals(2, run("search", LlmCall.class, params("u-1", "conv-a")).size());
        assertEquals(4, run("search", LlmCall.class, params("u-1", null)).size());
        assertEquals(0, run("search", LlmCall.class, params("u-1", "conv-c")).size(), "another user's conversation");
    }

    @Test
    @DisplayName("grouped: a row per conversation, the calls made for none as one, costliest first, with totals and first and last call")
    void groupedByConversation() {
        List<Object[]> rows = run("totalsByConversation", Object[].class, params("u-1", null));

        assertEquals(3, rows.size());
        Object[] a = rows.get(0);
        assertEquals("conv-a", a[0]);
        assertEquals(2L, ((Number) a[1]).longValue());
        assertEquals(200L, ((Number) a[2]).longValue());
        assertEquals(20L, ((Number) a[3]).longValue());
        assertEquals(100L, ((Number) a[4]).longValue());
        assertEquals(10L, ((Number) a[5]).longValue());
        assertEquals(0.30, ((Number) a[6]).doubleValue(), 1e-9);
        assertEquals(T0, a[7]);
        assertEquals(T0.plusSeconds(60), a[8]);
        assertEquals("conv-b", rows.get(1)[0]);
        assertNull(rows.get(2)[0], "the calls made for no conversation, together");
        assertEquals(1L, ((Number) rows.get(2)[1]).longValue());
    }
}
