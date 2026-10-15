package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Whose budget a request spends. Everything but MCP was counted against the client's address, and
 * the users of one organization share an address - a VPN, an office NAT - so a handful of open
 * tabs spent the budget of everybody behind it.
 */
class ApiRateLimitKeyTest {

    private static final WebFilterChain PASS = exchange -> Mono.empty();
    private static final String SESSION = "X-Test-Session";

    // Stands in for the signed access token: the header's value is the verified user.
    private static ApiRateLimitFilter filterWithSessions() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter();
        filter.setIdentity(exchange -> exchange.getRequest().getHeaders().getFirst(SESSION));
        return filter;
    }

    // Every request comes from ONE address, as behind an office NAT.
    private static HttpStatus send(ApiRateLimitFilter filter, String method, String path, String user) {
        MockServerHttpRequest.BaseBuilder<?> request = "POST".equals(method)
                ? MockServerHttpRequest.post(path) : MockServerHttpRequest.get(path);
        request.remoteAddress(new InetSocketAddress("10.0.0.7", 5000));
        if (user != null) request.header(SESSION, user);
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, PASS).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static HttpStatus sendTimes(ApiRateLimitFilter filter, String method, String path, String user, int times) {
        HttpStatus last = null;
        for (int i = 0; i < times; i++) last = send(filter, method, path, user);
        return last;
    }

    @Test
    @DisplayName("signed-in users behind one address each have a budget of their own")
    void usersBehindOneAddressDoNotShare() {
        ApiRateLimitFilter filter = filterWithSessions();

        for (int user = 0; user < 10; user++) {
            assertNull(sendTimes(filter, "GET", "/api/v1/chat/conversations", "user-" + user, 120),
                    "user-" + user + " is inside their own 120 a minute");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "GET", "/api/v1/chat/conversations", "user-0"),
                "and one past it is refused for that user");
        assertNull(send(filter, "GET", "/api/v1/chat/conversations", "user-11"), "but for nobody else");
    }

    @Test
    @DisplayName("one user spending their chat messages does not spend a colleague's")
    void chatMessagesAreCountedPerUser() {
        ApiRateLimitFilter filter = filterWithSessions();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, sendTimes(filter, "POST", "/api/v1/chat", "ana", 31));
        assertNull(send(filter, "POST", "/api/v1/chat", "binh"));
    }

    @Test
    @DisplayName("requests with no verified session share the address's budget")
    void visitorsShareTheAddress() {
        ApiRateLimitFilter filter = filterWithSessions();

        assertNull(sendTimes(filter, "POST", "/api/v1/chat", null, 30));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "POST", "/api/v1/chat", null));
        // A signed-in user at the same address is not caught in it.
        assertNull(send(filter, "POST", "/api/v1/chat", "ana"));
    }

    @Test
    @DisplayName("sign-in attempts are counted against the address even with a valid session")
    void signInStaysPerAddress() {
        ApiRateLimitFilter filter = filterWithSessions();

        // Ten guesses, each presenting a different valid session: still one address, one budget.
        for (int i = 0; i < 10; i++) assertNull(send(filter, "POST", "/api/v1/auth/login", "user-" + i));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "POST", "/api/v1/auth/login", "user-10"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                sendTimes(filter, "POST", "/api/v1/auth/forgot-password", "user-0", 6));
    }

    @Test
    @DisplayName("an edition with no accounts counts by address, as it always did")
    void noIdentityMeansAddress() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter();

        assertNull(sendTimes(filter, "GET", "/api/v1/chat/conversations", "user-0", 120));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "GET", "/api/v1/chat/conversations", "user-1"));
    }

    @Test
    @DisplayName("a new random MCP token per request does not buy a new budget - the address has one too")
    void randomMcpTokensShareTheAddress() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter();
        HttpStatus last = null;
        for (int i = 0; i < 601; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.post("/mcp")
                    .remoteAddress(new InetSocketAddress("203.0.113.9", 5000))
                    .header("Authorization", "Bearer pxm_random" + i).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            filter.filter(exchange, PASS).block();
            last = (HttpStatus) exchange.getResponse().getStatusCode();
            if (i < 600) assertNull(last, "request " + i + " is inside the address's budget");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last);
    }
}
