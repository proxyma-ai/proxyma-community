package ai.proxyma.connectors.hubspot;

import ai.proxyma.core.common.net.RetryAfter;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * How every request to HubSpot is made: {@code https://api.hubapi.com} (or the connection's address),
 * the connection's token as a bearer on every request and on no other host - a Service Key, a legacy
 * private app's access token, or a sign-in's access token, all three sent the same way - and HubSpot's
 * two limits honored.
 *
 * <p><b>The burst limit</b> is a rolling ten-second window per account (100 to 250 requests by
 * subscription) and, for CRM search, five requests a second. A 429 for it is waited out: the
 * {@code Retry-After} when HubSpot sends one, else the window's own length from
 * {@code X-HubSpot-RateLimit-Interval-Milliseconds}. Requests are also paced so a pass does not
 * reach it in the first place.
 *
 * <p><b>The daily limit</b> (250,000 to 1,000,000 requests a day, shared by every private app and
 * Service Key of the account) is not waited out - it resets at midnight in the account's time zone.
 * A 429 whose {@code policyName} is {@code DAILY}, or whose daily remaining count is zero, is reported
 * as rate limited; and a pass stops on its own while {@value #DAILY_RESERVE_PERCENT}% of the day's
 * allowance is left, so indexing never spends what the account's other integrations need
 * ({@link #checkDailyBudget}).
 *
 * <p><b>Versions.</b> HubSpot's APIs are versioned by date since 2026 ({@code /crm/objects/2026-03/...}),
 * each version supported for at least 18 months; {@code v3} still answers under the old paths. The
 * version is pinned here and a source may override it (its API version field), so a version HubSpot
 * retires is a setting, not a release.
 */
final class HubSpotApi {

    static final String DEFAULT_BASE_URL = "https://api.hubapi.com";
    static final String DEFAULT_VERSION = "2026-03";
    // CRM search answers at most this many results for one query, however it is paged.
    static final int SEARCH_CAP = 10_000;
    // Stop a pass while this share of the day's allowance is left.
    static final int DAILY_RESERVE_PERCENT = 10;
    // About 100 requests per ten seconds, the smallest burst limit; search is five a second.
    static final Duration PACE = Duration.ofMillis(100);
    static final Duration SEARCH_PACE = Duration.ofMillis(210);

    static final String DAILY_REMAINING = "X-HubSpot-RateLimit-Daily-Remaining";
    static final String DAILY_MAX = "X-HubSpot-RateLimit-Daily";

    /** HubSpot's 429: the burst window waited out, the day's allowance reported. */
    static final RateLimitHint RATE_LIMIT = (response, now) -> {
        if (response.status() != 429) return Optional.empty();
        if (dailyExhausted(response)) return Optional.of(RateLimitHint.NEXT_PASS);
        Optional<Duration> stated = RetryAfter.parse(response.header("Retry-After"), now);
        if (stated.isPresent()) return stated;
        String interval = response.header("X-HubSpot-RateLimit-Interval-Milliseconds");
        if (interval != null) {
            try {
                long ms = Long.parseLong(interval.trim());
                if (ms > 0) return Optional.of(Duration.ofMillis(Math.min(ms, 10_000)));
            } catch (NumberFormatException ignored) {
                // an unreadable window says nothing about when to come back
            }
        }
        return Optional.of(Duration.ZERO);
    };

    private HubSpotApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        return builder(http, connection).minInterval(PACE).build();
    }

    /** The client for CRM search, paced to its own, lower limit. */
    static ApiClient searchClient(ConnectorHttp http, ResolvedCredentials connection) {
        return builder(http, connection).minInterval(SEARCH_PACE).build();
    }

    private static ApiClient.Builder builder(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        return http.client(base.replaceAll("/+$", ""))
                .service("HubSpot")
                .auth(ApiAuth.bearer(connection))
                .rateLimit(RATE_LIMIT);
    }

    /** The objects of one type: {@code crm/objects/2026-03/companies}, or {@code crm/v3/objects/companies}. */
    static String objects(String version, String type) {
        return legacy(version) ? "crm/" + version + "/objects/" + type : "crm/objects/" + version + "/" + type;
    }

    static String accountDetails(String version) {
        return "account-info/" + (legacy(version) ? "v3" : version) + "/details";
    }

    static String owners(String version) {
        return legacy(version) ? "crm/" + version + "/owners" : "crm/owners/" + version;
    }

    static String pipelines(String version, String type) {
        return (legacy(version) ? "crm/" + version + "/pipelines/" : "crm/pipelines/" + version + "/") + type;
    }

    private static boolean legacy(String version) {
        return version.startsWith("v");
    }

    /**
     * Stops a pass that would eat into the account's daily allowance: an answer saying less than
     * {@value #DAILY_RESERVE_PERCENT}% of it is left ends the listing with the rate limit's sentence.
     * Only legacy private apps and Service Keys are told their daily count; a sign-in is not, and is
     * not checked.
     */
    static void checkDailyBudget(ApiResponse response) {
        String remaining = response.header(DAILY_REMAINING);
        String max = response.header(DAILY_MAX);
        if (remaining == null || max == null) return;
        try {
            long left = Long.parseLong(remaining.trim());
            long day = Long.parseLong(max.trim());
            if (day > 0 && left * 100 < day * DAILY_RESERVE_PERCENT) {
                throw new ConnectorHttpException("HubSpot's daily request allowance is nearly used up, so this sync "
                        + "stopped to leave the rest to the account's other integrations. The next sync will try again.",
                        429, response.uri() == null ? null : response.uri().getRawPath());
            }
        } catch (NumberFormatException ignored) {
            // unreadable counts say nothing
        }
    }

    // The day's allowance is spent: the remaining count says zero, or the body names the daily policy.
    private static boolean dailyExhausted(ApiResponse response) {
        String remaining = response.header(DAILY_REMAINING);
        if (remaining != null && "0".equals(remaining.trim())) return true;
        byte[] body = response.bytes();
        if (body == null || body.length == 0) return false;
        String text = new String(body, StandardCharsets.UTF_8);
        return text.contains("\"DAILY\"");
    }
}
