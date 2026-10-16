package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestrator's half of stopping sync work before what it stands on (0.36.0 feedback): on the
 * context's close nothing new starts, and the stop waits for the passes and drains still running -
 * alongside the web server's graceful shutdown - before ChromaDB and the databases are destroyed.
 */
class SyncShutdownOrchestratorTest {

    private final RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final List<RegisteredConnector> all = new ArrayList<>();
    private final Map<UUID, CountDownLatch> release = new ConcurrentHashMap<>();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ran = ConcurrentHashMap.newKeySet();
    private SyncOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < 3; i++) {
            RegisteredConnector c = new RegisteredConnector();
            c.setId(UUID.randomUUID());
            c.setConnectorType(ConnectorType.WEB_PAGE);
            c.setName("Site " + i);
            all.add(c);
            release.put(c.getId(), new CountDownLatch(1));
            when(connectors.findById(c.getId())).thenReturn(Optional.of(c));
        }
        when(connectors.findAll()).thenAnswer(inv -> new ArrayList<>(all));
        doAnswer(inv -> {
            RegisteredConnector c = inv.getArgument(2);
            running.add(c.getId());
            ran.add(c.getId());
            release.get(c.getId()).await(10, TimeUnit.SECONDS);
            running.remove(c.getId());
            return null;
        }).when(syncService).reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean());
        orchestrator = new SyncOrchestratorService(List.of(ListedSources.plugin(ConnectorType.WEB_PAGE)), syncService,
                connectors, mock(TrackedDocumentRepository.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), mock(FeatureGateService.class), null);
        orchestrator.setMaxConcurrentSyncs(2);
    }

    private void awaitRunning(int n) throws InterruptedException {
        for (int i = 0; i < 250 && running.size() != n; i++) Thread.sleep(20);
    }

    @Test
    @DisplayName("stops starting work, waits for what runs, and starts nothing that was waiting")
    void stopWaitsForRunningWorkAndStartsNothing() throws InterruptedException {
        orchestrator.start();
        orchestrator.scheduledSync();
        awaitRunning(2);
        assertThat(orchestrator.waitingCount()).isEqualTo(1);

        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean stoppedEarly = new AtomicBoolean();
        orchestrator.onContextClosed();
        orchestrator.stop(stopped::countDown);
        verify(syncService).stopForShutdown();
        // Nothing new starts once stopping, whoever asks.
        assertThat(orchestrator.syncConnectorReportStarted(all.get(2).getId(), null)).isFalse();

        release.get(all.get(0).getId()).countDown();
        Thread.sleep(200);
        stoppedEarly.set(stopped.getCount() == 0);
        release.get(all.get(1).getId()).countDown();

        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stoppedEarly).isFalse();          // it waited for the second pass
        assertThat(orchestrator.hasRunningWork()).isFalse();
        assertThat(orchestrator.isRunning()).isFalse();
        assertThat(ran).doesNotContain(all.get(2).getId()); // the waiting pass never started
    }

    @Test
    @DisplayName("ticks after the close start no pass and no drain")
    void ticksAfterCloseDoNothing() throws InterruptedException {
        orchestrator.onContextClosed();
        orchestrator.scheduledSync();
        orchestrator.drainQueuedConnectors();
        Thread.sleep(200);

        assertThat(ran).isEmpty();
        verify(connectors, never()).findAll();
    }
}
