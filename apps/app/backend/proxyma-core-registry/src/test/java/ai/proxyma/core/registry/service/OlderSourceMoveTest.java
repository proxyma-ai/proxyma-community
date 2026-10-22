package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.StoredConfig;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.connection.ConnectorConnection;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A source an older build registered moves to another connection like any other (0.36.3 feedback).
 * A Jira project's or a Bitbucket repository's config column was its server's URL and its key only
 * its name, so the key read as empty: the source's dialog would not save without one, and the key
 * typed in was refused as "a different source". And an SVN repository moves only to a connection
 * for its own server, as registration requires.
 */
class OlderSourceMoveTest {

    private static final ConnectorPlugin JIRA = new ConnectorPlugin() {
        @Override public ConnectorDescriptor descriptor() { return CoreDescriptors.of(ConnectorType.JIRA); }
        @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
        @Override public String identity(Map<String, String> config) {
            String key = config.get("projectKey");
            return key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        }
    };

    // The default identity: the required fields, PROJ/repo.
    private static final ConnectorPlugin BITBUCKET = new ConnectorPlugin() {
        @Override public ConnectorDescriptor descriptor() { return CoreDescriptors.of(ConnectorType.BITBUCKET); }
        @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
    };

    private final ResolvedConnection cloud = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER,
            "Cloud site", ConnectionProtocol.JIRA, "https://acme.atlassian.net", "ana@example.com", "tok",
            false, true, true, null, null, null);

    private final ResolvedConnection bitbucket = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER,
            "Bitbucket", ConnectionProtocol.BITBUCKET, "https://bb.corp.example", null, "pat",
            false, true, true, null, null, null);
    private final ResolvedConnection svnSameServer = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER,
            "SVN", ConnectionProtocol.SVN, "https://svn.corp.example/repos", "ana", "pw",
            false, true, true, null, null, null);
    private final ResolvedConnection svnOtherServer = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER,
            "Elsewhere", ConnectionProtocol.SVN, "https://svn.other.example", "ana", "pw",
            false, true, true, null, null, null);

    private RegisteredConnectorRepository connectors;
    private RegisteredConnector row;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(i -> i.getArgument(0));
        row = new RegisteredConnector();
        row.setId(UUID.randomUUID());
        row.setConnectorType(ConnectorType.JIRA);
        row.setName("RHEU");
        row.setConfig("https://jira.corp.example");
        row.setConnectionId(UUID.randomUUID());
        when(connectors.findById(row.getId())).thenReturn(Optional.of(row));

        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwned(any(), eq(cloud.id()))).thenReturn(Optional.of(cloud));
        when(connections.resolveOwned(any(), eq(bitbucket.id()))).thenReturn(Optional.of(bitbucket));
        when(connections.resolveOwned(any(), eq(svnSameServer.id()))).thenReturn(Optional.of(svnSameServer));
        when(connections.resolveOwned(any(), eq(svnOtherServer.id()))).thenReturn(Optional.of(svnOtherServer));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
        service.setCatalog(ConnectorCatalog.of(List.of(JIRA, BITBUCKET), List.of()));
    }

    @Test
    @DisplayName("its key is read from its name, so the dialog opens with it filled in")
    void keyReadFromName() {
        assertThat(StoredConfig.fields(ConnectorType.JIRA, row.getConfig(), row.getName()))
                .containsEntry("projectKey", "RHEU");
    }

    @Test
    @DisplayName("moved with the key the dialog sends, it takes the connection and keeps its key as JSON")
    void movesToAnotherConnection() {
        Map<String, Object> update = new HashMap<>();
        update.put("connectionId", cloud.id().toString());
        update.put("projectKey", "RHEU");

        service.updateConnectorConfig(row.getId(), update, null);

        assertThat(row.getConnectionId()).isEqualTo(cloud.id());
        assertThat(ConfigJson.parse(row.getConfig())).containsEntry("projectKey", "RHEU");
    }

    @Test
    @DisplayName("another key is still another project, and refused")
    void anotherKeyIsStillRefused() {
        assertThatThrownBy(() -> service.updateConnectorConfig(row.getId(), Map.of("projectKey", "RH"), null))
                .hasMessage("That would make it a different source. Add it as a new source instead.");
    }

    private RegisteredConnector older(ConnectorType type, String name, String config) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(UUID.randomUUID());
        r.setConnectorType(type);
        r.setName(name);
        r.setConfig(config);
        r.setConnectionId(UUID.randomUUID());
        when(connectors.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("an older Bitbucket repository moves with the fields its dialog now shows")
    void olderBitbucketMoves() {
        RegisteredConnector repo = older(ConnectorType.BITBUCKET, "PROJ/my-repo", "https://bb.old.example");
        Map<String, String> shown = StoredConfig.fields(ConnectorType.BITBUCKET, repo.getConfig(), repo.getName());
        assertThat(shown).containsEntry("projectKey", "PROJ").containsEntry("repoSlug", "my-repo");

        Map<String, Object> update = new HashMap<>(shown);
        update.put("connectionId", bitbucket.id().toString());
        service.updateConnectorConfig(repo.getId(), update, null);

        assertThat(repo.getConnectionId()).isEqualTo(bitbucket.id());
        assertThat(ConfigJson.parse(repo.getConfig())).containsEntry("projectKey", "PROJ").containsEntry("repoSlug", "my-repo");
    }

    @Test
    @DisplayName("an SVN repository moves to a connection for its own server, and never to another")
    void svnMovesOnlyOnItsServer() {
        RegisteredConnector repo = older(ConnectorType.SVN, "trunk",
                ConfigJson.write(Map.of("repoPath", "https://svn.corp.example/repos/app/trunk")));
        UUID before = repo.getConnectionId();

        assertThatThrownBy(() -> service.updateConnectorConfig(repo.getId(),
                Map.of("connectionId", svnOtherServer.id().toString()), null))
                .hasMessageContaining("is not on the server of the SVN connection \"Elsewhere\"");
        assertThat(repo.getConnectionId()).isEqualTo(before);

        service.updateConnectorConfig(repo.getId(), Map.of("connectionId", svnSameServer.id().toString()), null);
        assertThat(repo.getConnectionId()).isEqualTo(svnSameServer.id());
    }
}
