package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How sync decides what to run next, and how it writes what it learned.
 *
 * <ul>
 *   <li>The auto-retry sweep looked at a source's backlog once per failed ROW, and the first row it
 *       requeued was a backlog to every other: one retry per source a minute.</li>
 *   <li>A source whose passes failed was due on every one-minute tick, for ever - a whole listing
 *       and an audit row a minute.</li>
 *   <li>Sync saved the source's whole row after every item, putting back edits made meanwhile, and
 *       the drain restamped the CURRENT embedding model on a source indexed with another.</li>
 * </ul>
 */
class SyncSchedulingTest {

    private RegisteredConnectorRepository connectors;
    private TrackedDocumentRepository documents;
    private SyncService syncService;
    private SyncOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        documents = mock(TrackedDocumentRepository.class);
        syncService = mock(SyncService.class);
        orchestrator = new SyncOrchestratorService(List.of(ListedSources.plugin(ConnectorType.WEB_PAGE)), syncService, connectors, documents,
                mock(SettingsService.class), mock(ConnectorEndpointResolver.class), mock(FeatureGateService.class), null);
    }

    private static TrackedDocument failed(UUID source) {
        TrackedDocument d = new TrackedDocument();
        d.setId(UUID.randomUUID());
        d.setConnectorId(source);
        d.setStatus(TrackedDocument.Status.FAILED);
        return d;
    }

    @Test
    @DisplayName("every due failure of a source with no backlog is retried in the same sweep")
    void dueFailuresAreRetriedTogether() {
        UUID idle = UUID.randomUUID();
        UUID busy = UUID.randomUUID();
        TrackedDocument a = failed(idle);
        TrackedDocument b = failed(idle);
        TrackedDocument c = failed(busy);
        when(documents.findByStatusAndRetryCountLessThanAndNextRetryAtBefore(any(), any(Integer.class), any()))
                .thenReturn(List.of(a, b, c));
        when(documents.countByConnectorIdAndStatusIn(eq(idle), any())).thenReturn(0L);
        when(documents.countByConnectorIdAndStatusIn(eq(busy), any())).thenReturn(4L);
        List<Collection<UUID>> requeued = new ArrayList<>();
        when(documents.requeueForRetry(any(), any(), any())).thenAnswer(inv -> {
            requeued.add(new ArrayList<>(inv.<Collection<UUID>>getArgument(0)));
            return requeued.get(requeued.size() - 1).size();
        });

        orchestrator.autoRetryFailedItems();

        assertThat(requeued).hasSize(1);
        assertThat(requeued.get(0)).containsExactlyInAnyOrder(a.getId(), b.getId());
        // One backlog check per source, not per row.
        verify(documents, times(1)).countByConnectorIdAndStatusIn(eq(idle), any());
    }

    @Test
    @DisplayName("a source whose pass failed is not listed again on the next tick, but \"Sync now\" still runs")
    void failingSourceBacksOff() throws Exception {
        RegisteredConnector site = new RegisteredConnector();
        site.setId(UUID.randomUUID());
        site.setConnectorType(ConnectorType.WEB_PAGE);
        site.setName("Docs");
        when(connectors.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(site)));
        when(connectors.findById(site.getId())).thenReturn(Optional.of(site));
        when(syncService.reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean()))
                .thenReturn(new SyncService.PassResult(0, 0, 0, false, false, "The site did not answer"));

        orchestrator.scheduledSync();
        verify(syncService, timeout(2000).times(1)).reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean());
        for (int i = 0; i < 100 && orchestrator.isSyncing(site.getId()); i++) Thread.sleep(20);

        orchestrator.scheduledSync();
        Thread.sleep(300);
        verify(syncService, times(1)).reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean());

        assertThat(orchestrator.syncConnectorReportStarted(site.getId(), null, true)).isTrue();
        verify(syncService, timeout(2000).times(2)).reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a pass writes the source's columns by targeted update and never saves its row back")
    void syncNeverSavesTheRow() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getEmbeddingModel()).thenReturn("model-b");
        when(settings.getEmbeddingProvider()).thenReturn("OPENAI");
        SyncService sync = new SyncService(documents, connectors, null, mock(EmbeddingService.class),
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class), mock(AppPaths.class),
                settings, null, mock(VectorStoreService.class), mock(BM25IndexService.class),
                mock(FileMetadataRepository.class), null, mock(TelemetryShipper.class));
        RegisteredConnector site = new RegisteredConnector();
        site.setId(UUID.randomUUID());
        site.setConnectorType(ConnectorType.WEB_PAGE);
        site.setStatus(RegisteredConnector.Status.ACTIVE);
        // Indexed with another model, and never reindexed since.
        site.setIndexedWithEmbeddingModel("model-a");
        when(connectors.findById(site.getId())).thenReturn(Optional.of(site));
        ItemMetadata page = new ItemMetadata();
        page.setId("https://docs.example.test/");
        page.setTitle("Docs");
        page.setLastModified(Instant.now());
        when(documents.findBySourceIdAndConnectorId(any(), any())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sync.reconcile(ListedSources.listing(List.of(page).iterator()), ListedSources.descriptor(ConnectorType.WEB_PAGE),
                site, true);
        sync.onConnectorDrained(site.getId());

        verify(connectors, never()).save(any());
        verify(connectors, org.mockito.Mockito.atLeastOnce())
                .updateCounts(eq(site.getId()), any(Integer.class), any(Integer.class), any(Integer.class));
        verify(connectors).markFullySynced(eq(site.getId()), any(), any());
        // Stamped only where none is recorded (the statement's own condition), never overwritten.
        verify(connectors).stampFirstEmbeddingModel(site.getId(), "model-b", "OPENAI");
        verify(connectors, never()).finishReindex(any(), any(), any());
    }
}
