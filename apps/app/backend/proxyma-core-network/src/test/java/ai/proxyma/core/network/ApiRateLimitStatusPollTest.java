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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The polls an open tab makes on its own - the maintenance gate, the announcement banner, a key
 * account's presence heartbeat - spend a budget of their own, not the one a person's work spends.
 * Measured 2026-10-06: they took about 26 of the default 120 a minute per tab, so the chat page
 * and a key account open side by side left too little for the account's live updates, and the
 * page was answered with 429s.
 */
class ApiRateLimitStatusPollTest {

    private static final WebFilterChain PASS = exchange -> Mono.empty();

    private static ApiRateLimitFilter filter() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter();
        filter.setIdentity(exchange -> "ana");
        return filter;
    }

    private static HttpStatus send(ApiRateLimitFilter filter, String method, String path) {
        MockServerHttpRequest.BaseBuilder<?> request = "POST".equals(method)
                ? MockServerHttpRequest.post(path) : MockServerHttpRequest.get(path);
        request.remoteAddress(new InetSocketAddress("10.0.0.7", 5000));
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, PASS).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static HttpStatus sendTimes(ApiRateLimitFilter filter, String method, String path, int times) {
        HttpStatus last = null;
        for (int i = 0; i < times; i++) last = send(filter, method, path);
        return last;
    }

    @Test
    @DisplayName("three tabs' worth of status polls leave the person's own budget untouched")
    void pollsDoNotSpendTheDefaultBudget() {
        ApiRateLimitFilter filter = filter();
        // A minute of three open tabs: the gate every 4 s, the banner every 8 s, presence every 20 s.
        sendTimes(filter, "GET", "/api/v1/maintenance/status", 45);
        sendTimes(filter, "GET", "/api/v1/maintenance/announcement", 24);
        sendTimes(filter, "POST", "/api/v1/key-accounts/0b8f6c1e-0000-4000-8000-000000000001/presence", 9);

        assertNull(sendTimes(filter, "GET", "/api/v1/key-accounts/0b8f6c1e-0000-4000-8000-000000000001", 120),
                "all 120 of the default budget are still the person's");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "GET", "/api/v1/key-accounts"),
                "and the default budget is no larger than it was");
    }

    @Test
    @DisplayName("the status polls are limited too")
    void pollsHaveALimit() {
        ApiRateLimitFilter filter = filter();
        assertNull(sendTimes(filter, "GET", "/api/v1/maintenance/status", 150));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter, "GET", "/api/v1/maintenance/announcement"));
    }

    @Test
    @DisplayName("only those polls: the account's other reads and writes stay in the default budget")
    void onlyThePolls() {
        assertTrue(ApiRateLimitFilter.isStatusPoll("/api/v1/maintenance/status"));
        assertTrue(ApiRateLimitFilter.isStatusPoll("/api/v1/maintenance/announcement"));
        assertTrue(ApiRateLimitFilter.isStatusPoll("/api/v1/key-accounts/abc/presence"));
        assertFalse(ApiRateLimitFilter.isStatusPoll("/api/v1/key-accounts/abc"));
        assertFalse(ApiRateLimitFilter.isStatusPoll("/api/v1/key-accounts/abc/presence/x"));
        assertFalse(ApiRateLimitFilter.isStatusPoll("/api/v1/key-accounts/abc/ratings"));
        assertFalse(ApiRateLimitFilter.isStatusPoll("/api/v1/maintenance"));
        assertFalse(ApiRateLimitFilter.isStatusPoll("/api/v1/auth/me"));
    }
}
