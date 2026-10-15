package ai.proxyma.connectors.slack;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How every request to Slack's Web API is made, for one token: the connection's API address
 * ({@code https://slack.com/api}, or a test's loopback server), the token as a bearer on every request
 * and on no other host, and Slack's two ways of failing.
 *
 * <p><b>An answer is not a success because it is a 200.</b> Slack answers nearly every refusal with
 * HTTP 200 and {@code {"ok": false, "error": "..."}} - a revoked token, a missing scope, a channel
 * the app is not in. {@link #call} reads the body and turns each into a {@link SlackApiException}
 * whose message is the sentence a person can act on.
 *
 * <p><b>Rate limits are per method, in tiers</b> (Tier 2 is about 20 requests a minute, Tier 3 about
 * 50, Tier 4 about 100), and only a customer-built INTERNAL app keeps them: a distributed app that is
 * not in the Slack Marketplace gets one request a minute and 15 messages a page on
 * {@code conversations.history} and {@code conversations.replies}, which is why this connector reads
 * through the customer's own app. Each tier gets a client of its own, paced to the tier's rate, so a
 * pass does not run into the limit to begin with; a 429 that comes anyway is waited out for its
 * {@code Retry-After} (or, without one, ten requests' worth of the tier's pace) and reported as rate
 * limited when that is too long to sit through. A {@code ratelimited} error in a 200 body - which
 * Slack documents for some methods - is reported the same way.
 */
final class SlackApi {

    static final String DEFAULT_BASE_URL = "https://slack.com/api";

    /**
     * Slack's documented per-method tiers that this connector calls, as the pace it keeps within each
     * (Tier 2, conversations.list, is not called: users.conversations lists only the app's channels).
     */
    enum Tier {
        // conversations.history, conversations.replies, conversations.info, users.conversations: 50+ a minute.
        TIER_3(Duration.ofMillis(1200)),
        // users.info, auth.test: 100+ a minute.
        TIER_4(Duration.ofMillis(600));

        final Duration pace;

        Tier(Duration pace) {
            this.pace = pace;
        }
    }

    // The errors that say the token itself is no good.
    private static final Set<String> AUTH_ERRORS = Set.of("invalid_auth", "not_authed", "account_inactive",
            "token_revoked", "token_expired", "no_permission", "org_login_required", "ekm_access_denied");
    // The errors that say Slack itself failed, which the next sync may not meet.
    private static final Set<String> SERVER_ERRORS = Set.of("fatal_error", "internal_error", "service_unavailable",
            "request_timeout");

    private final ConnectorHttp http;
    private final String baseUrl;
    private final ApiAuth auth;
    private final Map<Tier, ApiClient> clients = new EnumMap<>(Tier.class);

    private SlackApi(ConnectorHttp http, String baseUrl, ApiAuth auth) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.auth = auth;
    }

    /** The connection's bot token - or, for a connection that signs in, its access token. */
    static SlackApi bot(ConnectorHttp http, ResolvedCredentials connection) {
        return new SlackApi(http, baseUrl(connection), ApiAuth.bearer(connection));
    }

    /** The connection's user token, when it has one - else null. */
    static SlackApi user(ConnectorHttp http, ResolvedCredentials connection) {
        String token = connection.secret(SlackConnectionPlugin.USER_TOKEN);
        if (token == null || token.isBlank()) return null;
        return new SlackApi(http, baseUrl(connection), ApiAuth.bearer(token.strip()));
    }

    private static String baseUrl(ResolvedCredentials connection) {
        return connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
    }

    private ApiClient client(Tier tier) {
        return clients.computeIfAbsent(tier, t -> http.client(baseUrl)
                .service("Slack")
                .auth(auth)
                .rateLimit(hint(t))
                .minInterval(t.pace)
                .build());
    }

    /**
     * Slack's 429: its {@code Retry-After} when it sends one, else ten requests' worth of the
     * method's tier - Slack's windows are a minute long, so asking again at once only spends the
     * next attempt.
     */
    static RateLimitHint hint(Tier tier) {
        return (response, now) -> {
            if (response.status() != 429 || response.header("Retry-After") != null) return Optional.empty();
            return Optional.of(tier.pace.multipliedBy(10));
        };
    }

    /**
     * One Web API method, its arguments in the query string (every method this connector calls
     * accepts GET). The body when Slack says ok; otherwise the refusal as a sentence.
     */
    JsonNode call(Tier tier, String method, Map<String, ?> query) {
        ApiClient client = client(tier);
        ApiResponse response = client.get(method, query);
        if (!response.ok()) throw client.failure(response);
        JsonNode body = response.json();
        if (body.path("ok").asBoolean(false)) return body;
        String error = body.path("error").asText("");
        if ("ratelimited".equals(error)) {
            ConnectorHttpException limited = client.failure(new ApiResponse(response.uri(), 429, response.headers(),
                    response.bytes()));
            throw new SlackApiException(limited.getMessage(), 429, method, error);
        }
        throw refusal(method, error, body.path("needed").asText(""));
    }

    /**
     * Every page of a cursor-paged method's {@code field}, fetched as needed: Slack's
     * {@code response_metadata.next_cursor}, empty on the last page.
     */
    Iterator<JsonNode> pages(Tier tier, String method, Map<String, ?> query, String field) {
        return Paginator.iterate(next -> {
            Map<String, Object> q = new LinkedHashMap<>(query);
            if (next != null) q.put("cursor", next);
            JsonNode page = call(tier, method, q);
            return new Paginator.Page<>(Paginator.elements(page.path(field)),
                    page.path("response_metadata").path("next_cursor").asText(null));
        });
    }

    // The sentence for each refusal Slack states in its body.
    static SlackApiException refusal(String method, String error, String needed) {
        if (AUTH_ERRORS.contains(error)) {
            return new SlackApiException("Slack did not accept the token. Replace it, or sign in again, in "
                    + "Settings > Connections.", 401, method, error);
        }
        if ("missing_scope".equals(error)) {
            return new SlackApiException("The Slack app is missing the " + (needed.isBlank() ? "needed" : needed)
                    + " permission. Add it under OAuth & Permissions, reinstall the app, and update the token in "
                    + "Settings > Connections.", 403, method, error);
        }
        if ("not_in_channel".equals(error)) {
            return new SlackApiException("The Slack app is not in that channel any more. Invite it back with "
                    + "/invite, or remove the channel from the source.", 403, method, error);
        }
        if ("channel_not_found".equals(error) || "thread_not_found".equals(error) || "message_not_found".equals(error)) {
            return new SlackApiException("Slack has no such conversation, or the app cannot see it.", 404, method, error);
        }
        if (SERVER_ERRORS.contains(error)) {
            return new SlackApiException("Slack answered with a server error. The next sync will try again.", 503,
                    method, error);
        }
        return new SlackApiException("Slack refused the request (" + (error.isBlank() ? "no reason given" : error) + ").",
                400, method, error);
    }

    /** A refusal Slack stated in its answer's body, with Slack's own error code beside the sentence. */
    static final class SlackApiException extends ConnectorHttpException {

        private final String error;

        SlackApiException(String message, int status, String method, String error) {
            super(message, status, method);
            this.error = error;
        }

        /** Slack's error code - {@code invalid_auth}, {@code not_in_channel}, ... */
        String error() {
            return error;
        }

        /** The token cannot read this channel - it was never in it, or left. */
        boolean channelAccess() {
            return "not_in_channel".equals(error) || "channel_not_found".equals(error);
        }
    }
}
