package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The channel's numbers against loopback YouTube APIs (the Data API, the Analytics API's reports.query
 * and the Reporting API's jobs and CSVs, all on one test server, as a connection with a changed API URL
 * reaches them): each tool's answer, compact and with numbers as numbers; the named reports and nothing
 * else; the dates checked before anything is asked; a sign-in without YouTube Analytics, an API the
 * project has not enabled and a spent quota each said as what to do; and the reach job made on the first
 * call, reused after, its CSVs read once.
 */
class YouTubeAnalyticsToolsTest {

    private static final Instant NOW = Instant.parse("2026-10-31T06:00:00Z");
    private LoopbackApi api;
    private ToolConnectorResolver resolver;
    private UUID connectionId;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private YouTubeTools tools() {
        return tools(null);
    }

    // accountJson: what the sign-in kept of Google's token answer ({"scope":"..."}), or null for a
    // connection signed in before the scopes were kept.
    private YouTubeTools tools(String accountJson) {
        resolver = mock(ToolConnectorResolver.class);
        connectionId = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(connectionId, "local", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, false, null, null, accountJson);
        when(resolver.resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ))).thenReturn(Endpoint.of(connection));
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwned(any(), eq(connectionId))).thenReturn(Optional.of(connection));
        return new YouTubeTools(resolver, connections, new SourceContextFactory(null, null, null, null),
                ConnectorHttp.direct(), mock(McpGuardService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) {
        return (List<Map<String, Object>>) out.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> out) {
        return (List<List<Object>>) out.get("rows");
    }

    // -- the channel and its videos ---------------------------------------------------------------

    @Test
    @DisplayName("the channel's stats: numbers as numbers, a hidden subscriber count as null, and what they mean")
    void channelStats() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'Cam Am Tieu Dao',"
                + "'customUrl':'@camamtieudao','publishedAt':'2019-03-01T00:00:00Z'},'statistics':{'viewCount':'1234567',"
                + "'subscriberCount':'12300','hiddenSubscriberCount':false,'videoCount':'210'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        Map<String, Object> out = tools().getYouTubeChannelStats();
        assertThat(out).containsEntry("success", true).containsEntry("channelId", "UCowner").containsEntry("title", "Cam Am Tieu Dao")
                .containsEntry("handle", "@camamtieudao").containsEntry("subscriberCount", 12300L)
                .containsEntry("viewCount", 1234567L).containsEntry("videoCount", 210L)
                .containsEntry("uploadsPlaylistId", "UUowner").containsEntry("link", "https://www.youtube.com/@camamtieudao");
        assertThat((String) out.get("note")).contains("three significant figures").contains("public videos only");
        assertThat(api.requests("/youtube/v3/channels").get(0).param("part")).contains("statistics");
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ));

        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'T'},"
                + "'statistics':{'viewCount':'5','hiddenSubscriberCount':true,'videoCount':'1'}}]}");
        Map<String, Object> hidden = tools().getYouTubeChannelStats();
        assertThat(hidden).containsEntry("subscriberCount", null).containsEntry("link", "https://www.youtube.com/channel/UCowner");
        // No description is an empty one, never a missing field.
        assertThat(hidden).containsEntry("description", "");
    }

    @Test
    @DisplayName("the channel's description comes with the same one request, and a long one is cut with a marker")
    void channelDescription() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'Cam Am Tieu Dao',"
                + "'description':'  Dizi and xiao covers.\\nSheet music: camamtieudao.com  '},"
                + "'statistics':{'viewCount':'5','videoCount':'1'}}]}");
        Map<String, Object> out = tools().getYouTubeChannelStats();
        assertThat(out).containsEntry("description", "Dizi and xiao covers.\nSheet music: camamtieudao.com");
        // The part the overview already asked for carries it: one request, one quota unit.
        assertThat(api.requests("/youtube/v3/channels")).singleElement()
                .satisfies(r -> assertThat(r.param("part")).isEqualTo("snippet,statistics,contentDetails"));

        api.reset();
        String longText = "a".repeat(YouTubeTools.MAX_CHANNEL_DESCRIPTION + 500);
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'T','description':'"
                + longText + "'},'statistics':{'viewCount':'5','videoCount':'1'}}]}");
        String cut = (String) tools().getYouTubeChannelStats().get("description");
        assertThat(cut).hasSize(YouTubeTools.MAX_CHANNEL_DESCRIPTION + YouTubeTools.CUT.length()).endsWith("...(truncated)")
                .startsWith("a".repeat(YouTubeTools.MAX_CHANNEL_DESCRIPTION));
    }

    @Test
    @DisplayName("the newest uploads with their numbers, in the playlist's order, bounded, and after a date")
    void videos() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':["
                + "{'contentDetails':{'videoId':'v3','videoPublishedAt':'2026-10-20T00:00:00Z'}},"
                + "{'contentDetails':{'videoId':'v2','videoPublishedAt':'2026-10-01T00:00:00Z'}},"
                + "{'contentDetails':{'videoId':'v1','videoPublishedAt':'2026-09-01T00:00:00Z'}}],'nextPageToken':'p2'}");
        api.on("GET", "/youtube/v3/videos").json("{'items':["
                + "{'id':'v2','snippet':{'title':'Biet Tri Ky','publishedAt':'2026-10-01T00:00:00Z','liveBroadcastContent':'none'},"
                + "'contentDetails':{'duration':'PT4M13S'},'status':{'privacyStatus':'public'},"
                + "'statistics':{'viewCount':'900','likeCount':'40','commentCount':'12'}},"
                + "{'id':'v3','snippet':{'title':'Hoa Su Nha Nang','publishedAt':'2026-10-20T00:00:00Z'},"
                + "'contentDetails':{'duration':'PT58S'},'status':{'privacyStatus':'unlisted'},"
                + "'statistics':{'viewCount':'120','commentCount':'3'}}]}");

        Map<String, Object> out = tools().listYouTubeVideos(2, null, null);
        assertThat(out).containsEntry("success", true).containsEntry("channel", "Cam Am Tieu Dao").containsEntry("more", true);
        List<Map<String, Object>> videos = list(out, "videos");
        assertThat(videos).extracting(v -> v.get("videoId")).containsExactly("v3", "v2");
        assertThat(videos.get(0)).containsEntry("durationSeconds", 58L).containsEntry("visibility", "unlisted")
                .containsEntry("views", 120L).doesNotContainKey("likes").containsEntry("link", "https://www.youtube.com/watch?v=v3");
        assertThat(videos.get(1)).containsEntry("title", "Biet Tri Ky").containsEntry("durationSeconds", 253L)
                .containsEntry("likes", 40L).containsEntry("comments", 12L).doesNotContainKey("live");
        assertThat(api.requests("/youtube/v3/playlistItems").get(0).param("playlistId")).isEqualTo("UUowner");
        assertThat(api.requests("/youtube/v3/videos").get(0).param("id")).isEqualTo("v3,v2");

        Map<String, Object> recent = tools().listYouTubeVideos(null, "2026-09-15", null);
        assertThat(list(recent, "videos")).hasSize(2);
        assertThat(recent).containsEntry("more", false);
        assertThat(tools().listYouTubeVideos(null, "last week", null)).containsEntry("success", false);
        assertThat(videos).as("no description unless asked").allSatisfy(v -> assertThat(v).doesNotContainKey("description"));
    }

    @Test
    @DisplayName("each upload's description when asked, cut with a marker, from the same one videos.list call")
    void videoDescriptions() {
        String longText = "d".repeat(YouTubeTools.MAX_VIDEO_DESCRIPTION + 300);
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':["
                + "{'contentDetails':{'videoId':'v2','videoPublishedAt':'2026-10-20T00:00:00Z'}},"
                + "{'contentDetails':{'videoId':'v1','videoPublishedAt':'2026-10-01T00:00:00Z'}}]}");
        api.on("GET", "/youtube/v3/videos").json("{'items':["
                + "{'id':'v1','snippet':{'title':'Biet Tri Ky','description':'  Dizi cover. Sheet: camamtieudao.com  '}},"
                + "{'id':'v2','snippet':{'title':'Hoa Su','description':'" + longText + "'}}]}");

        List<Map<String, Object>> videos = list(tools().listYouTubeVideos(null, null, true), "videos");
        assertThat(videos).extracting(v -> v.get("videoId")).containsExactly("v2", "v1");
        assertThat(videos.get(1)).containsEntry("description", "Dizi cover. Sheet: camamtieudao.com");
        assertThat((String) videos.get(0).get("description"))
                .hasSize(YouTubeTools.MAX_VIDEO_DESCRIPTION + YouTubeTools.CUT.length()).endsWith(YouTubeTools.CUT);
        assertThat(api.requests("/youtube/v3/videos")).as("one call for the page, descriptions included").singleElement()
                .satisfies(r -> assertThat(r.param("part")).contains("snippet"));
    }

    // -- YouTube Analytics -------------------------------------------------------------------------

    private void overview(String... days) {
        StringBuilder rows = new StringBuilder();
        for (String d : days) {
            if (rows.length() > 0) rows.append(',');
            rows.append("['").append(d).append("',10,20,30,90,45.5,2,1,4,1,0]");
        }
        api.on("GET", "/v2/reports").json("{'kind':'youtubeAnalytics#resultTable','columnHeaders':["
                + "{'name':'day','columnType':'DIMENSION','dataType':'STRING'},"
                + "{'name':'engagedViews','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'views','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'estimatedMinutesWatched','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'averageViewDuration','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'averageViewPercentage','columnType':'METRIC','dataType':'FLOAT'},"
                + "{'name':'subscribersGained','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'subscribersLost','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'likes','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'comments','columnType':'METRIC','dataType':'INTEGER'},"
                + "{'name':'shares','columnType':'METRIC','dataType':'INTEGER'}],'rows':[" + rows + "]}");
    }

    @Test
    @DisplayName("overview: by day, with totals, numbers as numbers, asked as the channel's own named report")
    void overviewByDay() {
        overview("2026-10-01", "2026-10-02");
        Map<String, Object> out = tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "overview", null);
        assertThat(out).containsEntry("success", true).containsEntry("report", "overview").containsEntry("grouping", "day")
                .containsEntry("more", false);
        assertThat(((List<?>) out.get("columns")).get(0)).isEqualTo("day");
        assertThat(rows(out).get(0)).containsExactly("2026-10-01", 10L, 20L, 30L, 90L, 45.5, 2L, 1L, 4L, 1L, 0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertThat(totals).containsEntry("engagedViews", 20L).containsEntry("views", 40L)
                .containsEntry("estimatedMinutesWatched", 60L).containsEntry("averageViewDuration", 90.0)
                .containsEntry("averageViewPercentage", 45.5);
        assertThat((String) out.get("note")).contains("two to three days").contains("engagedViews");

        LoopbackApi.Recorded q = api.requests("/v2/reports").get(0);
        assertThat(q.param("ids")).isEqualTo("channel==MINE");
        assertThat(q.param("startDate")).isEqualTo("2026-10-01");
        assertThat(q.param("endDate")).isEqualTo("2026-10-28");
        assertThat(q.param("dimensions")).isEqualTo("day");
        assertThat(q.param("metrics")).isEqualTo("engagedViews,views,estimatedMinutesWatched,averageViewDuration,"
                + "averageViewPercentage,subscribersGained,subscribersLost,likes,comments,shares");
        assertThat(q.header("Authorization")).isEqualTo("Bearer ya29.test");
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ));
    }

    @Test
    @DisplayName("overview past 60 days is grouped by week, so a year stays about fifty rows")
    void overviewByWeek() {
        List<String> days = new ArrayList<>();
        for (int i = 0; i < 90; i++) days.add(java.time.LocalDate.parse("2026-07-01").plusDays(i).toString());
        overview(days.toArray(String[]::new));
        Map<String, Object> out = tools().getYouTubeAnalytics("2026-07-01", "2026-09-28", "overview", null);
        assertThat(out).containsEntry("grouping", "week");
        assertThat(((List<?>) out.get("columns")).get(0)).isEqualTo("week");
        // 2026-07-01 is a Wednesday: its week starts on Monday 2026-06-29 and holds five days.
        assertThat(rows(out).get(0)).startsWith("2026-06-29", 50L, 100L, 150L, 90.0, 45.5);
        assertThat(rows(out)).hasSizeLessThanOrEqualTo(14);
    }

    @Test
    @DisplayName("topVideos: the 50 most viewed, ranked by engaged views, each with its title")
    void topVideos() {
        api.on("GET", "/v2/reports").json("{'columnHeaders':[{'name':'video'},{'name':'engagedViews'},{'name':'views'}],"
                + "'rows':[['v1',5,100],['v2',50,90]]}");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v1','snippet':{'title':'One'}},{'id':'v2','snippet':{'title':'Two'}}]}");
        Map<String, Object> out = tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "topVideos", null);
        assertThat(out.get("columns")).isEqualTo(List.of("video", "title", "engagedViews", "views"));
        assertThat(rows(out)).containsExactly(List.of("v2", "Two", 50L, 90L), List.of("v1", "One", 5L, 100L));
        LoopbackApi.Recorded q = api.requests("/v2/reports").get(0);
        assertThat(q.param("dimensions")).isEqualTo("video");
        assertThat(q.param("sort")).isEqualTo("-views");
        assertThat(q.param("maxResults")).isEqualTo("50");
        assertThat(tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "topVideos", "v1"))
                .containsEntry("error", "topVideos covers the whole channel; leave videoId out.");
    }

    @Test
    @DisplayName("searchTerms is YouTube search's top 25; retention needs a video and is sampled to 50 points")
    void searchTermsAndRetention() {
        api.on("GET", "/v2/reports").json("{'columnHeaders':[{'name':'insightTrafficSourceDetail'},{'name':'engagedViews'},"
                + "{'name':'views'},{'name':'estimatedMinutesWatched'}],'rows':[['sao truc',3,9,20]]}");
        Map<String, Object> terms = tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "searchTerms", null);
        assertThat(rows(terms)).containsExactly(List.of("sao truc", 3L, 9L, 20L));
        LoopbackApi.Recorded q = api.requests("/v2/reports").get(0);
        assertThat(q.param("filters")).isEqualTo("insightTrafficSourceType==YT_SEARCH");
        assertThat(q.param("maxResults")).isEqualTo("25");
        assertThat(q.param("sort")).isEqualTo("-views");

        YouTubeTools tools = tools();
        assertThat(tools.getYouTubeAnalytics("2026-10-01", "2026-10-28", "retention", null)).extractingByKey("error")
                .asString().startsWith("The retention report is about one video");

        api.reset();
        StringBuilder curve = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (i > 1) curve.append(',');
            curve.append('[').append(i / 100.0).append(',').append(1 - i / 200.0).append(",0.5]");
        }
        api.on("GET", "/v2/reports").json("{'columnHeaders':[{'name':'elapsedVideoTimeRatio'},{'name':'audienceWatchRatio'},"
                + "{'name':'relativeRetentionPerformance'}],'rows':[" + curve + "]}");
        Map<String, Object> retention = tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "retention", "v2");
        assertThat(rows(retention)).hasSize(50);
        assertThat(rows(retention).get(49).get(0)).isEqualTo(1.0);
        assertThat(retention).containsEntry("videoId", "v2");
        LoopbackApi.Recorded r = api.requests("/v2/reports").get(0);
        assertThat(r.param("filters")).isEqualTo("video==v2");
        assertThat(r.param("metrics")).isEqualTo("audienceWatchRatio,relativeRetentionPerformance");
    }

    @Test
    @DisplayName("only the named reports, only real dates within a year: refused before anything is asked")
    void validation() {
        YouTubeTools tools = tools();
        assertThat(tools.getYouTubeAnalytics("2026-10-01", "2026-10-28", "estimatedRevenue", null)).containsEntry("error",
                "report is one of overview, topVideos, trafficSources, searchTerms, subscribedStatus, contentType, "
                        + "geography, retention.");
        // The sentence names every report there is, and nothing else.
        String names = String.join(", ", java.util.Arrays.stream(YouTubeAnalytics.Report.values()).map(r -> r.id).toList());
        assertThat(names).isEqualTo("overview, topVideos, trafficSources, searchTerms, subscribedStatus, contentType, "
                + "geography, retention");
        assertThat(tools.getYouTubeAnalytics("2026-10-01", "2026-10-28", null, null)).containsEntry("success", false);
        assertThat(tools.getYouTubeAnalytics("1/10/2026", "2026-10-28", "overview", null)).containsEntry("error",
                "startDate and endDate are dates written YYYY-MM-DD, such as 2026-10-01.");
        assertThat(tools.getYouTubeAnalytics("2026-10-28", "2026-10-01", "overview", null))
                .containsEntry("error", "startDate is after endDate.");
        assertThat(tools.getYouTubeAnalytics("2025-01-01", "2026-10-28", "overview", null))
                .containsEntry("error", "A period is at most 365 days; this one has 666.");
        assertThat(tools.getYouTubeAnalytics("2026-10-01", "2026-12-01", "overview", null)).extractingByKey("error")
                .asString().startsWith("endDate is in the future.");
        assertThat(tools.getYouTubeAnalytics("2026-10-01", "2026-10-28", "geography", "x/../y"))
                .containsEntry("error", "That is not a YouTube video ID.");
        assertThat(tools.getYouTubeReach("2026-10-01", "soon")).containsEntry("success", false);
        // A report name in any case is the report.
        assertThat(YouTubeAnalytics.Report.of(" TOPVIDEOS ")).isEqualTo(YouTubeAnalytics.Report.TOP_VIDEOS);
        assertThat(api.requests()).isEmpty();
        verify(resolver, never()).resolve(any(), any(), any(), any());
    }

    // -- refusals ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a sign-in without YouTube Analytics says to sign in again - asked, or known from the grant")
    void missingScope() {
        api.on("GET", "/v2/reports").status(403).json("{'error':{'code':403,'message':'Request had insufficient authentication "
                + "scopes.','errors':[{'reason':'insufficientPermissions'}],'status':'PERMISSION_DENIED',"
                + "'details':[{'reason':'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}]}}");
        String sentence = "The YouTube connection's sign-in does not allow YouTube Analytics. Tell the user to sign in again "
                + "on the YouTube connection in Settings > Connections to allow YouTube Analytics; comments keep working meanwhile.";
        assertThat(tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "overview", null)).containsEntry("error", sentence);

        api.on("GET", "/v1/jobs").status(403).json("{'error':{'code':403,'status':'PERMISSION_DENIED',"
                + "'details':[{'reason':'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}]}}");
        assertThat(tools().getYouTubeReach("2026-10-01", "2026-10-28")).containsEntry("error", sentence);

        api.reset();
        String grant = "{\"scope\":\"https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.force-ssl\"}";
        assertThat(tools(grant).getYouTubeAnalytics("2026-10-01", "2026-10-28", "overview", null)).containsEntry("error", sentence);
        assertThat(tools(grant).getYouTubeReach("2026-10-01", "2026-10-28")).containsEntry("error", sentence);
        assertThat(api.requests()).isEmpty();

        // A grant that allowed it is asked as usual.
        overview("2026-10-01");
        String allowed = "{\"scope\":\"https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/yt-analytics.readonly\"}";
        assertThat(tools(allowed).getYouTubeAnalytics("2026-10-01", "2026-10-28", "overview", null)).containsEntry("success", true);
    }

    @Test
    @DisplayName("an API the project has not enabled is named, and a spent quota is a limit, not a refusal")
    void apiNotEnabledAndQuota() {
        api.on("GET", "/v2/reports").status(403).json("{'error':{'code':403,'errors':[{'reason':'accessNotConfigured'}]}}");
        assertThat(tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "overview", null)).extractingByKey("error").asString()
                .startsWith("The YouTube Analytics API is not enabled").contains("enable the YouTube Analytics API in their Google Cloud project");

        api.on("GET", "/v1/jobs").status(403).json("{'error':{'code':403,'status':'PERMISSION_DENIED',"
                + "'details':[{'@type':'type.googleapis.com/google.rpc.ErrorInfo','reason':'SERVICE_DISABLED'}]}}");
        assertThat(tools().getYouTubeReach("2026-10-01", "2026-10-28")).extractingByKey("error").asString()
                .startsWith("The YouTube Reporting API is not enabled").contains("enable the YouTube Reporting API");

        api.on("GET", "/youtube/v3/channels").status(403).json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        assertThat(tools().getYouTubeChannelStats()).extractingByKey("error").asString()
                .contains("daily quota").contains("This is a limit, not a refusal");

        api.reset();
        api.on("GET", "/v2/reports").status(400).json("{'error':{'code':400,'message':'The query is not supported.'}}");
        assertThat(tools().getYouTubeAnalytics("2026-10-01", "2026-10-28", "contentType", null))
                .containsEntry("error", "YouTube Analytics refused the query: The query is not supported.");
    }

    // -- reach -------------------------------------------------------------------------------------

    private static final String CSV = "date,channel_id,video_id,video_thumbnail_impressions,video_thumbnail_impressions_ctr\n"
            + "20261020,UCowner,v1,1000,0.05\n"
            + "20261020,UCowner,v2,500,0.02\n";

    @Test
    @DisplayName("reach: the first call makes the job and says collection started; the next reads the CSVs, once each")
    void reachJobThenReports() {
        api.on("GET", "/v1/jobs").json("{}");
        api.on("POST", "/v1/jobs").json("{'id':'job1','reportTypeId':'channel_reach_basic_a1','name':'Proxyma reach',"
                + "'createTime':'2026-10-31T06:00:00Z'}");
        YouTubeTools tools = tools();

        Map<String, Object> first = tools.getYouTubeReach("2026-10-01", "2026-10-28");
        assertThat(first).containsEntry("success", true).containsEntry("collecting", "started")
                .containsEntry("collectionStartedOn", "2026-10-31");
        assertThat(list(first, "days")).isEmpty();
        assertThat((String) first.get("note")).contains("Collection started on 2026-10-31")
                .contains("the first day arrives in about two days").contains("YouTube keeps 60 days");
        assertThat(api.requests("/v1/jobs")).filteredOn(r -> r.method().equals("POST")).singleElement()
                .satisfies(r -> assertThat(r.body()).contains("\"reportTypeId\":\"channel_reach_basic_a1\""));
        assertThat(api.requests("/v1/jobs/job1/reports")).isEmpty();

        api.on("GET", "/v1/jobs/job1/reports").json("{'reports':["
                + "{'id':'r1','jobId':'job1','startTime':'2026-10-20T07:00:00Z','endTime':'2026-10-21T07:00:00Z',"
                + "'createTime':'2026-10-22T10:00:00Z','downloadUrl':'" + api.url("/v1/media/r1?alt=media") + "'}]}");
        api.on("GET", "/v1/media/r1").body(CSV.getBytes(StandardCharsets.UTF_8), "text/csv");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v1','snippet':{'title':'One'}}]}");

        Map<String, Object> second = tools.getYouTubeReach("2026-10-01", "2026-10-28");
        assertThat(second).containsEntry("collecting", "ready").containsEntry("missingDays", 27L).containsEntry("more", false);
        assertThat(list(second, "days")).singleElement().satisfies(d ->
                assertThat(d).containsEntry("date", "2026-10-20").containsEntry("impressions", 1500L).containsEntry("ctr", 0.04));
        assertThat(list(second, "videos")).extracting(v -> v.get("videoId")).containsExactly("v1", "v2");
        assertThat(list(second, "videos").get(0)).containsEntry("title", "One").containsEntry("impressions", 1000L)
                .containsEntry("ctr", 0.05);
        assertThat(list(second, "videos").get(1)).containsEntry("title", "");
        // The job is remembered: no second jobs.list, no second job.
        assertThat(api.requests("/v1/jobs")).hasSize(2);
        LoopbackApi.Recorded listed = api.requests("/v1/jobs/job1/reports").get(0);
        assertThat(listed.param("startTimeAtOrAfter")).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(listed.param("startTimeBefore")).isEqualTo("2026-10-29T00:00:00Z");
        assertThat(api.requests("/v1/media/r1").get(0).header("Authorization")).isEqualTo("Bearer ya29.test");

        tools.getYouTubeReach("2026-10-15", "2026-10-28");
        assertThat(api.requests("/v1/media/r1")).hasSize(1);
    }

    @Test
    @DisplayName("reach: an existing job is reused; recent with nothing yet is waiting; a re-issued day replaces the older copy")
    void reachExistingJob() {
        api.on("GET", "/v1/jobs").json("{'jobs':[{'id':'other','reportTypeId':'channel_basic_a3'},"
                + "{'id':'job9','reportTypeId':'channel_reach_basic_a1','createTime':'2026-10-30T00:00:00Z'}]}");
        api.on("GET", "/v1/jobs/job9/reports").json("{}");
        Map<String, Object> waiting = tools().getYouTubeReach("2026-10-01", "2026-10-28");
        assertThat(waiting).containsEntry("collecting", "waiting").containsEntry("collectionStartedOn", "2026-10-30");
        assertThat(api.requests("/v1/jobs")).noneMatch(r -> r.method().equals("POST"));

        api.reset();
        api.on("GET", "/v1/jobs").json("{'jobs':[{'id':'job9','reportTypeId':'channel_reach_basic_a1',"
                + "'createTime':'2026-09-01T00:00:00Z'}]}");
        api.on("GET", "/v1/jobs/job9/reports").json("{'reports':["
                + "{'id':'old','startTime':'2026-10-20T07:00:00Z','endTime':'2026-10-21T07:00:00Z','createTime':'2026-10-22T00:00:00Z',"
                + "'downloadUrl':'" + api.url("/v1/media/old") + "'},"
                + "{'id':'new','startTime':'2026-10-20T07:00:00Z','endTime':'2026-10-21T07:00:00Z','createTime':'2026-10-25T00:00:00Z',"
                + "'downloadUrl':'" + api.url("/v1/media/new") + "'}]}");
        api.on("GET", "/v1/media/new").text("date,channel_id,video_id,video_thumbnail_impressions,video_thumbnail_impressions_ctr\n"
                + "20261020,UCowner,v1,200,0.1\n");
        api.on("GET", "/youtube/v3/videos").json("{'items':[]}");
        Map<String, Object> ready = tools().getYouTubeReach("2026-10-20", "2026-10-20");
        assertThat(ready).containsEntry("collecting", "ready").doesNotContainKey("missingDays");
        assertThat(list(ready, "days")).singleElement().satisfies(d -> assertThat(d).containsEntry("impressions", 200L));
        assertThat(api.requests("/v1/media/old")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) ready.get("totals");
        assertThat(totals).containsEntry("impressions", 200L).containsEntry("ctr", 0.1);
    }

    @Test
    @DisplayName("a reach CSV is read by its header's names: quotes, a BOM, columns in another order, ISO or compact dates")
    void csv() {
        List<YouTubeReach.Row> rows = YouTubeReach.parse("\uFEFFvideo_id,video_thumbnail_impressions_ctr,date,"
                + "video_thumbnail_impressions,channel_id\r\n"
                + "\"v,1\",0.031,20261001,\"1,200\",UCx\r\n"
                + "v2,,2026-10-02,7,UCx\r\n"
                + "\r\n"
                + "broken\r\n");
        assertThat(rows).containsExactly(new YouTubeReach.Row("2026-10-01", "v,1", 1200, 0.031),
                new YouTubeReach.Row("2026-10-02", "v2", 7, 0));
        assertThat(YouTubeReach.fields("a,\"b \"\"c\"\"\",d")).containsExactly("a", "b \"c\"", "d");
        assertThat(YouTubeReach.parse("no,header,here\n1,2,3")).isEmpty();
        assertThat(YouTubeReach.parse("")).isEmpty();
    }
}
