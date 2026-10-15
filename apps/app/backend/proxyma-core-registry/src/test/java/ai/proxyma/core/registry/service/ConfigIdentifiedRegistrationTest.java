package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A type whose sources are told apart by their configuration and never by their name
 * (ConnectorPlugin.identifiedByConfig) - an Outlook mail folder or calendar, whose plugin is a Proxyma
 * Enterprise module's. The registry's half of that rule, with a stand-in plugin: the same
 * configuration through the same connection is the same source; a different one with a name already
 * taken is a second source with a number; and the plugin's own rules run before anything is stored.
 * It was ConnectorService.registerOutlook until 2026-10-15.
 */
class ConfigIdentifiedRegistrationTest {

    private static final String ANA = "ana";

    private static final ResolvedConnection ANA_MAIL = new ResolvedConnection(UUID.randomUUID(), ANA, "Mail",
            ConnectionProtocol.ZOOM, "https://api.zoom.us", null, "secret", true, true, false, null, null, null);

    // Identity: the folder. Name: what the person called it. A folder is required.
    private static final ConnectorPlugin FOLDERS = new ConnectorPlugin() {
        private final ConnectorDescriptor descriptor = ConnectorDescriptor.builder(ConnectorType.ZOOM)
                .protocol(ConnectionProtocol.ZOOM)
                .fields(FieldSpec.text("folder", "Folder"), FieldSpec.text("name", "Name"))
                .build();
        @Override public ConnectorDescriptor descriptor() { return descriptor; }
        @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
        @Override public boolean identifiedByConfig() { return true; }
        @Override public Map<String, String> prepare(Map<String, String> config) {
            if (config.get("folder") == null) throw new IllegalArgumentException("Choose a folder.");
            return Map.of("folder", config.get("folder").toLowerCase(), "name", config.getOrDefault("name", "Inbox"));
        }
        @Override public String identity(Map<String, String> config) { return config.getOrDefault("folder", ""); }
        @Override public String displayName(Map<String, String> config) { return config.get("name"); }
    };

    private final List<RegisteredConnector> rows = new CopyOnWriteArrayList<>();
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(a -> {
            RegisteredConnector r = a.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            if (!rows.contains(r)) rows.add(r);
            return r;
        });
        when(connectors.findByUserIdAndConnectorType(any(), any())).thenAnswer(a -> rows.stream()
                .filter(r -> a.getArgument(0).equals(r.getUserId()) && r.getConnectorType() == a.getArgument(1))
                .toList());
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(any(), any(), any())).thenAnswer(a -> rows.stream()
                .filter(r -> a.getArgument(0).equals(r.getUserId()) && r.getConnectorType() == a.getArgument(1)
                        && r.getName().equalsIgnoreCase(a.getArgument(2)))
                .toList());
        when(connectors.findById(any())).thenAnswer(a -> rows.stream()
                .filter(r -> r.getId().equals(a.getArgument(0))).findFirst());

        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolve(any())).thenAnswer(a -> Optional.of(ANA_MAIL).filter(c -> c.id().equals(a.getArgument(0))));
        when(connections.resolveOwned(any(), any())).thenAnswer(a -> Optional.of(ANA_MAIL).filter(c -> c.id().equals(a.getArgument(1))));
        when(connections.defaultFor(eq(ANA), eq(ConnectionProtocol.ZOOM))).thenReturn(Optional.of(ANA_MAIL));

        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                new ConnectorEndpointResolver(connectors, connections), connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
        service.setCatalog(ConnectorCatalog.of(List.of(FOLDERS), List.of()));
    }

    private UUID register(String folder, String name) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        if (folder != null) config.put("folder", folder);
        if (name != null) config.put("name", name);
        return service.register(new ConnectorService.SourceRequest(ConnectorType.ZOOM, null, config, null, null), ANA);
    }

    @Test
    @DisplayName("the same configuration is the same source, whatever it is called this time")
    void sameConfigurationIsOneSource() {
        UUID first = register("Archive", "Inbox");
        assertThat(register("ARCHIVE", "Something else")).isEqualTo(first);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getConfig()).contains("\"folder\":\"archive\"");
    }

    @Test
    @DisplayName("another configuration under a name already taken is a second source, numbered")
    void sameNameIsTwoSources() {
        UUID first = register("archive", "Inbox");
        UUID second = register("projects", "Inbox");
        assertThat(second).isNotEqualTo(first);
        assertThat(rows).extracting(RegisteredConnector::getName).containsExactly("Inbox", "Inbox (2)");
    }

    @Test
    @DisplayName("the plugin's own rules run before anything is stored")
    void pluginRulesRunFirst() {
        assertThatThrownBy(() -> register(null, "Inbox")).hasMessage("Choose a folder.");
        assertThat(rows).isEmpty();
    }
}
