package ai.proxyma.connectors.clickup;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.RatePacing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

/**
 * How every request to ClickUp is made: its API v2 ({@code https://api.clickup.com/api/v2}, or the
 * connection's address), and the connection's credential on every request and on no other host - a
 * personal token ({@code pk_...}) as the bare {@code Authorization} value, as ClickUp asks, or a
 * sign-in's access token as a bearer.
 *
 * <p>ClickUp allows 100 requests a minute per token on most plans (1,000 on Business Plus, 10,000 on
 * Enterprise) and answers more with a 429 and {@code X-RateLimit-Remaining: 0} beside
 * {@code X-RateLimit-Reset}, a Unix time in seconds - which {@link RateLimitHint#RESET_HEADERS} waits
 * for, and reports as rate limited when it is further off than the framework sits out. The same two
 * headers come on every answer, so {@link #PACING} slows a pass once ten requests are left in the
 * minute, spreading them to the reset rather than running into the 429.
 */
final class ClickUpApi {

    static final String DEFAULT_BASE_URL = "https://api.clickup.com/api/v2";
    static final RatePacing PACING = RatePacing.budget("X-RateLimit-Remaining", "X-RateLimit-Reset",
            RatePacing.Reset.EPOCH_SECONDS, Duration.ofMinutes(1), 10);

    private ClickUpApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        ApiAuth auth = connection.signsIn()
                ? ApiAuth.bearer(connection)
                : ApiAuth.header(HttpHeaders.AUTHORIZATION, connection::accessToken);
        return http.client(base.replaceAll("/+$", ""))
                .service("ClickUp")
                .auth(auth)
                .rateLimit(RateLimitHint.RESET_HEADERS)
                .pacing(PACING)
                .build();
    }
}
