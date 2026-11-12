package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.chat.channel.ChannelCommentsProvider;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ToolConnectorResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The comment queue's reading of a YouTube channel (YouTubeCommentSync, 2026-11-05) against a loopback YouTube Data API:
 * every comment that needs a decision read as its conversation - a viewer answering the owner back, a viewer under the
 * owner's own comment - with what came before and its video's title; what the channel answered or YouTube no longer has,
 * settled; the newest pages stopping where nothing is new, the walk into the older ones a page a pass by the token YouTube
 * gave; through the owner's own connection only; and every call counted.
 */
class YouTubeCommentSyncTest {

    private static final String CHANNEL = "UCowner";
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");
    private LoopbackApi api;
    private UUID connectionId;
    private YouTubeCommentSync sync;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + CHANNEL + "','snippet':{'title':'Cam Am Tieu Dao'},"
                + "'contentDetails':{'relatedPlaylists':{'uploads':'UUowner'}}}]}");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v1','snippet':{'title':'Dizi lesson 1'}}]}");
        connectionId = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(connectionId, "local", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, false, null, null, null);
        ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
        when(endpoints.byId(eq(ConnectorType.YOUTUBE_CHANNEL), eq(connectionId), eq("owner")))
                .thenReturn(ConnectorEndpointResolver.Endpoint.of(connection));
        when(endpoints.byId(eq(ConnectorType.YOUTUBE_CHANNEL), eq(connectionId), eq("someone-else")))
                .thenReturn(ConnectorEndpointResolver.Endpoint.EMPTY);
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwned(eq("owner"), eq(connectionId))).thenReturn(Optional.of(connection));
        SourceContextFactory contexts = new SourceContextFactory(null, null, null, null);
        YouTubeTools tools = new YouTubeTools(mock(ToolConnectorResolver.class), connections, contexts, ConnectorHttp.direct(),
                mock(McpGuardService.class));
        sync = new YouTubeCommentSync(endpoints, connections, contexts, ConnectorHttp.direct(), tools);
        sync.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private static String comment(String id, String author, String authorChannel, String text, String at) {
        return "{'id':'" + id + "','snippet':{'authorDisplayName':'" + author + "','authorChannelId':{'value':'"
                + authorChannel + "'},'textOriginal':'" + text + "','publishedAt':'" + at + "'}}";
    }

    private static String thread(String id, String top, String... replies) {
        return "{'id':'" + id + "','snippet':{'videoId':'v1','channelId':'" + CHANNEL + "','totalReplyCount':" + replies.length
                + ",'topLevelComment':" + top + "},'replies':{'comments':[" + String.join(",", replies) + "]}}";
    }

    @Test
    @DisplayName("a pass: the viewer who answered the owner back and the viewer under the owner's own comment need a decision, each with what came before and the video's title; what the channel answered is settled")
    void conversations() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", comment("t1", "Lan", "UCfan", "Which flute?", "2026-11-04T08:00:00Z"),
                        comment("t1.a", "Cam Am Tieu Dao", CHANNEL, "A dizi.", "2026-11-04T09:00:00Z"),
                        comment("t1.b", "Lan", "UCfan", "@Cam Am Tieu Dao which key?", "2026-11-04T10:00:00Z"))
                + "," + thread("t2", comment("t2", "Cam Am Tieu Dao", CHANNEL, "New video tomorrow!", "2026-11-03T08:00:00Z"),
                        comment("t2.a", "Minh", "UCfan2", "Cannot wait", "2026-11-03T09:00:00Z"))
                + "," + thread("t3", comment("t3", "Hoa", "UCfan3", "Lovely", "2026-11-02T08:00:00Z"),
                        comment("t3.a", "Cam Am Tieu Dao", CHANNEL, "Thank you!", "2026-11-02T09:00:00Z"))
                + "]}");

        ChannelCommentsProvider.Pass pass = sync.sync("owner", connectionId, ChannelCommentsProvider.Cursor.START, List.of());

        assertThat(pass.failed()).isFalse();
        assertThat(pass.channelId()).isEqualTo(CHANNEL);
        assertThat(pass.pending()).extracting(ChannelCommentsProvider.Comment::commentId).containsExactlyInAnyOrder("t1.b", "t2.a");
        ChannelCommentsProvider.Comment back = pass.pending().stream().filter(c -> c.commentId().equals("t1.b")).findFirst().orElseThrow();
        assertThat(back.reply()).isTrue();
        assertThat(back.threadId()).isEqualTo("t1");
        assertThat(back.videoTitle()).isEqualTo("Dizi lesson 1");
        assertThat(back.inReplyTo()).isEqualTo("A dizi.");
        assertThat(back.before()).extracting(ChannelCommentsProvider.Message::text).containsExactly("Which flute?", "A dizi.");
        assertThat(back.authorUrl()).isEqualTo("https://www.youtube.com/channel/UCfan");
        assertThat(pass.settled().get("t3").state()).isEqualTo(ChannelCommentsProvider.Settled.ANSWERED);
        assertThat(pass.settled().get("t3").channelReply()).isEqualTo("Thank you!");
        assertThat(pass.settled().get("t1").state()).as("answered before the viewer wrote back").isEqualTo(ChannelCommentsProvider.Settled.ANSWERED);
        assertThat(pass.settled().get("t1.b").state()).isEqualTo(ChannelCommentsProvider.Settled.WAITING);
        assertThat(pass.next().backlogDone()).as("one page: the walk reached the end").isTrue();
        assertThat(pass.next().newSince()).isEqualTo(NOW);
        assertThat(pass.calls()).as("the channel, the newest page, the walk's page and the titles").isEqualTo(4);
        assertThat(api.requests("/youtube/v3/videos")).singleElement().satisfies(r -> assertThat(r.param("id")).isEqualTo("v1"));
    }

    @Test
    @DisplayName("a video's title for the comment queue (2026-11-06): what is held with no call, YouTube only when asked to, a title read by a pass kept for both, and nothing through a connection not the owner's")
    void videoTitles() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + thread("t1", comment("t1", "Lan", "UCfan", "Which flute?", "2026-11-04T08:00:00Z")) + "]}");
        assertThat(sync.videoTitles("owner", connectionId, List.of("v1"), false)).as("nothing held yet, nothing asked").isEmpty();
        assertThat(api.requests("/youtube/v3/videos")).isEmpty();

        // A pass reads the title; from then on it is held, with no call.
        sync.sync("owner", connectionId, ChannelCommentsProvider.Cursor.START, List.of());
        assertThat(sync.videoTitles("owner", connectionId, List.of("v1"), false)).containsEntry("v1", "Dizi lesson 1");
        assertThat(sync.videoTitles("owner", connectionId, List.of("v1"), true)).containsEntry("v1", "Dizi lesson 1");
        assertThat(api.requests("/youtube/v3/videos")).hasSize(1);

        api.reset();
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v2','snippet':{'title':'Dizi lesson 2'}}]}");
        assertThat(sync.videoTitles("owner", connectionId, List.of("v2"), true)).containsEntry("v2", "Dizi lesson 2");
        assertThat(sync.videoTitles("someone-else", connectionId, List.of("v1", "v2"), true)).isEmpty();
        assertThat(sync.videoTitles("someone-else", connectionId, List.of("v1", "v2"), false)).isEmpty();
    }

    @Test
    @DisplayName("the newest pages stop where nothing is newer than the last pass; the walk into the older ones takes one page from the token it was given and hands on the next")
    void newestStopsAndTheWalkMovesOn() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'nextPageToken':'p2','items':["
                + thread("t1", comment("t1", "Lan", "UCfan", "Old", "2026-10-01T08:00:00Z")) + "]}");
        api.on("GET", "/youtube/v3/commentThreads").query("pageToken", "p5").json("{'nextPageToken':'p6','items':["
                + thread("t9", comment("t9", "Tu", "UCold", "Very old question", "2025-01-01T08:00:00Z")) + "]}");

        ChannelCommentsProvider.Pass pass = sync.sync("owner", connectionId,
                new ChannelCommentsProvider.Cursor("p5", null, false, Instant.parse("2026-11-01T00:00:00Z")), List.of());

        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("pageToken"))
                .as("page 2 of the newest is not asked: nothing there is new").containsExactly(null, "p5");
        assertThat(pass.pending()).extracting(ChannelCommentsProvider.Comment::commentId).containsExactlyInAnyOrder("t1", "t9");
        assertThat(pass.next().backlogToken()).isEqualTo("p6");
        assertThat(pass.next().backlogDone()).isFalse();
    }

    @Test
    @DisplayName("the comments still held open are asked by their threads, fifty a call: one answered since, one whose thread is gone, one still waiting")
    void recheck() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c1,c2,c3").json("{'items':["
                + thread("c1", comment("c1", "Lan", "UCfan", "Q", "2026-11-01T08:00:00Z"),
                        comment("c1.a", "Cam Am Tieu Dao", CHANNEL, "Answered here.", "2026-11-02T08:00:00Z"))
                + "," + thread("c3", comment("c3", "Minh", "UCfan2", "Q3", "2026-11-01T08:00:00Z")) + "]}");

        ChannelCommentsProvider.Pass pass = sync.sync("owner", connectionId,
                new ChannelCommentsProvider.Cursor(null, null, true, NOW.minusSeconds(60)), List.of("c1", "c2", "c3"));

        assertThat(pass.settled().get("c1").state()).isEqualTo(ChannelCommentsProvider.Settled.ANSWERED);
        assertThat(pass.settled().get("c1").channelReply()).isEqualTo("Answered here.");
        assertThat(pass.settled().get("c2").state()).isEqualTo(ChannelCommentsProvider.Settled.GONE);
        assertThat(pass.settled().get("c3").state()).isEqualTo(ChannelCommentsProvider.Settled.WAITING);
        assertThat(api.requests("/youtube/v3/commentThreads")).filteredOn(r -> r.param("id") != null).singleElement()
                .satisfies(r -> assertThat(r.param("part")).isEqualTo("snippet,replies"));
    }

    @Test
    @DisplayName("a long thread is read whole before it is judged; a connection not the owner's reads nothing; a refused read is a failed pass that keeps its cursor")
    void longThreadsOwnersAndFailures() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[{'id':'t1','snippet':{'videoId':'v1','totalReplyCount':7,"
                + "'topLevelComment':" + comment("t1", "Lan", "UCfan", "Which flute?", "2026-11-04T08:00:00Z") + "},"
                + "'replies':{'comments':[" + comment("t1.a", "Minh", "UCfan2", "me too", "2026-11-04T09:00:00Z") + "]}}]}");
        api.on("GET", "/youtube/v3/comments").query("parentId", "t1").json("{'items':["
                + comment("t1.a", "Minh", "UCfan2", "me too", "2026-11-04T09:00:00Z") + ","
                + comment("t1.g", "Cam Am Tieu Dao", CHANNEL, "A D5 dizi.", "2026-11-04T10:00:00Z") + "]}");

        ChannelCommentsProvider.Pass pass = sync.sync("owner", connectionId,
                new ChannelCommentsProvider.Cursor(null, null, true, null), List.of());
        assertThat(pass.pending()).as("the channel answered further down the thread").isEmpty();
        assertThat(pass.settled().get("t1").state()).isEqualTo(ChannelCommentsProvider.Settled.ANSWERED);

        ChannelCommentsProvider.Cursor cursor = new ChannelCommentsProvider.Cursor("p9", null, false, NOW);
        ChannelCommentsProvider.Pass none = sync.sync("someone-else", connectionId, cursor, List.of());
        assertThat(none.failed()).isTrue();
        assertThat(none.next()).isEqualTo(cursor);

        api.reset();
        api.on("GET", "/youtube/v3/channels").status(403).json("{'error':{'errors':[{'reason':'quotaExceeded'}]}}");
        ChannelCommentsProvider.Pass refused = sync.sync("owner", connectionId, cursor, List.of());
        assertThat(refused.failed()).isTrue();
        assertThat(refused.next()).isEqualTo(cursor);
        // A spent quota is said as the limit it is, never as access refused (2026-11-11).
        assertThat(refused.error()).startsWith("YouTube's daily quota for the connection's Google Cloud project is spent.")
                .doesNotContain("refused access");

        api.reset();
        api.on("GET", "/youtube/v3/channels").status(403).rawJson(
                ai.proxyma.core.settings.connection.BuiltInQuota.SPENT_BODY);
        ChannelCommentsProvider.Pass shared = sync.sync("owner", connectionId, cursor, List.of());
        assertThat(shared.error()).startsWith("YouTube's daily quota for Proxyma's shared sign-in is spent on this computer.")
                .doesNotContain("refused access");
    }

    @Test
    @DisplayName("viewers by their channel (2026-11-09): a page of channel ids in one call by id alone, each channel's name and handle, nothing through a connection not the owner's")
    void viewers() {
        String lan = "UC" + "a".repeat(22), minh = "UC" + "b".repeat(22);
        api.reset();
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'" + lan + "','snippet':{'title':'Lan Nguyen','customUrl':'@lan',"
                + "'thumbnails':{'default':{'url':'https://not-youtube.example/a.jpg'}}}},{'id':'" + minh + "','snippet':{'title':'Minh'}}]}");

        Map<String, ChannelCommentsProvider.Viewer> found = sync.viewers("owner", connectionId, List.of(lan, minh, "not-a-channel", lan));

        assertThat(found.get(lan)).isEqualTo(new ChannelCommentsProvider.Viewer("Lan Nguyen", "@lan", null));
        assertThat(found.get(minh)).isEqualTo(new ChannelCommentsProvider.Viewer("Minh", null, null));
        assertThat(api.requests("/youtube/v3/channels")).singleElement().satisfies(r -> {
            assertThat(r.param("id")).isEqualTo(lan + "," + minh);
            assertThat(r.param("part")).isEqualTo("snippet");
            assertThat(r.param("maxResults")).as("refused by YouTube beside an id list").isNull();
        });
        assertThat(sync.viewers("someone-else", connectionId, List.of(lan))).isEmpty();
        assertThat(sync.viewers("owner", connectionId, List.of("not-a-channel"))).isEmpty();
    }
}
