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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A source's first sync runs once, and its note always fits.
 *
 * <p>Two faults found together on 2026-10-02, by crawling a public wiki. A source that has never
 * finished a sync is "due" at every scheduler tick, and a tick that found it already running was
 * remembered as a follow-up - so every first sync longer than a minute was followed at once by a
 * second complete one. And the note a crawl leaves names the pages that would not load: past 500
 * characters the database refused it, at the very end of the pass, so the pass was recorded as
 * failed, the source stayed due, and it crawled the whole site again, for ever.
 */
class FirstSyncRunsOnceTest {

    private final UUID id = UUID.randomUUID();
    private final RegisteredConnector connector = new RegisteredConnector();
    private final RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger passes = new AtomicInteger();
    private SyncOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        connector.setId(id);
        connector.setConnectorType(ConnectorType.WEB_PAGE);
        connector.setName("A wiki");
        connector.setConfig("seedUrl=https://wiki.example/page");
        when(connectors.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(connector)));
        when(connectors.findById(id)).thenReturn(Optional.of(connector));
        // A pass that is still listing its source until the test lets it finish.
        doAnswer(inv -> {
            passes.incrementAndGet();
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(syncService).reconcile(any(ai.proxyma.core.connector.spi.ConnectorSession.class), any(), any(), anyBoolean());
        orchestrator = new SyncOrchestratorService(List.of(ListedSources.plugin(ConnectorType.WEB_PAGE)), syncService, connectors,
                mock(TrackedDocumentRepository.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), mock(FeatureGateService.class), null);
    }

    private void finishAndSettle() throws InterruptedException {
        release.countDown();
        for (int i = 0; i < 100 && orchestrator.isSyncing(id); i++) Thread.sleep(20);
        // A follow-up starts from the finished pass's own thread; give one the time to show itself.
        Thread.sleep(300);
        for (int i = 0; i < 100 && orchestrator.isSyncing(id); i++) Thread.sleep(20);
    }

    @Test
    @DisplayName("a scheduler tick during a first sync does not order a second one")
    void aTickDuringTheFirstPassOrdersNothing() throws InterruptedException {
        orchestrator.scheduledSync();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // A minute later, and a minute after that: never synced, so still "due".
        orchestrator.scheduledSync();
        orchestrator.scheduledSync();
        finishAndSettle();

        assertThat(passes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sync somebody asked for during a pass is still remembered")
    void aRequestDuringAPassIsRemembered() throws InterruptedException {
        orchestrator.scheduledSync();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(orchestrator.syncConnectorReportStarted(id, null)).isFalse();
        finishAndSettle();

        assertThat(passes.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a note longer than its column is cut to fit, not refused by the database")
    void aLongNoteIsCutToTheColumn() {
        String address = "https://wiki.example/spaces/TEAM/pages/123456789/" + "A+very+long+page+title+".repeat(8);
        String note = "stopped at the 100-page limit with 268 link(s) still to visit; 7 pages could not be "
                + "loaded: the server answered 403 Forbidden - it needs a login or blocks this crawler ("
                + address + " and 4 more); the server answered 404 Not Found - the page no longer exists ("
                + address + " and 1 more)";
        assertThat(note.length()).isGreaterThan(RegisteredConnector.LAST_SYNC_ERROR_MAX);

        connector.setLastSyncError(note);

        assertThat(connector.getLastSyncError()).hasSize(RegisteredConnector.LAST_SYNC_ERROR_MAX);
        assertThat(connector.getLastSyncError()).startsWith("stopped at the 100-page limit").endsWith("...");

        connector.setLastSyncError("stopped at the 100-page limit");
        assertThat(connector.getLastSyncError()).isEqualTo("stopped at the 100-page limit");
        connector.setLastSyncError(null);
        assertThat(connector.getLastSyncError()).isNull();
    }
}
