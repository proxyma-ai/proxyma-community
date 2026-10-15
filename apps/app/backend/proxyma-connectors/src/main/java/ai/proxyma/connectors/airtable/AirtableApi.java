package ai.proxyma.connectors.airtable;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

import java.time.Duration;
import java.util.Optional;

/**
 * How every request to Airtable's Web API is made: the connection's API address
 * ({@code https://api.airtable.com/v0}, or a test's loopback server), its token as a bearer on every
 * request and on no other host, and Airtable's way of saying "slow down".
 *
 * <p><b>A 429 is a penalty, not a hint.</b> Airtable allows 5 requests a second per base (and 50 a
 * second per token), and a request over either is answered 429 - after which EVERY request fails for
 * 30 seconds. Asking again after the usual backoff of half a second only spends the next attempt
 * inside the penalty. So a 429 without a {@code Retry-After} waits out the whole window, and requests
 * are paced to the per-base rate so a pass does not earn one to begin with.
 */
final class AirtableApi {

    static final String DEFAULT_BASE_URL = "https://api.airtable.com/v0";
    // 5 requests a second per base, kept with a margin.
    static final Duration PACE = Duration.ofMillis(220);
    // Airtable's documented penalty after a 429, and a second for the clocks to agree.
    static final Duration PENALTY = Duration.ofSeconds(31);
    // The most records a list answers with at once.
    static final int PAGE = 100;

    private AirtableApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
        return http.client(base)
                .service("Airtable")
                .auth(ApiAuth.bearer(connection))
                .rateLimit(PENALTY_WINDOW)
                .minInterval(PACE)
                .build();
    }

    /** A 429 with no {@code Retry-After} is the 30-second penalty window; one with it is the standard's. */
    static final RateLimitHint PENALTY_WINDOW = (response, now) -> {
        if (response.status() != 429 || response.header("Retry-After") != null) return Optional.empty();
        return Optional.of(PENALTY);
    };
}
