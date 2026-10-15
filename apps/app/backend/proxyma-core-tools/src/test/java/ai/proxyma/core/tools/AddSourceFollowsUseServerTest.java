package ai.proxyma.core.tools;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.sources.SourceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * addSource adds the source on the server the conversation switched to with useServer - for every
 * type that reads through a connection, from one tool.
 *
 * <p>The 0.27.0 feedback: the agent switched to a second Confluence server, searched it live, and
 * was then asked to register a space from it - and could not, because the register tools always
 * went through the DEFAULT connection. With nothing picked (and always over MCP, where there is no
 * conversation) the connection stays null and the service uses the default.
 */
class AddSourceFollowsUseServerTest {

    private final ConnectorService connectors = mock(ConnectorService.class);
    private final ActiveServers active = mock(ActiveServers.class);
    private final FeatureGateService gate = mock(FeatureGateService.class);
    private final UUID second = UUID.randomUUID();
    private final UUID registered = UUID.randomUUID();
    private SourceTools tools;

    private static ConnectorPlugin plugin(ConnectorType type) {
        // SharePoint's real descriptor is a Proxyma Enterprise module's; what this test needs of it is
        // that it is an ENTERPRISE type, which a stand-in says as well.
        ConnectorDescriptor d = type == ConnectorType.SHAREPOINT
                ? ConnectorDescriptor.builder(type).displayName("SharePoint")
                        .fields(ai.proxyma.core.connector.spi.FieldSpec.url("siteUrl", "Site URL").asRequired())
                        .capabilities(ai.proxyma.core.connector.spi.Capabilities.builder()
                                .edition(ai.proxyma.core.connector.spi.Edition.ENTERPRISE).build())
                        .build()
                : CoreDescriptors.of(type);
        return new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return d; }
            @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
        };
    }

    @BeforeEach
    void setUp() {
        when(gate.getFeatures()).thenReturn(Features.forTier(LicenseTier.COMMUNITY));
        ConnectorCatalog catalog = ConnectorCatalog.of(List.of(plugin(ConnectorType.JIRA), plugin(ConnectorType.CONFLUENCE),
                plugin(ConnectorType.BITBUCKET), plugin(ConnectorType.SVN), plugin(ConnectorType.WEB_PAGE),
                plugin(ConnectorType.SHAREPOINT)), List.of());
        tools = new SourceTools(connectors, catalog, mock(McpGuardService.class), active,
                mock(SourceContextFactory.class), mock(DocumentFetcher.class), gate, false);
        when(connectors.listVisibleTo(any())).thenReturn(List.of());
        when(connectors.register(any(), any())).thenReturn(registered);
    }

    private void picked(ConnectorType type) {
        when(active.active(type)).thenReturn(Optional.of(
                new Endpoint(second, "Second", "https://second.example.org", "tok", true, true)));
    }

    @Test
    @DisplayName("Confluence, Jira, Bitbucket and SVN: a source is added through the picked connection")
    void followsThePick() {
        for (ConnectorType type : List.of(ConnectorType.CONFLUENCE, ConnectorType.JIRA, ConnectorType.BITBUCKET,
                ConnectorType.SVN)) {
            picked(type);
            var out = tools.addSource(type.wireName(), Map.of("key", "value"), null, null);
            assertThat(out).as("%s", type).containsEntry("success", true).containsEntry("sourceId", registered.toString());
            verify(connectors).register(argThat(r -> r.type() == type && second.equals(r.connectionId())
                    && "value".equals(r.config().get("key"))), any());
        }
    }

    @Test
    @DisplayName("with nothing picked - or a type that needs no connection - the connection is left to the service")
    void nothingPickedMeansDefault() {
        when(active.active(any())).thenReturn(Optional.empty());

        tools.addSource("jira", Map.of("projectKey", "PROJ"), null, null);
        tools.addSource("webpage", Map.of("seedUrl", "https://docs.example.com"), null, null);

        verify(connectors).register(argThat(r -> r.type() == ConnectorType.JIRA && r.connectionId() == null), any());
        verify(connectors).register(argThat(r -> r.type() == ConnectorType.WEB_PAGE && r.connectionId() == null), any());
    }

    @Test
    @DisplayName("a type this edition does not offer is refused, and listSourceTypes does not list it")
    void enterpriseTypesOffDesktop() {
        var out = tools.addSource("sharepoint", Map.of("siteUrl", "https://x.sharepoint.com/sites/a"), null, null);

        assertThat(out).containsEntry("success", false);
        verify(connectors, never()).register(any(), any());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) tools.listSourceTypes().get("types");
        assertThat(types).extracting(t -> t.get("type")).contains("jira", "webpage").doesNotContain("sharepoint");
        assertThat(types.stream().filter(t -> "jira".equals(t.get("type"))).findFirst().orElseThrow().get("connection"))
                .isEqualTo("Jira");
    }

    @Test
    @DisplayName("an unknown type is refused with where to find the types")
    void unknownType() {
        assertThat(tools.addSource("nonsense", Map.of(), null, null))
                .containsEntry("success", false)
                .hasEntrySatisfying("error", e -> assertThat((String) e).contains("listSourceTypes"));
    }
}
