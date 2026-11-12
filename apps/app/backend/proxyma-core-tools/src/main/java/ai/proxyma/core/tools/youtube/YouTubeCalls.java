package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.GoogleError;
import ai.proxyma.core.connector.spi.RefusalReason;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Set;

/**
 * The three YouTube APIs the tools speak, over one sign-in: the Data API v3 (the channel, its videos,
 * its comments), the YouTube Analytics API ({@code reports.query}) and the YouTube Reporting API (the
 * daily reach CSVs, the only place impressions and their click-through rate are published).
 *
 * <p><b>Where each lives.</b> Google serves them on three hosts. A connection whose API URL was left at
 * Google's ({@code https://www.googleapis.com}) reaches each on its own host; one whose URL was changed
 * (More options - a test server, a relay) reaches all three there, under their own paths
 * ({@code /youtube/v3}, {@code /v2/reports}, {@code /v1/jobs}), which do not overlap. The bearer goes to
 * the one host its client is built for and to no other: a download link naming another host is refused
 * by ApiClient.
 *
 * <p><b>Refusals are sentences the model can act on</b>, read from the reason Google puts in the body
 * ({@code error.errors[].reason}, or {@code error.details[].reason} on the newer APIs): a sign-in that
 * did not allow YouTube Analytics, an API the Google Cloud project has not enabled, a spent daily quota
 * (a limit, not a refusal).
 */
final class YouTubeCalls {

    static final String DATA_URL = YouTubeTools.DEFAULT_BASE_URL;
    static final String ANALYTICS_URL = "https://youtubeanalytics.googleapis.com";
    static final String REPORTING_URL = "https://youtubereporting.googleapis.com";
    /** What both the Analytics and the Reporting API take, beside youtube.readonly. */
    static final String ANALYTICS_SCOPE = "https://www.googleapis.com/auth/yt-analytics.readonly";

    /**
     * The reason beside {@code quotaExceeded} when this install's share of the built-in sign-in's daily quota
     * is spent (BuiltInQuota.REASON) - the request was never sent. Its sentence starts as every spent quota's
     * does (YouTubeApprovalContext.QUOTA_SPENT), so an approved reply is held for the reset all the same.
     */
    static final String SHARED_QUOTA = "proxymaSharedQuota";
    static final String SHARED_QUOTA_SPENT = "YouTube's daily quota for Proxyma's shared sign-in is spent on this "
            + "computer.";

    /** Google's reason for a video, or a whole channel, whose comments are switched off. */
    static final String COMMENTS_DISABLED = "commentsDisabled";

    // A reach CSV or an Analytics answer is small; a hung server must not hold a tool call for minutes.
    private static final Duration MAX_TOTAL_WAIT = Duration.ofSeconds(45);

    enum Api {
        DATA(DATA_URL, "YouTube"),
        ANALYTICS(ANALYTICS_URL, "YouTube Analytics"),
        REPORTING(REPORTING_URL, "YouTube Reporting");

        final String defaultUrl;
        final String label;

        Api(String defaultUrl, String label) {
            this.defaultUrl = defaultUrl;
            this.label = label;
        }
    }

    private YouTubeCalls() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials credentials, Api api) {
        return http.client(base(credentials, api)).service(api.label).auth(ApiAuth.bearer(credentials))
                .refusalReason(RefusalReason.GOOGLE).maxTotalWait(MAX_TOTAL_WAIT).build();
    }

    static String base(ResolvedCredentials credentials, Api api) {
        if (!credentials.hasBaseUrl()) return api.defaultUrl;
        String base = credentials.baseUrl().strip().replaceAll("/+$", "");
        return base.equalsIgnoreCase(DATA_URL) ? api.defaultUrl : base;
    }

    /**
     * True when the sign-in is KNOWN not to have allowed YouTube Analytics: the token answer named its
     * scopes (kept by the connection, OAuth2Spec.keepGrantField) and this one is not among them. A
     * connection signed in before the scopes were kept says nothing, and is asked.
     */
    static boolean analyticsNotGranted(ResolvedCredentials credentials) {
        String scopes = credentials.grant("scope");
        if (scopes == null || scopes.isBlank()) return false;
        for (String s : scopes.split("\\s+")) if (ANALYTICS_SCOPE.equals(s)) return false;
        return true;
    }

    static YouTubeApiException analyticsNotAllowed() {
        return new YouTubeApiException("The YouTube connection's sign-in does not allow YouTube Analytics. Tell the user "
                + "to sign in again on the YouTube connection in Settings > Connections to allow YouTube Analytics; "
                + "comments keep working meanwhile.");
    }

    /** The answer's JSON when it succeeded; otherwise the refusal, said for what it is. */
    static JsonNode json(ApiClient client, Api api, ApiResponse response) {
        if (response.ok()) return response.json();
        throw refusal(client, api, response);
    }

    static RuntimeException refusal(ApiClient client, Api api, ApiResponse response) {
        Set<String> reasons = reasons(response);
        if (reasons.contains(SHARED_QUOTA)) {
            return new YouTubeApiException(SHARED_QUOTA_SPENT + " This is a limit, not a refusal: try again after "
                    + "midnight Pacific time, when it resets.");
        }
        if (reasons.contains("quotaExceeded") || reasons.contains("dailyLimitExceeded")) {
            return new YouTubeApiException("YouTube's daily quota for the connection's Google Cloud project is spent. "
                    + "This is a limit, not a refusal: try again after midnight Pacific time, when it resets.");
        }
        if (reasons.contains("rateLimitExceeded") || reasons.contains("userRateLimitExceeded")
                || reasons.contains("RATE_LIMIT_EXCEEDED") || response.status() == 429) {
            return new YouTubeApiException("YouTube is limiting how often it is asked. Wait a minute, then try again.");
        }
        if (reasons.contains("accessNotConfigured") || reasons.contains("SERVICE_DISABLED")) {
            return switch (api) {
                case ANALYTICS -> new YouTubeApiException("The YouTube Analytics API is not enabled in the connection's "
                        + "Google Cloud project. Tell the user to enable the YouTube Analytics API in their Google Cloud "
                        + "project (APIs & Services > Library), then try again.");
                case REPORTING -> new YouTubeApiException("The YouTube Reporting API is not enabled in the connection's "
                        + "Google Cloud project. Tell the user to enable the YouTube Reporting API in their Google Cloud "
                        + "project (APIs & Services > Library), then try again.");
                case DATA -> new YouTubeApiException("The YouTube Data API v3 is not enabled in the connection's Google "
                        + "Cloud project. Tell the user to enable it in Google Cloud > APIs & Services > Library, then try "
                        + "again. YouTube said: " + said(response));
            };
        }
        if (reasons.contains("insufficientPermissions") || reasons.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) {
            if (api != Api.DATA) return analyticsNotAllowed();
            return new YouTubeApiException("The YouTube connection's sign-in does not include every permission it needs. "
                    + "Tell the user to sign in again on the YouTube connection in Settings > Connections and tick every "
                    + "permission Google lists. YouTube said: " + said(response));
        }
        if (reasons.contains(COMMENTS_DISABLED)) {
            return new YouTubeApiException("Comments are turned off for this channel or its videos (videos made for kids "
                    + "have none), so there are no comments to read. YouTube said: " + said(response));
        }
        if (response.status() == 401) {
            return new YouTubeApiException("Google did not accept the YouTube connection's sign-in. Tell the user to sign "
                    + "in again on the YouTube connection in Settings > Connections.");
        }
        if (api == Api.ANALYTICS && response.status() == 400) {
            String message = message(response);
            if (!message.isEmpty()) return new YouTubeApiException("YouTube Analytics refused the query: " + message);
        }
        return client.failure(response);
    }

    // Every reason the error answer gives, from both places Google writes one.
    static Set<String> reasons(ApiResponse response) {
        return GoogleError.reasons(response);
    }

    // Google's own reason and message, kept beside the sentence so nobody has to guess (2026-11-03).
    private static String said(ApiResponse response) {
        String why = GoogleError.describe(response);
        return why == null ? "status " + response.status() : why;
    }

    private static String message(ApiResponse response) {
        try {
            String m = response.json().path("error").path("message").asText("").strip();
            return m.length() > 300 ? m.substring(0, 300) : m;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
