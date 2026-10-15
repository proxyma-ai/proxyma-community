package ai.proxyma.core.registry.service;

import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnection;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A network share reads through a Network share CONNECTION since 2026-10-01, and only one for the
 * path's own server - the connection's server is the one host the source may reach, so this is the
 * credential boundary the admin allow-list used to be. Pinned: the right connection is picked by
 * host, a chosen connection for another server is refused, a path with no connection for its
 * server is refused with where to add one, and nothing is saved when it is refused.
 */
class NetworkShareRegistrationTest {

    private RegisteredConnectorRepository connectors;
    private ConnectorConnectionService connections;
    private ConnectorService service;

    private final ResolvedConnection fs1 = share("Engineering", "fs1.corp.example", true);
    private final ResolvedConnection fs2 = share("Finance", "fs2.corp.example", false);

    private static ResolvedConnection share(String name, String host, boolean isDefault) {
        return new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER, name,
                ConnectionProtocol.NETWORK_SHARE, host, "CORP\\me", "pw", isDefault, true, false, null, null, null);
    }

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(i -> {
            RegisteredConnector r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        connections = mock(ConnectorConnectionService.class);
        when(connections.resolveAll(any(), eq(ConnectionProtocol.NETWORK_SHARE))).thenReturn(List.of(fs1, fs2));
        when(connections.resolveOwned(any(), eq(fs1.id()))).thenReturn(Optional.of(fs1));
        when(connections.resolveOwned(any(), eq(fs2.id()))).thenReturn(Optional.of(fs2));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
    }

    @Test
    @DisplayName("the source stores its normalized path as its one field, as JSON")
    void theConfigIsThePathAsAField() {
        service.registerNetworkShare("//FS1.corp.example/share/docs", true, null, null, null);

        ArgumentCaptor<RegisteredConnector> saved = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(connectors, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RegisteredConnector row = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(java.util.Map.of("uncPath", row.getName()),
                ai.proxyma.core.connector.spi.ConfigJson.parse(row.getConfig()));
    }

    @Test
    @DisplayName("with no connection named, the one for the path's own server is used - not the default")
    void theConnectionIsPickedByHost() {
        service.registerNetworkShare("\\\\FS2.corp.example\\books\\2026", true, null, null, null);

        ArgumentCaptor<RegisteredConnector> saved = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(connectors, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RegisteredConnector row = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(ConnectorType.NETWORK_SHARE, row.getConnectorType());
        assertEquals(fs2.id(), row.getConnectionId());
    }

    @Test
    @DisplayName("a chosen connection for another server is refused, and nothing is saved")
    void aChosenConnectionMustBeForThePathsServer() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.registerNetworkShare("\\\\fs2.corp.example\\books", true, null, null, fs1.id()));
        assertTrue(e.getMessage().contains("fs1.corp.example") && e.getMessage().contains("fs2.corp.example"), e.getMessage());
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("a path on a server the caller has no connection for is refused with where to add one")
    void noConnectionForTheServerIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.registerNetworkShare("\\\\attacker.example\\x", true, null, null, null));
        assertTrue(e.getMessage().contains("attacker.example") && e.getMessage().contains("Settings > Connections"),
                e.getMessage());
        verify(connectors, never()).save(any());
    }
}
