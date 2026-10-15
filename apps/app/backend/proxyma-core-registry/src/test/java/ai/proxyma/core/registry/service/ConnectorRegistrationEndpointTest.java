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
import ai.proxyma.core.connector.spi.AuthKind;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which connection a new source reads through, and what the resolver answers for it afterwards.
 *
 * <p>A source names ONE of its owner's connections, and sync, the chat tools and the MCP tools all
 * resolve through it. The expensive failures are the quiet ones: a source registered against
 * somebody else's connection (indexing with their token), a probe that ran against a different
 * server than the one saved, and a shared source that lends its owner's credential to whoever asks
 * about it. Runs the real {@link ConnectorEndpointResolver}; only the connection store is a stand-in.
 */
class ConnectorRegistrationEndpointTest {

    private static final String ANA = "ana";
    private static final String BO = "bo";

    private static final ResolvedConnection ANA_JIRA = connection(ANA, "Work", ConnectionProtocol.JIRA,
            "https://jira.work.example", null, "pat-ana", true);
    private static final ResolvedConnection ANA_JIRA_TEST = connection(ANA, "Test", ConnectionProtocol.JIRA,
            "https://jira.test.example", null, "pat-ana-test", false);
    private static final ResolvedConnection ANA_WIKI = connection(ANA, "Wiki", ConnectionProtocol.CONFLUENCE,
            "https://wiki.example", null, "pat-wiki", true);
    private static final ResolvedConnection ANA_SVN = connection(ANA, "Repo", ConnectionProtocol.SVN,
            "https://svn.example/svn", "ana", "secret", true);
    private static final ResolvedConnection BO_JIRA = connection(BO, "Bo's", ConnectionProtocol.JIRA,
            "https://jira.bo.example", null, "pat-bo", true);

    private RegisteredConnectorRepository connectors;
    private SyncOrchestratorService sync;
    private ConnectorConnectionService connections;
    private ConnectorEndpointResolver resolver;
    private ConnectorService service;
    // What the resolver answered for the candidate the probe was handed - captured INSIDE the
    // probe, since that is the moment the first sync's endpoint is decided.
    private final AtomicReference<ConnectorEndpointResolver.Endpoint> probed = new AtomicReference<>();

    private static ResolvedConnection connection(String owner, String name, ConnectionProtocol protocol, String url,
                                                 String username, String secret, boolean isDefault) {
        return new ResolvedConnection(UUID.randomUUID(), owner, name, protocol, url, username, secret, isDefault,
                true, false, null, null, null);
    }

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(any(), any(), any())).thenReturn(List.of());
        sync = mock(SyncOrchestratorService.class);
        connections = mock(ConnectorConnectionService.class);
        for (ResolvedConnection c : List.of(ANA_JIRA, ANA_JIRA_TEST, ANA_WIKI, ANA_SVN, BO_JIRA)) {
            when(connections.resolve(c.id())).thenReturn(Optional.of(c));
        }
        when(connections.resolveOwned(any(), any())).thenAnswer(a -> List.of(ANA_JIRA, ANA_JIRA_TEST, ANA_WIKI, ANA_SVN, BO_JIRA)
                .stream().filter(c -> c.id().equals(a.getArgument(1))
                        && c.ownerId().equals(ConnectorConnectionService.ownerKey(a.getArgument(0))))
                .findFirst());
        when(connections.defaultFor(ANA, ConnectionProtocol.JIRA)).thenReturn(Optional.of(ANA_JIRA));
        when(connections.defaultFor(ANA, ConnectionProtocol.SVN)).thenReturn(Optional.of(ANA_SVN));
        when(connections.defaultFor(BO, ConnectionProtocol.JIRA)).thenReturn(Optional.of(BO_JIRA));
        when(connections.defaultFor(any(), eq(ConnectionProtocol.BITBUCKET))).thenReturn(Optional.empty());

        resolver = new ConnectorEndpointResolver(connectors, connections);
        when(sync.verifyAccess(any())).thenAnswer(a -> {
            probed.set(resolver.resolve(a.<RegisteredConnector>getArgument(0)));
            return AccessCheck.verified();
        });

        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                resolver, connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
    }

    @Test
    @DisplayName("with no connection chosen, a source reads through the owner's default - and the first probe already does")
    void defaultConnection() {
        service.registerJiraProject("PROJ", ANA);

        RegisteredConnector saved = savedRow();
        assertEquals(ANA_JIRA.id(), saved.getConnectionId());
        assertEquals("https://jira.work.example", probed.get().baseUrl());
        assertEquals("pat-ana", probed.get().credential());
    }

    @Test
    @DisplayName("a chosen connection is used, not the default")
    void chosenConnection() {
        service.registerJiraProject("PROJ", ANA, ANA_JIRA_TEST.id());

        assertEquals(ANA_JIRA_TEST.id(), savedRow().getConnectionId());
        assertEquals("pat-ana-test", probed.get().credential());
    }

    @Test
    @DisplayName("somebody else's connection is refused before any probe - their token never reaches a sync")
    void someoneElsesConnectionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.registerJiraProject("PROJ", ANA, BO_JIRA.id()));
        verify(sync, never()).verifyAccess(any());
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("a connection to another service is refused - a Jira project cannot read through Confluence")
    void wrongServiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.registerJiraProject("PROJ", ANA, ANA_WIKI.id()));
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("with no connection at all, registration is refused and says where to add one")
    void noConnectionIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.registerBitbucketRepo("PROJ", "repo", ANA));
        assertTrue(e.getMessage().contains("Settings > Connections"), e.getMessage());
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("an SVN path is joined onto the connection's root; the whole repository URL is stored")
    void svnJoinsTheRoot() {
        service.registerSvnRepo("projects/demo", null, ANA, null, true, null);

        RegisteredConnector saved = savedRow();
        assertEquals(ConnectorType.SVN, saved.getConnectorType());
        // As its one field, the way every non-file type stores its configuration.
        assertEquals(java.util.Map.of("repoPath", "https://svn.example/svn/projects/demo"),
                ai.proxyma.core.connector.spi.ConfigJson.parse(saved.getConfig()));
        assertEquals(ANA_SVN.id(), saved.getConnectionId());
        assertEquals("ana:secret", probed.get().credential());
    }

    @Test
    @DisplayName("a source somebody else owns lends the caller nothing - they resolve to their own default")
    void foreignSourceLendsNothing() {
        RegisteredConnector anas = new RegisteredConnector();
        anas.setConnectorType(ConnectorType.JIRA);
        anas.setUserId(ANA);
        anas.setShared(true);
        anas.setConnectionId(ANA_JIRA.id());

        ConnectorEndpointResolver.Endpoint forBo = resolver.resolve(anas, BO);
        assertEquals(BO_JIRA.id(), forBo.connectionId());
        assertEquals("pat-bo", forBo.credential());
        assertFalse(forBo.credential().equals("pat-ana"));

        // The owner - and sync, which runs as the owner - get the source's own connection.
        assertEquals("pat-ana", resolver.resolve(anas, ANA).credential());
        assertEquals("pat-ana", resolver.resolve(anas).credential());
    }

    @Test
    @DisplayName("sync refuses a connection that is not the source owner's, even if a row was edited to name one")
    void syncChecksTheOwner() {
        RegisteredConnector tampered = new RegisteredConnector();
        tampered.setConnectorType(ConnectorType.JIRA);
        tampered.setUserId(ANA);
        tampered.setConnectionId(BO_JIRA.id());

        assertFalse(resolver.resolve(tampered).exists());
    }

    @Test
    @DisplayName("Desktop's sources, with no owner, read through the local owner's connections")
    void desktopOwner() {
        ResolvedConnection local = connection(ConnectorConnection.LOCAL_OWNER, "Work", ConnectionProtocol.JIRA,
                "https://jira.local.example", null, "pat-local", true);
        when(connections.resolve(local.id())).thenReturn(Optional.of(local));
        RegisteredConnector desktop = new RegisteredConnector();
        desktop.setConnectorType(ConnectorType.JIRA);
        desktop.setConnectionId(local.id());

        assertEquals("pat-local", resolver.resolve(desktop).credential());
        assertEquals("pat-local", resolver.resolve(desktop, null).credential());
    }

    @Test
    @DisplayName("registering a source again gives it a connection when it has none - the 0.18.0 carry-over repair")
    void reRegistrationAdoptsAConnection() {
        RegisteredConnector legacy = new RegisteredConnector();
        legacy.setId(UUID.randomUUID());
        legacy.setConnectorType(ConnectorType.JIRA);
        legacy.setName("PROJ");
        legacy.setUserId(ANA);
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "PROJ"))
                .thenReturn(List.of(legacy));

        UUID id = service.registerJiraProject("PROJ", ANA);

        assertEquals(legacy.getId(), id);
        assertEquals(ANA_JIRA.id(), savedRow().getConnectionId());
        assertTrue(resolver.resolve(legacy).exists());
    }

    @Test
    @DisplayName("registering a source again leaves a working connection alone - moving it is the edit dialog's job")
    void reRegistrationKeepsAWorkingConnection() {
        // Same server as the default, a different credential - a second account on one Jira.
        ResolvedConnection bot = connection(ANA, "Bot", ConnectionProtocol.JIRA,
                "https://jira.work.example", null, "pat-bot", false);
        when(connections.resolve(bot.id())).thenReturn(Optional.of(bot));
        when(connections.resolveOwned(ANA, bot.id())).thenReturn(Optional.of(bot));
        RegisteredConnector onBot = new RegisteredConnector();
        onBot.setId(UUID.randomUUID());
        onBot.setConnectorType(ConnectorType.JIRA);
        onBot.setName("PROJ");
        onBot.setUserId(ANA);
        onBot.setConnectionId(bot.id());
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "PROJ"))
                .thenReturn(List.of(onBot));

        // Through its own connection it is the same source, re-synced where it is.
        assertEquals(onBot.getId(), service.registerJiraProject("PROJ", ANA, bot.id()));
        assertEquals(bot.id(), onBot.getConnectionId());
        verify(connectors, never()).save(any());

        // Through the default - another account - it is another source since 2026-10-14 (the
        // connection is part of a source's identity: SourceIdentityTest), and the first is not moved.
        service.registerJiraProject("PROJ", ANA);
        assertEquals(ANA_JIRA.id(), savedRow().getConnectionId());
        assertEquals(bot.id(), onBot.getConnectionId());
    }

    @Test
    @DisplayName("from a dialog, adding a source with no connection again repairs it instead of refusing")
    void dialogReAddRepairs() {
        RegisteredConnector legacy = new RegisteredConnector();
        legacy.setId(UUID.randomUUID());
        legacy.setConnectorType(ConnectorType.JIRA);
        legacy.setName("PROJ");
        legacy.setUserId(ANA);
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "PROJ"))
                .thenReturn(List.of(legacy));

        UUID id = service.asNewSource(() -> service.registerJiraProject("PROJ", ANA));

        assertEquals(legacy.getId(), id);
        assertEquals(ANA_JIRA.id(), savedRow().getConnectionId());
    }

    @Test
    @DisplayName("from a dialog, adding a source that already works is still refused as a duplicate")
    void dialogReAddOfAWorkingSourceIsRefused() {
        RegisteredConnector working = new RegisteredConnector();
        working.setId(UUID.randomUUID());
        working.setConnectorType(ConnectorType.JIRA);
        working.setName("PROJ");
        working.setUserId(ANA);
        working.setConnectionId(ANA_JIRA.id());
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "PROJ"))
                .thenReturn(List.of(working));

        assertThrows(SourceAlreadyRegisteredException.class,
                () -> service.asNewSource(() -> service.registerJiraProject("PROJ", ANA)));
        verify(connectors, never()).save(any());
    }

    private RegisteredConnector savedRow() {
        ArgumentCaptor<RegisteredConnector> captor = ArgumentCaptor.forClass(RegisteredConnector.class);
        verify(connectors).save(captor.capture());
        return captor.getValue();
    }
    // An Atlassian Cloud connection: an email and an API token, which reaches the whole site.
    private static ResolvedConnection cloud(String owner, String name, ConnectionProtocol protocol, String url) {
        return new ResolvedConnection(UUID.randomUUID(), owner, name, protocol, url, owner + "@example.com", "api-token",
                true, true, false, null, null, null, AuthKind.BASIC, Map.of("token", "api-token"), Map.of());
    }

    private void owns(String owner, ResolvedConnection c) {
        when(connections.resolve(c.id())).thenReturn(Optional.of(c));
        when(connections.resolveOwned(owner, c.id())).thenReturn(Optional.of(c));
    }

    @Test
    @DisplayName("one Atlassian Cloud connection serves Jira AND Confluence: a space with no Confluence connection reads through the site's")
    void cloudConnectionServesBothProducts() {
        ResolvedConnection site = cloud("cy", "Acme", ConnectionProtocol.JIRA, "https://acme.atlassian.net");
        owns("cy", site);
        when(connections.defaultFor("cy", ConnectionProtocol.JIRA)).thenReturn(Optional.of(site));
        when(connections.resolveAll("cy", ConnectionProtocol.JIRA)).thenReturn(List.of(site));

        service.registerConfluenceSpace("DOCS", "cy");
        assertEquals(site.id(), savedRow().getConnectionId());
        assertEquals("https://acme.atlassian.net", probed.get().baseUrl());
        // What a tool naming no space reaches, and what useServer lists for Confluence.
        assertEquals(site.id(), resolver.forType(ConnectorType.CONFLUENCE, "cy").connectionId());
        assertEquals(List.of(site), resolver.connections(ConnectorType.CONFLUENCE, "cy"));
        assertEquals(site.id(), resolver.byId(ConnectorType.CONFLUENCE, site.id(), "cy").connectionId());
    }

    @Test
    @DisplayName("a Cloud connection chosen for the other product is accepted; a Data Center one is refused before any probe")
    void chosenAcrossProducts() {
        ResolvedConnection wiki = cloud("cy", "Acme wiki", ConnectionProtocol.CONFLUENCE, "https://acme.atlassian.net/wiki");
        owns("cy", wiki);
        service.registerJiraProject("PROJ", "cy", wiki.id());
        assertEquals(wiki.id(), savedRow().getConnectionId());

        // Ana's Jira connections are Data Center tokens: they reach a Jira server and nothing else.
        when(connections.resolveAll(ANA, ConnectionProtocol.JIRA)).thenReturn(List.of(ANA_JIRA, ANA_JIRA_TEST));
        assertThrows(IllegalArgumentException.class, () -> service.registerConfluenceSpace("DOCS", ANA, ANA_JIRA.id()));
        assertFalse(resolver.byId(ConnectorType.CONFLUENCE, ANA_JIRA.id(), ANA).exists());
        // Only the Jira project's probe ran: the refused space never reached one.
        verify(sync, org.mockito.Mockito.times(1)).verifyAccess(any());
    }

    @Test
    @DisplayName("Data Center keeps its rule: no Confluence connection and only Jira tokens means none to register through")
    void dataCenterServesOneProduct() {
        when(connections.resolveAll(ANA, ConnectionProtocol.JIRA)).thenReturn(List.of(ANA_JIRA, ANA_JIRA_TEST));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.registerConfluenceSpace("DOCS", ANA));
        assertTrue(e.getMessage().startsWith("You have no Confluence connection yet."));
        assertFalse(resolver.forType(ConnectorType.CONFLUENCE, ANA).exists());
        assertTrue(resolver.connections(ConnectorType.CONFLUENCE, ANA).isEmpty());
    }

    @Test
    @DisplayName("a service's own default wins over another service's Cloud connection")
    void ownServiceFirst() {
        ResolvedConnection site = cloud("cy", "Acme", ConnectionProtocol.JIRA, "https://acme.atlassian.net");
        ResolvedConnection wiki = cloud("cy", "Acme wiki", ConnectionProtocol.CONFLUENCE, "https://acme.atlassian.net/wiki");
        owns("cy", site);
        owns("cy", wiki);
        when(connections.defaultFor("cy", ConnectionProtocol.CONFLUENCE)).thenReturn(Optional.of(wiki));
        when(connections.resolveAll("cy", ConnectionProtocol.CONFLUENCE)).thenReturn(List.of(wiki));
        when(connections.resolveAll("cy", ConnectionProtocol.JIRA)).thenReturn(List.of(site));

        assertEquals(wiki.id(), resolver.forType(ConnectorType.CONFLUENCE, "cy").connectionId());
        assertEquals(List.of(wiki, site), resolver.connections(ConnectorType.CONFLUENCE, "cy"));
    }
}
