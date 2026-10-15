package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.SourceContext;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one way in for a type built on the connector framework: its configuration checked against its
 * descriptor on the server, stored as JSON, named by its plugin's identity, and deduplicated by that
 * identity on the connection's server - and an edit that keeps the identity, refused one that does not.
 */
class GenericRegistrationTest {

    private static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.CONFLUENCE)
            .protocol(ConnectionProtocol.CONFLUENCE)
            .displayName("Native wiki")
            .fields(FieldSpec.text("space", "Space").asRequired().pattern("[A-Z]+").placeholder("DOCS"),
                    FieldSpec.bool("comments", "Comments").defaultValue("true"))
            .capabilities(Capabilities.builder().shareable().build())
            .build();

    private static final ConnectorPlugin PLUGIN = new ConnectorPlugin() {
        @Override public ConnectorDescriptor descriptor() { return DESCRIPTOR; }
        @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
    };

    private final ResolvedConnection wiki = new ResolvedConnection(UUID.randomUUID(), ConnectorConnection.LOCAL_OWNER,
            "Wiki", ConnectionProtocol.CONFLUENCE, "https://wiki.example", null, "tok", true, true, false, null, null, null);

    private RegisteredConnectorRepository connectors;
    private SyncOrchestratorService sync;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(i -> {
            RegisteredConnector r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.defaultFor(any(), eq(ConnectionProtocol.CONFLUENCE))).thenReturn(Optional.of(wiki));
        ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
        when(endpoints.resolve(any(RegisteredConnector.class))).thenReturn(ConnectorEndpointResolver.Endpoint.of(wiki));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                endpoints, connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
        service.setCatalog(ConnectorCatalog.of(List.of(PLUGIN), List.of()));
    }

    private ConnectorService.SourceRequest request(Map<String, Object> config) {
        return new ConnectorService.SourceRequest(ConnectorType.CONFLUENCE, null, config, null, null);
    }

    @Test
    @DisplayName("a registration is checked against the descriptor, stored as JSON, and named by its identity")
    void registersThroughTheDescriptor() {
        service.register(request(Map.of("space", "DOCS")), null);

        ArgumentCaptor<RegisteredConnector> saved = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(connectors, atLeastOnce()).save(saved.capture());
        RegisteredConnector row = saved.getValue();
        assertThat(row.getName()).isEqualTo("DOCS");
        assertThat(row.getConnectionId()).isEqualTo(wiki.id());
        assertThat(ConfigJson.parse(row.getConfig())).containsExactly(
                Map.entry("space", "DOCS"), Map.entry("comments", "true"));
        // The probe ran on the candidate, with the connection already on it.
        ArgumentCaptor<RegisteredConnector> probed = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(sync).verifyAccess(probed.capture());
        assertThat(probed.getValue().getConnectionId()).isEqualTo(wiki.id());
    }

    @Test
    @DisplayName("a configuration the descriptor refuses saves nothing")
    void refusedConfigurationsSaveNothing() {
        assertThatThrownBy(() -> service.register(request(Map.of("space", "docs space")), null))
                .hasMessage("Space is not in the form expected (for example DOCS).");
        assertThatThrownBy(() -> service.register(request(Map.of()), null)).hasMessage("Space is required.");
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("the same identity on the same server is the same source; a dialog is told so")
    void sameIdentitySameServerIsOneSource() {
        RegisteredConnector existing = new RegisteredConnector();
        existing.setId(UUID.randomUUID());
        existing.setConnectorType(ConnectorType.CONFLUENCE);
        existing.setName("DOCS");
        existing.setConnectionId(wiki.id());
        when(connectors.findAllByConnectorTypeAndNameIgnoreCase(ConnectorType.CONFLUENCE, "DOCS"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.asNewSource(() -> service.register(request(Map.of("space", "DOCS")), null)))
                .isInstanceOf(SourceAlreadyRegisteredException.class);
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("an edit is checked the same way and keeps the identity; one that changes it is refused")
    void editsKeepTheIdentity() {
        RegisteredConnector row = new RegisteredConnector();
        UUID id = UUID.randomUUID();
        row.setId(id);
        row.setConnectorType(ConnectorType.CONFLUENCE);
        row.setName("DOCS");
        row.setConfig(ConfigJson.write(Map.of("space", "DOCS", "comments", "true")));
        when(connectors.findById(id)).thenReturn(Optional.of(row));

        service.updateConnectorConfig(id, Map.of("comments", false), null);
        assertThat(ConfigJson.parse(row.getConfig())).containsEntry("comments", "false").containsEntry("space", "DOCS");

        assertThatThrownBy(() -> service.updateConnectorConfig(id, Map.of("space", "OTHER"), null))
                .hasMessage("That would make it a different source. Add it as a new source instead.");
        assertThatThrownBy(() -> service.updateConnectorConfig(id, Map.of("nothing", "here"), null))
                .hasMessageContaining("is not editable");
    }

    @Test
    @DisplayName("a shareable source with every optional field empty has no stored form to compare, not a 500")
    void everythingSourceHasNoStoredForm() {
        ConnectorDescriptor boards = ConnectorDescriptor.builder(ConnectorType.CONFLUENCE)
                .protocol(ConnectionProtocol.CONFLUENCE)
                .displayName("Native boards")
                .fields(FieldSpec.text("boards", "Boards"))
                .capabilities(Capabilities.builder().shareable().build())
                .build();
        ConnectorPlugin plugin = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return boards; }
            @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
            @Override public String displayName(Map<String, String> config) {
                return config.getOrDefault("boards", "Every board");
            }
        };
        service.setCatalog(ConnectorCatalog.of(List.of(plugin), List.of()));

        assertThat(service.storedForm(request(Map.of()), null)).isEmpty();
        assertThat(service.storedForm(request(Map.of("boards", "FkRoad01")), null))
                .contains(Map.entry("{\"boards\":\"FkRoad01\"}", "FkRoad01"));
    }
}
