package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE PER-PERSON MONTHLY SPEND IS DELETED (2026-11-12 legal review): nothing deleted AttributedSpendMonth rows, so a
 * person's monthly totals outlived their account and every retention setting. They now age out with the usage
 * retention and go with the account. The JPQL is read off AttributedSpendRepository's own {@code @Query} and run on
 * H2, so this cannot drift from what the app runs.
 */
class AttributedSpendDeletesTest {

    private SessionFactory sessions;

    @BeforeEach
    void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(AttributedSpendMonth.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:attributed-deletes;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        month("2025-10", SpendSubject.user("u-1"));
        month("2025-11", SpendSubject.user("u-1"));
        month("2025-11", SpendSubject.user("u-2"));
        month("2026-11", SpendSubject.user("u-1"));
        month("2026-11", SpendSubject.SYSTEM);
    }

    @AfterEach
    void close() {
        sessions.close();
    }

    private void month(String yearMonth, SpendSubject subject) {
        sessions.inTransaction(s -> {
            AttributedSpendMonth m = new AttributedSpendMonth(yearMonth, subject, "CHAT", "Anthropic", "claude-haiku-5");
            m.add(100, 10, 0, 0, 0.01);
            s.persist(m);
        });
    }

    private int run(String method, Map<String, Object> params) throws Exception {
        String jpql = null;
        for (var m : AttributedSpendRepository.class.getMethods()) {
            if (m.getName().equals(method)) jpql = m.getAnnotation(Query.class).value();
        }
        String q = jpql;
        return sessions.fromTransaction(s -> {
            var mutation = s.createMutationQuery(q);
            params.forEach(mutation::setParameter);
            return mutation.executeUpdate();
        });
    }

    private List<String> left() {
        return sessions.fromSession(s -> s.createSelectionQuery(
                "select a.yearMonth || ':' || a.subjectId from AttributedSpendMonth a order by a.yearMonth, a.subjectId",
                String.class).getResultList());
    }

    @Test
    @DisplayName("months before the retention cutoff's month go; that month and later stay")
    void agesOut() throws Exception {
        assertEquals(3, run("deleteMonthsBefore", Map.of("month", "2026-11")));
        assertEquals(List.of("2026-11:system", "2026-11:u-1"), left());
    }

    @Test
    @DisplayName("an account's deletion takes every month of that person's, and no one else's")
    void withTheAccount() throws Exception {
        assertEquals(3, run("deleteForSubject", Map.of("kind", SpendSubject.Kind.USER, "subjectId", "u-1")));
        assertEquals(List.of("2025-11:u-2", "2026-11:system"), left());
    }
}
