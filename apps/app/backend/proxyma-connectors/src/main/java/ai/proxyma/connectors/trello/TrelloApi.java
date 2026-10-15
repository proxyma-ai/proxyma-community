package ai.proxyma.connectors.trello;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.Optional;

/**
 * How every request to Trello is made: its REST API ({@code https://api.trello.com/1}, or the
 * connection's address), and the connection's API key and user token on every request and on no
 * other host. Trello takes the pair as {@code key} and {@code token} query parameters or as an
 * {@code Authorization: OAuth oauth_consumer_key="...", oauth_token="..."} header; this sends the
 * header, so neither value is ever part of an address that a proxy or a log might keep.
 *
 * <p><b>Trello's rate limit</b> is 300 requests per 10 seconds per API key and 100 per 10 seconds per
 * token, answered 429 with {@code API_TOKEN_LIMIT_EXCEEDED} or {@code API_KEY_LIMIT_EXCEEDED}, and
 * the windows stated in {@code x-rate-limit-api-*-interval-ms} headers. {@link #RATE_LIMIT} waits
 * a whole window for the budget that ran out - Trello names no reset - and the framework reports the
 * source as rate limited when that is longer than it sits out.
 */
final class TrelloApi {

    static final String DEFAULT_BASE_URL = "https://api.trello.com/1";
    static final String API_KEY = "apiKey";
    static final String TOKEN = "token";

    // A budget's remaining count, and its window's length in milliseconds.
    private static final String[][] BUDGETS = {
            {"x-rate-limit-api-token-remaining", "x-rate-limit-api-token-interval-ms"},
            {"x-rate-limit-api-key-remaining", "x-rate-limit-api-key-interval-ms"},
            {"x-rate-limit-member-remaining", "x-rate-limit-member-interval-ms"},
    };

    /** A 429 without Retry-After: wait out the window of the budget that is spent, else back off. */
    static final RateLimitHint RATE_LIMIT = (response, now) -> {
        if (response.status() != 429 || response.header("Retry-After") != null) return Optional.empty();
        Duration wait = null;
        for (String[] budget : BUDGETS) {
            String remaining = response.header(budget[0]);
            String interval = response.header(budget[1]);
            if (interval == null || remaining != null && !"0".equals(remaining.trim())) continue;
            try {
                Duration window = Duration.ofMillis(Long.parseLong(interval.trim()));
                if (wait == null || window.compareTo(wait) > 0) wait = window;
            } catch (NumberFormatException ignored) {
                // an unreadable window says nothing about when to come back
            }
        }
        return Optional.of(wait == null ? Duration.ZERO : wait);
    };

    private TrelloApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        return http.client(base.replaceAll("/+$", ""))
                .service("Trello")
                .auth(ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> header(connection)))
                .rateLimit(RATE_LIMIT)
                .build();
    }

    static String header(ResolvedCredentials connection) {
        String key = connection.secret(API_KEY);
        String token = connection.secret(TOKEN);
        if (key == null || key.isBlank()) {
            throw new ConnectorAuthException("This connection has no Trello API key. Add it in Settings > Connections.");
        }
        if (token == null || token.isBlank()) {
            throw new ConnectorAuthException("This connection has no token. Add one in Settings > Connections.");
        }
        return "OAuth oauth_consumer_key=\"" + quoted(key) + "\", oauth_token=\"" + quoted(token) + "\"";
    }

    // A value inside the header's quotes: nothing that could end the quote or the line.
    private static String quoted(String value) {
        return value.strip().replaceAll("[\"\\\\\\r\\n]", "");
    }
}
