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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Removing a source removes the half-built shadow of a re-index it interrupted.
 *
 * <p>A model change re-embeds into a disposable shadow collection so the live one stays searchable
 * until the swap. Remove the source mid-way and the live collection is deleted, the registry row
 * that NAMES the shadow is deleted with it, and the shadow itself survives - taking real storage in
 * the one store both editions share, with nothing left anywhere that knows it exists.
 * performReindex() clears a stale shadow before starting a new one, but that only ever runs for a
 * source still there to re-index.
 *
 * <p>Driven through deleteAllByUserId(), which is the synchronous entry into the same teardown the
 * interactive delete runs on a virtual thread.
 */
class ConnectorRemovalShadowCollectionTest {

    private static final UUID CONNECTOR_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String LIVE_COLLECTION = "11111111222233334444555555555555";
    private static final String SHADOW_COLLECTION = LIVE_COLLECTION + "_reindex";

    private RegisteredConnectorRepository connectors;
    private VectorStoreService vectors;
    private BM25IndexService bm25;
    private ConnectorService service;
    private RegisteredConnector connector;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        vectors = mock(VectorStoreService.class);
        bm25 = mock(BM25IndexService.class);
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class),
                mock(SyncOrchestratorService.class), vectors, bm25,
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class),
                mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class),
                mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class), null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));

        connector = new RegisteredConnector();
        connector.setId(CONNECTOR_ID);
        connector.setUserId("owner");
        connector.setConnectorType(ai.proxyma.core.connector.ConnectorType.JIRA);
        when(connectors.findByUserId("owner")).thenReturn(List.of(connector));
    }

    @Test
    @DisplayName("an interrupted re-index's shadow collection goes with the source")
    void deletesTheShadowCollectionToo() {
        connector.setReindexInProgress(true);
        connector.setReindexShadowCollectionId(SHADOW_COLLECTION);

        service.deleteAllByUserId("owner");

        verify(vectors).deleteCollection(LIVE_COLLECTION);
        verify(bm25).deleteCollection(LIVE_COLLECTION);
        verify(vectors).deleteCollection(SHADOW_COLLECTION);
        verify(bm25).deleteCollection(SHADOW_COLLECTION);
    }

    @Test
    @DisplayName("a source with no re-index in flight has nothing extra deleted")
    void deletesOnlyTheLiveCollectionOtherwise() {
        service.deleteAllByUserId("owner");

        verify(vectors).deleteCollection(LIVE_COLLECTION);
        verify(bm25).deleteCollection(LIVE_COLLECTION);
        // A shadow id derived rather than read would delete a collection nobody built - harmless
        // today, and exactly the kind of guess that stops being harmless when the naming changes.
        verify(vectors, never()).deleteCollection(SHADOW_COLLECTION);
        verify(bm25, never()).deleteCollection(SHADOW_COLLECTION);
    }
}
