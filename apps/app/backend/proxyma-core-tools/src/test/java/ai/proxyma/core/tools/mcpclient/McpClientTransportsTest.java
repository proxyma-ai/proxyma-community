package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.settings.connection.ConnectionStatus;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The MCP client against real servers over all three transports - a child process over stdio, a
 * streamable HTTP endpoint and an SSE one - through the same McpClientManager the chat uses.
 *
 * <p>What it pins: each transport connects, lists and calls; a server that demands a token gets the
 * stored one and is refused without it; and a LOCAL server starts with a clean environment -
 * Proxyma's own variables (provider keys, database passwords) never reach a program somebody
 * downloaded.
 */
class McpClientTransportsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer http;
    private McpClientManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.closeAll();
        if (http != null) http.stop(0);
    }

    private McpClientManager manager() {
        manager = new McpClientManager(WebClient.builder(), mock(McpOAuthService.class), "test");
        return manager;
    }

    private static ResolvedMcpServer server(McpServerConfig config, String url, McpServerConfig.McpSecrets secrets) {
        return new ResolvedMcpServer(UUID.randomUUID(), "_local", "Test server", url, config,
                secrets == null ? McpServerConfig.McpSecrets.empty() : secrets, false, ConnectionStatus.CONNECTED, "v1");
    }

    @Test
    @DisplayName("stdio: a child process lists and calls, and sees only the clean environment plus its own variables")
    void stdio() {
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        Path classes = Path.of(FakeStdioMcpServer.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                .replaceFirst("^/([A-Za-z]:)", "$1"));
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.STDIO, javaExe,
                List.of("-cp", classes.toString(), FakeStdioMcpServer.class.getName()),
                McpServerConfig.Auth.NONE, null, List.of("FAKE_MCP_GREETING"), null);
        ResolvedMcpServer server = server(config, null,
                new McpServerConfig.McpSecrets(null, null, Map.of("FAKE_MCP_GREETING", "hello"), null, null));

        McpClientManager.Session session = manager().session(server);
        assertEquals(List.of("echo", "environment"), session.tools().stream().map(McpSchema.Tool::name).toList());
        assertEquals("ping", text(manager.call(server, "echo", Map.of("text", "ping"))));

        String env = text(manager.call(server, "environment", Map.of()));
        assertTrue(env.startsWith("greeting=hello;"), env);
        Set<String> names = new HashSet<>(Arrays.asList(env.substring(env.indexOf("names=") + 6).split(",")));
        names.remove("FAKE_MCP_GREETING");
        for (String name : names) {
            assertTrue(McpTransports.INHERITED_ENV.contains(name.toUpperCase(java.util.Locale.ROOT)),
                    "the local server inherited '" + name + "' from Proxyma");
        }
    }

    @Test
    @DisplayName("streamable HTTP: the stored bearer token is sent, and a server refusing the request is reported, not hung on")
    void streamableHttpWithToken() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, ""); return; }
            if (!"Bearer t-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) { reply(ex, 401, ""); return; }
            JsonNode msg = JSON.readTree(ex.getRequestBody());
            JsonNode out = respond(msg);
            if (out == null) { reply(ex, 202, ""); return; }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            reply(ex, 200, JSON.writeValueAsString(out));
        });
        http.start();
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.STREAMABLE_HTTP, null, null,
                McpServerConfig.Auth.BEARER, null, null, null);

        ResolvedMcpServer good = server(config, url, new McpServerConfig.McpSecrets("t-1", null, null, null, null));
        assertEquals("5", text(manager().call(good, "add", Map.of("a", 2, "b", 3))));
        // A result past WebClient's default 256 KB buffer arrives whole.
        assertEquals(1_000_000, text(manager.call(good, "add", Map.of("big", true))).length());

        ResolvedMcpServer wrong = server(config, url, new McpServerConfig.McpSecrets("t-2", null, null, null, null));
        McpClientManager.McpServerUnavailableException refused = assertThrows(
                McpClientManager.McpServerUnavailableException.class, () -> manager.session(wrong));
        assertTrue(refused.getMessage().contains("Test server"), refused.getMessage());
    }

    @Test
    @DisplayName("SSE: the endpoint the stream names is posted to, and answers come back on the stream")
    void sse() throws Exception {
        Map<String, OutputStream> streams = new ConcurrentHashMap<>();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.createContext("/sse", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            String session = UUID.randomUUID().toString();
            OutputStream out = ex.getResponseBody();
            streams.put(session, out);
            out.write(("event: endpoint\ndata: /messages?sessionId=" + session + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
        http.createContext("/messages", ex -> {
            String session = ex.getRequestURI().getQuery().replace("sessionId=", "");
            JsonNode out = respond(JSON.readTree(ex.getRequestBody()));
            reply(ex, 202, "");
            if (out == null) return;
            OutputStream stream = streams.get(session);
            synchronized (stream) {
                stream.write(("event: message\ndata: " + JSON.writeValueAsString(out) + "\n\n").getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        });
        http.start();
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/sse";
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.SSE, null, null,
                McpServerConfig.Auth.NONE, null, null, null);

        assertEquals("7", text(manager().call(server(config, url, null), "add", Map.of("a", 3, "b", 4))));
    }

    @Test
    @DisplayName("streamable HTTP: a server's root typed for its endpoint - 404, 405 or a web page - is told apart from other failures")
    void endpointMissingFromTheAddress() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/missing", ex -> reply(ex, 404, "<html>Not Found</html>"));
        http.createContext("/method", ex -> reply(ex, 405, ""));
        http.createContext("/page", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            reply(ex, 200, "<!DOCTYPE html><html><body>Sign in</body></html>");
        });
        http.createContext("/gone/mcp", ex -> reply(ex, 404, ""));
        http.createContext("/refused", ex -> reply(ex, 500, ""));
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.STREAMABLE_HTTP, null, null,
                McpServerConfig.Auth.NONE, null, null, null);

        for (String path : List.of("/missing", "/method/", "/page")) {
            ResolvedMcpServer s = server(config, base + path, null);
            RuntimeException e = assertThrows(RuntimeException.class, () -> manager().probe(s));
            assertTrue(McpServerToolSource.missesItsEndpoint(s, e), path + ": " + e.getMessage());
        }
        // At an address that already ends in /mcp the path is not the problem; nor is a server error.
        for (String path : List.of("/gone/mcp", "/refused")) {
            ResolvedMcpServer s = server(config, base + path, null);
            RuntimeException e = assertThrows(RuntimeException.class, () -> manager().probe(s));
            assertTrue(!McpServerToolSource.missesItsEndpoint(s, e), path + ": " + e.getMessage());
        }
    }

    @Test
    @DisplayName("a result the server marks as an error is the failure envelope; media is named, not passed on")
    void rendering() {
        String failed = McpServerToolSource.render(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("no such ticket")), true));
        assertTrue(failed.startsWith("{\"success\":false"), failed);
        assertTrue(failed.contains("no such ticket"));
        String image = McpServerToolSource.render(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("chart:"), new McpSchema.ImageContent(null, "AAAA", "image/png")), false));
        assertEquals("chart:\n[image, image/png, not shown]", image);
    }

    // -- a minimal server ------------------------------------------------------------------------------

    private static JsonNode respond(JsonNode msg) {
        if (!msg.hasNonNull("id")) return null;
        ObjectNode out = JSON.createObjectNode().put("jsonrpc", "2.0").set("id", msg.get("id"));
        String method = msg.path("method").asText();
        ObjectNode result = JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", msg.path("params").path("protocolVersion").asText("2025-06-18"));
                result.set("capabilities", JSON.createObjectNode().set("tools", JSON.createObjectNode()));
                result.set("serverInfo", JSON.createObjectNode().put("name", "fake-http").put("version", "1"));
            }
            case "tools/list" -> result.set("tools", JSON.createArrayNode().add(JSON.createObjectNode()
                    .put("name", "add").put("description", "Adds")
                    .set("inputSchema", JSON.createObjectNode().put("type", "object"))));
            case "tools/call" -> {
                JsonNode a = msg.path("params").path("arguments");
                if (a.path("big").asBoolean()) {
                    result.set("content", JSON.createArrayNode().add(JSON.createObjectNode()
                            .put("type", "text").put("text", "x".repeat(1_000_000))));
                    break;
                }
                long sum = a.path("a").asLong() + a.path("b").asLong();
                result.set("content", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("type", "text").put("text", String.valueOf(sum))));
            }
            default -> { }
        }
        out.set("result", result);
        return out;
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("a server that cannot be reached is not dialled again on the next step, and a save lets it try")
    void unreachableServerBacksOff() {
        McpServerConfig config = new McpServerConfig(McpServerConfig.Transport.STREAMABLE_HTTP, null, null,
                McpServerConfig.Auth.NONE, null, null, null);
        int deadPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ResolvedMcpServer dead = server(config, "http://127.0.0.1:" + deadPort + "/mcp", null);
        McpClientManager m = manager();
        assertThrows(McpClientManager.McpServerUnavailableException.class, () -> m.session(dead));
        long started = System.nanoTime();
        McpClientManager.McpServerUnavailableException again = assertThrows(
                McpClientManager.McpServerUnavailableException.class, () -> m.session(dead));
        assertTrue(again.getMessage().contains("not tried again"), again.getMessage());
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "the second attempt dialled");

        m.onConnectionChanged(new ai.proxyma.core.settings.connection.ConnectionChangedEvent(dead.id(), false));
        McpClientManager.McpServerUnavailableException afterSave = assertThrows(
                McpClientManager.McpServerUnavailableException.class, () -> m.session(dead));
        assertTrue(!afterSave.getMessage().contains("not tried again"), afterSave.getMessage());
    }

    @Test
    @DisplayName("a call is sent again only when the server cannot have run it")
    void retryOnlyWhatCannotRunTwice() {
        assertTrue(McpClientManager.looksLikeABrokenConnection(
                new RuntimeException(new java.net.ConnectException("Connection refused"))));
        assertTrue(McpClientManager.looksLikeABrokenConnection(
                new io.modelcontextprotocol.spec.McpTransportSessionNotFoundException("gone")));
        // An answer from the server, whatever it says, and a cut after the request went out.
        assertTrue(!McpClientManager.looksLikeABrokenConnection(new io.modelcontextprotocol.spec.McpError(
                new McpSchema.JSONRPCResponse.JSONRPCError(-32000, "Issue PAY-1 is closed", null))));
        assertTrue(!McpClientManager.looksLikeABrokenConnection(new java.io.IOException("Connection reset")));
        assertTrue(!McpClientManager.looksLikeABrokenConnection(new IllegalStateException("session expired, closed")));
    }

    @Test
    @DisplayName("two tools of one server never share an advertised name, and cmd.exe syntax is refused")
    void namesAndShellSyntax() {
        String part = McpToolNames.serverPart("Files", UUID.randomUUID());
        Set<String> names = new HashSet<>();
        for (String raw : List.of("search", "Search", "files_read", "files.read")) names.add(McpToolNames.toolName(part, raw));
        assertEquals(4, names.size(), names.toString());
        assertEquals(McpToolNames.toolName(part, "search"), "mcp__" + part + "__search");

        assertThrows(IllegalStateException.class, () ->
                McpTransports.refuseShellSyntax("npx", List.of("server", "https://x.example/?a=1&b=2")));
        McpTransports.refuseShellSyntax("npx", List.of("-y", "@scope/server", "--port", "3000"));
    }
}
