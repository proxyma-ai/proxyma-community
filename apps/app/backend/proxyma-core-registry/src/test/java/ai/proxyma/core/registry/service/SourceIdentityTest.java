package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.connector.spi.Capabilities;
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
 * The connection is part of what makes a source the source it is.
 *
 * <p>Two sources were told apart by their name and their connection's server address. A service with
 * no address per account - IMAP, Zoom, Gmail, Slack, every cloud API at one URL - gave a second
 * account's "INBOX" or "My meetings" the name and the address of the first's, and it was refused as
 * "already one of your sources". Through a different connection a source is a different source;
 * through the same one, the same source. And somebody else's shared source is offered instead of a
 * new one only when it reads the same thing - the same fields on the same server.
 */
class SourceIdentityTest {

    private static final String ANA = "ana";
    private static final String BO = "bo";

    // Two of Ana's meeting accounts on one cloud API - one address for everybody.
    private static final ResolvedConnection ANA_WORK = connection(ANA, ConnectionProtocol.ZOOM, "https://api.zoom.us", true);
    private static final ResolvedConnection ANA_CLUB = connection(ANA, ConnectionProtocol.ZOOM, "https://api.zoom.us", false);
    // Repositories: Ana's and Bo's accounts on one GitHub, and Ana's on her company's own.
    private static final ResolvedConnection ANA_GITHUB = connection(ANA, ConnectionProtocol.GITHUB, "https://api.github.com", true);
    private static final ResolvedConnection ANA_GHE = connection(ANA, ConnectionProtocol.GITHUB, "https://ghe.corp.example/api/v3", false);
    private static final ResolvedConnection BO_GITHUB = connection(BO, ConnectionProtocol.GITHUB, "https://api.github.com", true);

    // A personal source: what one account sees, never shared.
    private static final ConnectorPlugin MEETINGS = plugin(ConnectorDescriptor.builder(ConnectorType.ZOOM)
            .protocol(ConnectionProtocol.ZOOM)
            .fields(FieldSpec.text("scope", "Whose meetings").asRequired())
            .build());
    // A team source: the same repository is the same content for everybody who can read it.
    private static final ConnectorPlugin REPOS = plugin(ConnectorDescriptor.builder(ConnectorType.GITHUB)
            .protocol(ConnectionProtocol.GITHUB)
            .fields(FieldSpec.text("repo", "Repository").asRequired())
            .capabilities(Capabilities.builder().shareable().build())
            .build());

    private final List<RegisteredConnector> rows = new CopyOnWriteArrayList<>();
    private ConnectorConnectionService connections;
    private RegisteredConnectorRepository connectors;
    private ConnectorService service;

    private static ResolvedConnection connection(String owner, ConnectionProtocol protocol, String url, boolean isDefault) {
        return new ResolvedConnection(UUID.randomUUID(), owner, protocol.name(), protocol, url, null, "secret",
                isDefault, true, false, null, null, null);
    }

    private static ConnectorPlugin plugin(ConnectorDescriptor descriptor) {
        return new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return descriptor; }
            @Override public ConnectorSession open(SourceContext context) { throw new UnsupportedOperationException(); }
        };
    }

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(a -> {
            RegisteredConnector r = a.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            if (!rows.contains(r)) rows.add(r);
            return r;
        });
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(any(), any(), any())).thenAnswer(a -> rows.stream()
                .filter(r -> a.getArgument(0).equals(r.getUserId()) && r.getConnectorType() == a.getArgument(1)
                        && r.getName().equalsIgnoreCase(a.getArgument(2)))
                .toList());
        when(connectors.findById(any())).thenAnswer(a -> rows.stream()
                .filter(r -> r.getId().equals(a.getArgument(0))).findFirst());
        when(connectors.findSharedOrOwnByConnectorTypeAndConfigAndName(any(), any(), anyString(), any()))
                .thenAnswer(a -> rows.stream()
                        .filter(r -> r.getConnectorType() == a.getArgument(0) && r.getConfig().equals(a.getArgument(1))
                                && r.getName().equalsIgnoreCase(a.getArgument(2))
                                && (r.isShared() || r.getUserId().equals(a.getArgument(3))))
                        .toList());

        connections = mock(ConnectorConnectionService.class);
        List<ResolvedConnection> all = List.of(ANA_WORK, ANA_CLUB, ANA_GITHUB, ANA_GHE, BO_GITHUB);
        when(connections.resolve(any())).thenAnswer(a -> all.stream().filter(c -> c.id().equals(a.getArgument(0))).findFirst());
        when(connections.resolveOwned(any(), any())).thenAnswer(a -> all.stream()
                .filter(c -> c.id().equals(a.getArgument(1))
                        && c.ownerId().equals(ConnectorConnectionService.ownerKey(a.getArgument(0))))
                .findFirst());
        when(connections.defaultFor(eq(ANA), eq(ConnectionProtocol.ZOOM))).thenReturn(Optional.of(ANA_WORK));
        when(connections.defaultFor(eq(ANA), eq(ConnectionProtocol.GITHUB))).thenReturn(Optional.of(ANA_GITHUB));
        when(connections.defaultFor(eq(BO), eq(ConnectionProtocol.GITHUB))).thenReturn(Optional.of(BO_GITHUB));

        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                new ConnectorEndpointResolver(connectors, connections), connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
        service.setCatalog(ConnectorCatalog.of(List.of(MEETINGS, REPOS), List.of()));
    }

    private UUID register(ConnectorType type, String field, String value, UUID connection, String user) {
        return service.register(new ConnectorService.SourceRequest(type, connection, Map.of(field, value), null, null), user);
    }

    @Test
    @DisplayName("one name through two accounts at one address is two sources")
    void twoAccountsAreTwoSources() {
        UUID work = register(ConnectorType.ZOOM, "scope", "My meetings", ANA_WORK.id(), ANA);
        UUID club = service.asNewSource(() -> register(ConnectorType.ZOOM, "scope", "My meetings", ANA_CLUB.id(), ANA));

        assertThat(club).isNotEqualTo(work);
        assertThat(rows).extracting(RegisteredConnector::getConnectionId).containsExactly(ANA_WORK.id(), ANA_CLUB.id());
    }

    @Test
    @DisplayName("one name through the same account is the same source - and a dialog is told so")
    void oneAccountIsOneSource() {
        UUID first = register(ConnectorType.ZOOM, "scope", "My meetings", null, ANA);

        // Named explicitly or taken as the default, the same connection is the same source.
        assertThat(register(ConnectorType.ZOOM, "scope", "my meetings", ANA_WORK.id(), ANA)).isEqualTo(first);
        assertThatThrownBy(() -> service.asNewSource(() -> register(ConnectorType.ZOOM, "scope", "My meetings", null, ANA)))
                .isInstanceOf(SourceAlreadyRegisteredException.class);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("a source whose connection is gone is repaired through whichever connection registers it again")
    void anOrphanIsRepaired() {
        RegisteredConnector orphan = new RegisteredConnector();
        orphan.setId(UUID.randomUUID());
        orphan.setUserId(ANA);
        orphan.setConnectorType(ConnectorType.ZOOM);
        orphan.setName("My meetings");
        orphan.setConfig("{\"scope\":\"My meetings\"}");
        orphan.setConnectionId(UUID.randomUUID()); // deleted
        rows.add(orphan);

        assertThat(service.asNewSource(() -> register(ConnectorType.ZOOM, "scope", "My meetings", ANA_CLUB.id(), ANA)))
                .isEqualTo(orphan.getId());
        assertThat(orphan.getConnectionId()).isEqualTo(ANA_CLUB.id());
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("somebody's shared repository is offered only when it is the same repository on the same server")
    void sharedDuplicatesReadTheSameThing() {
        UUID bos = register(ConnectorType.GITHUB, "repo", "acme/handbook", null, BO);
        rows.stream().filter(r -> r.getId().equals(bos)).findFirst().orElseThrow().setShared(true);

        // Ana on the same GitHub: Bo's copy is the same repository.
        assertThat(service.findSharedDuplicate(new ConnectorService.SourceRequest(ConnectorType.GITHUB, null,
                Map.of("repo", "acme/handbook"), null, null), ANA)).map(RegisteredConnector::getId).contains(bos);
        // Ana on her company's own GitHub: the same name is another repository.
        assertThat(service.findSharedDuplicate(new ConnectorService.SourceRequest(ConnectorType.GITHUB, ANA_GHE.id(),
                Map.of("repo", "acme/handbook"), null, null), ANA)).isEmpty();
        // Bo's own source is his, not a duplicate to offer him.
        assertThat(service.findSharedDuplicate(new ConnectorService.SourceRequest(ConnectorType.GITHUB, null,
                Map.of("repo", "acme/handbook"), null, null), BO)).isEmpty();
    }

    @Test
    @DisplayName("a personal source is never offered as somebody else's duplicate")
    void personalSourcesHaveNoSharedDuplicate() {
        UUID mine = register(ConnectorType.ZOOM, "scope", "My meetings", null, ANA);
        rows.stream().filter(r -> r.getId().equals(mine)).findFirst().orElseThrow().setShared(true);

        assertThat(service.findSharedDuplicate(new ConnectorService.SourceRequest(ConnectorType.ZOOM, null,
                Map.of("scope", "My meetings"), null, null), BO)).isEmpty();
    }
}
