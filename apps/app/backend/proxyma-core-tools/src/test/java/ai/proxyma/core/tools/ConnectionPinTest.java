package ai.proxyma.core.tools;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.config.ConnectorDefaultsProperties;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A SAVED AGENT'S CONNECTION PINS (Constellations, 2026-11-03): with two YouTube channels or two Jira sites, an
 * agent can be tied to one. A tool call resolves to the conversation's useServer pick, then the source the call
 * NAMES when it is the caller's own, then the agent's PIN for the service, then the caller's default - and the
 * pin is a redirect, never a grant: it is followed only while the connection is the turn's person's own (a pin
 * to another person's connection, or to one deleted, falls back to the default and lends nobody's credential),
 * and Read and Write are checked on the pinned connection as on any other.
 */
class ConnectionPinTest {

    private static final String CONVERSATION = "conv-agent";

    private final SmartChatMemory memory = mock(SmartChatMemory.class);
    private final AgentProfiles profiles = mock(AgentProfiles.class);

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    private void pin(String userId, Map<String, UUID> pins) {
        when(profiles.of(CONVERSATION, userId)).thenReturn(Optional.of(
                new AgentProfile(null, null, null, null, null, null, null, false, null, pins)));
    }

    private ToolConnectorResolver resolverOver(ConnectorEndpointResolver endpoints) {
        ActiveServers active = new ActiveServers(memory, endpoints);
        active.setAgentProfiles(profiles);
        return new ToolConnectorResolver(new ConnectorDefaultsProperties(), endpoints, active);
    }

    private static void as(String userId) {
        SearchContext.setUserId(userId);
        SearchContext.setConversationId(CONVERSATION);
    }

    /**
     * Over the REAL ConnectorEndpointResolver, with a store of connections answering by owner as the
     * repository does - so "not yours" is the resolver's own ownership rule, not a mock's say-so.
     */
    @Nested
    class Jira {

        private final List<ResolvedConnection> store = new ArrayList<>();
        private final ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        private final RegisteredConnectorRepository sources = mock(RegisteredConnectorRepository.class);
        private ToolConnectorResolver resolver;

        private ResolvedConnection add(String owner, String name, String url, String token, boolean isDefault,
                                       boolean read, boolean write) {
            ResolvedConnection c = new ResolvedConnection(UUID.randomUUID(), ConnectorConnectionService.ownerKey(owner), name,
                    ConnectionProtocol.JIRA, url, null, token, isDefault, read, write, null, null, null);
            store.add(c);
            return c;
        }

        private List<ResolvedConnection> owned(String user, ConnectionProtocol protocol) {
            String key = ConnectorConnectionService.ownerKey(user);
            return store.stream().filter(c -> c.ownerId().equals(key) && c.protocol() == protocol)
                    .sorted((a, b) -> Boolean.compare(!a.isDefault(), !b.isDefault())).toList();
        }

        @BeforeEach
        void setUp() {
            when(connections.resolveOwned(any(), any())).thenAnswer(i -> {
                String key = ConnectorConnectionService.ownerKey(i.getArgument(0));
                UUID id = i.getArgument(1);
                return store.stream().filter(c -> c.id().equals(id) && c.ownerId().equals(key)).findFirst();
            });
            when(connections.resolveAll(any(), any())).thenAnswer(i -> owned(i.getArgument(0), i.getArgument(1)));
            when(connections.defaultFor(any(), any())).thenAnswer(i -> owned(i.getArgument(0), i.getArgument(1)).stream().findFirst());
            when(connections.resolve(any())).thenAnswer(i -> store.stream().filter(c -> c.id().equals(i.getArgument(0))).findFirst());
            resolver = resolverOver(new ConnectorEndpointResolver(sources, connections));
        }

        @Test
        @DisplayName("the pin wins over the default; a useServer pick and a source the caller names win over the pin")
        void order() {
            add("ana", "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            ResolvedConnection vendor = add("ana", "Vendor Jira", "https://jira.vendor.example", "vendor-token", false, true, true);
            ResolvedConnection picked = add("ana", "Lab Jira", "https://jira.lab.example", "lab-token", false, true, true);
            ResolvedConnection own = add("ana", "Own source's Jira", "https://jira.own.example", "own-token", false, true, true);
            pin("ana", Map.of("jira", vendor.id()));
            as("ana");

            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("vendor-token");

            RegisteredConnector source = new RegisteredConnector();
            source.setId(UUID.randomUUID());
            source.setName("OWN");
            source.setUserId("ana");
            source.setConnectorType(ConnectorType.JIRA);
            source.setConnectionId(own.id());
            when(sources.findAllByUserIdAndConnectorTypeAndNameIgnoreCase("ana", ConnectorType.JIRA, "OWN"))
                    .thenReturn(List.of(source));
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", "OWN", Access.READ).credential())
                    .as("a source of the caller's own the call names").isEqualTo("own-token");
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", "ELSEWHERE", Access.READ).credential())
                    .as("a key no source has").isEqualTo("vendor-token");

            when(memory.activeServers(CONVERSATION)).thenReturn("JIRA=" + picked.id());
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", "OWN", Access.READ).credential())
                    .as("the conversation's pick").isEqualTo("lab-token");
        }

        @Test
        @DisplayName("a source somebody else owns lends nothing, so the pin - not their connection - is used")
        void foreignSourceFallsToThePin() {
            add("ana", "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            ResolvedConnection vendor = add("ana", "Vendor Jira", "https://jira.vendor.example", "vendor-token", false, true, true);
            ResolvedConnection bobs = add("bob", "Bob's Jira", "https://jira.bob.example", "bob-token", true, true, true);
            RegisteredConnector shared = new RegisteredConnector();
            shared.setId(UUID.randomUUID());
            shared.setName("SHARED");
            shared.setUserId("bob");
            shared.setShared(true);
            shared.setConnectorType(ConnectorType.JIRA);
            shared.setConnectionId(bobs.id());
            when(sources.findAllByConnectorTypeAndNameIgnoreCase(ConnectorType.JIRA, "SHARED")).thenReturn(List.of(shared));
            pin("ana", Map.of("jira", vendor.id()));
            as("ana");

            assertThat(resolver.target(ConnectorType.JIRA, "Jira", "SHARED", Access.READ).credential()).isEqualTo("vendor-token");
        }

        @Test
        @DisplayName("a pin to another person's connection is ignored: the turn's person's own default is used")
        void someoneElsesConnectionIsIgnored() {
            add("ana", "Ana's Jira", "https://jira.ana.example", "ana-token", true, true, true);
            ResolvedConnection bobs = add("bob", "Bob's Jira", "https://jira.bob.example", "bob-token", true, true, true);
            pin("ana", Map.of("jira", bobs.id()));
            as("ana");

            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.WRITE).credential()).isEqualTo("ana-token");
            assertThat(new ActiveServers(memory, null).pinned(ConnectorType.JIRA))
                    .as("with no profiles at all, nothing is pinned").isEmpty();
        }

        @Test
        @DisplayName("a pinned connection deleted since falls back to the default")
        void deletedPinFallsBack() {
            add("ana", "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            ResolvedConnection vendor = add("ana", "Vendor Jira", "https://jira.vendor.example", "vendor-token", false, true, true);
            pin("ana", Map.of("jira", vendor.id()));
            as("ana");
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("vendor-token");

            store.remove(vendor);
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("company-token");
        }

        @Test
        @DisplayName("Read and Write are checked on the PINNED connection, not on the default")
        void switchesOfThePinnedConnection() {
            add("ana", "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            ResolvedConnection readOnly = add("ana", "Vendor Jira", "https://jira.vendor.example", "vendor-token", false, true, false);
            pin("ana", Map.of("jira", readOnly.id()));
            as("ana");

            assertThat(resolver.resolveAccessToken("jira", "Jira", null, Access.READ)).isEqualTo("vendor-token");
            assertThatThrownBy(() -> resolver.resolveAccessToken("jira", "Jira", null, Access.WRITE))
                    .hasMessageContaining("write is turned off")
                    .hasMessageContaining("\"Vendor Jira\"");
        }

        @Test
        @DisplayName("Desktop's one local person: a pin to one of the local connections is followed")
        void desktopLocalPerson() {
            add(null, "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            ResolvedConnection vendor = add(null, "Vendor Jira", "https://jira.vendor.example", "vendor-token", false, true, true);
            when(profiles.of(eq(CONVERSATION), isNull())).thenReturn(Optional.of(
                    new AgentProfile(null, null, null, null, null, null, null, false, null, Map.of("jira", vendor.id()))));
            SearchContext.setConversationId(CONVERSATION);

            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("vendor-token");
            ActiveServers active = new ActiveServers(memory, new ConnectorEndpointResolver(sources, connections));
            active.setAgentProfiles(profiles);
            assertThat(active.activeId(ConnectorType.JIRA)).as("what useServer lists as in use").isEqualTo(vendor.id());
        }

        @Test
        @DisplayName("an ordinary conversation, and one with no conversation at all (MCP), resolve as they always have")
        void noPinNoChange() {
            add("ana", "Company Jira", "https://jira.company.example", "company-token", true, true, true);
            when(profiles.of(anyString(), anyString())).thenReturn(Optional.empty());
            as("ana");
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("company-token");
            SearchContext.clear();
            SearchContext.setUserId("ana");
            assertThat(resolver.target(ConnectorType.JIRA, "Jira", null, Access.READ).credential()).isEqualTo("company-token");
        }
    }

    /** YouTube's tools resolve with no source key at all: the pin is what ties an agent to one of two channels. */
    @Nested
    class YouTube {

        private final ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
        private final Endpoint main = new Endpoint(UUID.randomUUID(), "Main channel", null, "main-token", true, true);
        private final Endpoint second = new Endpoint(UUID.randomUUID(), "Second channel", null, "second-token", true, false);

        @BeforeEach
        void setUp() {
            when(endpoints.forSource(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any())).thenReturn(main);
            when(endpoints.forType(eq(ConnectorType.YOUTUBE_CHANNEL), any())).thenReturn(main);
            when(endpoints.byId(ConnectorType.YOUTUBE_CHANNEL, second.connectionId(), "ana")).thenReturn(second);
            when(endpoints.byId(eq(ConnectorType.YOUTUBE_CHANNEL), eq(second.connectionId()), eq("bob"))).thenReturn(Endpoint.EMPTY);
        }

        @Test
        @DisplayName("a pinned channel is the one read and replied on - and its Write switch is the one checked")
        void pinnedChannel() {
            ToolConnectorResolver resolver = resolverOver(endpoints);
            pin("ana", Map.of("youtube", second.connectionId()));
            as("ana");

            assertThat(resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, "YouTube", null, Access.READ).connectionName())
                    .isEqualTo("Second channel");
            assertThatThrownBy(() -> resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, "YouTube", null, Access.WRITE))
                    .hasMessageContaining("\"Second channel\"");
        }

        @Test
        @DisplayName("another member talking to the same agent is not lent the channel: their own default is used")
        void anotherMember() {
            ToolConnectorResolver resolver = resolverOver(endpoints);
            pin("bob", Map.of("youtube", second.connectionId()));
            as("bob");

            assertThat(resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, "YouTube", null, Access.WRITE).connectionName())
                    .isEqualTo("Main channel");
        }

        @Test
        @DisplayName("a pin for another service changes nothing here")
        void otherService() {
            ToolConnectorResolver resolver = resolverOver(endpoints);
            pin("ana", Map.of("jira", second.connectionId()));
            as("ana");
            assertThat(resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, "YouTube", null, Access.READ).connectionName())
                    .isEqualTo("Main channel");
        }
    }
}
