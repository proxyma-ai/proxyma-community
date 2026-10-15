package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
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
 * Adding a source that is already there, from a dialog, is refused with a sentence that says so -
 * the maintainer's call, 2026-09-27. It used to re-sync the existing source and drop everything
 * the dialog had been given, while reporting success.
 *
 * <p>The other half is pinned too, because it is what an upload and the agent's register tools
 * depend on: outside {@link ConnectorService#asNewSource} the same call still hands
 * back the existing source.
 */
class SourceAlreadyRegisteredTest {

    private static final String SHARE = "\\\\fs1.corp.example\\share\\docs";

    private RegisteredConnectorRepository connectors;
    private ConnectorService service;
    private UUID existingId;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        RegisteredConnector existing = new RegisteredConnector();
        existingId = UUID.randomUUID();
        existing.setId(existingId);
        existing.setConnectorType(ConnectorType.NETWORK_SHARE);
        existing.setName(SHARE);
        existing.setConfig(SHARE);
        when(connectors.findAllByConnectorTypeAndNameIgnoreCase(eq(ConnectorType.NETWORK_SHARE), any()))
                .thenReturn(java.util.List.of(existing));

        // A share is registered through the owner's Network share connection for its server.
        var connections = mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class);
        var share = new ai.proxyma.core.settings.connection.ResolvedConnection(
                UUID.randomUUID(), "_local", "Files", ai.proxyma.core.connector.ConnectionProtocol.NETWORK_SHARE,
                "fs1.corp.example", "CORP\\me", "pw", true, true, false, null, null, null);
        when(connections.resolveAll(any(), eq(ai.proxyma.core.connector.ConnectionProtocol.NETWORK_SHARE)))
                .thenReturn(java.util.List.of(share));
        when(connections.resolveOwned(any(), eq(share.id()))).thenReturn(Optional.of(share));
        // The existing source already reads through it, as every share registered since
        // 2026-10-01 does.
        existing.setConnectionId(share.id());
        ConnectorEndpointResolver resolver = mock(ConnectorEndpointResolver.class);
        when(resolver.resolve(any(RegisteredConnector.class))).thenReturn(new ConnectorEndpointResolver.Endpoint(
                share.id(), share.name(), share.baseUrl(), share.credential(), true, false));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class),
                mock(SyncOrchestratorService.class), mock(VectorStoreService.class), mock(BM25IndexService.class),
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class), mock(AppPaths.class),
                mock(SettingsService.class), resolver,
                connections, null,
                mock(FeatureGateService.class), mock(RestClient.Builder.class), mock(TelemetryShipper.class),
                mock(FileSourceMode.class));
    }

    @Test
    @DisplayName("from a dialog, an existing source is refused by name, and nothing is saved")
    void aDialogIsToldTheSourceExists() {
        SourceAlreadyRegisteredException e = assertThrows(SourceAlreadyRegisteredException.class,
                () -> service.asNewSource(() -> service.registerNetworkShare(SHARE, true, null, "pdf")));
        assertTrue(e.getMessage().contains(SHARE), e.getMessage());
        assertTrue(e.getMessage().contains("already one of your sources"), e.getMessage());
        // The dialog builds its own sentence from the name, in the reader's language.
        assertEquals(SHARE, e.sourceName());
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("everywhere else, an existing source is still handed back to be re-synced")
    void otherCallersStillGetTheExistingSource() {
        assertEquals(existingId, service.registerNetworkShare(SHARE, true, null));
    }

    @Test
    @DisplayName("a refusal does not leak into the next registration on the same thread")
    void theFlagIsClearedAfterARefusal() {
        assertThrows(SourceAlreadyRegisteredException.class,
                () -> service.asNewSource(() -> service.registerNetworkShare(SHARE, true, null, null)));
        assertEquals(existingId, service.registerNetworkShare(SHARE, true, null));
    }
}
