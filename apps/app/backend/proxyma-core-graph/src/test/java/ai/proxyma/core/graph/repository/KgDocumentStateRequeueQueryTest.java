package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgDocumentState;
import ai.proxyma.core.graph.model.KgDocumentState.Status;
import ai.proxyma.core.graph.model.KgMention;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The query that finds DONE rows an outage wrote, run for real against H2.
 *
 * <p>Not a mock, because the query IS the rule: which rows are provably unread lives entirely in
 * its WHERE clause, and a Spring Data {@code @Query} is only parsed when the application starts - a
 * typo in it would pass every mock-based test and then stop the product from booting. The JPQL is
 * read off the repository's own annotation, so this runs the string that ships and not a copy.
 */
class KgDocumentStateRequeueQueryTest {

    private static final UUID CONNECTOR = UUID.randomUUID();

    private SessionFactory sessions;

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(KgDocumentState.class)
                .addAnnotatedClass(KgMention.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:requeue-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private UUID row(Status status, Integer chunksAnswered) {
        UUID id = UUID.randomUUID();
        KgDocumentState s = new KgDocumentState(id, CONNECTOR);
        s.setStatus(status);
        s.setChunkHash("hash");
        s.setChunksAnswered(chunksAnswered);
        sessions.inTransaction(session -> session.persist(s));
        return id;
    }

    private void mention(UUID documentId) {
        mention(documentId, null);
    }

    private void mention(UUID documentId, String chunkRef) {
        KgMention m = new KgMention();
        m.setEntityId(UUID.randomUUID());
        m.setConnectorId(CONNECTOR);
        m.setDocumentId(documentId);
        m.setChunkRef(chunkRef);
        sessions.inTransaction(session -> session.persist(m));
    }

    private int requeue() throws NoSuchMethodException {
        String jpql = KgDocumentStateRepository.class
                .getMethod("requeueDoneWithoutAnswers", Status.class, Status.class, Instant.class)
                .getAnnotation(Query.class).value();
        return sessions.fromTransaction(session -> session.createMutationQuery(jpql)
                .setParameter("done", Status.DONE)
                .setParameter("pending", Status.PENDING)
                .setParameter("now", Instant.now())
                .executeUpdate());
    }

    private Status statusOf(UUID id) {
        return sessions.fromTransaction(session -> session.get(KgDocumentState.class, id).getStatus());
    }

    @Test
    @DisplayName("only DONE rows with no mentions and no answer on record go back to PENDING")
    void requeuesExactlyTheUnreadRows() throws Exception {
        // What the 2026-09-16 outage left behind: DONE, nothing in the graph, written before any
        // answer was counted. Indistinguishable from a legacy empty document, so re-read once.
        UUID outageRow = row(Status.DONE, null);
        // Cannot be written today - extraction throws first - but repaired if a path ever slips.
        UUID noAnswerRow = row(Status.DONE, 0);

        // A legacy row with a graph: something answered, whatever the column says.
        UUID legacyWithGraph = row(Status.DONE, null);
        mention(legacyWithGraph);
        // Read, and the model found nothing. A real answer - never offered again.
        UUID genuinelyEmpty = row(Status.DONE, 2);
        // Not DONE, so not this query's business.
        UUID failed = row(Status.FAILED, null);
        // graph-24: an outage row the metadata pass has since given its metadata mentions - which
        // need no model - is still a row no model read.
        UUID outageWithMetadata = row(Status.DONE, null);
        mention(outageWithMetadata, "meta");

        assertEquals(3, requeue());
        assertEquals(Status.PENDING, statusOf(outageWithMetadata));

        assertEquals(Status.PENDING, statusOf(outageRow));
        assertEquals(Status.PENDING, statusOf(noAnswerRow));
        assertEquals(Status.DONE, statusOf(legacyWithGraph));
        assertEquals(Status.DONE, statusOf(genuinelyEmpty));
        assertEquals(Status.FAILED, statusOf(failed));
    }
}
