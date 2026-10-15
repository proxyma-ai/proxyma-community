package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query behind {@link ConnectorService#findDocumentsNamed}, run for real against H2 with the
 * patterns the service builds - how the key-account agent's citation of an indexed FILE (a name, not
 * a web address) finds the one document it means.
 *
 * <p>Not a mock, because the WHERE clause and the LIKE escaping are the rule: a file name is full of
 * '_', which LIKE reads as "any character", and a Spring Data {@code @Query} is only parsed when the
 * application starts. The JPQL is read off the repository's own annotation, as
 * KgDocumentStateRequeueQueryTest does.
 */
class DocumentNameQueryTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID OTHER_SOURCE = UUID.randomUUID();

    private SessionFactory sessions;

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(TrackedDocument.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:docname-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private void doc(UUID connector, String sourceId, String title, TrackedDocument.Status status) {
        TrackedDocument d = new TrackedDocument();
        d.setConnectorId(connector);
        d.setSourceId(sourceId);
        d.setTitle(title);
        d.setConnectorType(ConnectorType.UPLOADED_FILES);
        d.setStatus(status);
        d.setSourceLastModified(Instant.now());
        sessions.inTransaction(session -> session.persist(d));
    }

    private List<String> named(UUID connector, String name) throws NoSuchMethodException {
        String jpql = TrackedDocumentRepository.class
                .getMethod("findNameCandidates", UUID.class, String.class, String.class, String.class)
                .getAnnotation(Query.class).value();
        ConnectorService.NamePatterns q = ConnectorService.NamePatterns.of(name);
        return sessions.fromTransaction(session -> session.createSelectionQuery(jpql, TrackedDocument.class)
                .setParameter("connectorId", connector)
                .setParameter("name", q.name())
                .setParameter("slashSuffix", q.slashSuffix())
                .setParameter("backslashSuffix", q.backslashSuffix())
                .getResultList()).stream().map(TrackedDocument::getSourceId).toList();
    }

    @Test
    @DisplayName("a file is found by the name a search result shows for it, by its title, and in any case")
    void findsByFileNameAndTitle() throws Exception {
        doc(SOURCE, "/data/uploads/u1/f9/sow-02-warehouse-mobile-apps.md", "sow-02-warehouse-mobile-apps.md",
                TrackedDocument.Status.COMPLETED);
        doc(SOURCE, "\\\\files.example.test\\share\\Minutes\\steering-2026-09.docx", "Steering minutes, September",
                TrackedDocument.Status.COMPLETED);

        assertThat(named(SOURCE, "sow-02-warehouse-mobile-apps.md"))
                .containsExactly("/data/uploads/u1/f9/sow-02-warehouse-mobile-apps.md");
        assertThat(named(SOURCE, "SOW-02-Warehouse-Mobile-Apps.MD")).hasSize(1);
        // A network share's sourceId is a UNC path: its last segment is after a back slash.
        assertThat(named(SOURCE, "steering-2026-09.docx")).hasSize(1);
        assertThat(named(SOURCE, "steering minutes, september")).hasSize(1);
    }

    @Test
    @DisplayName("'_' and '%' in a name are literal, and a deleted or another source's document is never found")
    void wildcardsAreLiteral() throws Exception {
        doc(SOURCE, "/data/uploads/u1/a/revenue_2026.csv", "revenue_2026.csv", TrackedDocument.Status.COMPLETED);
        doc(SOURCE, "/data/uploads/u1/b/revenueX2026.csv", "revenueX2026.csv", TrackedDocument.Status.COMPLETED);
        doc(SOURCE, "/data/uploads/u1/c/old-plan.md", "old-plan.md", TrackedDocument.Status.DELETED);
        doc(OTHER_SOURCE, "/data/uploads/u2/d/revenue_2026.csv", "revenue_2026.csv", TrackedDocument.Status.COMPLETED);

        assertThat(named(SOURCE, "revenue_2026.csv")).containsExactly("/data/uploads/u1/a/revenue_2026.csv");
        assertThat(named(SOURCE, "revenue%.csv")).isEmpty();
        assertThat(named(SOURCE, "old-plan.md")).isEmpty();
        // A segment of the name is not the name.
        assertThat(named(SOURCE, "2026.csv")).isEmpty();
    }
}
