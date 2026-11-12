package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.settings.connection.BuiltInQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This install's share of the built-in sign-in's daily quota, spent (BuiltInQuota, 2026-11-08): the answer
 * given in place of the request reads as YouTube's own spent quota - so an approved reply is held for the
 * reset (YouTubeApprovalContext.QUOTA_SPENT) - and says the limit is the shared sign-in's.
 */
class YouTubeSharedQuotaTest {

    private static ApiResponse spent() {
        return new ApiResponse(URI.create("https://www.googleapis.com/youtube/v3/comments"), 403, null,
                BuiltInQuota.SPENT_BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the tools' sentence names the shared sign-in and starts as every spent quota's does")
    void sentence() {
        ApiClient client = ConnectorHttp.direct().client("https://www.googleapis.com").auth(ApiAuth.none()).build();

        RuntimeException e = YouTubeCalls.refusal(client, YouTubeCalls.Api.DATA, spent());

        assertThat(e.getMessage()).startsWith(YouTubeApprovalContext.QUOTA_SPENT).contains("Proxyma's shared sign-in")
                .contains("midnight Pacific time");
        assertThat(YouTubeCalls.SHARED_QUOTA).isEqualTo(BuiltInQuota.REASON);
        // Read as a spent quota by every reader of Google's error shape.
        assertThat(YouTubeCalls.reasons(spent())).contains("quotaExceeded");
    }

    @Test
    @DisplayName("a held reply's result tells the shared sign-in's limit from the person's own project's, for the card's hint (2026-11-11)")
    void sharedLimitOnTheCard() {
        ApiClient client = ConnectorHttp.direct().client("https://www.googleapis.com").auth(ApiAuth.none()).build();
        String shared = YouTubeCalls.refusal(client, YouTubeCalls.Api.DATA, spent()).getMessage();

        assertThat(YouTubeApprovalContext.sharedLimit("{\"success\":false,\"error\":\"" + shared + "\"}")).isTrue();
        assertThat(YouTubeApprovalContext.sharedLimit("YouTube's daily quota for the connection's Google Cloud project is "
                + "spent. This is a limit, not a refusal: try again after midnight Pacific time, when it resets.")).isFalse();
        assertThat(YouTubeApprovalContext.sharedLimit(null)).isFalse();
    }
}
