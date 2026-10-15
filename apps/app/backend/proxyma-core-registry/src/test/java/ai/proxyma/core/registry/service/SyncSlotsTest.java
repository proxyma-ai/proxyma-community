package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sources do not all sync at once (maintainer's feedback on 0.26.0, 2026-10-04). Passes the
 * scheduler starts wait for one of a fixed number of slots, in the order they became due; a
 * source somebody pressed "Sync now" on starts at once.
 */
class SyncSlotsTest {

    private final RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final List<RegisteredConnector> all = new ArrayList<>();
    // Each running pass holds until its latch is released.
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
            c.setConfig("seedUrl=https://site" + i + ".example/");
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
        orchestrator = new SyncOrchestratorService(List.of(ListedSources.plugin(ConnectorType.WEB_PAGE)), syncService, connectors,
                mock(TrackedDocumentRepository.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), mock(FeatureGateService.class), null);
        orchestrator.setMaxConcurrentSyncs(2);
    }

    @AfterEach
    void releaseAll() {
        release.values().forEach(CountDownLatch::countDown);
    }

    private void awaitRunning(int n) throws InterruptedException {
        for (int i = 0; i < 250 && running.size() != n; i++) Thread.sleep(20);
        // Long enough for a pass that should NOT have started to show itself.
        Thread.sleep(200);
    }

    @Test
    @DisplayName("three sources due with two slots: two list, the third waits until one ends")
    void theThirdWaitsForASlot() throws InterruptedException {
        orchestrator.scheduledSync();
        awaitRunning(2);

        assertThat(running).containsExactlyInAnyOrder(all.get(0).getId(), all.get(1).getId());
        assertThat(orchestrator.waitingCount()).isEqualTo(1);
        assertThat(orchestrator.isSyncing(all.get(2).getId())).isFalse();

        // A tick while it waits does not start it either.
        orchestrator.scheduledSync();
        awaitRunning(2);
        assertThat(ran).doesNotContain(all.get(2).getId());

        release.get(all.get(0).getId()).countDown();
        awaitRunning(2);

        assertThat(running).contains(all.get(2).getId());
        assertThat(orchestrator.waitingCount()).isZero();
    }

    @Test
    @DisplayName("\"Sync now\" on a source starts it at once, whatever the scheduler is running")
    void syncNowDoesNotWait() throws InterruptedException {
        orchestrator.scheduledSync();
        awaitRunning(2);
        assertThat(orchestrator.waitingCount()).isEqualTo(1);

        assertThat(orchestrator.syncConnectorReportStarted(all.get(2).getId(), null)).isTrue();
        awaitRunning(3);

        assertThat(running).hasSize(3);
        assertThat(orchestrator.waitingCount()).isZero();
    }
}
