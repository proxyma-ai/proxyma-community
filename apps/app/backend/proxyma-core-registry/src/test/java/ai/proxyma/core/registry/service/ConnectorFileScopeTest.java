package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnection;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The file-scope knobs a folder has - file types and subfolders - on the remote file-based types.
 *
 * <p>Registration and the edit dialog are two doors onto the same two columns, so both are pinned:
 * the stored list is FileTypeSupport's normalized form whichever door it came through, the
 * subfolder switch carries the folder's tier clamp through both, and a type with no subfolders to
 * skip refuses the switch rather than storing a setting its connector would ignore.
 */
class ConnectorFileScopeTest {

    private RegisteredConnectorRepository connectors;
    private FeatureGateService gate;
    private ConnectorService service;
    private ConnectorConnectionService connections;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        gate = mock(FeatureGateService.class);
        connections = mock(ConnectorConnectionService.class);
        ResolvedConnection svn = new ResolvedConnection(
                UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER, "Repo", ConnectionProtocol.SVN,
                "https://svn.example/repos", "me", "pw", true, true, false, null, null, null);
        when(connections.defaultFor(any(), any())).thenReturn(Optional.of(svn));
        when(connections.resolveOwned(any(), any())).thenReturn(Optional.of(svn));
        ResolvedConnection share = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER, "Files",
                ConnectionProtocol.NETWORK_SHARE, "fs1.corp.example", "CORP\\me", "pw",
                true, true, false, null, null, null);
        when(connections.resolveAll(any(), eq(ConnectionProtocol.NETWORK_SHARE))).thenReturn(java.util.List.of(share));
        when(connections.resolveOwned(any(), eq(share.id()))).thenReturn(Optional.of(share));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), connections, null, gate,
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
    }

    @Test
    @DisplayName("a network share registered with file types stores the normalized list")
    void registrationStoresNormalizedFileTypes() {
        service.registerNetworkShare("\\\\fs1.corp.example\\share\\docs", true, null, " .PDF, docx ,pdf");

        RegisteredConnector saved = savedRow();
        assertEquals(ConnectorType.NETWORK_SHARE, saved.getConnectorType());
        assertEquals("pdf,docx", saved.getFileTypes());
        assertTrue(saved.isRecursive());
    }

    @Test
    @DisplayName("subfolders on an SVN source are what was asked for - no tier clamps them since the two tiers")
    void svnRecursionIsKept() {
        service.registerSvnRepo("https://svn.example/repos/demo", null, null, null, true, "");

        RegisteredConnector saved = savedRow();
        assertTrue(saved.isRecursive());
        assertNull(saved.getFileTypes(), "blank must mean every supported type, stored as null");
    }

    /**
     * An uploaded folder's subfolders are files somebody uploaded on purpose, stored and counted
     * against their quota. A tier's recursive-scan switch once left them stored and never indexed;
     * since the two-tier model there is no such switch, and they are kept on every edition.
     */
    @Test
    @DisplayName("an uploaded folder keeps its subfolders")
    void uploadedFolderKeepsItsSubfolders(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        FileSourceMode uploads = mock(FileSourceMode.class);
        when(uploads.uploadsEnabled()).thenReturn(true);
        SyncOrchestratorService probe = mock(SyncOrchestratorService.class);
        when(probe.verifyAccess(any())).thenReturn(AccessCheck.verified());
        ConnectorService server = new ConnectorService(connectors, mock(TrackedDocumentRepository.class),
                probe, mock(VectorStoreService.class), mock(BM25IndexService.class),
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class), mock(AppPaths.class),
                mock(SettingsService.class), mock(ConnectorEndpointResolver.class), connections, null, gate,
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), uploads);

        server.registerFiles("Contracts", root.resolve("Contracts").toString(), "u1");

        RegisteredConnector saved = savedRow();
        assertEquals(ConnectorType.UPLOADED_FILES, saved.getConnectorType());
        assertTrue(saved.isRecursive());
    }

    /**
     * The probe and every sync send the connection's credential to the source's URL, so a URL on
     * another server - one the agent was handed in a document - would hand the password to that
     * server. Refused before anything is probed or saved.
     */
    @Test
    @DisplayName("an SVN URL on a server other than the connection's is refused before it is probed")
    void svnUrlOnAnotherServerIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.registerSvnRepo("https://svn.attacker.example/repos/demo", null, null, null, true, null));
        assertTrue(e.getMessage().contains("not on the server of the SVN connection"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.registerSvnRepo("https://svn.example/other/demo", null, null, null, true, null));
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("the edit dialog can change a SharePoint site's file types, and is refused a subfolder switch")
    void sharePointScopeEdits() {
        // A type with a file-type filter and no subfolders to skip, as SharePoint's descriptor says -
        // which is a Proxyma Enterprise module's, so a stand-in carrying the two traits is wired here.
        ai.proxyma.core.connector.spi.ConnectorDescriptor sharePoint =
                ai.proxyma.core.connector.spi.ConnectorDescriptor.builder(ConnectorType.SHAREPOINT)
                        .displayName("SharePoint")
                        .capabilities(ai.proxyma.core.connector.spi.Capabilities.builder().fileTypeFilter().build())
                        .build();
        service.setCatalog(ai.proxyma.core.connector.spi.ConnectorCatalog.of(java.util.List.of(
                new ai.proxyma.core.connector.spi.ConnectorPlugin() {
                    @Override public ai.proxyma.core.connector.spi.ConnectorDescriptor descriptor() { return sharePoint; }
                    @Override public ai.proxyma.core.connector.spi.ConnectorSession open(
                            ai.proxyma.core.connector.spi.SourceContext context) { throw new UnsupportedOperationException(); }
                }), java.util.List.of()));
        RegisteredConnector site = existing(ConnectorType.SHAREPOINT);

        service.updateConnectorConfig(site.getId(), Map.of("fileTypes", "xlsx,.PDF"), null);
        assertEquals("xlsx,pdf", site.getFileTypes());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateConnectorConfig(site.getId(), Map.of("recursive", false), null));
    }

    @Test
    @DisplayName("the edit dialog can narrow an SVN source to one folder level and clear its file types")
    void svnScopeEdits() {
        RegisteredConnector repo = existing(ConnectorType.SVN);
        repo.setFileTypes("pdf");

        service.updateConnectorConfig(repo.getId(), Map.of("recursive", false, "fileTypes", ""), null);

        assertFalse(repo.isRecursive());
        assertNull(repo.getFileTypes());
    }

    @Test
    @DisplayName("Jira has no file scope: the edit path refuses file types for it")
    void nonFileTypesIgnoreFileTypes() {
        RegisteredConnector project = existing(ConnectorType.JIRA);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateConnectorConfig(project.getId(), Map.of("fileTypes", "pdf"), null));
        verify(connectors, never()).save(any());
    }

    private RegisteredConnector existing(ConnectorType type) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(UUID.randomUUID());
        r.setConnectorType(type);
        r.setName("source");
        r.setConfig("https://example");
        when(connectors.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private RegisteredConnector savedRow() {
        ArgumentCaptor<RegisteredConnector> captor = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(connectors).save(captor.capture());
        return captor.getValue();
    }
}
