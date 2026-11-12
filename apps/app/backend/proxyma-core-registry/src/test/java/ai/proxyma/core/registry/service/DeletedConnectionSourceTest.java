package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A source whose connection was deleted (2026-11-03: a connection in use may be deleted, and its sources
 * are kept - ConnectionUsage). They are told at once, in one update that touches nothing but their status
 * and note; their items are never deleted; and a sync of one says the same sentence rather than opening a
 * session with no credential and failing with whatever the connector throws.
 */
class DeletedConnectionSourceTest {

    private final UUID id = UUID.randomUUID();
    private final UUID goneConnection = UUID.randomUUID();
    private final RegisteredConnector source = new RegisteredConnector();
    private final RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
    private final TrackedDocumentRepository documents = mock(TrackedDocumentRepository.class);
    private final ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
    private final AtomicInteger sessions = new AtomicInteger();
    private SyncOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        source.setId(id);
        source.setConnectorType(ConnectorType.JIRA);
        source.setName("PROJ");
        source.setConnectionId(goneConnection);
        when(connectors.findById(id)).thenReturn(Optional.of(source));
        // The connection does not resolve any more.
        when(endpoints.resolve(any(RegisteredConnector.class))).thenReturn(ConnectorEndpointResolver.Endpoint.EMPTY);
        ConnectorPlugin jira = new ConnectorPlugin() {
            @Override
            public ConnectorDescriptor descriptor() {
                return CoreDescriptors.of(ConnectorType.JIRA);
            }

            @Override
            public ConnectorSession open(SourceContext context) {
                sessions.incrementAndGet();
                throw new IllegalStateException("no credential");
            }
        };
        orchestrator = new SyncOrchestratorService(List.of(jira), mock(SyncService.class), connectors, documents,
                mock(SettingsService.class), endpoints, mock(FeatureGateService.class), null);
    }

    @Test
    @DisplayName("deleting the connection marks its sources in one update - status and note only, no item touched")
    void deletionMarksTheSources() {
        RegistryConnectionUsage usage = new RegistryConnectionUsage(connectors);

        usage.connectionDeleted(goneConnection);

        verify(connectors).markConnectionGone(eq(goneConnection), eq(RegisteredConnector.Status.ERROR),
                eq(SyncOrchestratorService.CONNECTION_DELETED_MESSAGE));
        verifyNoInteractions(documents);
    }

    @Test
    @DisplayName("a YouTube source is removed with its deleted connection - YouTube's policies ask for the channel's data to go with the access - and others are kept")
    void youTubeSourcesGoWithTheConnection() {
        RegisteredConnector channel = new RegisteredConnector();
        channel.setId(UUID.randomUUID());
        channel.setConnectorType(ConnectorType.YOUTUBE_CHANNEL);
        channel.setName("My channel");
        channel.setConnectionId(goneConnection);
        when(connectors.findByConnectionIdAndConnectorType(goneConnection, ConnectorType.YOUTUBE_CHANNEL))
                .thenReturn(List.of(channel));
        List<UUID> removed = new java.util.ArrayList<>();
        RegistryConnectionUsage usage = new RegistryConnectionUsage(connectors);
        usage.setRemoverForTest(removed::add);

        usage.connectionDeleted(goneConnection);

        assertThat(removed).containsExactly(channel.getId());
        // The Jira source of the same connection was only marked, its items left alone.
        verify(connectors).markConnectionGone(eq(goneConnection), eq(RegisteredConnector.Status.ERROR),
                eq(SyncOrchestratorService.CONNECTION_DELETED_MESSAGE));
        verifyNoInteractions(documents);
    }

    @Test
    @DisplayName("the warning's names come from the sources reading through it, a page of them")
    void namesForTheWarning() {
        when(connectors.findNamesByConnectionId(eq(goneConnection), any(Pageable.class))).thenReturn(List.of("PROJ", "OPS"));

        assertThat(new RegistryConnectionUsage(connectors).sourceNamesUsing(goneConnection, 5)).containsExactly("PROJ", "OPS");
        assertThat(new RegistryConnectionUsage(connectors).sourceNamesUsing(goneConnection, 0)).isEmpty();
    }

    @Test
    @DisplayName("a sync of such a source opens no session and says its connection was deleted")
    void syncSaysTheConnectionWasDeleted() {
        assertThat(orchestrator.syncConnectorReportStarted(id, null)).isFalse();

        assertThat(sessions.get()).isZero();
        verify(connectors).updateSyncState(id, RegisteredConnector.Status.ERROR,
                "Its connection was deleted. Choose another connection in the source's settings.");
        verifyNoInteractions(documents);
    }

    @Test
    @DisplayName("a source that never named a connection still says it has none")
    void neverHadOne() {
        source.setConnectionId(null);

        assertThat(orchestrator.noConnectionMessage(source)).startsWith("This source has no ").endsWith("Settings > Connections.");
        source.setConnectionId(goneConnection);
        assertThat(orchestrator.noConnectionMessage(source)).isEqualTo(SyncOrchestratorService.CONNECTION_DELETED_MESSAGE);
    }
}
