package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.settings.connection.ConnectionStatus;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Signing an MCP server in, against a small authorization server: discovery from the 401, dynamic
 * client registration, the authorize address with PKCE, the callback's code exchange, and refresh.
 *
 * <p>What is pinned is what a sign-in gets wrong quietly: a code accepted without its verifier, a
 * state usable twice or by anybody, a token sent to a server it was not issued for.
 */
class McpOAuthServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer http;
    private String base;
    private final Map<String, String> challenges = new LinkedHashMap<>();
    private final AtomicReference<String> registeredRedirect = new AtomicReference<>();
    private int refreshes;

    private UUID id;
    private AtomicReference<ResolvedMcpServer> stored;
    private ConnectorConnectionService connections;
    private McpOAuthService oauth;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource\", scope=\"tools\"");
            reply(ex, 401, "");
        });
        http.createContext("/.well-known/oauth-protected-resource", ex ->
                reply(ex, 200, "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}"));
        http.createContext("/.well-known/oauth-authorization-server", ex -> reply(ex, 200, "{\"issuer\":\"" + base
                + "\",\"authorization_endpoint\":\"" + base + "/authorize\",\"token_endpoint\":\"" + base
                + "/token\",\"registration_endpoint\":\"" + base + "/register\"}"));
        http.createContext("/register", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            registeredRedirect.set(body.path("redirect_uris").get(0).asText());
            reply(ex, 201, "{\"client_id\":\"client-7\"}");
        });
        http.createContext("/token", ex -> {
            Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("authorization_code".equals(form.get("grant_type"))) {
                String expected = challenges.remove(form.get("code"));
                if (expected == null || !expected.equals(s256(form.get("code_verifier")))) {
                    reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                reply(ex, 200, "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");
            } else if (("rt-" + (refreshes + 1)).equals(form.get("refresh_token"))) {
                // Rotating, as many servers do: each refresh token works once and the next is issued.
                refreshes++;
                reply(ex, 200, "{\"access_token\":\"at-" + (refreshes + 1) + "\",\"refresh_token\":\"rt-"
                        + (refreshes + 1) + "\",\"expires_in\":3600}");
            } else {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
            }
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();

        id = UUID.randomUUID();
        stored = new AtomicReference<>(new ResolvedMcpServer(id, "_local", "Docs", base + "/mcp",
                new McpServerConfig(McpServerConfig.Transport.STREAMABLE_HTTP, null, null, McpServerConfig.Auth.OAUTH,
                        null, null, null), McpServerConfig.McpSecrets.empty(), false, null, "v1"));
        connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwnedMcp(anyString(), eq(id))).thenAnswer(inv -> Optional.of(stored.get()));
        when(connections.resolveMcp(id)).thenAnswer(inv -> Optional.of(stored.get()));
        doAnswer(inv -> {
            ResolvedMcpServer s = stored.get();
            stored.set(new ResolvedMcpServer(id, s.ownerId(), s.name(), s.url(), s.config().withOAuth(inv.getArgument(1)),
                    s.secrets(), false, s.status(), s.updatedAt()));
            return null;
        }).when(connections).recordMcpOAuthClient(eq(id), any(), any());
        doAnswer(inv -> {
            ResolvedMcpServer s = stored.get();
            stored.set(new ResolvedMcpServer(id, s.ownerId(), s.name(), s.url(), s.config(),
                    s.secrets().withRefresh(null, inv.getArgument(1)), false, ConnectionStatus.CONNECTED, s.updatedAt()));
            return null;
        }).when(connections).recordMcpGrant(eq(id), any(), any(), anyBoolean());
        oauth = new McpOAuthService(connections, RestClient.builder());
    }

    @AfterEach
    void tearDown() {
        http.stop(0);
    }

    // The authorization server's half: approve at once, remembering the challenge for the code.
    private String approve(String authorizeUrl) {
        Map<String, String> q = form(URI.create(authorizeUrl).getRawQuery());
        String code = "code-" + UUID.randomUUID();
        challenges.put(code, q.get("code_challenge"));
        return code;
    }

    @Test
    @DisplayName("discovery, registration and PKCE, then the code exchange stores the refresh token")
    void signInEndToEnd() {
        String redirect = "http://localhost:4246" + McpOAuthService.CALLBACK_PATH;
        String url = oauth.start("", id, redirect);

        Map<String, String> q = form(URI.create(url).getRawQuery());
        assertTrue(url.startsWith(base + "/authorize?"), url);
        assertEquals("client-7", q.get("client_id"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(base + "/mcp", q.get("resource"));
        assertEquals("tools", q.get("scope"));
        assertEquals(redirect, registeredRedirect.get());

        assertEquals(McpOAuthService.Outcome.SIGNED_IN, oauth.complete(q.get("state"), approve(url), null));
        assertEquals("rt-1", stored.get().secrets().refreshToken());
        assertEquals("at-1", oauth.accessToken(id, base + "/mcp", false));
    }

    @Test
    @DisplayName("a state works once, and a code without its verifier is refused")
    void stateIsSingleUseAndCodeNeedsVerifier() {
        String url = oauth.start("", id, "http://localhost/cb");
        String state = form(URI.create(url).getRawQuery()).get("state");
        String code = approve(url);
        // The authorization server is handed a different challenge: the verifier will not match.
        challenges.put(code, "not-the-challenge");

        assertEquals(McpOAuthService.Outcome.FAILED, oauth.complete(state, code, null));
        assertEquals(McpOAuthService.Outcome.EXPIRED, oauth.complete(state, code, null));
        assertEquals(McpOAuthService.Outcome.EXPIRED, oauth.complete("made-up", "x", null));
        assertEquals(McpOAuthService.Outcome.DENIED,
                oauth.complete(form(URI.create(oauth.start("", id, "http://localhost/cb")).getRawQuery()).get("state"), null, "access_denied"));
    }

    @Test
    @DisplayName("a refused token is refreshed; a token is never sent to a server it was not issued for")
    void refreshAndBinding() {
        String url = oauth.start("", id, "http://localhost/cb");
        oauth.complete(form(URI.create(url).getRawQuery()).get("state"), approve(url), null);

        assertEquals("at-2", oauth.accessToken(id, base + "/mcp", true));
        assertEquals(1, refreshes);
        // The same connection, edited to point elsewhere: the cached token is not handed over.
        // The rotated refresh token is the one used next.
        String elsewhere = oauth.accessToken(id, "https://other.example/mcp", false);
        assertNotEquals("at-2", elsewhere);
        assertEquals("at-3", elsewhere);
        assertEquals(2, refreshes);
    }

    @Test
    @DisplayName("with no grant, a call says to sign in rather than failing obscurely")
    void noGrantSaysSignIn() {
        McpOAuthService.McpSignInException e = assertThrows(McpOAuthService.McpSignInException.class,
                () -> oauth.accessToken(id, base + "/mcp", false));
        assertTrue(e.getMessage().contains("sign in"), e.getMessage());
    }

    @Test
    @DisplayName("a sign-in that gave no refresh token stops reading as signed in once its token is gone")
    void grantWithoutRefreshEnds() {
        ResolvedMcpServer s = stored.get();
        stored.set(new ResolvedMcpServer(id, s.ownerId(), s.name(), s.url(), s.config(), s.secrets(), false,
                ConnectionStatus.CONNECTED, s.updatedAt()));
        assertThrows(McpOAuthService.McpSignInException.class, () -> oauth.accessToken(id, base + "/mcp", false));
        org.mockito.Mockito.verify(connections).markNeedsReconnect(eq(id), anyString());
    }

    // -- helpers ---------------------------------------------------------------------------------------

    private static Map<String, String> form(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }
}
