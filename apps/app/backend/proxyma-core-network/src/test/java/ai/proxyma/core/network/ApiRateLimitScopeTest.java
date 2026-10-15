package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The limiter covers the API and MCP only (2026-09-29). The page and its script chunks shared the
 * default API budget, so two reloads in a minute answered the app itself with a 429.
 */
class ApiRateLimitScopeTest {

    private static final WebFilterChain PASS = exchange -> Mono.empty();

    private static HttpStatus statusAfter(ApiRateLimitFilter filter, String path, int requests) {
        HttpStatus last = null;
        for (int i = 0; i < requests; i++) {
            MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                    .remoteAddress(new java.net.InetSocketAddress("10.0.0.7", 5000)).build());
            filter.filter(ex, PASS).block();
            last = (HttpStatus) ex.getResponse().getStatusCode();
        }
        return last;
    }

    @Test
    @DisplayName("the app's own page and assets are never rate limited")
    void staticFilesPass() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter();
        assertNull(statusAfter(filter, "/assets/ChatPage-abc.js", 500));
        assertNull(statusAfter(filter, "/admin", 500));
    }

    @Test
    @DisplayName("the API still is")
    void apiIsLimited() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusAfter(new ApiRateLimitFilter(), "/api/v1/settings/x", 200));
    }

    @Test
    @DisplayName("so is starting an OAuth sign-in, each of which holds a session in a store of 10,000")
    void oauthSignInIsLimited() {
        assertNull(statusAfter(new ApiRateLimitFilter(), "/oauth2/authorization/sso", 60));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusAfter(new ApiRateLimitFilter(), "/oauth2/authorization/sso", 61));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusAfter(new ApiRateLimitFilter(), "/login/oauth2/code/sso", 61));
    }
}
