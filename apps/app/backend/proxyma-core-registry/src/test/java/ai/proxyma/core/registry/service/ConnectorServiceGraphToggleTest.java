package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The graph is a plug-in: plugging it in or out changes the graph and nothing else.
 *
 * <p>Neither direction may re-sync, re-index or delete from the index. Switching ON used to be
 * useless on an indexed source without a re-index; that is fixed in GraphExtractionOrchestrator,
 * and this pins that the fix did not arrive as a re-index hidden behind the toggle. Switching OFF
 * deletes the source's graph - and must never reach its vectors, which are what search runs on.
 */
class ConnectorServiceGraphToggleTest {

    private static final UUID ID = UUID.randomUUID();

    private RegisteredConnectorRepository connectors;
    private TrackedDocumentRepository documents;
    private SyncOrchestratorService sync;
    private VectorStoreService vectors;
    private BM25IndexService bm25;
    private KnowledgeGraphService graph;
    private ConnectorService service;
    private RegisteredConnector connector;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        documents = mock(TrackedDocumentRepository.class);
        sync = mock(SyncOrchestratorService.class);
        vectors = mock(VectorStoreService.class);
        bm25 = mock(BM25IndexService.class);
        graph = mock(KnowledgeGraphService.class);
        service = new ConnectorService(connectors, documents, sync, vectors, bm25, graph,
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class),
                mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class), null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));

        connector = new RegisteredConnector();
        connector.setId(ID);
        when(connectors.findById(ID)).thenReturn(Optional.of(connector));
    }

    @Test
    @DisplayName("switching the graph on re-indexes nothing and deletes nothing")
    void switchingOnTouchesOnlyTheFlag() {
        connector.setGraphEnabled(false);

        service.setGraphEnabled(ID, true, null);

        verify(connectors).save(connector);
        verifyNoInteractions(vectors, bm25, sync);
        verify(documents, never()).save(any());
        verify(graph, never()).forgetConnector(any());
    }

    @Test
    @DisplayName("switching the graph off deletes the graph and leaves vectors, keyword index and documents alone")
    void switchingOffTouchesOnlyTheGraph() {
        connector.setGraphEnabled(true);

        service.setGraphEnabled(ID, false, null);

        verify(graph).forgetConnector(ID);
        verifyNoInteractions(vectors, bm25, sync);
        verify(documents, never()).save(any());
        verify(documents, never()).deleteAll(any());
    }
}
