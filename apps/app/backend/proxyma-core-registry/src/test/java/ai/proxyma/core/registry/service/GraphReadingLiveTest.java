package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "The graph is reading this item" means the worker has it OPEN - not that a progress row says so.
 *
 * <p>A row is marked EXTRACTING when a document is opened and stays that way if the process stops
 * mid-document, until the worker reaches it again - which with sync paused or the model down can be
 * never. Since 2026-09-24 a source card spins and the item list pins a row off this answer, so a
 * stale row would be a spinner that never stops on a source nothing is working on.
 */
class GraphReadingLiveTest {

    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID LIVE = UUID.randomUUID();
    private static final UUID STALE = UUID.randomUUID();

    private KnowledgeGraphService graph;
    private TrackedDocumentRepository documents;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        graph = mock(KnowledgeGraphService.class);
        documents = mock(TrackedDocumentRepository.class);
        service = new ConnectorService(mock(RegisteredConnectorRepository.class), documents,
                mock(SyncOrchestratorService.class), mock(VectorStoreService.class), mock(BM25IndexService.class),
                graph, mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class),
                mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class), null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));

        when(documents.countByConnectorIdAndStatus(eq(CONNECTOR), any())).thenReturn(10L);
        when(documents.findAllById(any())).thenReturn(List.of());
        when(graph.progressFor(CONNECTOR)).thenReturn(new KnowledgeGraphService.DocumentProgress(
                3, 0, 7 + 40, 20 + 100,
                List.of(new KnowledgeGraphService.Reading(LIVE, 7, 20),
                        new KnowledgeGraphService.Reading(STALE, 40, 100))));
    }

    @Test
    @DisplayName("a row the worker does not have open is not reported as being read, nor counted in the passages")
    void staleRowIsDropped() {
        GraphExtractionOrchestrator worker = mock(GraphExtractionOrchestrator.class);
        when(worker.isExtracting(LIVE)).thenReturn(true);
        when(worker.isExtracting(STALE)).thenReturn(false);
        service.setGraphWorker(worker);

        ConnectorService.GraphProgress p = service.graphProgress(CONNECTOR);

        assertEquals(List.of(LIVE), p.reading().stream().map(ConnectorService.ReadingItem::documentId).toList());
        assertEquals(7, p.chunksDone());
        assertEquals(20, p.chunkTotal());
    }

    @Test
    @DisplayName("nothing open at all reads as nothing being read")
    void idleWorker() {
        service.setGraphWorker(mock(GraphExtractionOrchestrator.class));

        ConnectorService.GraphProgress p = service.graphProgress(CONNECTOR);

        assertEquals(0, p.reading().size());
        assertEquals(0, p.chunkTotal());
        // What has been read and what is left are not the worker's to say.
        assertEquals(3, p.extracted());
        assertEquals(7, p.pending());
    }
}
