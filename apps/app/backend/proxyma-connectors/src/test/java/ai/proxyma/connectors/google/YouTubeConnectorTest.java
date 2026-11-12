package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceLabel;
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * YouTube against a loopback YouTube Data API: the connection's sign-in and Test, the channel's
 * videos and comment threads listed and bounded, a thread read with all its replies and whether the
 * channel has answered, and a spent quota said as one.
 */
class YouTubeConnectorTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final String CHANNEL = "UCowner";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials token() {
        return ResolvedCredentials.token(api.baseUrl(), "ya29.test");
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(YouTubeConnectorPlugin.DESCRIPTOR.fields(), fields);
        return new YouTubeConnectorPlugin().open(new SourceContext(null, ConnectorType.YOUTUBE_CHANNEL, null, null, config,
                null, null, token(), SourceScope.defaults(), ConnectorHttp.direct(waits::add), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private void channel() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
    }

    // An upload as the uploads playlist lists it: its id and nothing the index reads.
    private static String upload(String id) {
        return "{'contentDetails':{'videoId':'" + id + "'}}";
    }

    // A video as videos.list reads it.
    private static String video(String id, String title, String description, String published) {
        return "{'id':'" + id + "','snippet':{'title':'" + title + "','description':'" + description + "','publishedAt':'"
                + published + "'},'status':{'privacyStatus':'public'}}";
    }

    private void videos(String... videos) {
        api.on("GET", "/youtube/v3/videos").json("{'items':[" + String.join(",", videos) + "]}");
    }

    private static String comment(String id, String author, String authorChannel, String text, String updated) {
        return "{'id':'" + id + "','snippet':{'authorDisplayName':'" + author + "','authorChannelId':{'value':'"
                + authorChannel + "'},'textOriginal':'" + text + "','publishedAt':'" + updated + "','updatedAt':'" + updated + "'}}";
    }

    private static String thread(String id, String videoId, String top, int total, String... replies) {
        return "{'id':'" + id + "','snippet':{'videoId':'" + videoId + "','totalReplyCount':" + total + ","
                + "'topLevelComment':" + top + "}" + (replies.length == 0 ? "" : ",'replies':{'comments':["
                + String.join(",", replies) + "]}") + "}";
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -- the connection ------------------------------------------------------------------------

    @Test
    @DisplayName("Test names the channel the sign-in reads as")
    void signedInAs() {
        channel();
        ConnectionTestResult ok = new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        // No Analytics API answering here (404): the channel is the verdict, and nothing is said of the numbers.
        assertThat(ok.message()).isEqualTo("Signed in as Cam Am Tieu Dao");
        LoopbackApi.Recorded asked = api.requests("/youtube/v3/channels").get(0);
        assertThat(asked.param("mine")).isEqualTo("true");
        assertThat(asked.header("Authorization")).isEqualTo("Bearer ya29.test");
    }

    @Test
    @DisplayName("Test says YouTube Analytics can be read, and starts the reach collection once")
    void analyticsAllowed() {
        channel();
        api.on("GET", "/v2/reports").json("{'kind':'youtubeAnalytics#resultTable','columnHeaders':[],'rows':[]}");
        api.on("GET", "/v1/jobs").json("{}");
        api.on("POST", "/v1/jobs").json("{'id':'job1','reportTypeId':'channel_reach_basic_a1'}");
        ConnectionTestResult ok = new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.template()).isEqualTo("Signed in as {user}. YouTube Analytics is allowed.");
        assertThat(ok.message()).isEqualTo("Signed in as Cam Am Tieu Dao. YouTube Analytics is allowed.");
        assertThat(api.requests("/v2/reports").get(0).param("ids")).isEqualTo("channel==MINE");
        assertThat(api.requests("/v1/jobs")).filteredOn(r -> r.method().equals("POST")).singleElement()
                .satisfies(r -> assertThat(r.body()).contains("\"reportTypeId\":\"channel_reach_basic_a1\""));

        api.reset();
        channel();
        api.on("GET", "/v2/reports").json("{'rows':[]}");
        api.on("GET", "/v1/jobs").json("{'jobs':[{'id':'job1','reportTypeId':'channel_reach_basic_a1'}]}");
        assertThat(new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct()).ok()).isTrue();
        assertThat(api.requests("/v1/jobs")).noneMatch(r -> r.method().equals("POST"));
    }

    @Test
    @DisplayName("Test says, beside the channel, when YouTube Analytics was not allowed or an API is not enabled")
    void analyticsNotes() {
        channel();
        api.on("GET", "/v2/reports").status(403).json("{'error':{'code':403,'errors':[{'reason':'insufficientPermissions'}]}}");
        ConnectionTestResult scope = new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct());
        assertThat(scope.ok()).isTrue();
        assertThat(scope.template()).isEqualTo("Signed in as {user}. YouTube Analytics is not allowed yet: sign in again to allow it.");

        // A grant that names its scopes without Analytics is told so without asking Google.
        api.reset();
        channel();
        ResolvedCredentials granted = new ResolvedCredentials(null, null, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("token", "ya29.test"), Map.of(), null, Map.of("scope", "https://www.googleapis.com/auth/youtube.readonly"));
        assertThat(new YouTubeConnectionPlugin().test(granted, ConnectorHttp.direct()).template())
                .isEqualTo("Signed in as {user}. YouTube Analytics is not allowed yet: sign in again to allow it.");
        assertThat(api.requests("/v2/reports")).isEmpty();

        api.reset();
        channel();
        api.on("GET", "/v2/reports").status(403).json("{'error':{'code':403,'errors':[{'reason':'accessNotConfigured'}]}}");
        assertThat(new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct()).message())
                .isEqualTo("Signed in as Cam Am Tieu Dao. Enable the YouTube Analytics API in the Google Cloud project to "
                        + "read the channel's numbers.");

        api.reset();
        channel();
        api.on("GET", "/v2/reports").json("{'rows':[]}");
        api.on("GET", "/v1/jobs").status(403).json("{'error':{'code':403,'status':'PERMISSION_DENIED',"
                + "'details':[{'reason':'SERVICE_DISABLED'}]}}");
        ConnectionTestResult reporting = new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct());
        assertThat(reporting.ok()).isTrue();
        assertThat(reporting.template()).isEqualTo("Signed in as {user}. Enable the YouTube Reporting API in the Google "
                + "Cloud project to read impressions and click-through rate.");
    }

    @Test
    @DisplayName("a project without the YouTube Data API says so, and an account with no channel says that")
    void refusals() {
        api.on("GET", "/youtube/v3/channels").status(403).json("{'error':{'code':403,'errors':[{'reason':'accessNotConfigured'}]}}");
        ConnectionTestResult refused = new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Google refused access. Check that the YouTube Data API v3 is enabled "
                + "in the Google Cloud project.");

        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[]}");
        assertThat(new YouTubeConnectionPlugin().test(token(), ConnectorHttp.direct()).message())
                .startsWith("This Google account has no YouTube channel.");
    }

    @Test
    @DisplayName("the sign-in reads by default and asks to reply only while the Write switch is on")
    void scopes() {
        OAuth2Spec spec = new YouTubeConnectionPlugin().oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(spec.scopes()).containsExactly("https://www.googleapis.com/auth/youtube.readonly",
                "https://www.googleapis.com/auth/yt-analytics.readonly");
        // The scopes Google says were granted are kept, so a tool can tell YouTube Analytics was refused.
        assertThat(spec.grantFields()).containsExactly("scope");
        assertThat(spec.writeScopes()).containsExactly("https://www.googleapis.com/auth/youtube.force-ssl");
        // force-ssl reads too: with Write on, youtube.readonly is not asked beside it (2026-11-08).
        assertThat(spec.requestedScopes(false)).containsExactly("https://www.googleapis.com/auth/youtube.readonly",
                "https://www.googleapis.com/auth/yt-analytics.readonly");
        assertThat(spec.requestedScopes(true)).containsExactly("https://www.googleapis.com/auth/yt-analytics.readonly",
                "https://www.googleapis.com/auth/youtube.force-ssl");
        // Removing the connection removes Proxyma's access at Google.
        assertThat(spec.revokeUrl()).isEqualTo("https://oauth2.googleapis.com/revoke");
        assertThat(spec.extraAuthorizeParams()).containsEntry("access_type", "offline")
                .as("the account chooser always shows, so a second channel of one Google account can be picked")
                .containsEntry("prompt", "select_account consent");

        var d = YouTubeConnectionPlugin.DESCRIPTOR;
        assertThat(d.sourceTypes()).containsExactly(ConnectorType.YOUTUBE_CHANNEL);
        assertThat(d.auth(AuthKind.OAUTH2_CODE).fields()).extracting(FieldSpec::key).containsExactly("clientId", "clientSecret");
        assertThat(d.supportsWrite()).isTrue();
        // The Google connection itself stays read-only and never names YouTube.
        assertThat(GoogleConnectionPlugin.DESCRIPTOR.sourceTypes()).doesNotContain(ConnectorType.YOUTUBE_CHANNEL);
        assertThat(new GoogleConnectionPlugin().oauth2().scopes()).noneMatch(s -> s.contains("youtube"));
    }

    // -- listing -------------------------------------------------------------------------------

    @Test
    @DisplayName("a pass lists the uploads and the comment threads, named by their video, newest reply as last-modified")
    void listing() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "],'nextPageToken':'p2'}");
        api.on("GET", "/youtube/v3/playlistItems").query("pageToken", "p2").json("{'items':[" + upload("v2") + "]}");
        videos(video("v1", "Hong Nhan Xua", "", "2026-01-02T00:00:00Z"), video("v2", "Biet Tri Ky", "", "2025-05-01T00:00:00Z"));
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v1", comment("c1", "Lan", "UCfan", "Which flute is this?", "2026-10-20T00:00:00Z"), 1,
                        comment("c1.r1", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi.", "2026-10-21T00:00:00Z"))
                + "," + thread("t2", "v2", comment("c2", "Minh", "UCfan2", "Sheet music please", "2026-10-22T00:00:00Z"), 0)
                + "]}");

        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("video:v1", "video:v2", "thread:t1", "thread:t2");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            ItemMetadata t1 = items.get(2);
            assertThat(t1.getTitle()).isEqualTo("Comment by Lan on Hong Nhan Xua");
            assertThat(t1.getLastModified()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
            assertThat(t1.getExtra("url")).isEqualTo("https://www.youtube.com/watch?v=v1&lc=c1");
            assertThat(items.get(0).getExtra("url")).isEqualTo("https://www.youtube.com/watch?v=v1");
        }
        assertThat(api.requests()).filteredOn(r -> r.path().endsWith("/commentThreads")).singleElement()
                .satisfies(r -> {
                    assertThat(r.param("allThreadsRelatedToChannelId")).isEqualTo(CHANNEL);
                    assertThat(r.param("order")).isEqualTo("time");
                });
    }

    @Test
    @DisplayName("a pass stops at its bounds and says so, so what it did not reach is not purged")
    void bounded() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "," + upload("v2") + "]}");
        videos(video("v1", "A", "", "2026-01-02T00:00:00Z"), video("v2", "B", "", "2026-01-01T00:00:00Z"));
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v1", comment("c1", "Lan", "UCfan", "Hi", "2026-10-20T00:00:00Z"), 0) + "],'nextPageToken':'more'}");
        api.on("GET", "/youtube/v3/commentThreads").query("pageToken", "more").json("{'items':["
                + thread("t2", "v1", comment("c2", "Minh", "UCfan", "Hello", "2026-10-19T00:00:00Z"), 0) + "]}");

        try (ConnectorSession s = session(Map.of("maxVideos", 1, "maxThreads", 1))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("video:v1", "thread:t1");
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).isEqualTo("Only the newest 1 videos and 1 comment threads are read each time.");
        }
        // The page's videos in one call, however few of them the bound lets through.
        assertThat(api.requests("/youtube/v3/videos")).singleElement();
    }

    @Test
    @DisplayName("each upload is an item with its description, hashed as the text it indexes as, so only an edit re-reads it")
    void videosWithDescriptions() throws IOException {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "," + upload("v2") + "," + upload("gone") + "]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        videos(video("v1", "Hong Nhan Xua", "Dizi in D. Sheet music: https://camamtieudao.com/hong-nhan-xua", "2026-01-02T00:00:00Z"),
                video("v2", "Biet Tri Ky", "", "2025-05-01T00:00:00Z"));

        List<ItemMetadata> items;
        String indexed;
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            items = drain(listing);
            // An upload videos.list does not return is not listed: this complete pass is what lets it go.
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("video:v1", "video:v2");
            assertThat(listing.isPartial()).isFalse();
            assertThat(listing.hasErrored()).isFalse();
            indexed = read(s.fetch(items.get(0)));
        }
        ItemMetadata v1 = items.get(0);
        assertThat(v1.getTitle()).isEqualTo("Hong Nhan Xua");
        assertThat(v1.getExtra("url")).isEqualTo("https://www.youtube.com/watch?v=v1");
        // Seen now; whether it changed is the hash's to say.
        assertThat(v1.getLastModified()).isEqualTo(NOW);
        assertThat(indexed).startsWith("# Hong Nhan Xua").contains("## Description",
                "Sheet music: https://camamtieudao.com/hong-nhan-xua", "- Published: 2026-01-02T00:00:00Z");
        assertThat(v1.getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo(YouTubeApi.hash(indexed));
        // Three uploads, one videos.list call: the playlist's ids, in its order.
        assertThat(api.requests("/youtube/v3/videos")).hasSize(2)
                .first().satisfies(r -> {
                    assertThat(r.param("id")).isEqualTo("v1,v2,gone");
                    assertThat(r.param("part")).isEqualTo("snippet,status");
                });

        // The next pass: v1 as it was hashes the same; v2's description edited hashes otherwise.
        api.reset();
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "," + upload("v2") + "]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        videos(video("v1", "Hong Nhan Xua", "Dizi in D. Sheet music: https://camamtieudao.com/hong-nhan-xua", "2026-01-02T00:00:00Z"),
                video("v2", "Biet Tri Ky", "Now with the sheet music.", "2025-05-01T00:00:00Z"));
        try (ConnectorSession s = session(Map.of())) {
            List<ItemMetadata> again = drain(s.list(ListRequest.full()));
            assertThat(again.get(0).getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo(v1.getExtra(ItemMetadata.CONTENT_HASH));
            assertThat(again.get(1).getExtra(ItemMetadata.CONTENT_HASH)).isNotEqualTo(items.get(1).getExtra(ItemMetadata.CONTENT_HASH));
        }
    }

    @Test
    @DisplayName("uploads are read 50 to a videos.list call: two pages of the playlist, two calls")
    void videosBatched() {
        channel();
        StringBuilder first = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            if (i <= 50) first.append(i > 1 ? "," : "").append(upload("v" + i));
            all.append(i > 1 ? "," : "").append(video("v" + i, "Video " + i, "About " + i, "2026-01-01T00:00:00Z"));
        }
        StringBuilder second = new StringBuilder();
        for (int i = 51; i <= 60; i++) second.append(i > 51 ? "," : "").append(upload("v" + i));
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + first + "],'nextPageToken':'p2'}");
        api.on("GET", "/youtube/v3/playlistItems").query("pageToken", "p2").json("{'items':[" + second + "]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        videos(all.toString());

        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).hasSize(60);
        }
        List<LoopbackApi.Recorded> calls = api.requests("/youtube/v3/videos");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).param("id").split(",")).hasSize(50).startsWith("v1").endsWith("v50");
        assertThat(calls.get(1).param("id").split(",")).hasSize(10).startsWith("v51").endsWith("v60");
    }

    @Test
    @DisplayName("a quota spent reading the videos is a limit: the pass errors, so nothing it did not list is purged")
    void videosQuotaExceeded() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        api.on("GET", "/youtube/v3/videos").status(403).json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("YouTube is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("a spent daily quota is said as a limit for the next sync, not as access refused")
    void quotaExceeded() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[]}");
        api.on("GET", "/youtube/v3/commentThreads").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("YouTube is limiting how often it is asked. The next sync will try again.");
        }
    }

    // -- refusals and the per-video fallback (2026-11-03) ------------------------------------------

    private static String refusal(String reason, String message) {
        return "{'error':{'code':403,'message':'" + message + "','errors':[{'reason':'" + reason + "'}]}}";
    }

    private Listing listThreadsOnly(Map<String, Object> fields) {
        ConnectorSession s = session(fields);
        return s.list(ListRequest.full());
    }

    @Test
    @DisplayName("a refused comment listing says what to do and keeps YouTube's own reason")
    void refusalsSayWhy() {
        Map<String, String> expected = Map.of(
                "insufficientPermissions", "The YouTube connection's sign-in does not include every permission it needs. "
                        + "Sign in again on the YouTube connection in Settings > Connections and tick every permission "
                        + "Google lists. YouTube said: insufficientPermissions (Request had insufficient authentication scopes.)",
                "accessNotConfigured", "The YouTube Data API v3 is not enabled in the connection's Google Cloud project. "
                        + "Enable it in Google Cloud > APIs & Services > Library, then sync again. YouTube said: "
                        + "accessNotConfigured (Request had insufficient authentication scopes.)",
                "channelNotFound", "YouTube refused access with the connection's credential. Check that its account may read "
                        + "this. YouTube said: channelNotFound (Request had insufficient authentication scopes.)");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            api.reset();
            channel();
            api.on("GET", "/youtube/v3/commentThreads").status(403)
                    .json(refusal(e.getKey(), "Request had insufficient authentication scopes."));
            Listing listing = listThreadsOnly(Map.of("maxVideos", 0));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).as(e.getKey()).isTrue();
            assertThat(listing.getErrorMessage()).as(e.getKey()).isEqualTo(e.getValue());
            // Refused: never read video by video.
            assertThat(api.requests("/youtube/v3/playlistItems")).as(e.getKey()).isEmpty();
        }
    }

    @Test
    @DisplayName("a channel whose comments cannot be listed at once is read video by video: a video with comments off is skipped, the pass is partial and purges nothing")
    void perVideoFallback() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "," + upload("v2") + "," + upload("v3")
                + "],'nextPageToken':'older'}");
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("commentsDisabled", "The channel has disabled comments on some videos."));
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v1")
                .json("{'items':[" + thread("t1", "v1", comment("c1", "Lan", "UCfan", "Which flute?", "2026-10-20T00:00:00Z"), 0)
                        + "],'nextPageToken':'more'}");
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v2").status(403)
                .json(refusal("commentsDisabled", "The video identified by the videoId parameter has disabled comments."));
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v3")
                .json("{'items':[" + thread("t3", "v3", comment("c3", "Minh", "UCfan2", "Sheet please", "2026-10-22T00:00:00Z"), 0) + "]}");

        Listing listing = listThreadsOnly(Map.of("maxVideos", 0));
        assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("thread:t1", "thread:t3");
        assertThat(listing.hasErrored()).isFalse();
        assertThat(listing.isPartial()).as("only the newest videos were read").isTrue();
        assertThat(listing.getGoneItemIds()).isEmpty();
        assertThat(listing.getPartialReason()).isEqualTo("YouTube does not list this channel's comments all at once, so its "
                + "newest 3 videos were read one by one (1 with comments turned off).");
        // One refused call, one page of uploads (the newest 20), then one call a video - one page each.
        assertThat(api.requests("/youtube/v3/playlistItems")).singleElement()
                .satisfies(r -> assertThat(r.param("maxResults")).isEqualTo(String.valueOf(YouTubeSession.FALLBACK_VIDEOS)));
        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("videoId"))
                .containsExactly(null, "v1", "v2", "v3");
    }

    @Test
    @DisplayName("every video with comments off: nothing to read, said plainly, and still partial")
    void everyVideoCommentsOff() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "," + upload("v2") + "]}");
        api.on("GET", "/youtube/v3/commentThreads").status(403)
                .json(refusal("commentsDisabled", "The video identified by the videoId parameter has disabled comments."));
        Listing listing = listThreadsOnly(Map.of("maxVideos", 0));
        assertThat(drain(listing)).isEmpty();
        assertThat(listing.hasErrored()).isFalse();
        assertThat(listing.isPartial()).isTrue();
        assertThat(listing.getPartialReason()).isEqualTo("Comments are turned off for this channel or its videos (videos made "
                + "for kids have none), so there are no comments to read.");
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(3);
    }

    @Test
    @DisplayName("a video refused for another reason than comments off ends the pass with that refusal, so nothing is purged")
    void perVideoOtherRefusal() {
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + upload("v1") + "]}");
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("forbidden", "The request is not properly authorized."));
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v1").status(403)
                .json(refusal("forbidden", "The request is not properly authorized."));
        Listing listing = listThreadsOnly(Map.of("maxVideos", 0));
        drain(listing);
        assertThat(listing.hasErrored()).isTrue();
        assertThat(listing.getErrorMessage()).isEqualTo("YouTube refused access with the connection's credential. Check that "
                + "its account may read this. YouTube said: forbidden (The request is not properly authorized.)");
    }

    // -- fetching ------------------------------------------------------------------------------

    @Test
    @DisplayName("a thread reads with every reply, the channel's own marked, and whether it has answered")
    void threadWithAllReplies() throws IOException {
        channel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "t1").json("{'items':["
                + thread("t1", "v1", comment("c1", "Lan", "UCfan", "Which key for beginners?", "2026-10-20T00:00:00Z"), 7,
                        comment("r1", "Minh", "UCfan2", "Same question", "2026-10-21T00:00:00Z")) + "]}");
        StringBuilder replies = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) replies.append(',');
            replies.append(comment("r" + i, "Fan " + i, "UCfan" + i, "Reply " + i, "2026-10-2" + i + "T00:00:00Z"));
        }
        replies.append(',').append(comment("r7", "Cam Am Tieu Dao", CHANNEL, "C5 or D5 is easiest.", "2026-10-28T00:00:00Z"));
        api.on("GET", "/youtube/v3/comments").json("{'items':[" + replies + "]}");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v1','snippet':{'title':'Hong Nhan Xua'}}]}");

        ItemMetadata item = new ItemMetadata();
        item.setId("thread:t1");
        String text;
        try (ConnectorSession s = session(Map.of())) {
            text = read(s.fetch(item));
        }
        assertThat(text).startsWith("# Comment by Lan on \"Hong Nhan Xua\"");
        assertThat(text).contains("- Thread ID: t1", "- Comment ID: c1", "- Answered by the channel: yes",
                "- Replies: 7", "Which key for beginners?", "- Cam Am Tieu Dao (the channel), 2026-10-28T00:00:00Z: C5 or D5 is easiest.");
        assertThat(text).contains("Reply 6");
        assertThat(api.requests()).filteredOn(r -> r.path().endsWith("/comments")).singleElement()
                .satisfies(r -> assertThat(r.param("parentId")).isEqualTo("c1"));
    }

    @Test
    @DisplayName("an unanswered thread says so; a thread or video YouTube no longer has is gone")
    void unansweredAndGone() throws IOException {
        channel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "t2").json("{'items':["
                + thread("t2", "v2", comment("c2", "Minh", "UCfan2", "Sheet music please", "2026-10-22T00:00:00Z"), 0) + "]}");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "gone").json("{'items':[]}");
        api.on("GET", "/youtube/v3/videos").query("id", "v2").json("{'items':[{'id':'v2','snippet':{'title':'Biet Tri Ky',"
                + "'description':'A cover.','publishedAt':'2025-05-01T00:00:00Z'},'status':{'privacyStatus':'unlisted'}}]}");
        api.on("GET", "/youtube/v3/videos").query("id", "nope").json("{'items':[]}");

        try (ConnectorSession s = session(Map.of())) {
            ItemMetadata t2 = new ItemMetadata();
            t2.setId("thread:t2");
            assertThat(read(s.fetch(t2))).contains("- Answered by the channel: no", "Sheet music please")
                    .doesNotContain("## Replies");

            ItemMetadata v2 = new ItemMetadata();
            v2.setId("video:v2");
            assertThat(read(s.fetch(v2))).startsWith("# Biet Tri Ky").contains("- Visibility: unlisted", "A cover.",
                    "- Link: https://www.youtube.com/watch?v=v2");

            ItemMetadata gone = new ItemMetadata();
            gone.setId("thread:gone");
            assertThatThrownBy(() -> s.fetch(gone)).isInstanceOf(ItemGoneException.class);
            ItemMetadata noVideo = new ItemMetadata();
            noVideo.setId("video:nope");
            assertThatThrownBy(() -> s.fetch(noVideo)).isInstanceOf(ItemGoneException.class);
        }
    }

    @Test
    @DisplayName("registration's probe: a channel is verified, an account with none is refused with a sentence")
    void probe() {
        channel();
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[]}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).startsWith("This Google account has no YouTube channel.");
        }
        assertThat(new YouTubeConnectorPlugin().identity(Map.of())).isEqualTo("channel");
        assertThat(YouTubeConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
    }

    // -- the source's name and link ------------------------------------------------------------

    @Test
    @DisplayName("the source is named after the channel's title and links to the channel, its handle first")
    void namedAfterTheChannel() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao',"
                + "'customUrl':'@camamtieudao'},'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        try (ConnectorSession s = session(Map.of())) {
            // Nothing read yet, nothing to say - and asking makes no request.
            assertThat(s.label()).isNull();
            assertThat(api.requests("/youtube/v3/channels")).isEmpty();
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
            assertThat(s.label()).isEqualTo(new SourceLabel("Cam Am Tieu Dao", "https://www.youtube.com/@camamtieudao"));
        }

        // A channel with no handle links by its id, and a pass names it as the probe does.
        api.reset();
        channel();
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        try (ConnectorSession s = session(Map.of())) {
            drain(s.list(ListRequest.full()));
            assertThat(s.label()).isEqualTo(new SourceLabel("Cam Am Tieu Dao", "https://www.youtube.com/channel/" + CHANNEL));
        }

        // The name is only a label: the type is matched by its configuration, and links to the page kept for it.
        YouTubeConnectorPlugin plugin = new YouTubeConnectorPlugin();
        assertThat(plugin.identifiedByConfig()).isTrue();
        assertThat(plugin.displayName(Map.of())).isEqualTo("YouTube channel");
        assertThat(plugin.webUrl(Map.of(SourceLabel.WEB_URL, "https://www.youtube.com/@camamtieudao"), null))
                .isEqualTo("https://www.youtube.com/@camamtieudao");
        // Until the channel has been read, and for anything that is not a YouTube address, YouTube Studio.
        assertThat(plugin.webUrl(Map.of(), null)).isEqualTo("https://studio.youtube.com/");
        assertThat(plugin.webUrl(Map.of(SourceLabel.WEB_URL, "https://evil.example/"), null)).isEqualTo("https://studio.youtube.com/");
    }

    private static final String READ = "https://www.googleapis.com/auth/youtube.readonly";
    private static final String NUMBERS = "https://www.googleapis.com/auth/yt-analytics.readonly";
    private static final String WRITE = "https://www.googleapis.com/auth/youtube.force-ssl";

    @Test
    @DisplayName("granular consent: a grant that cannot read, or cannot post with Write on, needs a new sign-in; analytics left out does not")
    void consentShortfall() {
        YouTubeConnectionPlugin p = new YouTubeConnectionPlugin();
        // Everything asked, under either set - and the older set of all three.
        assertThat(p.consentShortfall(java.util.Set.of(READ, NUMBERS), false)).isEmpty();
        assertThat(p.consentShortfall(java.util.Set.of(NUMBERS, WRITE), true)).isEmpty();
        assertThat(p.consentShortfall(java.util.Set.of(READ, NUMBERS, WRITE), true)).isEmpty();
        // Analytics unticked: comments work, so nothing to sign in again for yet.
        assertThat(p.consentShortfall(java.util.Set.of(READ), false)).isEmpty();
        assertThat(p.consentShortfall(java.util.Set.of(WRITE), true)).isEmpty();
        // Write off and no force-ssl is the ordinary read-only grant.
        assertThat(p.consentShortfall(java.util.Set.of(READ, NUMBERS), false)).isEmpty();
        // Nothing that reads the channel.
        assertThat(p.consentShortfall(java.util.Set.of(NUMBERS), false)).hasValueSatisfying(s -> assertThat(s)
                .contains("read the channel's comments and videos").contains("Sign in again"));
        assertThat(p.consentShortfall(java.util.Set.of(NUMBERS), true)).hasValueSatisfying(s -> assertThat(s)
                .contains("read the channel's comments"));
        // Write on, force-ssl unticked: replies cannot be posted.
        assertThat(p.consentShortfall(java.util.Set.of(READ, NUMBERS), true)).hasValueSatisfying(s -> assertThat(s)
                .contains("post the replies you approve").contains("turn Write off"));
    }

    @Test
    @DisplayName("each Data API request is priced as YouTube prices it; the Analytics and Reporting APIs cost nothing here")
    void requestUnits() {
        YouTubeConnectionPlugin p = new YouTubeConnectionPlugin();
        java.util.function.BiFunction<org.springframework.http.HttpMethod, String, Integer> cost =
                (m, url) -> p.requestUnits(m, java.net.URI.create(url));
        var GET = org.springframework.http.HttpMethod.GET;
        var POST = org.springframework.http.HttpMethod.POST;
        assertThat(cost.apply(GET, "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet")).isEqualTo(1);
        assertThat(cost.apply(GET, "https://www.googleapis.com/youtube/v3/videos?id=a")).isEqualTo(1);
        assertThat(cost.apply(GET, "https://www.googleapis.com/youtube/v3/channels?mine=true")).isEqualTo(1);
        assertThat(cost.apply(POST, "https://www.googleapis.com/youtube/v3/comments?part=snippet")).isEqualTo(50);
        assertThat(cost.apply(POST, "https://www.googleapis.com/youtube/v3/commentThreads")).isEqualTo(50);
        assertThat(cost.apply(POST, "https://www.googleapis.com/youtube/v3/comments/setModerationStatus")).isEqualTo(50);
        assertThat(cost.apply(org.springframework.http.HttpMethod.DELETE, "https://www.googleapis.com/youtube/v3/comments")).isEqualTo(50);
        assertThat(cost.apply(POST, "https://www.googleapis.com/youtube/v3/captions")).isEqualTo(400);
        // A test server under the connection's own URL is priced by path the same way.
        assertThat(cost.apply(GET, "http://127.0.0.1:9/youtube/v3/playlistItems")).isEqualTo(1);
        assertThat(cost.apply(GET, "https://youtubeanalytics.googleapis.com/v2/reports")).isZero();
        assertThat(cost.apply(POST, "https://youtubereporting.googleapis.com/v1/jobs")).isZero();
    }

    @Test
    @DisplayName("the request that says whose channel a connection is: channels.list mine, and nothing else (2026-11-11)")
    void identityRequest() {
        YouTubeConnectionPlugin plugin = new YouTubeConnectionPlugin();
        org.springframework.http.HttpMethod get = org.springframework.http.HttpMethod.GET;
        assertThat(plugin.identityRequest(get, java.net.URI.create(
                "https://www.googleapis.com/youtube/v3/channels?part=snippet%2CcontentDetails&mine=true"))).isTrue();
        assertThat(plugin.identityRequest(get, java.net.URI.create(
                "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=UC1,UC2"))).as("viewers' channels").isFalse();
        assertThat(plugin.identityRequest(get, java.net.URI.create(
                "https://www.googleapis.com/youtube/v3/commentThreads?mine=true"))).isFalse();
        assertThat(plugin.identityRequest(org.springframework.http.HttpMethod.POST, java.net.URI.create(
                "https://www.googleapis.com/youtube/v3/channels?mine=true"))).isFalse();
    }
}
