package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * What a YouTube reply waiting for approval shows, against a loopback YouTube Data API: the video, the
 * viewer, their comment and the reply, in that order - looked up through the connection resolved for
 * READ and owned by the conversation's user; the video from the comment's THREAD, since a comment read
 * alone names none (2026-11-02: the page showed no video); a reply's video off its parent's thread; a
 * video's title asked once for many drafts. And since 2026-11-05 (the maintainer: "The cards must always
 * have all the fields") a card is WHOLE or refused: a comment, a video or a viewer YouTube does not have is
 * refused for certain (a reason a waiting request is withdrawn with), a spent quota or an outage for now.
 */
class YouTubeApprovalContextTest {

    private LoopbackApi api;
    private ToolConnectorResolver resolver;
    private ConnectorConnectionService connections;
    private UUID connectionId;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        resolver = mock(ToolConnectorResolver.class);
        connections = mock(ConnectorConnectionService.class);
        connectionId = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(connectionId, "u-1", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, false, null, null, null);
        when(resolver.resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ))).thenReturn(Endpoint.of(connection));
        when(connections.resolveOwned(eq("u-1"), eq(connectionId))).thenReturn(Optional.of(connection));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private YouTubeApprovalContext provider() {
        return new YouTubeApprovalContext(resolver, connections, new SourceContextFactory(null, null, null, null),
                ConnectorHttp.direct());
    }

    private static Map<String, Object> reply(String commentId, String text) {
        return Map.of("commentId", commentId, "text", text);
    }
    /** The channel the connection posts as: Cam Am Tieu Dao, @camamtieudao. */
    private void ourChannel() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCcam','snippet':{'title':'Cam Am Tieu Dao',"
                + "'customUrl':'@camamtieudao'}}]}");
    }

    /** A top-level comment's thread under video v2, on {@code channelId}'s channel, by Minh. */
    private void threadOn(String commentId, String channelId) {
        api.on("GET", "/youtube/v3/commentThreads").query("id", commentId).json("{'items':[{'id':'" + commentId
                + "','snippet':{'videoId':'v2','channelId':'" + channelId + "','topLevelComment':{'id':'" + commentId
                + "','snippet':{'authorDisplayName':'Minh','authorChannelUrl':'http://www.youtube.com/@minh',"
                + "'textOriginal':'Sheet music please'}}}}]}");
    }

    private void video(String id, String title) {
        api.on("GET", "/youtube/v3/videos").query("id", id).json("{'items':[{'id':'" + id + "','snippet':{'title':'" + title
                + "','channelId':'UCcam'}}]}");
    }

    private ApprovalContextProvider.Unavailable refused(Map<String, Object> arguments, String userId) {
        try {
            provider().contextFor("replyToYouTubeComment", arguments, "conv-1", userId);
        } catch (ApprovalContextProvider.Unavailable e) {
            return e;
        }
        throw new AssertionError("the card was made");
    }

    @Test
    @DisplayName("the whole card from the comment's thread: the video, the viewer, their comment linked on the video, the channel replied as, the reply")
    void wholeCard() {
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{'videoId':'v2',"
                + "'channelId':'UCcam','topLevelComment':{'id':'c3','snippet':{'authorDisplayName':'Minh',"
                + "'authorChannelUrl':'http://www.youtube.com/@minh','authorChannelId':{'value':'UCfan2'},"
                + "'textOriginal':'Sheet music please'}}}}]}");
        video("v2", "Biet Tri Ky - dizi cover");

        ApprovalContext c = provider().contextFor("replyToYouTubeComment",
                reply("thread:c3", "  The sheet is on camamtieudao.com.  "), "conv-1", "u-1").orElseThrow();

        assertThat(c.fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "replyAs", "reply");
        assertThat(c.fields().get(0)).isEqualTo(Field.link("video", "Biet Tri Ky - dizi cover",
                "https://www.youtube.com/watch?v=v2"));
        assertThat(c.fields().get(1)).isEqualTo(Field.link("viewer", "Minh", "http://www.youtube.com/@minh"));
        assertThat(c.fields().get(2)).isEqualTo(new Field("comment", "Sheet music please",
                "https://www.youtube.com/watch?v=v2&lc=c3", ApprovalContext.QUOTE));
        assertThat(c.fields().get(4)).isEqualTo(Field.editable("reply", "The sheet is on camamtieudao.com.", "text"));
        assertThat(provider().requiredFields("replyToYouTubeComment"))
                .containsExactly("video", "viewer", "comment", "replyAs", "reply");
        assertThat(provider().requiredFields("listYouTubeComments")).isEmpty();
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).param("part")).isEqualTo("snippet");
        assertThat(api.requests("/youtube/v3/commentThreads").get(0).header("Authorization")).isEqualTo("Bearer ya29.test");
        assertThat(api.requests("/youtube/v3/comments")).as("the thread says it all").isEmpty();
        verify(resolver).resolve(eq(ConnectorType.YOUTUBE_CHANNEL), any(), any(), eq(Access.READ));
        verify(resolver, never()).resolve(any(), any(), any(), eq(Access.WRITE));
    }

    @Test
    @DisplayName("when the comment was written comes after it, as an ISO time the page shows as an age - a two-year-old comment too")
    void commentDate() {
        // 2026-11-03: the team answers old comments too, newest first, and the person approving should see how old.
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{'videoId':'v2',"
                + "'channelId':'UCcam','topLevelComment':{'id':'c3','snippet':{'authorDisplayName':'Minh',"
                + "'authorChannelId':{'value':'UCfan2'},'textOriginal':'Sheet music please',"
                + "'publishedAt':'2024-10-01T08:30:00Z'}}}}]}");
        video("v2", "Biet Tri Ky");

        ApprovalContext c = provider().contextFor("replyToYouTubeComment", reply("c3", "Thanks!"), "conv-1", "u-1").orElseThrow();
        assertThat(c.fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "commentedAt", "replyAs", "reply");
        assertThat(c.fields().get(3)).isEqualTo(Field.text("commentedAt", "2024-10-01T08:30:00Z"));
        // Stored with the request and read back, it stays.
        assertThat(ApprovalContext.fromJson(c.toJson()).orElseThrow().fields().get(3).value()).isEqualTo("2024-10-01T08:30:00Z");

        // A time that is not one is left out, never shown raw.
        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c4").json("{'items':[{'id':'c4','snippet':{'videoId':'v2',"
                + "'channelId':'UCcam','topLevelComment':{'id':'c4','snippet':{'authorDisplayName':'Lan',"
                + "'authorChannelId':{'value':'UCfan3'},'textOriginal':'Hi','publishedAt':'yesterday'}}}}]}");
        video("v2", "Biet Tri Ky");
        assertThat(provider().contextFor("replyToYouTubeComment", reply("c4", "Hello"), "conv-1", "u-1").orElseThrow().fields())
                .extracting(Field::key).doesNotContain("commentedAt");
    }

    @Test
    @DisplayName("a comment read alone carries no videoId, so the video comes from its thread (the page showed none)")
    void videoFromTheThreadNotTheComment() {
        ourChannel();
        // What comments.list really answers: no videoId in the snippet.
        api.on("GET", "/youtube/v3/comments").json("{'items':[{'id':'c3','snippet':{'authorDisplayName':'Minh',"
                + "'textOriginal':'Hi'}}]}");
        threadOn("c3", "UCcam");
        video("v2", "Hoa Tau");

        ApprovalContext c = provider().contextFor("replyToYouTubeComment", reply("c3", "Thanks"), "conv-1", "u-1").orElseThrow();
        assertThat(c.fields().get(0)).isEqualTo(Field.link("video", "Hoa Tau", "https://www.youtube.com/watch?v=v2"));
    }

    @Test
    @DisplayName("a title Proxyma already holds - the channel source's index, any lookup's - is used with no videos.list call when the thread names the video's channel (2026-11-06)")
    void heldTitleAsksNothing() {
        ourChannel();
        threadOn("c3", "UCcam");
        api.on("GET", "/youtube/v3/videos").status(403).json("{'error':{'code':403,'message':'quota',"
                + "'errors':[{'reason':'quotaExceeded'}]}}");
        YouTubeApprovalContext p = provider();
        YouTubeVideoTitles held = YouTubeVideoTitles.memoryOnly();
        held.remember(connectionId, "v2", "Hoa Tau Sao Truc");
        p.setVideoTitles(held);

        ApprovalContext c = p.contextFor("replyToYouTubeComment", reply("c3", "Thanks"), "conv-1", "u-1").orElseThrow();
        assertThat(c.fields().get(0)).isEqualTo(Field.link("video", "Hoa Tau Sao Truc", "https://www.youtube.com/watch?v=v2"));
        assertThat(api.requests("/youtube/v3/videos")).as("videos.list's quota spent, and never asked").isEmpty();
    }

    @Test
    @DisplayName("a thread the comment sync just read is the card's: no commentThreads call for the check or the card, until it is an hour old")
    void threadFromTheSync() throws Exception {
        ourChannel();
        threadOn("c3", "UCcam");
        video("v2", "Hoa Tau");
        YouTubeApprovalContext p = provider();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> at =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-11T08:00:00Z"));
        p.setClock(new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return at.get(); }
        });
        com.fasterxml.jackson.databind.JsonNode read = new com.fasterxml.jackson.databind.ObjectMapper().readTree((
                "{'id':'c3','snippet':{'videoId':'v2','channelId':'UCcam','topLevelComment':{'id':'c3',"
                + "'snippet':{'authorDisplayName':'Minh','authorChannelUrl':'http://www.youtube.com/@minh','textOriginal':'Sheet music please'}}}}").replace('\'', '"'));
        p.heldFromSync(connectionId, java.util.List.of(read));

        assertThat(p.refusal("replyToYouTubeComment", reply("c3", "Thanks"), "conv-1", "u-1")).isEmpty();
        assertThat(p.contextFor("replyToYouTubeComment", reply("c3", "Thanks"), "conv-1", "u-1")).isPresent();
        assertThat(api.requests("/youtube/v3/commentThreads")).as("both read what the sync read").isEmpty();

        at.set(at.get().plus(YouTubeApprovalContext.SYNCED_KEPT).plusSeconds(1));
        assertThat(p.contextFor("replyToYouTubeComment", reply("c3", "Thanks"), "conv-1", "u-1")).isPresent();
        assertThat(api.requests("/youtube/v3/commentThreads")).as("an hour on, asked again").hasSize(1);
    }

    @Test
    @DisplayName("twenty drafts on one video ask for its title once, and for the channel once")
    void titleRemembered() {
        ourChannel();
        threadOn("c3", "UCcam");
        video("v2", "Hoa Tau");
        YouTubeApprovalContext p = provider();
        for (int i = 0; i < 20; i++) {
            assertThat(p.contextFor("replyToYouTubeComment", reply("c3", "Thanks " + i), "conv-1", "u-1")).isPresent();
        }
        assertThat(api.requests("/youtube/v3/videos")).hasSize(1);
        assertThat(api.requests("/youtube/v3/channels")).hasSize(1);
        assertThat(api.requests("/youtube/v3/commentThreads")).hasSize(20);
    }

    @Test
    @DisplayName("a reply named by its id: read alone, its video and its channel its parent's thread's - never asked as a thread itself - and its thread's opening comment shown")
    void replysParent() {
        // 2026-11-05: cards with no video at all - a reply read alone names none.
        ourChannel();
        api.on("GET", "/youtube/v3/comments").query("id", "c3.r1").json("{'items':[{'id':'c3.r1','snippet':{'parentId':'c3',"
                + "'authorDisplayName':'Lan','authorChannelId':{'value':'UCfan'},'textOriginal':'Me too!'}}]}");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{'videoId':'v9',"
                + "'channelId':'UCcam','topLevelComment':{'id':'c3','snippet':{'authorDisplayName':'Minh',"
                + "'textOriginal':'Sheet music please'}}}}]}");
        video("v9", "Hoa Tau");

        ApprovalContext c = provider().contextFor("replyToYouTubeComment", reply("c3.r1", "Coming soon"), "conv-1", "u-1")
                .orElseThrow();

        assertThat(c.fields().get(0)).isEqualTo(Field.link("video", "Hoa Tau", "https://www.youtube.com/watch?v=v9"));
        assertThat(c.fields().get(1)).isEqualTo(Field.link("viewer", "Lan", "https://www.youtube.com/channel/UCfan"));
        // The conversation it is part of (2026-11-10): the comment that opened its thread, and who wrote it.
        assertThat(c.fields().get(2)).isEqualTo(Field.text(YouTubeApprovalContext.THREAD_AUTHOR, "Minh"));
        assertThat(c.fields().get(3)).isEqualTo(new Field(YouTubeApprovalContext.THREAD_START, "Sheet music please",
                "https://www.youtube.com/watch?v=v9&lc=c3", ApprovalContext.QUOTE));
        assertThat(c.fields().get(4)).isEqualTo(new Field("comment", "Me too!", "https://www.youtube.com/watch?v=v9&lc=c3.r1",
                ApprovalContext.QUOTE));
        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("id")).containsExactly("c3");

        // Its parent's thread on another channel's video: refused as made for another channel, a definite answer.
        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/comments").query("id", "c5.r1").json("{'items':[{'id':'c5.r1','snippet':{'parentId':'c5',"
                + "'authorDisplayName':'Lan','authorChannelId':{'value':'UCfan'},'textOriginal':'Me too!'}}]}");
        threadOn("c5", "UClesyhau");
        video("v2", "Hoa Tau");
        ApprovalContextProvider.Unavailable other = refused(reply("c5.r1", "Hi"), "u-1");
        assertThat(other.reason()).isEqualTo(YouTubeApprovalContext.OTHER_CHANNEL);
        assertThat(other.getMessage()).startsWith("That comment is under a video of another YouTube channel");

        // Its parent's thread gone: the comment cannot be read.
        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/comments").query("id", "c6.r1").json("{'items':[{'id':'c6.r1','snippet':{'parentId':'c6',"
                + "'authorDisplayName':'Lan','authorChannelId':{'value':'UCfan'},'textOriginal':'Me too!'}}]}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        assertThat(refused(reply("c6.r1", "Hi"), "u-1").reason()).isEqualTo(YouTubeApprovalContext.MISSING_COMMENT);
    }

    @Test
    @DisplayName("a video's title is read with videos.list; a video YouTube does not have is refused for certain, one it failed to read for now")
    void videoTitleOrRefused() {
        ourChannel();
        threadOn("c3", "UCcam");
        api.on("GET", "/youtube/v3/videos").json("{'items':[]}");
        ApprovalContextProvider.Unavailable gone = refused(reply("c3", "Hello"), "u-1");
        assertThat(gone.definite()).isTrue();
        assertThat(gone.reason()).isEqualTo(YouTubeApprovalContext.MISSING_VIDEO)
                .isEqualTo("Missing details: the video could not be read.");
        assertThat(gone.getMessage()).contains("private or deleted").contains("Nothing was kept");

        // Never a card with the video named by its id (2026-11-05: "8QFVNhzw5GE" on the Approvals page).
        api.reset();
        ourChannel();
        threadOn("c3", "UCcam");
        api.on("GET", "/youtube/v3/videos").status(500).json("{'error':{'code':500}}");
        ApprovalContextProvider.Unavailable failed = refused(reply("c3", "Hello"), "u-1");
        assertThat(failed.definite()).as("an outage may pass: never withdraws").isFalse();
        assertThat(failed.getMessage()).contains("try again in a few minutes");
    }

    @Test
    @DisplayName("a spent daily quota refuses the call as a limit to wait out, never as a missing comment")
    void quotaIsTransient() {
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded','message':'quota'}]}}");
        ApprovalContextProvider.Unavailable u = refused(reply("c3", "Hello"), "u-1");
        assertThat(u.definite()).isFalse();
        assertThat(u.getMessage()).contains("daily quota").contains("midnight Pacific time");
    }

    @Test
    @DisplayName("the video's channel is the one replied as, read off the video when the thread names none; another channel's is refused")
    void videosChannel() {
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{'videoId':'v7',"
                + "'topLevelComment':{'id':'c3','snippet':{'authorDisplayName':'Minh','authorChannelId':{'value':'UCfan'},"
                + "'textOriginal':'Hi'}}}}]}");
        api.on("GET", "/youtube/v3/videos").query("id", "v7").json("{'items':[{'id':'v7','snippet':{'title':'Hoa Tau',"
                + "'channelId':'UClesyhau'}}]}");
        assertThat(refused(reply("c3", "Hello"), "u-1").reason()).isEqualTo(YouTubeApprovalContext.OTHER_CHANNEL);

        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c3").json("{'items':[{'id':'c3','snippet':{'videoId':'v7',"
                + "'topLevelComment':{'id':'c3','snippet':{'authorDisplayName':'Minh','authorChannelId':{'value':'UCfan'},"
                + "'textOriginal':'Hi'}}}}]}");
        api.on("GET", "/youtube/v3/videos").query("id", "v7").json("{'items':[{'id':'v7','snippet':{'title':'Hoa Tau'}}]}");
        assertThat(refused(reply("c3", "Hello"), "u-1").definite())
                .as("nobody says whose video it is: refused for now, never withdrawn").isFalse();
    }

    @Test
    @DisplayName("a comment with no author or no words, a reply with no text, no comment named: refused, each for certain")
    void missingFieldsRefused() {
        ourChannel();
        video("v2", "Hoa Tau");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c-anon").json("{'items':[{'id':'c-anon','snippet':{"
                + "'videoId':'v2','channelId':'UCcam','topLevelComment':{'id':'c-anon','snippet':{'textOriginal':'Hi'}}}}]}");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c-blank").json("{'items':[{'id':'c-blank','snippet':{"
                + "'videoId':'v2','channelId':'UCcam','topLevelComment':{'id':'c-blank','snippet':{'authorDisplayName':'Minh',"
                + "'authorChannelId':{'value':'UCfan'},'textOriginal':'  '}}}}]}");

        assertThat(refused(reply("c-anon", "Hello"), "u-1").reason()).isEqualTo(YouTubeApprovalContext.MISSING_VIEWER);
        assertThat(refused(reply("c-blank", "Hello"), "u-1").reason()).isEqualTo(YouTubeApprovalContext.MISSING_COMMENT);
        assertThat(refused(reply("c-anon", "   "), "u-1").reason()).isEqualTo(YouTubeApprovalContext.MISSING_REPLY);
        assertThat(refused(Map.of("text", "Hi"), "u-1").reason()).isEqualTo(YouTubeApprovalContext.MISSING_COMMENT);
        assertThat(refused(reply("c-anon", "x".repeat(YouTubeTools.MAX_REPLY_CHARS + 1)), "u-1").definite())
                .as("too long: the agent shortens it; a waiting one is the person's to edit").isFalse();
    }

    @Test
    @DisplayName("the reply tool is described, and only it: a request kept without its context is looked up again")
    void describesTheReply() {
        assertThat(provider().describes("replyToYouTubeComment")).isTrue();
        assertThat(provider().describes("listYouTubeComments")).isFalse();
    }

    @Test
    @DisplayName("a refused read is for now, a comment YouTube does not have is for certain, a connection not the user's is for now, another tool is empty")
    void failuresRefuse() {
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").status(403).json("{'error':{'code':403,'errors':[{'reason':'forbidden'}]}}");
        api.on("GET", "/youtube/v3/comments").status(403).json("{'error':{'code':403,'errors':[{'reason':'forbidden'}]}}");
        assertThat(refused(reply("c3", "Hi"), "u-1").definite()).isFalse();

        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        api.on("GET", "/youtube/v3/comments").json("{'items':[]}");
        ApprovalContextProvider.Unavailable gone = refused(reply("c3", "Hi"), "u-1");
        assertThat(gone.reason()).isEqualTo(YouTubeApprovalContext.MISSING_COMMENT)
                .isEqualTo("Missing details: the comment could not be read.");
        assertThat(gone.getMessage()).startsWith("YouTube has no comment with that commentId");

        assertThat(refused(reply("c3", "Hi"), "someone-else").definite()).isFalse();
        assertThat(provider().contextFor("listYouTubeComments", reply("c3", "Hi"), "conv-1", "u-1")).isEmpty();
    }

    @Test
    @DisplayName("through the inbox's lookup: a whole card is ready, a missing comment refused with its reason, a quota refused with none")
    void throughApprovalContexts() {
        ourChannel();
        threadOn("c3", "UCcam");
        video("v2", "Hoa Tau");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c-gone").json("{'items':[]}");
        api.on("GET", "/youtube/v3/comments").query("id", "c-gone").json("{'items':[]}");
        ai.proxyma.core.chat.approval.ApprovalContexts contexts = ai.proxyma.core.chat.approval.ApprovalContexts.of(
                java.util.List.of(provider()), java.time.Duration.ofSeconds(5));

        ai.proxyma.core.chat.approval.ApprovalContexts.Prepared ready = contexts.prepare("replyToYouTubeComment",
                "{\"commentId\":\"c3\",\"text\":\"Thanks\"}", "conv-1", "u-1");
        assertThat(ready.refused()).isFalse();
        assertThat(ready.context().fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "replyAs", "reply");

        ai.proxyma.core.chat.approval.ApprovalContexts.Prepared gone = contexts.prepare("replyToYouTubeComment",
                "{\"commentId\":\"c-gone\",\"text\":\"Thanks\"}", "conv-1", "u-1");
        assertThat(gone.refused()).isTrue();
        assertThat(gone.definite()).isTrue();
        assertThat(gone.reason()).isEqualTo(YouTubeApprovalContext.MISSING_COMMENT);

        api.reset();
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        ai.proxyma.core.chat.approval.ApprovalContexts.Prepared quota = contexts.prepare("replyToYouTubeComment",
                "{\"commentId\":\"c9\",\"text\":\"Thanks\"}", "conv-1", "u-1");
        assertThat(quota.refused()).isTrue();
        assertThat(quota.definite()).isFalse();
        assertThat(quota.refusal()).contains("daily quota");
    }

    // -- A draft whose comment was answered meanwhile (2026-11-04) ------------------------------------

    private static ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting waiting(String requestId, String commentId) {
        return new ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting(requestId, "conv-1",
                Map.of("commentId", commentId, "text", "Thanks!"));
    }

    private void channel() {
        api.on("GET", "/youtube/v3/channels").json("{'items':[{'id':'UCowner'}]}");
    }

    @Test
    @DisplayName("waiting replies, one batch: a comment the channel replied to is answered, one with viewers' replies only stays, one YouTube no longer has is gone")
    void answeredKeptGone() {
        // The maintainer, 2026-11-04: "Many comments I already replied but still asked to approve."
        channel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'c1','snippet':{'totalReplyCount':2},'replies':{'comments':["
                + "{'id':'c1.a','snippet':{'authorChannelId':{'value':'UCfan'},'textOriginal':'+1'}},"
                + "{'id':'c1.b','snippet':{'authorChannelId':{'value':'UCowner'},'textOriginal':'Cam on ban!'}}]}},"
                + "{'id':'c2','snippet':{'totalReplyCount':1},'replies':{'comments':["
                + "{'id':'c2.a','snippet':{'authorChannelId':{'value':'UCfan'},'textOriginal':'me too'}}]}}]}");

        Map<String, String> done = provider().settled("replyToYouTubeComment",
                java.util.List.of(waiting("r1", "c1"), waiting("r2", "thread:c2"), waiting("r3", "c3")), "u-1");

        assertThat(done).containsExactlyInAnyOrderEntriesOf(Map.of("r1", YouTubeApprovalContext.ANSWERED,
                "r3", YouTubeApprovalContext.GONE));
        assertThat(YouTubeApprovalContext.ANSWERED).isEqualTo("Already answered on YouTube.");
        // The Overview counts the person's own answers by this exact constant (StepInFigures).
        assertThat(YouTubeApprovalContext.ANSWERED)
                .isEqualTo(ai.proxyma.core.chat.approval.ApprovalContextProvider.ANSWERED_BY_THE_PERSON);
        assertThat(YouTubeApprovalContext.GONE).isEqualTo("The comment is gone.");
        // One unit for the channel, one for the three threads.
        assertThat(api.requests("/youtube/v3/commentThreads")).singleElement().satisfies(r -> {
            assertThat(r.param("id")).isEqualTo("c1,c2,c3");
            assertThat(r.param("part")).isEqualTo("snippet,replies");
            assertThat(r.header("Authorization")).isEqualTo("Bearer ya29.test");
        });
        assertThat(api.requests("/youtube/v3/channels")).hasSize(1);
        assertThat(api.requests("/youtube/v3/comments")).as("every thread said it inline").isEmpty();
        verify(resolver, never()).resolve(any(), any(), any(), eq(Access.WRITE));
    }

    @Test
    @DisplayName("fifty threads a call: fifty-one waiting replies are two commentThreads calls, and the channel is asked once for both checks")
    void batchedByFifty() {
        channel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        java.util.List<ai.proxyma.core.chat.approval.ApprovalContextProvider.Waiting> many = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) many.add(waiting("r" + i, "c" + i));
        YouTubeApprovalContext provider = provider();

        assertThat(provider.settled("replyToYouTubeComment", many, "u-1")).hasSize(51)
                .containsValue(YouTubeApprovalContext.GONE);
        assertThat(api.requests("/youtube/v3/commentThreads")).extracting(r -> r.param("id").split(",").length)
                .containsExactly(YouTubeApprovalContext.BATCH, 1);

        provider.settled("replyToYouTubeComment", many.subList(0, 1), "u-1");
        assertThat(api.requests("/youtube/v3/channels")).as("the channel's id remembered").hasSize(1);
    }

    @Test
    @DisplayName("a thread with more replies than YouTube carries inline is read whole before it is called unanswered")
    void longThreadReadWhole() {
        channel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[{'id':'c1','snippet':{'totalReplyCount':7},"
                + "'replies':{'comments':[{'id':'c1.a','snippet':{'authorChannelId':{'value':'UCfan'}}}]}}]}");
        api.on("GET", "/youtube/v3/comments").query("parentId", "c1").json("{'items':["
                + "{'id':'c1.a','snippet':{'authorChannelId':{'value':'UCfan'}}},"
                + "{'id':'c1.g','snippet':{'authorChannelId':{'value':'UCowner'}}}]}");

        assertThat(provider().settled("replyToYouTubeComment", java.util.List.of(waiting("r1", "c1")), "u-1"))
                .containsExactly(Map.entry("r1", YouTubeApprovalContext.ANSWERED));
        assertThat(api.requests("/youtube/v3/comments")).singleElement()
                .satisfies(r -> assertThat(r.param("maxResults")).isEqualTo("100"));
    }

    @Test
    @DisplayName("what cannot be read keeps its draft: a refused batch, an unreadable channel, a connection not the user's, another tool")
    void unreadableStays() {
        channel();
        api.on("GET", "/youtube/v3/commentThreads").status(403).json("{'error':{'code':403,'errors':[{'reason':'quotaExceeded'}]}}");
        assertThat(provider().settled("replyToYouTubeComment", java.util.List.of(waiting("r1", "c1")), "u-1")).isEmpty();

        api.reset();
        api.on("GET", "/youtube/v3/channels").status(500).json("{'error':{'code':500}}");
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        assertThat(provider().settled("replyToYouTubeComment", java.util.List.of(waiting("r1", "c1")), "u-1")).isEmpty();
        assertThat(api.requests("/youtube/v3/commentThreads")).as("nothing is called gone without the channel").isEmpty();

        api.reset();
        channel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':[]}");
        assertThat(provider().settled("replyToYouTubeComment", java.util.List.of(waiting("r1", "c1")), "someone-else")).isEmpty();
        assertThat(provider().settled("listYouTubeComments", java.util.List.of(waiting("r1", "c1")), "u-1")).isEmpty();
        assertThat(api.requests()).isEmpty();
        assertThat(provider().settles("replyToYouTubeComment")).isTrue();
        assertThat(provider().settles("listYouTubeComments")).isFalse();
    }

    @Test
    @DisplayName("a reply refused for YouTube's daily quota is held until five minutes past the next midnight Pacific time; any other failure is not")
    void quotaRefusalWaitsForTheReset() {
        // 2026-11-04: sixteen approved replies "not posted because YouTube's quota is exhausted" (ApprovalHoldTest).
        String quota = "{\"success\":false,\"error\":\"YouTube's daily quota for the connection's Google Cloud project is "
                + "spent. Nothing was posted; try again tomorrow.\"}";
        // 18:14 UTC on 4 November is 10:14 Pacific (PST, UTC-8): the reset is 08:00 UTC on the 5th.
        java.time.Instant at = java.time.Instant.parse("2026-11-04T18:14:46Z");
        assertThat(provider().heldUntil("replyToYouTubeComment", quota, at))
                .contains(java.time.Instant.parse("2026-11-05T08:05:00Z"));
        // Past midnight Pacific already: the NEXT one. In summer time (PDT, UTC-7) the reset is 07:00 UTC.
        assertThat(provider().heldUntil("replyToYouTubeComment", quota, java.time.Instant.parse("2026-11-05T08:30:00Z")))
                .contains(java.time.Instant.parse("2026-11-06T08:05:00Z"));
        assertThat(provider().heldUntil("replyToYouTubeComment", quota, java.time.Instant.parse("2026-07-01T12:00:00Z")))
                .contains(java.time.Instant.parse("2026-07-02T07:05:00Z"));
        // A refusal waiting does not cure, a success, another tool: nothing held.
        assertThat(provider().heldUntil("replyToYouTubeComment",
                "{\"success\":false,\"error\":\"YouTube refused the reply: comments are turned off on that video.\"}", at)).isEmpty();
        assertThat(provider().heldUntil("replyToYouTubeComment", "{\"success\":true,\"replyId\":\"r1\"}", at)).isEmpty();
        assertThat(provider().heldUntil("listYouTubeComments", quota, at)).isEmpty();
    }

    // -- One channel (2026-11-05: a team for @camamtieudao had drafts for @lesyhau's comments) ------------------

    @Test
    @DisplayName("the card says which channel the reply is posted as - the connection's, by its title, linked - just before the reply")
    void replyAs() {
        ourChannel();
        threadOn("c3", "UCcam");
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v2','snippet':{'title':'Biet Tri Ky'}}]}");

        ApprovalContext c = provider().contextFor("replyToYouTubeComment", reply("c3", "Thanks!"), "conv-1", "u-1").orElseThrow();

        assertThat(c.fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "replyAs", "reply");
        assertThat(c.fields().get(3)).isEqualTo(Field.link("replyAs", "Cam Am Tieu Dao", "https://www.youtube.com/@camamtieudao"));
        // Without a handle, the channel's own page.
        assertThat(new YouTubeApprovalContext.Channel("UCx", "X", null).link()).isEqualTo("https://www.youtube.com/channel/UCx");
    }

    @Test
    @DisplayName("a reply for a comment under another channel's video is refused before it is kept; the channel's own, or one YouTube does not place, is not")
    void anotherChannelsCommentRefused() {
        ourChannel();
        threadOn("c-lesyhau", "UClesyhau");
        threadOn("c-cam", "UCcam");
        api.on("GET", "/youtube/v3/commentThreads").query("id", "c-unknown").json("{'items':[{'id':'c-unknown','snippet':{"
                + "'videoId':'v2','topLevelComment':{'id':'c-unknown','snippet':{'textOriginal':'Hi'}}}}]}");
        YouTubeApprovalContext provider = provider();

        assertThat(provider.refusal("replyToYouTubeComment", reply("c-lesyhau", "Hi"), "conv-1", "u-1")).hasValueSatisfying(why ->
                assertThat(why).startsWith("That comment is under a video of another YouTube channel, not \"Cam Am Tieu Dao\""));
        assertThat(provider.refusal("replyToYouTubeComment", reply("c-cam", "Hi"), "conv-1", "u-1")).isEmpty();
        assertThat(provider.refusal("replyToYouTubeComment", reply("c-unknown", "Hi"), "conv-1", "u-1"))
                .as("YouTube does not say whose it is: not refused").isEmpty();
        // A reply named by its own id is placed by its thread.
        assertThat(provider.refusal("replyToYouTubeComment", reply("c-lesyhau.r1", "Hi"), "conv-1", "u-1")).isPresent();
        assertThat(provider.refusal("listYouTubeComments", reply("c-lesyhau", "Hi"), "conv-1", "u-1")).isEmpty();
        assertThat(provider.refusal("replyToYouTubeComment", reply("c-lesyhau", "Hi"), "conv-1", "someone-else")).isEmpty();

        // The thread the refusal read serves the context's lookup that follows, once.
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'v2','snippet':{'title':'Biet Tri Ky'}}]}");
        int before = api.requests("/youtube/v3/commentThreads").size();
        provider.contextFor("replyToYouTubeComment", reply("c-cam", "Hi"), "conv-1", "u-1");
        assertThat(api.requests("/youtube/v3/commentThreads")).as("read once for both").hasSize(before);
        provider.contextFor("replyToYouTubeComment", reply("c-cam", "Hi"), "conv-1", "u-1");
        assertThat(api.requests("/youtube/v3/commentThreads")).as("never served twice").hasSize(before + 1);
        assertThat(api.requests("/youtube/v3/channels")).as("the channel remembered").hasSize(1);
    }

    @Test
    @DisplayName("a waiting reply for a comment of another channel than the one its conversation posts as now is withdrawn as made for another channel")
    void otherChannelWithdrawn() {
        ourChannel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'c1','snippet':{'channelId':'UClesyhau','totalReplyCount':0}},"
                + "{'id':'c2','snippet':{'channelId':'UCcam','totalReplyCount':0}}]}");

        Map<String, String> done = provider().settled("replyToYouTubeComment",
                java.util.List.of(waiting("r1", "c1"), waiting("r2", "c2")), "u-1");

        assertThat(done).containsExactly(Map.entry("r1", YouTubeApprovalContext.OTHER_CHANNEL));
        assertThat(YouTubeApprovalContext.OTHER_CHANNEL).isEqualTo("Made for another channel.");
    }

    @Test
    @DisplayName("a waiting reply to a viewer's REPLY is answered only by the channel writing after that reply, and gone when the thread read whole no longer holds it")
    void replyToAReplySettled() {
        // 2026-11-05, the conversation: the channel answered c1, the viewer wrote back (c1.b) - a draft for c1.b is not
        // settled by the channel's earlier reply, only by one after it.
        channel();
        api.on("GET", "/youtube/v3/commentThreads").json("{'items':["
                + "{'id':'c1','snippet':{'totalReplyCount':2},'replies':{'comments':["
                + "{'id':'c1.a','snippet':{'authorChannelId':{'value':'UCowner'},'textOriginal':'Thanks!','publishedAt':'2026-11-01T00:00:00Z'}},"
                + "{'id':'c1.b','snippet':{'authorChannelId':{'value':'UCfan'},'textOriginal':'Which key?','publishedAt':'2026-11-02T00:00:00Z'}}]}},"
                + "{'id':'c2','snippet':{'totalReplyCount':2},'replies':{'comments':["
                + "{'id':'c2.a','snippet':{'authorChannelId':{'value':'UCfan'},'textOriginal':'And this?','publishedAt':'2026-11-01T00:00:00Z'}},"
                + "{'id':'c2.b','snippet':{'authorChannelId':{'value':'UCowner'},'textOriginal':'D key.','publishedAt':'2026-11-02T00:00:00Z'}}]}}]}");

        Map<String, String> done = provider().settled("replyToYouTubeComment", java.util.List.of(waiting("r1", "c1.b"),
                waiting("r2", "c2.a"), waiting("r3", "c2.gone")), "u-1");

        assertThat(done).containsExactlyInAnyOrderEntriesOf(Map.of("r2", YouTubeApprovalContext.ANSWERED,
                "r3", YouTubeApprovalContext.GONE));
        assertThat(api.requests("/youtube/v3/commentThreads")).singleElement()
                .satisfies(r -> assertThat(r.param("id")).isEqualTo("c1,c2"));
    }

    @Test
    @DisplayName("a comment's id names the subject of the reply waiting for it; anything else names none")
    void subjectNamed() {
        assertThat(provider().subjectNamed("Ugx1abc")).contains("youtube-comment:Ugx1abc");
        assertThat(provider().subjectNamed("thread:Ugx1abc")).contains("youtube-comment:Ugx1abc");
        assertThat(provider().subjectNamed("c3/../x")).isEmpty();
    }
}
