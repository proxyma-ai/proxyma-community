package ai.proxyma.core.network;

import ai.proxyma.core.system.util.AccessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The single-user editions' network boundary, through the filters in the order they run
 * (editions-assembly-01, registry-sync-01, tools-core-05, connector-framework-01).
 *
 * <p>Desktop and the Home Server have no accounts, and the Host header used to be the whole
 * boundary - a header any client on the network writes itself, on a listener bound to every
 * interface. Every API call now presents the install's access code, as a cookie a handshake sets or
 * as the install's {@code pxm_} token. These run the REAL WebConfig filters and AccessGate against
 * mock exchanges: a request that reaches the end of the chain is a request the app would serve.
 */
class AccessGateTest {

    private static final String CODE = "a-test-access-code-long-enough-1234";
    private static final InetSocketAddress LAN_PEER = new InetSocketAddress("192.168.1.50", 51000);
    private static final InetSocketAddress LOOPBACK_PEER = new InetSocketAddress("127.0.0.1", 51000);

    private AccessGate gate;
    private List<WebFilter> filters;

    @BeforeEach
    void setUp() {
        gate = new AccessGate(CODE, false, "<data>/access-code");
        WebConfig web = new WebConfig(gate);
        ReflectionTestUtils.setField(web, "allowedHostsRaw", "");
        web.resolveExposure();
        filters = List.of(web.hostValidationFilter(), web.exposedCapabilityFilter(),
                web.fetchMetadataFilter(), web.accessFilter());
    }

    /** Runs the request through the filters; true when it reached the handler behind them. */
    private boolean served(ServerWebExchange exchange) {
        AtomicBoolean reached = new AtomicBoolean();
        new FilteringWebHandler(ex -> {
            reached.set(true);
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }, filters).handle(exchange).block();
        return reached.get();
    }

    private static MockServerHttpRequest.BaseBuilder<?> get(String path, InetSocketAddress peer) {
        return MockServerHttpRequest.get(path).remoteAddress(peer).header(HttpHeaders.HOST, "localhost:4246");
    }

    @Nested
    @DisplayName("no credential, no API")
    class Refused {

        @Test
        @DisplayName("an API call with neither cookie nor token is answered 401 access_required")
        void noCredential() {
            MockServerWebExchange exchange = MockServerWebExchange.from(get("/api/v1/settings/network", LOOPBACK_PEER));
            assertThat(served(exchange)).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("access_required");
        }

        @Test
        @DisplayName("a LAN peer sending Host: localhost - the exploit of every report - is refused")
        void forgedHostFromTheNetwork() {
            for (MockServerHttpRequest request : List.of(
                    MockServerHttpRequest.post("/api/v1/config-transfer/export").remoteAddress(LAN_PEER)
                            .header(HttpHeaders.HOST, "localhost").body("{\"passphrase\":\"x\"}"),
                    MockServerHttpRequest.post("/api/v1/connections/instances").remoteAddress(LAN_PEER)
                            .header(HttpHeaders.HOST, "localhost:4246").body("{}"),
                    MockServerHttpRequest.post("/mcp").remoteAddress(LAN_PEER)
                            .header(HttpHeaders.HOST, "127.0.0.1:4246").body("{}"),
                    get("/api/v1/events", LAN_PEER).build())) {
                MockServerWebExchange exchange = MockServerWebExchange.from(request);
                assertThat(served(exchange)).as(request.getPath().value()).isFalse();
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("a wrong cookie or a wrong token is no credential")
        void wrongCredentials() {
            assertThat(served(MockServerWebExchange.from(get("/api/v1/chat/conversations", LOOPBACK_PEER)
                    .cookie(new HttpCookie(AccessGate.COOKIE, CODE))))).as("the code itself is not the cookie").isFalse();
            assertThat(served(MockServerWebExchange.from(get("/mcp", LOOPBACK_PEER)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer pxm_" + "0".repeat(64))))).isFalse();
            assertThat(served(MockServerWebExchange.from(get("/mcp", LOOPBACK_PEER)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + CODE)))).as("the code is not the token").isFalse();
        }
    }

    @Nested
    @DisplayName("the credentials")
    class Admitted {

        @Test
        @DisplayName("GET /?access=<code> sets an HttpOnly, SameSite=Strict session cookie and leaves the code out of the address")
        void handshakeSetsTheCookie() {
            MockServerWebExchange exchange = MockServerWebExchange.from(get("/?access=" + CODE, LOOPBACK_PEER));
            assertThat(served(exchange)).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("/");
            ResponseCookie cookie = exchange.getResponse().getCookies().getFirst(AccessGate.COOKIE);
            assertThat(cookie).isNotNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo("Strict");
            assertThat(cookie.getMaxAge().isNegative()).as("a session cookie has no Max-Age").isTrue();
            assertThat(cookie.getValue()).isNotEqualTo(CODE);

            // ...and that cookie opens the API.
            assertThat(served(MockServerWebExchange.from(get("/api/v1/settings/network", LOOPBACK_PEER)
                    .cookie(new HttpCookie(AccessGate.COOKIE, cookie.getValue()))))).isTrue();
        }

        @Test
        @DisplayName("a wrong code redirects too, with no cookie - the page then asks for the code")
        void wrongHandshake() {
            MockServerWebExchange exchange = MockServerWebExchange.from(get("/?access=not-the-code", LOOPBACK_PEER));
            served(exchange);
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(exchange.getResponse().getCookies()).doesNotContainKey(AccessGate.COOKIE);
        }

        @Test
        @DisplayName("a one-time code works once")
        void oneTimeCode() {
            String once = gate.issueOneTimeCode();
            MockServerWebExchange first = MockServerWebExchange.from(get("/?access=" + once, LOOPBACK_PEER));
            served(first);
            assertThat(first.getResponse().getCookies()).containsKey(AccessGate.COOKIE);
            MockServerWebExchange again = MockServerWebExchange.from(get("/?access=" + once, LOOPBACK_PEER));
            served(again);
            assertThat(again.getResponse().getCookies()).doesNotContainKey(AccessGate.COOKIE);
        }

        @Test
        @DisplayName("an MCP client with the install's pxm_ token reaches /mcp, from anywhere it can route from")
        void mcpWithTheToken() {
            String token = gate.bearerToken();
            assertThat(token).matches("pxm_[0-9a-f]{64}");
            assertThat(served(MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").remoteAddress(LOOPBACK_PEER)
                    .header(HttpHeaders.HOST, "localhost:4246")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body("{}")))).isTrue();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/search?q=x", LAN_PEER)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))).as("and the API, like Enterprise's tokens").isTrue();
        }

        @Test
        @DisplayName("POST /api/v1/access with the code sets the cookie; a wrong one is 401 access_denied")
        void enterScreen() {
            AccessController controller = new AccessController(gate);
            MockServerWebExchange ok = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/access"));
            ResponseEntity<Object> admitted = controller.enter(Map.of("code", CODE), ok).block();
            assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(ok.getResponse().getCookies()).containsKey(AccessGate.COOKIE);

            MockServerWebExchange wrong = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/access"));
            ResponseEntity<Object> refused = controller.enter(Map.of("code", "guess"), wrong).block();
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refused.getBody()).isEqualTo(Map.of("error", "access_denied"));
            assertThat(wrong.getResponse().getCookies()).doesNotContainKey(AccessGate.COOKIE);
        }
    }

    @Nested
    @DisplayName("what stays open")
    class Open {

        @Test
        @DisplayName("the health probes answer with no credential")
        void probes() {
            for (String path : List.of("/api/v1/health", "/api/v1/health/live", "/api/v1/health/ready")) {
                assertThat(served(MockServerWebExchange.from(get(path, LAN_PEER)))).as(path).isTrue();
            }
            assertThat(served(MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.DELETE, "/api/v1/health")
                    .remoteAddress(LAN_PEER).header(HttpHeaders.HOST, "localhost")))).as("GET only").isFalse();
        }

        @Test
        @DisplayName("the page and its assets load, so the access screen can be shown")
        void pageAndAssets() {
            for (String path : List.of("/", "/index.html", "/assets/index-abc.js", "/appearance-init.js", "/chat/123")) {
                assertThat(served(MockServerWebExchange.from(get(path, LAN_PEER)))).as(path).isTrue();
            }
        }

        @Test
        @DisplayName("the handshake POST and the two OAuth callbacks, which carry their own single-use state")
        void handshakeAndCallbacks() {
            assertThat(served(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/access")
                    .remoteAddress(LOOPBACK_PEER).header(HttpHeaders.HOST, "localhost").body("{}")))).isTrue();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/microsoft/oauth/callback?state=s", LOOPBACK_PEER))))
                    .as("Microsoft 365's callback is Enterprise Server's alone").isFalse();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/mcp-oauth/callback?state=s", LOOPBACK_PEER)))).isTrue();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/connections/oauth/callback?state=s", LOOPBACK_PEER)))).isTrue();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/connections/instances/x/oauth/start", LOOPBACK_PEER))))
                    .as("starting a sign-in still needs a credential").isFalse();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/access", LOOPBACK_PEER))))
                    .as("asking whether one is in still needs a credential").isFalse();
            assertThat(served(MockServerWebExchange.from(get("/api/v1/access/token", LOOPBACK_PEER)))).isFalse();
        }
    }

    @Nested
    @DisplayName("a custom source's ingest token")
    class IngestToken {

        private static final String SOURCE = "6f1d2c3b-4a59-4e6f-8a7b-1c2d3e4f5a6b";
        private static final String OTHER = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        private static final String TOKEN = "pxm_" + "3c".repeat(32);

        @BeforeEach
        void registry() {
            // The registry's answer: this token belongs to SOURCE, and nothing else is an ingest token.
            ai.proxyma.core.common.ingest.IngestTokenCheck check =
                    (source, raw) -> source.toString().equals(SOURCE) && raw.equals(TOKEN);
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.common.ingest.IngestTokenCheck> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(check);
            gate.setIngestTokens(provider);
        }

        private boolean servedWith(HttpMethod method, String path, String bearer) {
            return served(MockServerWebExchange.from(MockServerHttpRequest.method(method, path).remoteAddress(LAN_PEER)
                    .header(HttpHeaders.HOST, "localhost:4246").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .body("{}")));
        }

        @Test
        @DisplayName("opens its own source's ingestion endpoints without the access code - a script carries no more than it needs")
        void opensItsOwnSource() {
            String documents = "/api/v1/ingest/sources/" + SOURCE + "/documents";
            assertThat(servedWith(HttpMethod.POST, documents, TOKEN)).isTrue();
            assertThat(servedWith(HttpMethod.GET, documents, TOKEN)).isTrue();
            assertThat(servedWith(HttpMethod.POST, documents + "/batch", TOKEN)).isTrue();
            assertThat(servedWith(HttpMethod.DELETE, documents + "/release-notes-2", TOKEN)).isTrue();
        }

        @Test
        @DisplayName("opens nothing else - not another source's endpoints, not the rest of the API, not /mcp")
        void opensNothingElse() {
            assertThat(servedWith(HttpMethod.POST, "/api/v1/ingest/sources/" + OTHER + "/documents", TOKEN)).isFalse();
            assertThat(servedWith(HttpMethod.GET, "/api/v1/indexing/" + SOURCE + "/ingest", TOKEN))
                    .as("making tokens is the owner's, behind the access code").isFalse();
            assertThat(servedWith(HttpMethod.GET, "/api/v1/indexing/summary", TOKEN)).isFalse();
            assertThat(servedWith(HttpMethod.POST, "/api/v1/search", TOKEN)).isFalse();
            assertThat(servedWith(HttpMethod.POST, "/mcp", TOKEN)).isFalse();
            assertThat(servedWith(HttpMethod.GET, "/api/v1/ingest/sources/" + SOURCE + "/documents/a/b", TOKEN))
                    .as("only the routes the API has").isFalse();
            assertThat(servedWith(HttpMethod.PUT, "/api/v1/ingest/sources/" + SOURCE + "/documents", TOKEN)).isFalse();
        }

        @Test
        @DisplayName("a token the registry does not know opens no ingestion endpoint either")
        void unknownToken() {
            assertThat(servedWith(HttpMethod.POST, "/api/v1/ingest/sources/" + SOURCE + "/documents",
                    "pxm_" + "0".repeat(64))).isFalse();
        }
    }

    @Nested
    @DisplayName("the Host check is still the DNS-rebinding defense")
    class Rebinding {

        @Test
        @DisplayName("a Host header this instance does not answer to is refused before the code is asked")
        void unknownHost() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/settings/network")
                    .remoteAddress(LOOPBACK_PEER).header(HttpHeaders.HOST, "evil.example:4246")
                    .cookie(new HttpCookie(AccessGate.COOKIE, AccessCode.sessionValue(CODE))));
            assertThat(served(exchange)).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
