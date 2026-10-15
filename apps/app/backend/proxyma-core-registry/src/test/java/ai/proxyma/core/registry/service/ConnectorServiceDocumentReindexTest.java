package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-indexing ONE document: what it resets, who may ask, and the one state in which it must refuse.
 *
 * <p>The refusal is the point. The drain loop owns a row for the whole of DOWNLOADING ->
 * CONVERTING -> EMBEDDING and writes its status, chunk counts and reindexReplace flag back as it
 * goes, so resetting them underneath it does not cancel that run - the reset is simply lost, and
 * the losing combination (reindexReplace cleared, old chunks still in the index) leaves the
 * document indexed twice.
 */
class ConnectorServiceDocumentReindexTest {

    private static final UUID CONNECTOR_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    private RegisteredConnectorRepository connectors;
    private TrackedDocumentRepository documents;
    private ConnectorService service;
    private RegisteredConnector connector;
    private TrackedDocument document;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        documents = mock(TrackedDocumentRepository.class);
        service = new ConnectorService(connectors, documents, mock(SyncOrchestratorService.class),
                mock(VectorStoreService.class), mock(BM25IndexService.class),
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class),
                mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class),
                mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class), null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));

        connector = new RegisteredConnector();
        connector.setId(CONNECTOR_ID);
        connector.setUserId("owner");
        when(connectors.findById(CONNECTOR_ID)).thenReturn(Optional.of(connector));

        document = new TrackedDocument();
        document.setId(DOCUMENT_ID);
        document.setConnectorId(CONNECTOR_ID);
        document.setStatus(TrackedDocument.Status.COMPLETED);
        document.setTotalChunks(42);
        document.setCurrentChunks(42);
        document.setLastIndexedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
    }

    @Test
    @DisplayName("an indexed document is requeued to replace its own chunks")
    void queuesTheDocumentForReplacement() {
        service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "owner");

        assertEquals(TrackedDocument.Status.QUEUED, document.getStatus());
        assertTrue(document.isReindexReplace(),
                "without this the new chunks are added beside the old ones, not instead of them");
        assertEquals(0, document.getTotalChunks());
        assertEquals(0, document.getCurrentChunks());
        assertNull(document.getLastIndexedAt());
        assertNotNull(document.getLastTouchedAt(),
                "an explicit re-index request is one of the two things that moves a row up the list");
        verify(documents).save(document);
    }

    @Test
    @DisplayName("a document that has exhausted its retries gets a fresh budget")
    void resetsTheRetryBudget() {
        document.setStatus(TrackedDocument.Status.FAILED);
        document.setRetryCount(TrackedDocument.MAX_AUTO_RETRIES);
        document.setNextRetryAt(Instant.parse("2026-09-01T00:00:00Z"));
        document.setFailureMessage("No extractable text content (empty after conversion)");

        service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "owner");

        // reconcileItem leaves an exhausted FAILED item alone. Without the reset the request is
        // accepted and then quietly undone on the next sync - which is what "Retry failed" did
        // before it got the same fix.
        assertEquals(0, document.getRetryCount());
        assertNull(document.getNextRetryAt());
        assertNull(document.getFailureMessage());
    }

    @Test
    @DisplayName("the owner's processed count is recomputed, or the drain loop never picks it up")
    void recomputesProcessedCountSoThereIsABacklog() {
        connector.setItemCount(10);
        connector.setProcessedCount(10);
        when(documents.countByConnectorIdAndStatus(CONNECTOR_ID, TrackedDocument.Status.COMPLETED))
                .thenReturn(9L);

        service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "owner");

        // The drain loop skips a source where itemCount <= processedCount + failedCount. Leaving
        // the stale 10 there makes this request look accepted and do nothing at all.
        assertEquals(9, connector.getProcessedCount());
        verify(connectors).save(connector);
    }

    @Test
    @DisplayName("refused while the document is mid-run")
    void refusesWhileTheDocumentIsBeingIndexed() {
        for (TrackedDocument.Status active : new TrackedDocument.Status[] {
                TrackedDocument.Status.DOWNLOADING,
                TrackedDocument.Status.CONVERTING,
                TrackedDocument.Status.EMBEDDING}) {
            document.setStatus(active);

            assertThrows(IllegalStateException.class,
                    () -> service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "owner"),
                    active + " is the drain loop's row to write, not ours");
        }
        verify(documents, never()).save(any());
    }

    @Test
    @DisplayName("owner only - a shared source is readable by others, not re-indexable by them")
    void refusesANonOwner() {
        connector.setShared(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "somebody-else"));
        verify(documents, never()).save(any());
    }

    @Test
    @DisplayName("a document of another source is not re-indexable through this one")
    void refusesADocumentOfAnotherConnector() {
        document.setConnectorId(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class,
                () -> service.reindexDocument(CONNECTOR_ID, DOCUMENT_ID, "owner"));
        verify(documents, never()).save(any());
    }
}
