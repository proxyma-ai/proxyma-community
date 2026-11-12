package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.chat.approval.CommentFactsProvider.Comment;
import ai.proxyma.core.chat.approval.CommentFactsProvider.Facts;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who wrote a comment a dashboard names, when, under which video, and that video's title (2026-11-05, the
 * Overview's topics drawn as the approval page draws a comment), against a loopback YouTube Data API: batched,
 * remembered per connection - a comment or video YouTube no longer has included - only through a connection
 * the owner has that serves YouTube, and never thrown from.
 */
class YouTubeCommentFactsTest {

    private LoopbackApi api;
    private ConnectorEndpointResolver endpoints;
    private ConnectorConnectionService connections;
    private UUID connectionId;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        endpoints = mock(ConnectorEndpointResolver.class);
        connections = mock(ConnectorConnectionService.class);
        connectionId = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(connectionId, "u-1", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, false, null, null, null);
        when(endpoints.byId(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any())).thenReturn(Endpoint.EMPTY);
        when(endpoints.byId(ConnectorType.YOUTUBE_CHANNEL, connectionId, "u-1")).thenReturn(Endpoint.of(connection));
        when(connections.resolveOwned(eq("u-1"), eq(connectionId))).thenReturn(Optional.of(connection));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private YouTubeCommentFacts provider() {
        return new YouTubeCommentFacts(endpoints, connections, new SourceContextFactory(null, null, null, null), ConnectorHttp.direct());
    }

    private void threads() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'c1','snippet':{'videoId':'qjjROCqCfys','topLevelComment':{'id':'c1','snippet':{'authorDisplayName':'@minh',"
                + "'authorChannelUrl':'http://www.youtube.com/@minh','publishedAt':'2026-02-01T08:30:00Z'}}}},"
                + "{'id':'c2','snippet':{'videoId':'KdL7ntwE2fk','topLevelComment':{'id':'c2','snippet':{'authorDisplayName':'@lan',"
                + "'authorChannelId':{'value':'UClan'},'publishedAt':'yesterday'}}}}]}");
        api.on("GET", "/youtube/v3/comments").json("{'items':[{'id':'c1.r1','snippet':{'authorDisplayName':'@an',"
                + "'publishedAt':'2026-03-01T00:00:00Z','parentId':'c1'}}]}");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'qjjROCqCfys','snippet':{'title':'Biet Tri Ky - dizi cover'}},"
                + "{'id':'KdL7ntwE2fk','snippet':{'title':'Hoa Tau Sao Truc'}}]}");
    }

    @Test
    @DisplayName("each comment's author, channel, date and video, and every video's title - in one call each, a reply's video off its thread")
    void factsInOneCallEach() {
        threads();
        Facts f = provider().lookUp("u-1", connectionId, List.of("c1", "c2", "c1.r1", "gone"), List.of("KdL7ntwE2fk"));

        assertThat(f.complete()).isTrue();
        assertThat(f.comments()).containsOnlyKeys("c1", "c2", "c1.r1");
        assertThat(f.comments().get("c1")).isEqualTo(new Comment("@minh", "http://www.youtube.com/@minh", "2026-02-01T08:30:00Z", "qjjROCqCfys"));
        // A channel by its id when YouTube gives no address; a time that is not one is left out.
        assertThat(f.comments().get("c2")).isEqualTo(new Comment("@lan", "https://www.youtube.com/channel/UClan", null, "KdL7ntwE2fk"));
        assertThat(f.comments().get("c1.r1")).isEqualTo(new Comment("@an", null, "2026-03-01T00:00:00Z", "qjjROCqCfys"));
        assertThat(f.videoTitles()).containsEntry("qjjROCqCfys", "Biet Tri Ky - dizi cover").containsEntry("KdL7ntwE2fk", "Hoa Tau Sao Truc");

        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(1);
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("id")).isEqualTo("c1,c2,gone");
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).header("Authorization")).isEqualTo("Bearer ya29.test");
        assertThat(api.requests("/youtube/v3/comments").get(0).param("id")).isEqualTo("c1.r1");
        assertThat(api.requests("/youtube/v3/videos")).hasSize(1);
        assertThat(api.requests("/youtube/v3/videos").get(0).param("id")).isEqualTo("KdL7ntwE2fk,qjjROCqCfys");
    }

    @Test
    @DisplayName("an answer is remembered per connection for half an hour - a comment YouTube no longer has too - then asked again")
    void remembered() {
        threads();
        YouTubeCommentFacts p = provider();
        Instant now = Instant.parse("2026-11-05T08:00:00Z");
        p.setClock(Clock.fixed(now, ZoneOffset.UTC));
        p.lookUp("u-1", connectionId, List.of("c1", "gone"), List.of());
        Facts again = p.lookUp("u-1", connectionId, List.of("c1", "gone"), List.of());
        assertThat(again.comments()).containsOnlyKeys("c1");
        assertThat(again.videoTitles()).containsOnlyKeys("qjjROCqCfys");
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(1);
        assertThat(api.requests("/youtube/v3/videos")).hasSize(1);

        p.setClock(Clock.fixed(now.plus(YouTubeCommentFacts.TTL).plusSeconds(1), ZoneOffset.UTC));
        p.lookUp("u-1", connectionId, List.of("c1"), List.of());
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(2);
    }

    @Test
    @DisplayName("fifty ids a call, and at most three hundred comments a lookup")
    void batched() {
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 400; i++) ids.add("c" + i);
        Facts f = provider().lookUp("u-1", connectionId, ids, List.of());
        assertThat(f.comments()).isEmpty();
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(YouTubeCommentFacts.COMMENTS_MAX / YouTubeCommentFacts.BATCH);
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("id").split(",")).hasSize(YouTubeCommentFacts.BATCH);
    }

    @Test
    @DisplayName("YouTube unreachable is no throw: what is known, marked incomplete; and a connection not the owner's answers nothing")
    void neverThrows() {
        api.on("GET", "/youtube/v3/commentThreads").status(500).json("{'error':{'message':'Backend Error'}}");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'KdL7ntwE2fk','snippet':{'title':'Hoa Tau Sao Truc'}}]}");
        Facts f = provider().lookUp("u-1", connectionId, List.of("c1"), List.of("KdL7ntwE2fk"));
        assertThat(f.complete()).isFalse();
        assertThat(f.comments()).isEmpty();
        assertThat(f.videoTitles()).containsOnlyKeys("KdL7ntwE2fk");

        api.reset();
        assertThat(provider().lookUp("u-2", connectionId, List.of("c1"), List.of("KdL7ntwE2fk"))).isEqualTo(Facts.NONE);
        assertThat(provider().lookUp("u-1", UUID.randomUUID(), List.of("c1"), List.of())).isEqualTo(Facts.NONE);
        assertThat(provider().lookUp("u-1", connectionId, List.of("not an id", "a/b"), List.of("short"))).isEqualTo(Facts.NONE);
        assertThat(api.requests()).isEmpty();
    }
}
