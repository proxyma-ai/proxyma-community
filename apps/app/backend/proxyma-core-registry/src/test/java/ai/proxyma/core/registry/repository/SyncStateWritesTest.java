package ai.proxyma.core.registry.repository;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statements sync writes a source's row and documents with, run against H2 with the repositories
 * that ship.
 *
 * <p>Sync and the drain used to read the row, change a counter and save it back - and save() writes
 * every column, so an edit a person made in between (sharing, priority, scope, even a deletion in
 * progress) was put back by the next item indexed. Each write is now one statement naming its own
 * columns; these pin that an edit survives every one of them. They also parse every @Query here,
 * which Spring otherwise does only when the application starts.
 */
class SyncStateWritesTest {

    private SessionFactory sessions;

    record Repos(RegisteredConnectorRepository connectors, TrackedDocumentRepository documents) {}

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(RegisteredConnector.class)
                .addAnnotatedClass(TrackedDocument.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:sync-writes-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private <T> T tx(Function<Repos, T> work) {
        EntityManager em = sessions.createEntityManager();
        try {
            em.getTransaction().begin();
            JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
            T result = work.apply(new Repos(factory.getRepository(RegisteredConnectorRepository.class),
                    factory.getRepository(TrackedDocumentRepository.class)));
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    private UUID source() {
        return tx(r -> {
            RegisteredConnector c = new RegisteredConnector();
            c.setConnectorType(ConnectorType.CONFLUENCE);
            c.setName("ENG");
            c.setStatus(RegisteredConnector.Status.ACTIVE);
            return r.connectors().save(c).getId();
        });
    }

    private RegisteredConnector read(UUID id) {
        return tx(r -> r.connectors().findById(id).orElseThrow());
    }

    private UUID document(UUID source, TrackedDocument.Status status) {
        return tx(r -> {
            TrackedDocument d = new TrackedDocument();
            d.setConnectorId(source);
            d.setConnectorType(ConnectorType.CONFLUENCE);
            d.setSourceId("page-" + UUID.randomUUID());
            d.setTitle("A page");
            d.setStatus(status);
            d.setSourceLastModified(Instant.now());
            d.setRetryCount(1);
            d.setFailureMessage(status == TrackedDocument.Status.FAILED ? "it failed" : null);
            return r.documents().save(d).getId();
        });
    }

    @Test
    @DisplayName("a person's edit survives every write sync makes to the source's row")
    void editsSurviveSyncWrites() {
        UUID id = source();
        // The person's edit, committed between the background's read and its write.
        tx(r -> {
            RegisteredConnector c = r.connectors().findById(id).orElseThrow();
            c.setShared(true);
            c.setPriority(true);
            c.setGraphEnabled(true);
            c.setFileTypes("pdf");
            c.setDeleting(true);
            return null;
        });
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        tx(r -> {
            r.connectors().updateCounts(id, 10, 7, 2);
            r.connectors().updateSyncState(id, RegisteredConnector.Status.ERROR, "the server said no");
            r.connectors().updateStatus(id, RegisteredConnector.Status.PAUSED);
            r.connectors().updateAllItemsFetched(id, true);
            r.connectors().markFullySynced(id, at, "cursor-1");
            r.connectors().clearLastFullSyncAt(id);
            r.connectors().markSynced(id, at, "cursor-2");
            r.connectors().stampFirstEmbeddingModel(id, "model-a", "OLLAMA");
            return null;
        });

        RegisteredConnector after = read(id);
        assertThat(after.isShared()).isTrue();
        assertThat(after.isPriority()).isTrue();
        assertThat(after.isGraphEnabled()).isTrue();
        assertThat(after.getFileTypes()).isEqualTo("pdf");
        assertThat(after.isDeleting()).isTrue();
        assertThat(after.getItemCount()).isEqualTo(10);
        assertThat(after.getProcessedCount()).isEqualTo(7);
        assertThat(after.getFailedCount()).isEqualTo(2);
        assertThat(after.getStatus()).isEqualTo(RegisteredConnector.Status.PAUSED);
        assertThat(after.getLastSyncError()).isEqualTo("the server said no");
        assertThat(after.isAllItemsFetched()).isTrue();
        assertThat(after.getLastFullSyncAt()).isNull();
        assertThat(after.getLastSyncedAt()).isNotNull();
        // The cursor is written with the checkpoint it belongs to - the last one wins.
        assertThat(after.getSyncCursor()).isEqualTo("cursor-2");
        assertThat(after.getIndexedWithEmbeddingModel()).isEqualTo("model-a");
    }

    @Test
    @DisplayName("the embedding model is stamped the first time only; a reindex restamps it")
    void modelStampedOnce() {
        UUID id = source();
        tx(r -> r.connectors().stampFirstEmbeddingModel(id, "model-a", "OLLAMA"));
        tx(r -> r.connectors().stampFirstEmbeddingModel(id, "model-b", "OPENAI"));
        assertThat(read(id).getIndexedWithEmbeddingModel()).isEqualTo("model-a");
        assertThat(read(id).getIndexedWithEmbeddingProvider()).isEqualTo("OLLAMA");

        tx(r -> {
            RegisteredConnector c = r.connectors().findById(id).orElseThrow();
            c.setReindexInProgress(true);
            c.setReindexShadowCollectionId("abc_reindex");
            return null;
        });
        tx(r -> r.connectors().finishReindex(id, "model-b", "OPENAI"));
        RegisteredConnector after = read(id);
        assertThat(after.getIndexedWithEmbeddingModel()).isEqualTo("model-b");
        assertThat(after.isReindexInProgress()).isFalse();
        assertThat(after.getReindexShadowCollectionId()).isNull();
    }

    @Test
    @DisplayName("a reindex requeues the live documents and leaves the deleted ones deleted")
    void reindexSkipsDeletedDocuments() {
        UUID id = source();
        UUID live = document(id, TrackedDocument.Status.COMPLETED);
        UUID failed = document(id, TrackedDocument.Status.FAILED);
        UUID deleted = document(id, TrackedDocument.Status.DELETED);

        List<UUID> ids = tx(r -> r.documents().findIdsByConnectorIdAndStatusNot(id, TrackedDocument.Status.DELETED));
        int requeued = tx(r -> r.documents().requeueLiveForReindex(id, true,
                TrackedDocument.Status.QUEUED, TrackedDocument.Status.DELETED));

        assertThat(ids).containsExactlyInAnyOrder(live, failed);
        assertThat(requeued).isEqualTo(2);
        assertThat(status(live)).isEqualTo(TrackedDocument.Status.QUEUED);
        assertThat(status(failed)).isEqualTo(TrackedDocument.Status.QUEUED);
        assertThat(status(deleted)).isEqualTo(TrackedDocument.Status.DELETED);
    }

    @Test
    @DisplayName("retrying failed documents resets their budget; the auto-retry spends one, all at once")
    void failedDocumentsRequeue() {
        UUID id = source();
        UUID a = document(id, TrackedDocument.Status.FAILED);
        UUID b = document(id, TrackedDocument.Status.FAILED);
        UUID done = document(id, TrackedDocument.Status.COMPLETED);

        int retried = tx(r -> r.documents().requeueForRetry(List.of(a, b, done),
                TrackedDocument.Status.QUEUED, TrackedDocument.Status.FAILED));
        assertThat(retried).isEqualTo(2);
        TrackedDocument first = tx(r -> r.documents().findById(a).orElseThrow());
        assertThat(first.getStatus()).isEqualTo(TrackedDocument.Status.QUEUED);
        assertThat(first.getRetryCount()).isEqualTo(2);
        assertThat(first.getFailureMessage()).isNull();
        assertThat(status(done)).isEqualTo(TrackedDocument.Status.COMPLETED);

        tx(r -> {
            TrackedDocument d = r.documents().findById(a).orElseThrow();
            d.setStatus(TrackedDocument.Status.FAILED);
            return null;
        });
        int reset = tx(r -> r.documents().requeueFailed(id, TrackedDocument.Status.QUEUED, TrackedDocument.Status.FAILED));
        assertThat(reset).isEqualTo(1);
        assertThat(tx(r -> r.documents().findById(a).orElseThrow()).getRetryCount()).isZero();
    }

    private TrackedDocument.Status status(UUID doc) {
        return tx(r -> r.documents().findById(doc).orElseThrow()).getStatus();
    }
}
