package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import ai.proxyma.core.license.Features;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import ai.proxyma.core.settings.service.WriteToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An "MCP server" connection: how Proxyma reaches somebody's MCP server, and what it keeps of it.
 *
 * <p>Security tests wearing behavior clothes, as the other connection tests are. What is pinned is
 * what fails silently: a bearer token, header value or environment value written in clear text or
 * shown back; a value erased by an unrelated edit; an OAuth grant carried to a server it was not
 * issued for; somebody else's private server offered to a user; and the one switch every MCP tool
 * call depends on - that it is a write, and asks first.
 */
class McpServerConnectionTest {

    private static final String ANA = "ana";
    private static final String BO = "bo";

    private final Map<UUID, ConnectorConnection> rows = new LinkedHashMap<>();
    private ConnectorConnectionRepository repository;
    private CredentialEncryptionService encryption;
    private ConnectorConnectionService server;
    private ConnectorConnectionService desktop;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectorConnectionRepository.class);
        when(repository.save(any(ConnectorConnection.class))).thenAnswer(inv -> {
            ConnectorConnection c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            rows.put(c.getId(), c);
            return c;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
        when(repository.findByIdAndOwnerId(any(UUID.class), anyString())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))).filter(c -> c.getOwnerId().equals(inv.getArgument(1))));
        when(repository.findByOwnerIdOrderByNameAsc(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0))).sorted(Comparator.comparing(ConnectorConnection::getName)).toList());
        when(repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(anyString(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0)) && c.getProtocol() == inv.getArgument(1)).toList());
        when(repository.findByOwnerIdAndNameIgnoreCase(anyString(), anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0)) && c.getName().equalsIgnoreCase(inv.getArgument(1))).findFirst());
        // The repository query, as written: MCP servers with Read on, the owner's own or shared.
        when(repository.findMcpServersUsableBy(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getProtocol() == ConnectionProtocol.MCP_SERVER && c.isAllowRead()
                        && (c.getOwnerId().equals(inv.getArgument(0)) || c.isShared()))
                .sorted(Comparator.comparing(ConnectorConnection::getName)).toList());

        encryption = mock(CredentialEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            return v.substring(4, v.length() - 1);
        });
        // The LOWEST tier: an MCP server is on every tier.
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenAnswer(inv -> Features.forTier(LicenseTier.COMMUNITY));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConnectionUsage> usage = mock(ObjectProvider.class);

        server = new ConnectorConnectionService(repository, encryption, mock(ApplicationEventPublisher.class), gate, usage, true);
        desktop = new ConnectorConnectionService(repository, encryption, mock(ApplicationEventPublisher.class), gate, usage, false);
    }

    private static ConnectorConnectionService.ConnectionInput http(String name, String url, McpServerConfig.Auth auth,
                                                                   String token, Map<String, String> headers, Boolean shared) {
        return new ConnectorConnectionService.ConnectionInput(name, ConnectionProtocol.MCP_SERVER, url, null, token,
                false, null, null, null, new ConnectorConnectionService.McpInput(
                McpServerConfig.Transport.STREAMABLE_HTTP, null, null, auth, headers, null, shared, null, null, null));
    }

    private static ConnectorConnectionService.ConnectionInput stdio(String name, String command, List<String> args,
                                                                    Map<String, String> env) {
        return new ConnectorConnectionService.ConnectionInput(name, ConnectionProtocol.MCP_SERVER, null, null, null,
                false, null, null, null, new ConnectorConnectionService.McpInput(
                McpServerConfig.Transport.STDIO, command, args, null, null, env, null, null, null, null));
    }

    @Test
    @DisplayName("on every tier, a bearer token is stored encrypted and shown only masked; Write is never on")
    void bearerTokenIsEncryptedAndMasked() {
        ConnectorConnectionView view = server.create(ANA, http("Docs", "https://mcp.example.com/mcp",
                McpServerConfig.Auth.BEARER, "sk-live-abcdefghijklmnop", null, null));

        assertEquals("sk-l***nop", view.secretMasked());
        assertEquals(McpServerConfig.Transport.STREAMABLE_HTTP, view.mcp().transport());
        assertFalse(view.allowWrite());
        assertTrue(rows.get(view.id()).getSecret().startsWith("ENC("));
        assertFalse(rows.get(view.id()).getMcpConfig().contains("sk-live"));
        assertEquals("sk-live-abcdefghijklmnop", server.resolveMcp(view.id()).orElseThrow().secrets().bearerToken());
    }

    @Test
    @DisplayName("header and environment VALUES stay secret: the view and the settings carry names only")
    void headerAndEnvValuesAreNeverShown() {
        ConnectorConnectionView http = server.create(ANA, http("Tickets", "https://mcp.example.com/mcp",
                McpServerConfig.Auth.HEADERS, null, Map.of("X-Api-Key", "k-123"), null));
        ConnectorConnectionView local = desktop.create(null, stdio("Files", "npx", List.of("-y", "files-mcp"),
                Map.of("FILES_TOKEN", "t-456")));

        assertEquals(List.of("X-Api-Key"), http.mcp().headerNames());
        assertEquals(List.of("FILES_TOKEN"), local.mcp().envNames());
        String everything = http + local.toString() + rows.get(http.id()).getMcpConfig() + rows.get(local.id()).getMcpConfig();
        assertFalse(everything.contains("k-123"));
        assertFalse(everything.contains("t-456"));
        assertEquals("t-456", desktop.resolveMcp(local.id()).orElseThrow().secrets().env().get("FILES_TOKEN"));
    }

    @Test
    @DisplayName("an edit keeps a value sent blank, takes a new one, and drops a name left out")
    void editsMergeSecretsByName() {
        ConnectorConnectionView v = desktop.create(null, stdio("Files", "npx", List.of("files-mcp"),
                Map.of("A", "1", "B", "2", "C", "3")));
        desktop.update(null, v.id(), new ConnectorConnectionService.ConnectionInput("Files", null, null, null, null,
                false, null, null, null, new ConnectorConnectionService.McpInput(null, null, null, null, null,
                Map.of("A", "", "B", "22"), null, null, null, null)));

        Map<String, String> env = desktop.resolveMcp(v.id()).orElseThrow().secrets().env();
        assertEquals(Map.of("A", "1", "B", "22"), env);
    }

    @Test
    @DisplayName("each transport needs what it runs on, and names must be names")
    void validation() {
        assertThrows(ConnectorConnectionService.InvalidConnectionException.class,
                () -> desktop.create(null, stdio("Local", " ", List.of(), null)));
        assertThrows(ConnectorConnectionService.InvalidConnectionException.class,
                () -> server.create(ANA, http("Remote", null, McpServerConfig.Auth.NONE, null, null, null)));
        assertThrows(ConnectorConnectionService.InvalidConnectionException.class,
                () -> server.create(ANA, http("Remote", "ftp://example.com", McpServerConfig.Auth.NONE, null, null, null)));
        assertThrows(ConnectorConnectionService.InvalidConnectionException.class,
                () -> server.create(ANA, http("Remote", "https://x.example", McpServerConfig.Auth.HEADERS, null,
                        Map.of("Bad Header", "v"), null)));
        assertThrows(ConnectorConnectionService.InvalidConnectionException.class,
                () -> desktop.create(null, stdio("Local", "npx", List.of(), Map.of("1BAD", "v"))));
    }

    @Test
    @DisplayName("a grant is forgotten when the connection is pointed at another server")
    void movingTheServerForgetsTheGrant() {
        ConnectorConnectionView v = server.create(ANA, http("Docs", "https://a.example/mcp",
                McpServerConfig.Auth.OAUTH, null, null, null));
        server.recordMcpOAuthClient(v.id(), new McpServerConfig.OAuthClient("client-1", "https://a.example/authorize",
                "https://a.example/token", "https://a.example/mcp", null, true), null);
        server.recordMcpGrant(v.id(), "refresh-1", Instant.now(), true);
        assertTrue(server.get(ANA, v.id()).mcp().signedIn());

        server.update(ANA, v.id(), http("Docs", "https://b.example/mcp", null, null, null, null));

        ResolvedMcpServer moved = server.resolveMcp(v.id()).orElseThrow();
        assertNull(moved.secrets().refreshToken());
        assertNull(moved.config().oauth());
        assertFalse(server.get(ANA, v.id()).mcp().signedIn());
    }

    @Test
    @DisplayName("a person's agent gets their own servers and the shared ones - never another's private one, never one switched off")
    void usableServers() {
        ConnectorConnectionView anaPrivate = server.create(ANA, http("Ana's", "https://a.example/mcp", null, null, null, null));
        server.create(BO, http("Bo's", "https://b.example/mcp", null, null, null, null));
        ConnectorConnectionView shared = server.create(BO, http("Company", "https://c.example/mcp", null, null, null, true));
        ConnectorConnectionView off = server.create(ANA, http("Off", "https://d.example/mcp", null, null, null, null));
        server.update(ANA, off.id(), new ConnectorConnectionService.ConnectionInput("Off", null, null, null, null,
                false, null, false, null, null));

        List<UUID> ids = server.mcpServersUsableBy(ANA).stream().map(ResolvedMcpServer::id).toList();
        assertEquals(List.of(anaPrivate.id(), shared.id()), ids);
    }

    @Test
    @DisplayName("sharing with everyone exists only on Enterprise Server")
    void sharedOnlyOnServer() {
        ConnectorConnectionView local = desktop.create(null, http("Docs", "https://a.example/mcp", null, null, null, true));
        assertFalse(local.mcp().shared());
    }

    @Test
    @DisplayName("every MCP tool call is a write, whatever the server says about it - so it asks first")
    void everyMcpToolIsAWrite() {
        assertTrue(WriteToolRegistry.isWriteCall("mcp__docs_1a2b__search", "{}"));
        assertTrue(WriteToolRegistry.isWriteCall("mcp__docs_1a2b__search", Map.of()));
        assertFalse(WriteToolRegistry.isWriteCall("searchIndexedSources", "{}"));
    }
}
