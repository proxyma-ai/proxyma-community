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
import ai.proxyma.core.settings.service.WriteToolRegistry;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The YouTube tools against a loopback YouTube Data API, over a stand-in connection: the comments still
 * waiting for the channel's answer, and a reply - posted exactly once, through the connection resolved
 * for WRITE, refused before anything is sent when it is empty, too long or names no comment, and said in
 * words when YouTube refuses it.
 */
class YouTubeToolsTest {

    private static final String CHANNEL = "UCowner";
    private LoopbackApi api;
    private ToolConnectorResolver resolver;
    private McpGuardService guard;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'}}]}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private YouTubeTools tools(boolean writeAllowed) {
        resolver = mock(ToolConnectorResolver.class);
        guard = mock(McpGuardService.class);
        UUID id = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(id, "local", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, writeAllowed, null, null, null);
        when(resolver.resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ))).thenReturn(Endpoint.of(connection));
        if (writeAllowed) {
            when(resolver.resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.WRITE))).thenReturn(Endpoint.of(connection));
        } else {
            when(resolver.resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.WRITE)))
                    .thenThrow(new IllegalStateException("YouTube write is turned off for the connection \"My channel\"."));
        }
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwned(any(), eq(id))).thenReturn(Optional.of(connection));
        return new YouTubeTools(resolver, connections, new SourceContextFactory(null, null, null, null),
                ConnectorHttp.direct(), guard);
    }

    private static String comment(String id, String author, String authorChannel, String text) {
        return "{'id':'" + id + "','snippet':{'authorDisplayName':'" + author + "','authorChannelId':{'value':'"
                + authorChannel + "'},'textOriginal':'" + text + "','publishedAt':'2026-10-20T00:00:00Z'}}";
    }

    private static String thread(String id, String videoId, String top, String... replies) {
        return "{'id':'" + id + "','snippet':{'videoId':'" + videoId + "','totalReplyCount':" + replies.length + ","
                + "'topLevelComment':" + top + "}" + (replies.length == 0 ? "" : ",'replies':{'comments':["
                + String.join(",", replies) + "]}") + "}";
    }

    @Test
    @DisplayName("the comments waiting for an answer: answered ones and the channel's own are left out, and more is said")
    void unansweredComments() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v1", comment("c1", "Lan", "UCfan", "Which flute is this?"),
                        comment("r1", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi."))
                + "," + thread("t2", "v1", comment("c2", "Cam Am Tieu Dao", CHANNEL, "New video tomorrow!"))
                + "," + thread("t3", "v2", comment("c3", "Minh", "UCfan2", "Sheet music please"))
                + "," + thread("t4", "v2", comment("c4", "Hoa", "UCfan3", "Which key for beginners?"))
                + "]}");

        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        assertThat(out).containsEntry("success", true).containsEntry("channel", "Cam Am Tieu Dao").containsEntry("more", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) out.get("comments");
        assertThat(comments).extracting(c -> c.get("commentId")).containsExactly("c3", "c4");
        assertThat(comments.get(0)).containsEntry("text", "Sheet music please").containsEntry("answeredByChannel", false)
                .containsEntry("link", "https://www.youtube.com/watch?v=v2&lc=c3").doesNotContainKey("byChannel");
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("allThreadsRelatedToChannelId")).isEqualTo(CHANNEL);
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ));

        Map<String, Object> one = tools(true).listYouTubeComments(null, true, 1, null, null);
        assertThat(one).containsEntry("more", true);
        Map<String, Object> all = tools(true).listYouTubeComments(null, false, 50, null, null);
        assertThat((List<?>) all.get("comments")).hasSize(4);
        // The owner's own words come with an answered thread, so an agent can learn how they write to viewers.
        @SuppressWarnings("unchecked")
        Map<String, Object> answered = ((List<Map<String, Object>>) all.get("comments")).get(0);
        assertThat(answered).containsEntry("commentId", "c1").containsEntry("answeredByChannel", true)
                .containsEntry("channelReplies", List.of("A D5 dizi."));
        assertThat(comments.get(0)).doesNotContainKey("channelReplies");
    }

    private static String commentAt(String id, String author, String authorChannel, String text, String at) {
        return "{'id':'" + id + "','snippet':{'authorDisplayName':'" + author + "','authorChannelId':{'value':'"
                + authorChannel + "'},'textOriginal':'" + text + "','publishedAt':'" + at + "'}}";
    }

    @Test
    @DisplayName("publishedAfter lists only newer comments and stops paging at the first older one; a drafted reply is marked, and left out of what waits for an answer")
    void newerAndDrafted() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'p2','items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Which flute?", "2026-10-20T10:00:00Z"))
                + "," + thread("t3", "v1", commentAt("c3", "Minh", "UCfan2", "Sheet music please", "2026-10-19T10:00:00Z"))
                + "," + thread("t2", "v1", commentAt("c2", "Hoa", "UCfan3", "Old question", "2026-10-10T10:00:00Z"))
                + "]}");
        YouTubeTools tools = tools(true);
        ai.proxyma.core.chat.agents.ApprovalInbox inbox = mock(ai.proxyma.core.chat.agents.ApprovalInbox.class);
        when(inbox.waitingSubjects(any(), any())).thenReturn(java.util.Set.of("youtube-comment:c3"));
        tools.setApprovalsForTest(inbox);

        Map<String, Object> waiting = tools.listYouTubeComments(null, null, null, "2026-10-15T00:00:00Z", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> open = (List<Map<String, Object>>) waiting.get("comments");
        assertThat(open).extracting(c -> c.get("commentId")).containsExactly("c1");
        assertThat(waiting).containsEntry("more", false);
        assertThat(api.requests("/youtube/v3/commentThreads")).as("no second page after an older thread").hasSize(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) tools.listYouTubeComments(null, false, null,
                "2026-10-15T00:00:00Z", null).get("comments");
        assertThat(all).extracting(c -> c.get("commentId")).containsExactly("c1", "c3");
        assertThat(all.get(1)).containsEntry("draftWaiting", true);
        assertThat(all.get(0)).doesNotContainKey("draftWaiting");

        assertThat(tools.listYouTubeComments(null, null, null, "last week", null)).containsEntry("success", false);
        assertThat(tools.listYouTubeComments(null, null, null, null, "long ago")).containsEntry("success", false);
        // Everything newer than publishedAfter was looked at: the walk below starts there.
        assertThat(waiting).containsEntry("oldestScanned", "2026-10-15T00:00:00Z");
    }

    // -- old comments too, newest first (2026-11-03, the maintainer: "start with the latest-not-replied") --------

    @SuppressWarnings("unchecked")
    private static List<Object> ids(Map<String, Object> listing) {
        return ((List<Map<String, Object>>) listing.get("comments")).stream().map(c -> c.get("commentId")).toList();
    }

    // A page of 100 threads, newest first, one an hour back from start; answered when the index is not in open.
    private String page(String token, String next, java.time.Instant start, int first, java.util.Set<Integer> open) {
        StringBuilder items = new StringBuilder();
        for (int i = first; i < first + 100; i++) {
            String at = start.minusSeconds(3600L * i).toString();
            String top = commentAt("c" + i, "Viewer " + i, "UCv" + i, "Question " + i, at);
            String t = open.contains(i) ? thread("t" + i, "v1", top)
                    : thread("t" + i, "v1", top, commentAt("r" + i, "Cam Am Tieu Dao", CHANNEL, "Thanks", at));
            items.append(items.isEmpty() ? "" : ",").append(t);
        }
        LoopbackApi.Route route = api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL);
        if (token != null) route.query("pageToken", token);
        route.json("{" + (next == null ? "" : "'nextPageToken':'" + next + "',") + "'items':[" + items + "]}");
        return start.minusSeconds(3600L * (first + 99)).toString();
    }

    @Test
    @DisplayName("unanswered comments are looked for page after page, newest first, and the listing stops at the page that fills its limit")
    void deeperUntilTheLimit() {
        java.time.Instant start = java.time.Instant.parse("2026-10-30T00:00:00Z");
        // Five pages; the unanswered ones are deep: 150 and 320, then 420 and 430.
        page(null, "p2", start, 0, java.util.Set.of());
        page("p2", "p3", start, 100, java.util.Set.of(150));
        page("p3", "p4", start, 200, java.util.Set.of());
        page("p4", "p5", start, 300, java.util.Set.of(320));
        page("p5", null, start, 400, java.util.Set.of(420, 430));

        Map<String, Object> two = tools(true).listYouTubeComments(null, null, 2, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> found = (List<Map<String, Object>>) two.get("comments");
        assertThat(found).extracting(c -> c.get("commentId")).containsExactly("c150", "c320");
        assertThat(api.requests("/youtube/v3/commentThreads")).as("no page past the one that filled the limit").hasSize(4);
        // It looked down to the second match and no further, and more may wait below.
        assertThat(two).containsEntry("more", true).containsEntry("oldestScanned", start.minusSeconds(3600L * 320).toString());

        // Every comment answered on every page: down to the last, and nothing more.
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'T'}}]}");
        page(null, "p2", start, 0, java.util.Set.of());
        String last = page("p2", null, start, 100, java.util.Set.of());
        Map<String, Object> none = tools(true).listYouTubeComments(null, null, 50, null, null);
        assertThat(none).containsEntry("more", false).containsEntry("oldestScanned", last).extractingByKey("comments").asList().isEmpty();
    }

    @Test
    @DisplayName("the deep look is bounded: twenty pages, 2,000 threads, then more and how far it got")
    void deeperBounded() {
        java.time.Instant start = java.time.Instant.parse("2026-10-30T00:00:00Z");
        // Every page answered and another always waiting.
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'more','items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Hi", start.toString()),
                        commentAt("r1", "Cam Am Tieu Dao", CHANNEL, "Hello", start.toString())) + "]}");
        Map<String, Object> out = tools(true).listYouTubeComments(null, null, 50, null, null);
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(YouTubeTools.DEEP_PAGES);
        assertThat(out).containsEntry("more", true).containsEntry("oldestScanned", start.toString());
        // A listing of every comment still reads two pages.
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'T'}}]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'more','items':[]}");
        tools(true).listYouTubeComments(null, false, 50, null, null);
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(YouTubeTools.SCAN_PAGES);
    }

    @Test
    @DisplayName("publishedBefore: newer threads are passed over, the next batch below it comes newest first, answered and drafted ones still left out")
    void belowAPoint() {
        java.time.Instant start = java.time.Instant.parse("2026-10-30T00:00:00Z");
        page(null, "p2", start, 0, java.util.Set.of(5, 50));
        page("p2", "p3", start, 100, java.util.Set.of(110, 140, 160, 170));
        page("p3", null, start, 200, java.util.Set.of(250));
        YouTubeTools tools = tools(true);
        ai.proxyma.core.chat.agents.ApprovalInbox inbox = mock(ai.proxyma.core.chat.agents.ApprovalInbox.class);
        when(inbox.waitingSubjects(any(), any())).thenReturn(java.util.Set.of("youtube-comment:c140"));
        tools.setApprovalsForTest(inbox);

        // Below c120's time: c110 and everything newer are not looked at; c140 waits for approval.
        String point = start.minusSeconds(3600L * 120).toString();
        Map<String, Object> out = tools.listYouTubeComments(null, null, 2, null, point);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> found = (List<Map<String, Object>>) out.get("comments");
        assertThat(found).extracting(c -> c.get("commentId")).containsExactly("c160", "c170");
        assertThat(out).containsEntry("more", true).containsEntry("oldestScanned", start.minusSeconds(3600L * 170).toString());

        // Continued from there: the last one, and the backlog is done.
        Map<String, Object> next = tools.listYouTubeComments(null, null, 2, null, (String) out.get("oldestScanned"));
        assertThat(next).containsEntry("more", false);
        assertThat(ids(next)).containsExactly("c250");

        // A page wholly newer than the point is passed over, not counted against the twenty.
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'T'}}]}");
        page(null, "p2", start, 0, java.util.Set.of(1, 2, 3));
        page("p2", null, start, 100, java.util.Set.of(180));
        Map<String, Object> below = tools.listYouTubeComments(null, null, 50, null, start.minusSeconds(3600L * 99).toString());
        assertThat(ids(below)).containsExactly("c180");
        assertThat(below).containsEntry("more", false);
    }

    @Test
    @DisplayName("a point deeper than a listing reaches is said, with nothing more to walk")
    void beyondReach() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'more','items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Hi", "2026-10-30T00:00:00Z")) + "]}");
        Map<String, Object> out = tools(true).listYouTubeComments(null, null, 50, null, "2020-01-01T00:00:00Z");
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(YouTubeThreadScan.SKIP_PAGES);
        assertThat(out).containsEntry("success", true).containsEntry("more", false).doesNotContainKey("oldestScanned");
        assertThat((String) out.get("note")).contains("deeper than a listing reaches");
    }

    @Test
    @DisplayName("a reply is posted once, as the parent's reply, through the connection resolved for WRITE")
    void replyPostedOnce() {
        api.on("POST", "/youtube/v3/comments").json("{'id':'c3.reply1','snippet':{'videoId':'v2','parentId':'c3'}}");
        YouTubeTools tools = tools(true);

        Map<String, Object> out = tools.replyToYouTubeComment("thread:c3", "  The sheet is on camamtieudao.com.  ");
        assertThat(out).containsEntry("success", true).containsEntry("commentId", "c3").containsEntry("replyId", "c3.reply1")
                .containsEntry("link", "https://www.youtube.com/watch?v=v2&lc=c3.reply1");
        List<LoopbackApi.Recorded> posts = api.requests("/youtube/v3/comments");
        assertThat(posts).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.param("part")).isEqualTo("snippet");
            assertThat(r.body()).contains("\"parentId\":\"c3\"").contains("\"textOriginal\":\"The sheet is on camamtieudao.com.\"");
            assertThat(r.header("Authorization")).isEqualTo("Bearer ya29.test");
        });
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.WRITE));
        verify(guard).checkYouTubeWrite(any());
        assertThat(WriteToolRegistry.NAMES).contains("replyToYouTubeComment");
        assertThat(WriteToolRegistry.isWriteCall("replyToYouTubeComment", "{}")).isTrue();
        assertThat(WriteToolRegistry.connectorFor("replyToYouTubeComment").type()).isEqualTo(ConnectorType.YOUTUBE_CHANNEL);
    }

    @Test
    @DisplayName("the conversation: a viewer answering the owner back and a viewer replying under the owner's own comment wait for the channel, each with what came before; an answered reply does not")
    void viewerRepliesWait() {
        // 2026-11-05, the maintainer: "the viewer comment, I reply, then he/she reply, then I need to reply another one".
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'t1','snippet':{'videoId':'v1','totalReplyCount':2,'topLevelComment':"
                + commentAt("t1", "Lan", "UCfan", "Which flute is this?", "2026-10-20T00:00:00Z") + "},'replies':{'comments':["
                + commentAt("t1.b", "Lan", "UCfan", "@Cam Am Tieu Dao and the key?", "2026-10-22T00:00:00Z") + ","
                + commentAt("t1.a", "Cam Am Tieu Dao", CHANNEL, "A dizi.", "2026-10-21T00:00:00Z") + "]}},"
                + "{'id':'t2','snippet':{'videoId':'v1','totalReplyCount':1,'topLevelComment':"
                + commentAt("t2", "Cam Am Tieu Dao", CHANNEL, "New video tomorrow!", "2026-10-19T00:00:00Z") + "},'replies':{'comments':["
                + commentAt("t2.a", "Minh", "UCfan2", "Can not wait", "2026-10-19T05:00:00Z") + "]}},"
                + "{'id':'t3','snippet':{'videoId':'v2','totalReplyCount':2,'topLevelComment':"
                + commentAt("t3", "Hoa", "UCfan3", "Lovely", "2026-10-18T00:00:00Z") + "},'replies':{'comments':["
                + commentAt("t3.a", "Hoa", "UCfan3", "So calm", "2026-10-18T01:00:00Z") + ","
                + commentAt("t3.b", "Cam Am Tieu Dao", CHANNEL, "Thank you!", "2026-10-18T02:00:00Z") + "]}}"
                + "]}");

        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) out.get("comments");
        assertThat(comments).extracting(c -> c.get("commentId")).containsExactly("t1.b", "t2.a");
        assertThat(comments.get(0)).containsEntry("parentId", "t1").containsEntry("author", "Lan")
                .containsEntry("inReplyTo", "A dizi.").containsEntry("link", "https://www.youtube.com/watch?v=v1&lc=t1.b");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = (List<Map<String, Object>>) comments.get(0).get("conversation");
        assertThat(before).extracting(m -> m.get("text")).containsExactly("Which flute is this?", "A dizi.");
        assertThat(before.get(1)).containsEntry("byChannel", true);
        assertThat(comments.get(1)).containsEntry("parentId", "t2").containsEntry("inReplyTo", "New video tomorrow!");
    }

    @Test
    @DisplayName("a thread with more replies than YouTube carries inline is read whole (comments.list parentId=) before it is judged")
    void longThreadReadWhole() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'t1','snippet':{'videoId':'v1','totalReplyCount':7,'topLevelComment':"
                + commentAt("t1", "Lan", "UCfan", "Which flute?", "2026-10-20T00:00:00Z") + "},'replies':{'comments':["
                + commentAt("t1.a", "Minh", "UCfan2", "me too", "2026-10-20T01:00:00Z") + "]}}]}");
        api.on("GET", "/youtube/v3/comments").query("parentId", "t1").json("{'items':["
                + commentAt("t1.a", "Minh", "UCfan2", "me too", "2026-10-20T01:00:00Z") + ","
                + commentAt("t1.g", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi.", "2026-10-21T00:00:00Z") + "]}");

        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        assertThat((List<?>) out.get("comments")).as("the channel answered further down the thread").isEmpty();
        assertThat(api.requests("/youtube/v3/comments")).singleElement()
                .satisfies(r -> assertThat(r.param("maxResults")).isEqualTo("100"));
    }

    @Test
    @DisplayName("a reply to a viewer's reply is posted under the thread's top-level comment - YouTube's one level - and names the reply it answers")
    void replyToAReplyPostedUnderTheThread() {
        api.on("POST", "/youtube/v3/comments").json("{'id':'c3.reply2','snippet':{'videoId':'v2','parentId':'c3'}}");

        Map<String, Object> out = tools(true).replyToYouTubeComment("c3.viewer1", "@Lan the D key.");
        assertThat(out).containsEntry("success", true).containsEntry("commentId", "c3.viewer1")
                .containsEntry("replyId", "c3.reply2");
        assertThat(api.requests("/youtube/v3/comments")).singleElement()
                .satisfies(r -> assertThat(r.body()).contains("\"parentId\":\"c3\"").doesNotContain("c3.viewer1"));
    }

    @Test
    @DisplayName("a reply to a comment under another channel's video is not posted as this channel, and says why")
    void anotherChannelsCommentNotPosted() {
        // 2026-11-05: a draft made before the team moved to another channel, approved after.
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{"
                + "'channelId':'UClesyhau','videoId':'v2'}}]}");
        api.on("POST", "/youtube/v3/comments").json("{'id':'c3.reply1','snippet':{'videoId':'v2','parentId':'c3'}}");

        Map<String, Object> out = tools(true).replyToYouTubeComment("c3", "Thanks!");

        assertThat(out).containsEntry("success", false);
        assertThat((String) out.get("error")).startsWith("Not posted: That comment is under a video of another YouTube channel, "
                + "not \"Cam Am Tieu Dao\"");
        assertThat(api.requests("/youtube/v3/comments")).as("nothing posted").isEmpty();

        // The channel's own comment is posted.
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'}}]}");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c4").json("{'items':[{'id':'c4','snippet':{"
                + "'channelId':'" + CHANNEL + "','videoId':'v2'}}]}");
        api.on("POST", "/youtube/v3/comments").json("{'id':'c4.reply1','snippet':{'videoId':'v2','parentId':'c4'}}");
        assertThat(tools(true).replyToYouTubeComment("c4", "Thanks!")).containsEntry("success", true);
        assertThat(api.requests("/youtube/v3/comments")).singleElement();
    }

    @Test
    @DisplayName("a server error after posting is not posted again")
    void notRetried() {
        api.on("POST", "/youtube/v3/comments").status(503).json("{'error':{'code':503}}").then()
                .json("{'id':'second','snippet':{'videoId':'v2'}}");
        Map<String, Object> out = tools(true).replyToYouTubeComment("c3", "Thanks!");
        assertThat(out).containsEntry("success", false);
        assertThat(api.requests("/youtube/v3/comments")).hasSize(1);
    }

    @Test
    @DisplayName("an empty, too long or nameless reply, or a connection whose Write is off, sends nothing")
    void refusedBeforeSending() {
        YouTubeTools tools = tools(true);
        assertThat(tools.replyToYouTubeComment("c3", "   ")).containsEntry("success", false);
        assertThat(tools.replyToYouTubeComment("c3", "x".repeat(YouTubeTools.MAX_REPLY_CHARS + 1)))
                .extractingByKey("error").asString().contains("at most 10000 characters");
        assertThat(tools.replyToYouTubeComment(" ", "Hi")).containsEntry("success", false);
        assertThat(tools.replyToYouTubeComment("c3/../x", "Hi")).containsEntry("success", false);
        verify(resolver, never()).resolve(any(), any(), any(), eq(Access.WRITE));

        YouTubeTools readOnly = tools(false);
        assertThat(readOnly.replyToYouTubeComment("c3", "Hi")).extractingByKey("error").asString()
                .contains("write is turned off");
        assertThat(api.requests("/youtube/v3/comments")).isEmpty();
    }

    @Test
    @DisplayName("a reply YouTube refuses for want of permission says to sign in again with Write allowed")
    void refusedForScope() {
        api.on("POST", "/youtube/v3/comments").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'insufficientPermissions'}]}}");
        assertThat(tools(true).replyToYouTubeComment("c3", "Hi")).extractingByKey("error").asString()
                .startsWith("YouTube refused the reply: the connection was signed in without permission to reply.");

        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "'}]}");
        api.on("POST", "/youtube/v3/comments").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'commentsDisabled'}]}}");
        assertThat(tools(true).replyToYouTubeComment("c3", "Hi")).containsEntry("error",
                "YouTube refused the reply: comments are turned off on that video.");
    }

    @Test
    @DisplayName("a video reads with its title, description and visibility; a malformed id asks nothing")
    void video() {
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v2','snippet':{'title':'Biet Tri Ky','description':'A cover.',"
                + "'publishedAt':'2025-05-01T00:00:00Z'},'status':{'privacyStatus':'public'}}]}");
        assertThat(tools(true).getYouTubeVideo("v2")).containsEntry("title", "Biet Tri Ky").containsEntry("description", "A cover.")
                .containsEntry("visibility", "public").containsEntry("link", "https://www.youtube.com/watch?v=v2");
        YouTubeTools tools = tools(true);
        assertThat(tools.getYouTubeVideo("v2; drop")).containsEntry("success", false);
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("a viewer's history: only their own threads, with what the channel said to them, and how many it answered")
    void viewerHistory() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v3", comment("c1", "Tuan", "UCtuan", "Thoi sao nay key gi anh?"))
                + "," + thread("t2", "v1", comment("c2", "Lan", "UCfan", "Which flute is this?"))
                + "," + thread("t3", "v2", comment("c3", "Tuan", "UCtuan", "Hay qua anh oi"),
                        comment("r3", "Cam Am Tieu Dao", CHANNEL, "Cam on em nhe!"))
                + "]}");

        Map<String, Object> out = tools(true).getYouTubeViewerHistory("UCtuan", null);
        assertThat(out).containsEntry("success", true).containsEntry("viewer", "Tuan").containsEntry("repliedTo", 1L)
                .containsEntry("scanned", 3).containsEntry("more", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) out.get("comments");
        assertThat(comments).extracting(c -> c.get("commentId")).containsExactly("c1", "c3");
        assertThat(comments.get(1)).containsEntry("channelReplies", List.of("Cam on em nhe!")).doesNotContainKey("author");
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("allThreadsRelatedToChannelId")).isEqualTo(CHANNEL);

        assertThat(tools(true).getYouTubeViewerHistory("UCtuan", 1)).containsEntry("more", true);
        YouTubeTools refused = tools(true);
        assertThat(refused.getYouTubeViewerHistory("not an id!", null)).containsEntry("success", false);
        verifyNoInteractions(resolver);
    }

    // -- the owner's voice: the channel's own replies ----------------------------------------------

    @Test
    @DisplayName("the channel's own replies only, each beside the comment it answered - a reply to a reply beside that reply - newest first")
    void channelReplies() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Which flute is this?", "2026-10-20T10:00:00Z"),
                        commentAt("r1", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi, ban nhe!", "2026-10-21T08:00:00Z"),
                        commentAt("r2", "Minh", "UCminh", "Thanks from me too", "2026-10-21T09:00:00Z"))
                + "," + thread("t2", "v1", commentAt("c2", "Hoa", "UChoa", "\u2665\u2665", "2026-10-19T10:00:00Z"),
                        commentAt("r3", "@Tuan", "UCtuan", "Me too, which key?", "2026-10-19T11:00:00Z"),
                        commentAt("r4", "Cam Am Tieu Dao", CHANNEL, "@Tuan Key of C, em nhe", "2026-10-22T08:00:00Z"),
                        commentAt("r5", "Cam Am Tieu Dao", CHANNEL, "\u2665", "2026-10-19T12:00:00Z"))
                + "," + thread("t3", "v2", commentAt("c3", "Cam Am Tieu Dao", CHANNEL, "New video tomorrow!", "2026-10-18T10:00:00Z"),
                        commentAt("r6", "Cam Am Tieu Dao", CHANNEL, "@Lan see you there", "2026-10-18T11:00:00Z"))
                + "," + thread("t4", "v2", commentAt("c4", "Ky", "UCky", "Sheet music please", "2026-10-17T10:00:00Z"))
                + "]}");

        Map<String, Object> out = tools(true).listYouTubeChannelReplies(null, null);
        assertThat(out).containsEntry("success", true).containsEntry("channel", "Cam Am Tieu Dao")
                .containsEntry("scanned", 4).containsEntry("more", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replies = (List<Map<String, Object>>) out.get("replies");
        // The owner's words only - a viewer's reply is never one, nor is what the owner says under their own comment.
        assertThat(replies).extracting(r -> r.get("reply")).containsExactly("@Tuan Key of C, em nhe", "A D5 dizi, ban nhe!", "\u2665");
        assertThat(replies.get(0)).containsEntry("commentId", "r3").containsEntry("author", "@Tuan")
                .containsEntry("authorChannelId", "UCtuan").containsEntry("comment", "Me too, which key?")
                .containsEntry("repliedAt", "2026-10-22T08:00:00Z").containsEntry("link", "https://www.youtube.com/watch?v=v1&lc=r4");
        assertThat(replies.get(1)).containsEntry("commentId", "c1").containsEntry("author", "Lan")
                .containsEntry("comment", "Which flute is this?").containsEntry("commentedAt", "2026-10-20T10:00:00Z")
                .containsEntry("emojiOnly", false).containsEntry("videoId", "v1");
        // The owner's reaction to an emoji-only comment is a lesson too.
        assertThat(replies.get(2)).containsEntry("commentId", "c2").containsEntry("emojiOnly", true);
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("allThreadsRelatedToChannelId")).isEqualTo(CHANNEL);
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("part")).isEqualTo("snippet,replies");
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ));

        assertThat(tools(true).listYouTubeChannelReplies(2, null)).containsEntry("more", true)
                .extractingByKey("replies").asList().hasSize(2);
        // Since last time: only the replies written after it, whenever the comment was.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> since = (List<Map<String, Object>>) tools(true)
                .listYouTubeChannelReplies(null, "2026-10-21T12:00:00Z").get("replies");
        assertThat(since).extracting(r -> r.get("commentId")).containsExactly("r3");
        assertThat(tools(true).listYouTubeChannelReplies(null, "last week")).containsEntry("success", false);
    }

    @Test
    @DisplayName("the replies look through at most five pages of threads, stop at a page older than publishedAfter, and a long text is cut")
    void channelRepliesBounded() {
        String longReply = "x".repeat(YouTubeTools.MAX_PAIR_TEXT + 100);
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'more','items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Hay qua", "2026-10-20T10:00:00Z"),
                        commentAt("r1", "Cam Am Tieu Dao", CHANNEL, longReply, "2026-10-20T11:00:00Z"))
                + "]}");
        Map<String, Object> out = tools(true).listYouTubeChannelReplies(YouTubeTools.MAX_REPLIES + 50, null);
        assertThat(api.requests("/youtube/v3/commentThreads")).as("five quota units at most").hasSize(YouTubeTools.REPLY_PAGES);
        assertThat(out).containsEntry("more", true).containsEntry("scanned", YouTubeTools.REPLY_PAGES);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) out.get("replies")).get(0);
        assertThat((String) first.get("reply")).hasSize(YouTubeTools.MAX_PAIR_TEXT + YouTubeTools.CUT.length()).endsWith(YouTubeTools.CUT);

        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'T'}}]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'more','items':["
                + thread("t1", "v1", commentAt("c1", "Lan", "UCfan", "Old", "2026-09-01T10:00:00Z"),
                        commentAt("r1", "Cam Am Tieu Dao", CHANNEL, "Thanks", "2026-09-02T11:00:00Z"))
                + "]}");
        Map<String, Object> since = tools(true).listYouTubeChannelReplies(null, "2026-10-01T00:00:00Z");
        assertThat(api.requests("/youtube/v3/commentThreads")).as("no page past one older than publishedAfter").hasSize(1);
        assertThat(since).containsEntry("more", false).extractingByKey("replies").asList().isEmpty();
    }

    @Test
    @DisplayName("a spent quota is said as a limit, not a refusal")
    void channelRepliesQuota() {
        api.on("GET", "/youtube/v3/commentThreads").status(403).json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        assertThat(tools(true).listYouTubeChannelReplies(null, null)).containsEntry("success", false)
                .extractingByKey("error").asString().contains("daily quota").contains("This is a limit, not a refusal");
    }

    // -- refusals and the per-video fallback (2026-11-03, the maintainer's second channel) -------------

    private static String refusal(String reason, String message) {
        return "{'error':{'code':403,'message':'" + message + "','errors':[{'reason':'" + reason + "'}]}}";
    }

    // A channel with an uploads playlist, and nothing listed in it yet.
    private void uploadingChannel() {
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
    }

    private void channelWithUploads(String... videoIds) {
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        StringBuilder items = new StringBuilder();
        for (String id : videoIds) items.append(items.isEmpty() ? "" : ",").append("{'contentDetails':{'videoId':'").append(id).append("'}}");
        api.on("GET", "/youtube/v3/playlistItems").json("{'items':[" + items + "]}");
    }

    @Test
    @DisplayName("each refusal a working channel can give is a sentence the model can act on, with YouTube's reason kept")
    void refusalsSayWhy() {
        Map<String, String> expected = Map.of(
                "insufficientPermissions", "The YouTube connection's sign-in does not include every permission it needs. Tell "
                        + "the user to sign in again on the YouTube connection in Settings > Connections and tick every "
                        + "permission Google lists. YouTube said: insufficientPermissions (Not allowed.)",
                "accessNotConfigured", "The YouTube Data API v3 is not enabled in the connection's Google Cloud project. Tell "
                        + "the user to enable it in Google Cloud > APIs & Services > Library, then try again. YouTube said: "
                        + "accessNotConfigured (Not allowed.)",
                "commentsDisabled", "Comments are turned off for this channel or its videos (videos made for kids have none), "
                        + "so there are no comments to read. YouTube said: commentsDisabled (Not allowed.)",
                "forbidden", "YouTube refused access with the connection's credential. Check that its account may read this. "
                        + "YouTube said: forbidden (Not allowed.)");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            api.reset();
            api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "'}]}");
            api.on("GET", "/youtube/v3/commentThreads").status(403).json(refusal(e.getKey(), "Not allowed."));
            // One video's comments: a refusal is that video's, never read another way.
            assertThat(tools(true).listYouTubeComments("v1", null, null, null, null)).as(e.getKey())
                    .containsEntry("success", false).containsEntry("error", e.getValue());
        }
        // The channel's replies take the same words.
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "'}]}");
        api.on("GET", "/youtube/v3/commentThreads").status(403).json(refusal("insufficientPermissions", "Not allowed."));
        assertThat(tools(true).listYouTubeChannelReplies(null, null)).extractingByKey("error").asString()
                .contains("tick every permission Google lists").endsWith("YouTube said: insufficientPermissions (Not allowed.)");
    }

    @Test
    @DisplayName("a channel whose comments cannot be listed at once is read video by video, skipping a video with comments off, and says so")
    void perVideoFallback() {
        channelWithUploads("v1", "v2", "v3");
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("commentsDisabled", "Comments are disabled."));
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v1").status(403)
                .json(refusal("commentsDisabled", "The video identified by the videoId parameter has disabled comments."));
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v2").json("{'items':["
                + thread("t2", "v2", commentAt("c2", "Lan", "UCfan", "Which flute?", "2026-10-20T10:00:00Z"),
                        commentAt("r2", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi!", "2026-10-21T10:00:00Z"))
                + "," + thread("t4", "v2", commentAt("c4", "Lan", "UCfan", "And the key?", "2026-10-19T10:00:00Z")) + "]}");
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", "v3").json("{'items':["
                + thread("t3", "v3", commentAt("c3", "Minh", "UCfan2", "Sheet please", "2026-10-22T10:00:00Z")) + "]}");

        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        assertThat(out).containsEntry("success", true).containsEntry("more", false).containsEntry("videosRead", 3)
                .containsEntry("videosWithCommentsOff", 1);
        assertThat((String) out.get("note")).contains("read one by one: 3 read, 1 with comments turned off");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) out.get("comments");
        assertThat(comments).extracting(c -> c.get("commentId")).containsExactly("c4", "c3");
        // One refused call, the newest 20 uploads once, then one call a video.
        assertThat(api.requests("/youtube/v3/playlistItems")).singleElement()
                .satisfies(r -> assertThat(r.param("maxResults")).isEqualTo(String.valueOf(YouTubeThreadScan.FALLBACK_VIDEOS)));
        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("videoId"))
                .containsExactly(null, "v1", "v2", "v3");

        // It stops at the limit: the third video is not asked for once the second filled it.
        Map<String, Object> one = tools(true).listYouTubeComments(null, false, 1, null, null);
        assertThat(one).containsEntry("more", true).extractingByKey("comments").asList().hasSize(1);

        // The same fallback serves the channel's replies and a viewer's history.
        Map<String, Object> replies = tools(true).listYouTubeChannelReplies(null, null);
        assertThat(replies).containsEntry("success", true).containsEntry("videosWithCommentsOff", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) replies.get("replies");
        assertThat(pairs).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("reply", "A D5 dizi!").containsEntry("commentId", "c2"));
        Map<String, Object> history = tools(true).getYouTubeViewerHistory("UCfan", null);
        assertThat(history).containsEntry("success", true).containsEntry("scanned", 3).containsEntry("videosRead", 3);
        assertThat(history).extractingByKey("comments").asList().hasSize(2);
    }

    @Test
    @DisplayName("every video with comments off: the channel has no comments to read, said plainly")
    void everyVideoCommentsOff() {
        channelWithUploads("v1", "v2");
        api.on("GET", "/youtube/v3/commentThreads").status(403)
                .json(refusal("commentsDisabled", "The video identified by the videoId parameter has disabled comments."));
        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        assertThat(out).containsEntry("success", true).containsEntry("more", false).containsEntry("videosRead", 2)
                .containsEntry("videosWithCommentsOff", 2).containsEntry("note", "Comments are turned off on all of the "
                        + "channel's newest 2 videos (videos made for kids have none), so the channel has no comments to read.");
        assertThat(out).extractingByKey("comments").asList().isEmpty();
    }

    @Test
    @DisplayName("read video by video, at most the newest 20 uploads are asked for, one quota unit each")
    void perVideoBounded() {
        String[] ids = new String[30];
        for (int i = 0; i < ids.length; i++) ids[i] = "v" + i;
        channelWithUploads(ids);
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("forbidden", "Forbidden."));
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        Map<String, Object> out = tools(true).listYouTubeComments(null, null, null, null, null);
        assertThat(out).containsEntry("success", true).containsEntry("videosRead", YouTubeThreadScan.FALLBACK_VIDEOS)
                .containsEntry("more", true);
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(1 + YouTubeThreadScan.FALLBACK_VIDEOS);
    }

    // One page of uploads, v<from>..v<to - 1>, video i published i days before start.
    private void uploadsPage(String token, String next, java.time.Instant start, int from, int to) {
        StringBuilder items = new StringBuilder();
        for (int i = from; i < to; i++) {
            items.append(items.isEmpty() ? "" : ",").append("{'contentDetails':{'videoId':'v").append(i)
                    .append("','videoPublishedAt':'").append(start.minusSeconds(86_400L * i)).append("'}}");
        }
        LoopbackApi.Route route = api.on("GET", "/youtube/v3/playlistItems");
        if (token != null) route.query("pageToken", token);
        route.json("{" + (next == null ? "" : "'nextPageToken':'" + next + "',") + "'items':[" + items + "]}");
    }

    private void openComments(String video, int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            items.append(items.isEmpty() ? "" : ",").append(thread("t" + video + "-" + i, video,
                    commentAt("c" + video + "-" + i, "Lan", "UCfan", "Question " + i, "2026-10-20T10:00:00Z")));
        }
        api.on("GET", "/youtube/v3/commentThreads").query("videoId", video).json("{'items':[" + items + "]}");
    }

    @Test
    @DisplayName("read video by video below a point: only older uploads, paged past the newest twenty, each video whole, and the point moves video by video")
    void perVideoBelowAPoint() {
        java.time.Instant start = java.time.Instant.parse("2026-10-30T00:00:00Z");
        uploadingChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("commentsDisabled", "Comments are disabled."));
        uploadsPage(null, "u2", start, 0, 50);
        uploadsPage("u2", null, start, 50, 60);
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[" + thread("tx", "vx",
                commentAt("cx", "Minh", "UCfan2", "Hello", "2026-10-20T10:00:00Z")) + "]}");

        // Below v10's day: v0-v10 are not asked for, v11-v59 each give their one comment - 49 videos, past twenty.
        Map<String, Object> out = tools(true).listYouTubeComments(null, null, 50, null, start.minusSeconds(86_400L * 10).toString());
        assertThat(out).containsEntry("success", true).containsEntry("more", false).containsEntry("videosRead", 49)
                .containsEntry("oldestScanned", start.minusSeconds(86_400L * 59).toString());
        assertThat(out).extractingByKey("comments").asList().hasSize(49);
        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("videoId"))
                .doesNotContain("v0", "v10").contains("v11", "v59");
        assertThat(api.requests("/youtube/v3/playlistItems")).extracting(r -> r.param("pageToken")).containsExactly(null, "u2");

        // A video that would overflow the limit waits whole for the next listing; the point stops before it.
        openComments("v11", 1);
        openComments("v12", 2);
        Map<String, Object> first = tools(true).listYouTubeComments(null, null, 2, null, start.minusSeconds(86_400L * 10).toString());
        assertThat(first).containsEntry("more", true).containsEntry("oldestScanned", start.minusSeconds(86_400L * 11).toString());
        assertThat(ids(first)).containsExactly("cv11-0");
        Map<String, Object> second = tools(true).listYouTubeComments(null, null, 2, null, (String) first.get("oldestScanned"));
        assertThat(second).containsEntry("oldestScanned", start.minusSeconds(86_400L * 12).toString());
        assertThat(ids(second)).containsExactly("cv12-0", "cv12-1");

        // Bounded: at most fifty calls for videos, what is left said as more.
        api.reset();
        uploadingChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("allThreadsRelatedToChannelId", CHANNEL).status(403)
                .json(refusal("forbidden", "Forbidden."));
        uploadsPage(null, "u2", start, 0, 50);
        uploadsPage("u2", "u3", start, 50, 100);
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        Map<String, Object> bounded = tools(true).listYouTubeComments(null, null, 50, null, start.toString());
        assertThat(bounded).containsEntry("more", true).containsEntry("videosRead", YouTubeThreadScan.DEEP_VIDEO_CALLS);
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(1 + YouTubeThreadScan.DEEP_VIDEO_CALLS);
    }

    @Test
    @DisplayName("a comment with no words is marked emojiOnly, with its author's channel id")
    void emojiOnly() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", "v1", comment("c1", "Lac", "UClac", "\u2665\u2665\u2665"))
                + "," + thread("t2", "v1", comment("c2", "Hoa", "UChoa", "Hay qu\u00e1 \u2665"))
                + "]}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) tools(true).listYouTubeComments(null, null, null, null, null).get("comments");
        assertThat(comments.get(0)).containsEntry("emojiOnly", true).containsEntry("authorChannelId", "UClac");
        assertThat(comments.get(1)).containsEntry("emojiOnly", false);
        assertThat(YouTubeTools.emojiOnly("  ")).isFalse();
        assertThat(YouTubeTools.emojiOnly("!!! \ud83d\ude0d")).isTrue();
        assertThat(YouTubeTools.emojiOnly("10\u0111")).isFalse();
    }

    @Test
    @DisplayName("listWaitingYouTubeReplies lists the person's replies waiting for approval from the inbox - no YouTube call - each with its comment, draft, age and whether the person is editing it")
    void waitingReplies() {
        YouTubeTools tools = tools(true);
        ai.proxyma.core.chat.agents.ApprovalInbox inbox = mock(ai.proxyma.core.chat.agents.ApprovalInbox.class);
        ai.proxyma.core.chat.approval.ApprovalContext context = new ai.proxyma.core.chat.approval.ApprovalContext("YouTube", List.of(
                ai.proxyma.core.chat.approval.ApprovalContext.Field.link("viewer", "Minh", "https://www.youtube.com/channel/UCfan2"),
                new ai.proxyma.core.chat.approval.ApprovalContext.Field("comment", "Sheet music please",
                        "https://www.youtube.com/watch?v=v9&lc=c3", ai.proxyma.core.chat.approval.ApprovalContext.QUOTE),
                ai.proxyma.core.chat.approval.ApprovalContext.Field.editable("reply", "Cam on ban", "text")));
        java.time.Instant asked = java.time.Instant.now().minusSeconds(600);
        java.util.function.BiFunction<String, String, ai.proxyma.core.chat.agents.ApprovalInbox.View> view = (comment, text) ->
                new ai.proxyma.core.chat.agents.ApprovalInbox.View(UUID.randomUUID().toString(), "conv", "conv", null,
                        "replyToYouTubeComment", "{\"commentId\":\"" + comment + "\",\"text\":\"" + text + "\"}", "PENDING",
                        asked, null, null, null, comment.equals("c3") ? context : null, null, false, "youtube-comment:" + comment);
        // Two on the first page of three in all: the total is the inbox's count, never the page's.
        when(inbox.waiting(any(), eq("replyToYouTubeComment"), eq(0), eq(2))).thenReturn(
                new ai.proxyma.core.chat.agents.ApprovalInbox.WaitingPage(List.of(
                        new ai.proxyma.core.chat.agents.ApprovalInbox.Waiting(view.apply("c3", "Cam on ban"), true),
                        new ai.proxyma.core.chat.agents.ApprovalInbox.Waiting(view.apply("c4", "Hi"), false)), 3, 0, 2));
        when(inbox.waiting(any(), eq("replyToYouTubeComment"), eq(1), eq(2))).thenReturn(
                new ai.proxyma.core.chat.agents.ApprovalInbox.WaitingPage(List.of(
                        new ai.proxyma.core.chat.agents.ApprovalInbox.Waiting(view.apply("c5", "Hello"), false)), 3, 1, 2));
        tools.setApprovalsForTest(inbox);

        Map<String, Object> out = tools.listWaitingYouTubeReplies(2, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replies = (List<Map<String, Object>>) out.get("replies");
        assertThat(replies).hasSize(2);
        assertThat(out).containsEntry("more", true).containsEntry("total", 3L);
        Map<String, Object> second = tools.listWaitingYouTubeReplies(2, 2);
        assertThat(second).containsEntry("more", false).containsEntry("total", 3L);
        assertThat((List<?>) second.get("replies")).hasSize(1);
        assertThat(replies.get(0)).containsEntry("commentId", "c3").containsEntry("author", "Minh")
                .containsEntry("comment", "Sheet music please").containsEntry("link", "https://www.youtube.com/watch?v=v9&lc=c3")
                .containsEntry("draft", "Cam on ban").containsEntry("beingEdited", true);
        assertThat((Long) replies.get(0).get("waitingMinutes")).isBetween(9L, 11L);
        assertThat(replies.get(1)).containsEntry("commentId", "c4").containsEntry("draft", "Hi").containsEntry("beingEdited", false)
                .containsEntry("link", null);
        assertThat(api.requests("/youtube/v3/commentThreads")).isEmpty();
        verifyNoInteractions(guard);
    }
}
