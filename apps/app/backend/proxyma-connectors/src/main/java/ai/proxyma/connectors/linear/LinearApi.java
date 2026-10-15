package ai.proxyma.connectors.linear;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How every request to Linear is made: one GraphQL endpoint ({@code https://api.linear.app/graphql},
 * or the connection's address with {@code /graphql} after it), and the connection's credential on
 * every request and on no other host - a personal API key as the bare {@code Authorization} value,
 * as Linear asks, or a sign-in's access token as a bearer, refreshed once when Linear refuses it.
 *
 * <p><b>Linear's rate limit is not a 429.</b> A request over the hourly request or complexity budget
 * is answered HTTP 400 with {@code RATELIMITED} as the GraphQL error's {@code extensions.code}, and
 * the budgets' state in {@code X-RateLimit-*} headers whose resets are epoch MILLISECONDS
 * (linear.app/developers/rate-limiting). {@link #RATE_LIMIT} reads both: an exhausted budget's
 * reset decides the wait, and the error code alone - the headers missing - still makes the answer a
 * limit to back off from rather than a refusal, both while it is retried and in the sentence once it
 * is final - "Linear is limiting how often it is asked", not "Linear refused the query".
 */
final class LinearApi {

    static final String DEFAULT_BASE_URL = "https://api.linear.app";
    static final String GRAPHQL = "graphql";
    static final String RATE_LIMITED = "RATELIMITED";

    // The budgets Linear reports, each a remaining count and the epoch-millisecond reset beside it.
    private static final String[][] BUDGETS = {
            {"X-RateLimit-Requests-Remaining", "X-RateLimit-Requests-Reset"},
            {"X-RateLimit-Complexity-Remaining", "X-RateLimit-Complexity-Reset"},
            {"X-RateLimit-Endpoint-Requests-Remaining", "X-RateLimit-Endpoint-Requests-Reset"},
    };

    /**
     * A 400 (or 429) that is Linear's rate limit: wait until the exhausted budget resets, or - when
     * only the body says so - back off and ask again.
     */
    static final RateLimitHint RATE_LIMIT = (response, now) -> {
        if (response.status() != 400 && response.status() != 429) return Optional.empty();
        Instant until = null;
        for (String[] budget : BUDGETS) {
            String remaining = response.header(budget[0]);
            String reset = response.header(budget[1]);
            if (remaining == null || reset == null || !"0".equals(remaining.trim())) continue;
            try {
                Instant at = Instant.ofEpochMilli(Long.parseLong(reset.trim()));
                if (until == null || at.isAfter(until)) until = at;
            } catch (NumberFormatException ignored) {
                // an unreadable reset says nothing about when to come back
            }
        }
        if (until != null) {
            Duration wait = Duration.between(now, until);
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait.plusSeconds(1));
        }
        return rateLimitedBody(response) ? Optional.of(Duration.ZERO) : Optional.empty();
    };

    private LinearApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        base = base.replaceAll("/+$", "").replaceFirst("(?i)/graphql$", "");
        ApiAuth auth = connection.signsIn()
                ? ApiAuth.bearer(connection)
                : ApiAuth.header(HttpHeaders.AUTHORIZATION, connection::accessToken);
        return http.client(base)
                .service("Linear")
                .auth(auth)
                .rateLimit(RATE_LIMIT)
                .build();
    }

    /**
     * One GraphQL query, its {@code data} back. A failed answer throws the framework's sentence for
     * its status - a rate limit as one - and an answer whose errors left no data throws Linear's own
     * message; an authentication error inside a GraphQL answer reads as the 401 it means.
     */
    static JsonNode query(ApiClient api, String operation, String query, Map<String, ?> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationName", operation);
        body.put("query", query);
        body.put("variables", variables == null ? Map.of() : variables);
        ApiResponse response = api.send(HttpMethod.POST, GRAPHQL, Map.of(), body, Map.of());
        if (RATE_LIMIT.waitFor(response, Instant.now()).isPresent()) throw api.failure(response);
        JsonNode json = parse(response);
        JsonNode error = json == null ? null : json.path("errors").path(0);
        String code = error == null ? "" : error.path("extensions").path("code").asText("");
        if ("AUTHENTICATION_ERROR".equals(code)) throw api.failure(withStatus(response, 401));
        if ("FORBIDDEN".equals(code)) throw api.failure(withStatus(response, 403));
        if (!response.ok()) {
            String message = error == null ? "" : error.path("message").asText("");
            int status = response.status();
            if (message.isBlank() || status == 401 || status == 403 || status >= 500) throw api.failure(response);
            throw new ConnectorHttpException("Linear refused the query: " + message, response.status(),
                    response.uri() == null ? null : response.uri().getRawPath());
        }
        JsonNode data = json == null ? null : json.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            String message = error == null ? "" : error.path("message").asText("");
            throw new ConnectorHttpException("Linear refused the query: " + (message.isBlank() ? "no data" : message),
                    response.status(), response.uri() == null ? null : response.uri().getRawPath());
        }
        return data;
    }

    // Whether the body's GraphQL errors name the rate limit. Read only once the body is there.
    private static boolean rateLimitedBody(ApiResponse response) {
        if (response.bytes().length == 0) return false;
        String text = new String(response.bytes(), StandardCharsets.UTF_8);
        if (!text.contains(RATE_LIMITED)) return false;
        JsonNode json = parse(response);
        if (json == null) return false;
        for (JsonNode e : json.path("errors")) {
            if (RATE_LIMITED.equals(e.path("extensions").path("code").asText())) return true;
        }
        return false;
    }

    private static JsonNode parse(ApiResponse response) {
        try {
            return response.json();
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    private static ApiResponse withStatus(ApiResponse response, int status) {
        return new ApiResponse(response.uri(), status, response.headers(), response.bytes());
    }
}
