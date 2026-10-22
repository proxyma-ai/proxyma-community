package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The connected MCP servers as the chat sees them: one skill per server, its tools behind it, and
 * each person seeing only the servers they may use.
 */
class McpServerToolSourceTest {

    private ConnectorConnectionService connections;
    private McpClientManager clients;
    private McpServerToolSource source;
    private ResolvedMcpServer anas;

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description("Does " + name)
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build();
    }

    @BeforeEach
    void setUp() {
        connections = mock(ConnectorConnectionService.class);
        clients = mock(McpClientManager.class);
        source = new McpServerToolSource(connections, clients, roles(null), false);
        anas = new ResolvedMcpServer(UUID.randomUUID(), "ana", "Tickets", "https://t.example/mcp",
                new McpServerConfig(McpServerConfig.Transport.STREAMABLE_HTTP, null, null, null, null, null, null),
                McpServerConfig.McpSecrets.empty(), false, null, "v1");
        when(connections.mcpServersUsableBy("ana")).thenReturn(List.of(anas));
        when(connections.mcpServersUsableBy("bo")).thenReturn(List.of());
        McpClientManager.Session session = new McpClientManager.Session(anas.id(), "v1", null, "tickets", "Use for tickets.",
                List.of(tool("search"), tool("create_ticket")), java.time.Instant.now(),
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now()));
        when(clients.session(any())).thenReturn(session);
        when(clients.knownTools(anas.id())).thenReturn(session.tools());
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.settings.connection.UserRoles> roles(
            ai.proxyma.core.settings.connection.UserRoles lookup) {
        org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.settings.connection.UserRoles> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);
        return provider;
    }

    @Test
    @DisplayName("on a multi-user server, a local-command server starts only when its owner is a Super admin now")
    void localServersNeedASuperAdminOwner() {
        ResolvedMcpServer local = new ResolvedMcpServer(UUID.randomUUID(), "ana", "Shell", null,
                new McpServerConfig(McpServerConfig.Transport.STDIO, "npx", List.of("server"), null, null, null, null),
                McpServerConfig.McpSecrets.empty(), true, null, "v1");
        when(connections.mcpServersUsableBy("cy")).thenReturn(List.of(anas, local));

        McpServerToolSource multi = new McpServerToolSource(connections, clients, roles(userId -> false), true);
        assertEquals(List.of("MCP: Tickets"), multi.skillsFor("cy").stream().map(s -> s.name()).toList());
        assertFalse(multi.readSkill("MCP: Shell", "cy").isPresent());
        assertTrue(multi.toolsFor("cy", Set.of("MCP: Shell")).isEmpty());
        assertFalse(multi.probe(local).ok());
        // No way to read the role is no local server at all.
        assertFalse(new McpServerToolSource(connections, clients, roles(null), true).mayStart(local));

        McpServerToolSource admins = new McpServerToolSource(connections, clients, roles("ana"::equals), true);
        assertEquals(2, admins.skillsFor("cy").size());
        // Desktop and Home Server have one user, who may start what they added.
        assertTrue(source.mayStart(local));
    }

    @Test
    @DisplayName("the system prompt lists a skill per server, without reaching any server")
    void skillsDoNotConnect() {
        assertEquals(List.of("MCP: Tickets"), source.skillsFor("ana").stream().map(s -> s.name()).toList());
        verify(clients, never()).session(any());
    }

    @Test
    @DisplayName("reading the skill gives the server's instructions and every tool under its advertised name")
    void readingTheSkill() {
        String text = source.readSkill("MCP: Tickets", "ana").orElseThrow();
        String part = McpToolNames.serverPart("Tickets", anas.id());
        assertTrue(text.contains("Use for tickets."), text);
        assertTrue(text.contains("`" + McpToolNames.toolName(part, "create_ticket") + "`"), text);
        assertTrue(text.contains("waits for their approval"), text);
    }

    @Test
    @DisplayName("tools are offered only once the skill is read, and only to a person who may use the server")
    void toolsFollowTheSkillAndThePerson() {
        assertTrue(source.toolsFor("ana", Set.of("Some other skill")).isEmpty());
        List<ToolCallback> tools = source.toolsFor("ana", Set.of("MCP: Tickets"));
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).getToolDefinition().name().startsWith("mcp__tickets_"));

        String name = tools.get(1).getToolDefinition().name();
        assertTrue(source.find("ana", name).isPresent());
        // Bo has no such server: a name copied from Ana's conversation reaches nothing for him.
        assertFalse(source.find("bo", name).isPresent());
        assertFalse(source.readSkill("MCP: Tickets", "bo").isPresent());
        assertTrue(source.toolsFor("bo", Set.of("MCP: Tickets")).isEmpty());
    }

    @Test
    @DisplayName("Test says what it reached, with the values filled in and carried for the page to translate")
    void probeMessage() {
        when(clients.probe(anas)).thenReturn(new McpClientManager.ProbeResult("tickets", 2));
        var ok = source.probe(anas);
        assertTrue(ok.ok());
        assertEquals("Connected to tickets. Tools available: 2.", ok.message());
        assertEquals("Connected to {server}. Tools available: {count}.", ok.template());

        when(clients.probe(anas)).thenThrow(new McpClientManager.McpServerUnavailableException("Tickets", new java.net.ConnectException("Connection refused")));
        var failed = source.probe(anas);
        assertFalse(failed.ok());
        assertTrue(failed.message().startsWith("Could not connect: "), failed.message());
        assertFalse(failed.message().contains("{reason}"), failed.message());
        assertEquals("Could not connect: {reason}", failed.template());

        // A 404 at a server's root: the raw reason, then where MCP servers usually answer.
        ResolvedMcpServer root = new ResolvedMcpServer(UUID.randomUUID(), "ana", "Tickets", "https://t.example/",
                anas.config(), McpServerConfig.McpSecrets.empty(), false, null, "v1");
        when(clients.probe(root)).thenThrow(new McpClientManager.McpServerUnavailableException("Tickets",
                org.springframework.web.reactive.function.client.WebClientResponseException.create(404, "Not Found",
                        null, null, null)));
        var hinted = source.probe(root);
        assertFalse(hinted.ok());
        assertEquals("Could not connect: {reason}. MCP servers usually answer at an address ending in /mcp "
                + "(or /sse for the older transport).", hinted.template());
        assertTrue(hinted.message().startsWith("Could not connect: 404 Not Found."), hinted.message());
    }

    @Test
    @DisplayName("advertised names fit every provider: the mcp__ prefix, 64 characters, a safe alphabet, unique")
    void names() {
        UUID id = UUID.randomUUID();
        String part = McpToolNames.serverPart("My Company's Big Server!", id);
        String longName = "x".repeat(200);
        String a = McpToolNames.toolName(part, longName + "a");
        String b = McpToolNames.toolName(part, longName + "b");
        for (String n : List.of(a, b, McpToolNames.toolName(part, "get file/contents"))) {
            assertTrue(n.startsWith("mcp__"), n);
            assertTrue(n.length() <= 64, n);
            assertTrue(n.matches("[a-z0-9_-]+"), n);
        }
        assertFalse(a.equals(b));
        Set<String> parts = new HashSet<>();
        IntStream.range(0, 50).forEach(i -> parts.add(McpToolNames.serverPart("Same name", UUID.randomUUID())));
        assertTrue(parts.size() > 40, "two connections with one name must not share their tools' names");
    }
}
