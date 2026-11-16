package ai.proxyma.core.settings.audit;

import ai.proxyma.core.common.audit.AuditCategory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE ACCEPTANCE OF THE LEGAL DOCUMENTS IS NEVER DELETED FROM THE LOGS (2026-11-14, the maintainer: "log the agreement,
 * non-deletable"): a tab's Clear and retention are the only deletes of audit rows on Desktop and Home Server, and both
 * pass a {@link AuditEvent#LEGAL_ACCEPTED} row over. The JPQL is read off AuditEventRepository's own {@code @Query} and
 * run on H2, so this cannot drift from what the app runs.
 */
class LegalAcceptanceKeptTest {

    private static final Instant OLD = Instant.now().minus(400, ChronoUnit.DAYS);

    private SessionFactory sessions;

    @BeforeEach
    void boot() {
        sessions = new Configuration()
                .addAnnotatedClass(AuditEvent.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:legal-kept;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        row(AuditCategory.LEGAL, AuditEvent.LEGAL_ACCEPTED, OLD);
        row(AuditCategory.CONFIG, "setting.changed", OLD);
        row(AuditCategory.DATA, "audit.cleared", OLD);
    }

    @AfterEach
    void close() {
        sessions.close();
    }

    private void row(String category, String action, Instant at) {
        sessions.inTransaction(s -> {
            AuditEvent e = new AuditEvent();
            e.setOccurredAt(at);
            e.setCategory(category);
            e.setAction(action);
            e.setOutcome("OK");
            e.setActorKind("USER");
            e.setActorId("_local");
            e.setTarget("2026-11-14");
            s.persist(e);
        });
    }

    private int run(String method, Map<String, Object> params) {
        String jpql = null;
        for (var m : AuditEventRepository.class.getMethods()) {
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
                "select e.action from AuditEvent e order by e.action", String.class).getResultList());
    }

    @Test
    @DisplayName("retention deletes everything past its days but the acceptance")
    void retentionKeepsIt() {
        assertEquals(2, run("deleteRecordedBefore", Map.of("cutoff", Instant.now())));
        assertEquals(List.of(AuditEvent.LEGAL_ACCEPTED), left());
    }

    @Test
    @DisplayName("clearing the Activity tab, where the acceptance is listed, keeps it")
    void clearKeepsIt() {
        assertEquals(2, run("deleteByCategories", Map.of("categories", AuditCategory.ACTIVITY)));
        assertEquals(List.of(AuditEvent.LEGAL_ACCEPTED), left());
    }

    @Test
    @DisplayName("the acceptance is read under the Activity tab")
    void listedUnderActivity() {
        assertEquals(true, AuditCategory.ACTIVITY.contains(AuditCategory.LEGAL));
        assertEquals(true, AuditCategory.ALL.contains(AuditCategory.LEGAL));
    }
}
