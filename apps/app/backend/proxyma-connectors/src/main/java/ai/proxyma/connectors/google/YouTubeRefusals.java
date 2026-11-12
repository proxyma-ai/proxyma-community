package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.GoogleError;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * What the YouTube source says when YouTube refuses one of its reads (2026-11-03: a channel whose
 * overview answered and whose every comment read was a 403 said only "refused access", and nobody
 * could tell why). Each refusal a working channel can give is a sentence a person can act on, with
 * Google's own reason kept at its end; anything else is ApiClient's sentence, which carries the reason
 * too (the client is built with RefusalReason.GOOGLE), and a spent quota stays the limit it is.
 */
final class YouTubeRefusals {

    static final String COMMENTS_DISABLED = "commentsDisabled";
    private static final Set<String> SCOPE = Set.of("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT");
    private static final Set<String> DISABLED = Set.of("accessNotConfigured", "SERVICE_DISABLED");
    private static final Set<String> LIMITS = Set.of("quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded",
            "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED");

    private YouTubeRefusals() {}

    /** The answer's JSON when it succeeded; otherwise its refusal, said for what it is. */
    static JsonNode json(ApiClient api, ApiResponse response) {
        if (response.ok()) return response.json();
        throw refused(api, response);
    }

    static ConnectorHttpException refused(ApiClient api, ApiResponse response) {
        // ApiClient's sentence first: the limit sentence for a spent quota, and the reason logged once.
        ConnectorHttpException plain = api.failure(response);
        Set<String> reasons = GoogleError.reasons(response);
        if (reasons.stream().anyMatch(LIMITS::contains) || response.status() == 429) return plain;
        String why = GoogleError.describe(response);
        if (why == null) return plain;
        int status = response.status();
        String path = plain.path();
        if (reasons.stream().anyMatch(SCOPE::contains)) {
            return new ConnectorHttpException("The YouTube connection's sign-in does not include every permission it "
                    + "needs. Sign in again on the YouTube connection in Settings > Connections and tick every permission "
                    + "Google lists. YouTube said: " + why, status, path);
        }
        if (reasons.stream().anyMatch(DISABLED::contains)) {
            return new ConnectorHttpException("The YouTube Data API v3 is not enabled in the connection's Google Cloud "
                    + "project. Enable it in Google Cloud > APIs & Services > Library, then sync again. YouTube said: "
                    + why, status, path);
        }
        if (reasons.contains(COMMENTS_DISABLED)) {
            return new ConnectorHttpException("Comments are turned off for this channel or its videos (videos made for "
                    + "kids have none), so there are no comments to read. YouTube said: " + why, status, path);
        }
        return plain;
    }

    /**
     * The channel's all-threads listing refused while the channel itself answered - comments off on the
     * channel or on some of its videos ({@code commentsDisabled}), or the listing {@code forbidden}. Each
     * video's own comments may still read.
     */
    static boolean channelThreadsRefused(ApiResponse response) {
        return response.status() == 403 && GoogleError.has(response, COMMENTS_DISABLED, "forbidden");
    }

    /** A video whose comments are turned off - skipped, and counted. */
    static boolean commentsOff(ApiResponse response) {
        return response.status() == 403 && GoogleError.has(response, COMMENTS_DISABLED);
    }
}
