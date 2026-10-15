package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.registry.controller.ConnectorController;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Sync now" runs a full pass, as it always has; {@code pass=changes} asks for the pass the scheduler
 * would run - a CHANGES pass where the source is eligible - so the incremental paths can be exercised
 * live. Eligibility is the one rule in SyncService, which the option does not loosen: a source that
 * never completed a full pass, a type with no incremental listing, or one due its daily full pass
 * still gets a full one.
 */
class SyncNowChangesPassTest {

    private final SyncOrchestratorService orchestrator = mock(SyncOrchestratorService.class);
    private final ConnectorController controller = new ConnectorController(mock(ConnectorService.class),
            mock(SettingsService.class), mock(ConnectorEndpointResolver.class), orchestrator,
            mock(FileMetadataRepository.class));

    private int sync(UUID id, String pass) {
        when(orchestrator.syncConnectorReportStarted(eq(id), any(), anyBoolean())).thenReturn(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/"));
        return controller.syncConnector(id, pass, exchange).block().getStatusCode().value();
    }

    @Test
    @DisplayName("Sync now is a forced full pass by default and with pass=full; pass=changes leaves the pass to the rule")
    void passOption() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertThat(sync(a, null)).isEqualTo(200);
        assertThat(sync(b, "full")).isEqualTo(200);
        assertThat(sync(c, "changes")).isEqualTo(200);
        verify(orchestrator).syncConnectorReportStarted(a, null, true);
        verify(orchestrator).syncConnectorReportStarted(b, null, true);
        verify(orchestrator).syncConnectorReportStarted(c, null, false);

        UUID d = UUID.randomUUID();
        assertThat(sync(d, "everything")).isEqualTo(400);
        verify(orchestrator, never()).syncConnectorReportStarted(eq(d), any(), anyBoolean());
    }

    private static ConnectorDescriptor descriptor(boolean incremental) {
        Capabilities.Builder caps = Capabilities.builder();
        if (incremental) caps.incremental();
        return ConnectorDescriptor.builder(ConnectorType.GITHUB).displayName("GitHub").capabilities(caps.build()).build();
    }

    private static RegisteredConnector source(boolean allFetched, Instant lastSynced, Instant lastFull) {
        RegisteredConnector r = new RegisteredConnector();
        r.setAllItemsFetched(allFetched);
        r.setLastSyncedAt(lastSynced);
        r.setLastFullSyncAt(lastFull);
        return r;
    }

    @Test
    @DisplayName("a CHANGES pass only for a source that has completed a full pass, lists incrementally and is not due its daily full pass")
    void eligibility() {
        Instant now = Instant.parse("2026-10-14T12:00:00Z");
        Instant hourAgo = now.minus(Duration.ofHours(1));
        RegisteredConnector ready = source(true, hourAgo, hourAgo);

        assertThat(SyncService.incrementalPass(ready, descriptor(true), false, now)).isTrue();
        // Forced full - the default "Sync now".
        assertThat(SyncService.incrementalPass(ready, descriptor(true), true, now)).isFalse();
        // The first pass, or one that never finished, lists everything.
        assertThat(SyncService.incrementalPass(source(false, hourAgo, hourAgo), descriptor(true), false, now)).isFalse();
        assertThat(SyncService.incrementalPass(source(true, null, hourAgo), descriptor(true), false, now)).isFalse();
        // A type with no incremental listing.
        assertThat(SyncService.incrementalPass(ready, descriptor(false), false, now)).isFalse();
        // The daily full pass that catches deletions is not skipped by asking for changes.
        assertThat(SyncService.incrementalPass(source(true, hourAgo, now.minus(Duration.ofDays(2))), descriptor(true),
                false, now)).isFalse();
        assertThat(SyncService.incrementalPass(source(true, hourAgo, null), descriptor(true), false, now)).isFalse();
    }
}
