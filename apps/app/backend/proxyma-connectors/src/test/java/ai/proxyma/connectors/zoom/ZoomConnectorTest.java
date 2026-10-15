package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Zoom connector against a loopback Zoom: the Server-to-Server token exchange (grant, account ID
 * and Basic client, one token for many requests), the recordings listed a month at a time and paged
 * by next_page_token, summaries joined to them by meeting UUID, a CHANGES pass that reads only the
 * days since the last one, the transcript (VTT) rendered as speaker turns, a meeting gone from Zoom,
 * the download kept to Zoom's hosts, the 401 and 429 sentences, a summary scope that is missing, and
 * the connection Test.
 */
class ZoomConnectorTest {

    private static final String TOKEN = "zoom-user-token";
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();

    @BeforeEach
    void start() {
        ZoomAccountToken.forgetAll();
        api = LoopbackApi.start();
    }

    // The person signed in, with no recordings or summaries - registered AFTER a test's own routes,
    // since of two routes as specific as each other the first one registered answers.
    private void defaults() {
        api.on("GET", "/v2/users/me").json("{'id':'u-ana','first_name':'Ana','last_name':'Lee','email':'ana@acme.com'}");
        api.on("GET", "/v2/users/me/recordings").json("{'meetings':[],'next_page_token':''}");
        api.on("GET", "/v2/users/me/meeting_summaries").json("{'summaries':[],'next_page_token':''}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials user() {
        return ResolvedCredentials.token(api.url("/v2"), TOKEN);
    }

    private ResolvedCredentials serverToServer(String secret) {
        return new ResolvedCredentials(null, ConnectionProtocol.ZOOM, AuthKind.KEY_PAIR, api.url("/v2"), null,
                Map.of(ZoomApi.CLIENT_SECRET, secret), Map.of(ZoomApi.ACCOUNT_ID, "acc-1", ZoomApi.CLIENT_ID, "cid-1"), null);
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(ZoomConnectorPlugin.DESCRIPTOR.fields(), fields);
        SourceContext context = new SourceContext(null, ConnectorType.ZOOM, null, null, config, null, null, credentials,
                SourceScope.defaults(), ConnectorHttp.direct(waits::add), Clock.fixed(NOW, ZoneOffset.UTC));
        return new ZoomConnectorPlugin(api.url("/oauth/token")).open(context);
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private String recording(String uuid, String topic, String start, String... files) {
        return "{'uuid':'" + uuid + "','id':81234567890,'host_id':'u-ana','topic':'" + topic + "','start_time':'" + start
                + "','duration':45,'share_url':'https://zoom.us/rec/share/" + topic.length() + "','recording_files':["
                + String.join(",", files) + "]}";
    }

    private String file(String type, String end, String path) {
        return "{'id':'f-" + type + "','file_type':'" + type + "','file_extension':'VTT','status':'completed',"
                + "'recording_end':'" + end + "','download_url':'" + api.url(path) + "'}";
    }

    @Test
    @DisplayName("the descriptor: meetings, incremental with a cursor, never shareable; identity is the people without case")
    void descriptor() {
        ZoomConnectorPlugin plugin = new ZoomConnectorPlugin();
        assertThat(plugin.descriptor().capabilities().incremental()).isTrue();
        assertThat(plugin.descriptor().capabilities().cursor()).isTrue();
        assertThat(plugin.descriptor().capabilities().shareable()).isFalse();
        assertThat(plugin.identity(Map.of("users", "Ana@Acme.com\nbob@acme.com")))
                .isEqualTo(plugin.identity(Map.of("users", "bob@acme.com\nana@acme.com")));
        assertThat(plugin.identity(Map.of())).isEqualTo("me");
        assertThat(plugin.displayName(Map.of())).isEqualTo("Zoom: my meetings");
        assertThat(plugin.displayName(Map.of("users", "ana@acme.com\n*"))).isEqualTo("Zoom: everyone");
        assertThat(ZoomConnectionPlugin.DESCRIPTOR.auth(AuthKind.KEY_PAIR).fields())
                .extracting(f -> f.key()).containsExactly("accountId", "clientId", "clientSecret");
    }

    @Test
    @DisplayName("a full listing asks a month at a time back to the window's start, follows next_page_token, and joins summaries by UUID")
    void fullListing() {
        api.on("GET", "/v2/users/me/recordings").query("from", "2026-08-15")
                .json("{'meetings':[" + recording("uuid-A==", "Pricing review", "2026-08-20T09:00:00Z",
                        file("TRANSCRIPT", "2026-08-20T09:45:00Z", "/rec/download/a")) + "],'next_page_token':'p2'}");
        api.on("GET", "/v2/users/me/recordings").query("from", "2026-08-15").query("next_page_token", "p2")
                .json("{'meetings':[" + recording("uuid-video-only", "Video only", "2026-08-21T09:00:00Z",
                        "{'file_type':'MP4','status':'completed','download_url':'" + api.url("/rec/download/v") + "'}")
                        + "],'next_page_token':''}");
        api.on("GET", "/v2/users/me/recordings").query("from", "2026-09-14")
                .json("{'meetings':[" + recording("uuid-B", "Roadmap", "2026-09-20T09:00:00Z",
                        file("CC", "2026-09-20T09:30:00Z", "/rec/download/b")) + "]}");
        api.on("GET", "/v2/users/me/meeting_summaries").query("from", "2026-09-14T00:00:00Z")
                .json("{'summaries':[{'meeting_uuid':'uuid-B','meeting_topic':'Roadmap','meeting_start_time':'2026-09-20T09:00:00Z',"
                        + "'summary_last_modified_time':'2026-09-20T10:00:00Z'},"
                        + "{'meeting_uuid':'uuid-C','meeting_topic':'Standup','meeting_start_time':'2026-09-22T08:00:00Z',"
                        + "'meeting_host_email':'ana@acme.com','summary_last_modified_time':'2026-09-22T08:20:00Z'}]}");
        defaults();

        try (ConnectorSession s = session(Map.of("days", "60"), user())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(listing.hasErrored()).as(listing.getErrorMessage()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("meeting:uuid-A==", "meeting:uuid-B", "meeting:uuid-C");
            ItemMetadata a = items.get(0);
            assertThat(a.getTitle()).isEqualTo("Pricing review (2026-08-20)");
            assertThat(a.getLastModified()).isEqualTo(Instant.parse("2026-08-20T09:45:00Z"));
            assertThat(a.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee (ana@acme.com)");
            assertThat(a.getExtra("url")).isEqualTo("https://zoom.us/rec/share/14");
            // A transcript and a summary: the newer of the two, a millisecond on.
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-09-20T10:00:00.001Z"));
            assertThat(items.get(2).getLastModified()).isEqualTo(Instant.parse("2026-09-22T08:20:00Z"));
            assertThat(ZoomSession.cursorDay(listing.nextCursor())).isEqualTo(NOW.atZone(ZoneOffset.UTC).toLocalDate());
        }
        List<LoopbackApi.Recorded> asked = api.requests("/v2/users/me/recordings");
        assertThat(asked).extracting(r -> r.param("from") + ".." + r.param("to")).containsExactly(
                "2026-08-15..2026-09-13", "2026-08-15..2026-09-13", "2026-09-14..2026-10-13", "2026-10-14..2026-10-14");
        assertThat(asked).allSatisfy(r -> {
            assertThat(r.param("page_size")).isEqualTo("300");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    @DisplayName("a CHANGES pass reads from the last complete pass's day, three days before it, through today")
    void changesPass() {
        defaults();
        try (ConnectorSession s = session(Map.of("days", "365"), user())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-11T03:00:00Z"), "{\"through\":\"2026-10-10\"}"));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"through\":\"2026-10-14\"}");
        }
        assertThat(api.requests("/v2/users/me/recordings")).extracting(r -> r.param("from") + ".." + r.param("to"))
                .containsExactly("2026-10-07..2026-10-14");
        assertThat(api.requests("/v2/users/me/meeting_summaries")).extracting(r -> r.param("from"))
                .containsExactly("2026-10-07T00:00:00Z");
    }

    @Test
    @DisplayName("a Server-to-Server app trades its account ID and Basic client for a token, once for many requests")
    void serverToServerToken() {
        api.on("POST", "/oauth/token").json("{'access_token':'s2s-token','token_type':'bearer','expires_in':3599,'api_url':'https://api.zoom.us'}");
        api.on("GET", "/v2/users/ana%40acme.com").json("{'id':'u-ana','email':'ana@acme.com'}");
        api.on("GET", "/v2/users/ana%40acme.com/recordings").json("{'meetings':[]}");
        api.on("GET", "/v2/users/ana%40acme.com/meeting_summaries").json("{'summaries':[]}");

        try (ConnectorSession s = session(Map.of("users", "ana@acme.com", "days", "40"), serverToServer("secret-1"))) {
            assertThat(s.verifyAccess().ok()).isTrue();
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).as(listing.getErrorMessage()).isFalse();
        }
        List<LoopbackApi.Recorded> grants = api.requests("/oauth/token");
        assertThat(grants).hasSize(1);
        LoopbackApi.Recorded grant = grants.get(0);
        assertThat(grant.header("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("cid-1:secret-1".getBytes(StandardCharsets.UTF_8)));
        assertThat(grant.body()).contains("grant_type=account_credentials").contains("account_id=acc-1");
        assertThat(api.requests("/v2/users/ana%40acme.com/recordings")).allSatisfy(r ->
                assertThat(r.header("Authorization")).isEqualTo("Bearer s2s-token"));
    }

    @Test
    @DisplayName("a Server-to-Server app with nobody named is refused at registration; its refused sign-in says what to check")
    void serverToServerRefusals() {
        try (ConnectorSession s = session(Map.of(), serverToServer("secret-1"))) {
            AccessCheck check = s.verifyAccess();
            assertThat(check.ok()).isFalse();
            assertThat(check.detail()).contains("People");
        }
        api.on("POST", "/oauth/token").status(400).json("{'reason':'Invalid client_id or client_secret','error':'invalid_client'}");
        ConnectionTestResult result = new ZoomConnectionPlugin(api.url("/oauth/authorize"), api.url("/oauth/token"))
                .test(serverToServer("wrong"), ConnectorHttp.direct(waits::add));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Invalid client_id or client_secret").contains("account ID");
    }

    @Test
    @DisplayName("the Test names the person signed in, and a Server-to-Server app with no 'me' is Connected")
    void connectionTest() {
        defaults();
        ZoomConnectionPlugin plugin = new ZoomConnectionPlugin(api.url("/oauth/authorize"), api.url("/oauth/token"));
        ConnectionTestResult result = plugin.test(user(), ConnectorHttp.direct(waits::add));
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("Signed in as Ana Lee (ana@acme.com)");
        assertThat(result.template()).isEqualTo("Signed in as {user}");

        api.reset();
        api.on("POST", "/oauth/token").json("{'access_token':'s2s-token','expires_in':3599}");
        api.on("GET", "/v2/users/me").status(400).json("{'code':1001,'message':'User does not exist: me'}");
        assertThat(plugin.test(serverToServer("secret-2"), ConnectorHttp.direct(waits::add)).message()).isEqualTo("Connected");

        api.reset();
        api.on("GET", "/v2/users/me").status(401).json("{'code':124,'message':'Invalid access token.'}");
        ConnectionTestResult refused = plugin.test(user(), ConnectorHttp.direct(waits::add));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Zoom did not accept the token.");
    }

    @Test
    @DisplayName("a fetch renders the meeting: host, start, duration, participants, summary and the transcript as speaker turns")
    void fetchRenders() throws IOException {
        String uuid = "/ab//c+d==";
        String path = "/v2/meetings/%252Fab%252F%252Fc%252Bd%253D%253D";
        api.on("GET", path + "/recordings").json(recording(uuid, "Pricing review", "2026-10-01T09:00:00Z",
                file("TRANSCRIPT", "2026-10-01T09:45:00Z", "/rec/download/t1")));
        api.on("GET", "/rec/download/t1").text("WEBVTT\n\n1\n00:00:01.120 --> 00:00:04.560\nAna Lee: Good morning, everyone.\n\n"
                + "2\n00:00:04.600 --> 00:00:07.000\nAna Lee: The QUARTZLAMP price goes up in May.\n\n"
                + "3\n00:01:02.000 --> 00:01:05.000\nBo Tran: Agreed.\n");
        api.on("GET", path + "/meeting_summary").json("{'meeting_uuid':'" + uuid + "','summary_content':'## Overview\\nPrices rise in May.'}");
        api.on("GET", "/v2/past_meetings/%252Fab%252F%252Fc%252Bd%253D%253D/participants").json("{'participants':[{'name':'Ana Lee'},{'name':'Bo Tran'},{'name':'Ana Lee'}]}");
        api.on("GET", "/v2/users/u-ana").json("{'id':'u-ana','first_name':'Ana','last_name':'Lee','email':'ana@acme.com'}");

        ItemMetadata item = new ItemMetadata();
        item.setId("meeting:" + uuid);
        String md;
        try (ConnectorSession s = session(Map.of(), user()); InputStream in = s.fetch(item)) {
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(md).startsWith("# Pricing review\n")
                .contains("- Host: Ana Lee (ana@acme.com)")
                .contains("- Started: 2026-10-01T09:00:00Z")
                .contains("- Duration: 45 minutes")
                .contains("- Participants: Ana Lee, Bo Tran")
                .contains("## Summary\n\n## Overview\nPrices rise in May.")
                .contains("[00:00:01] Ana Lee: Good morning, everyone. The QUARTZLAMP price goes up in May.")
                .contains("[00:01:02] Bo Tran: Agreed.");
        assertThat(api.requests("/rec/download/t1").get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("a meeting whose recording and summary are both gone is a gone item")
    void goneMeeting() {
        api.on("GET", "/v2/meetings/uuid-X/recordings").status(404).json("{'code':3301,'message':'This recording does not exist.'}");
        api.on("GET", "/v2/meetings/uuid-X/meeting_summary").status(404).json("{'code':3001,'message':'Meeting does not exist.'}");
        ItemMetadata item = new ItemMetadata();
        item.setId("meeting:uuid-X");
        try (ConnectorSession s = session(Map.of(), user())) {
            assertThatThrownBy(() -> s.fetch(item)).isInstanceOf(ItemGoneException.class);
        }
    }

    @Test
    @DisplayName("a transcript address outside Zoom is never sent the token")
    void downloadStaysOnZoom() {
        api.on("GET", "/v2/meetings/uuid-Y/recordings").json("{'uuid':'uuid-Y','topic':'T','recording_files':["
                + "{'file_type':'TRANSCRIPT','status':'completed','download_url':'https://files.example.net/t.vtt'}]}");
        ItemMetadata item = new ItemMetadata();
        item.setId("meeting:uuid-Y");
        try (ConnectorSession s = session(Map.of(), user())) {
            assertThatThrownBy(() -> s.fetch(item)).isInstanceOf(ConnectorException.class).hasMessageContaining("files.example.net");
        }
        String base = "https://api.zoom.us/v2";
        assertThat(ZoomApi.mayCarryToken(URI.create("https://us06web.zoom.us/rec/download/x"), base)).isTrue();
        assertThat(ZoomApi.mayCarryToken(URI.create("https://zoom.us/rec/download/x"), base)).isTrue();
        assertThat(ZoomApi.mayCarryToken(URI.create("http://zoom.us/rec/download/x"), base)).isFalse();
        assertThat(ZoomApi.mayCarryToken(URI.create("https://zoom.us.example.net/x"), base)).isFalse();
    }

    @Test
    @DisplayName("a refused token stops the listing with the sentence that says to sign in again")
    void unauthorized() {
        api.on("GET", "/v2/users/me").status(401).json("{'code':124,'message':'Invalid access token.'}");
        try (ConnectorSession s = session(Map.of(), user())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).contains("Zoom refused the connection's credential");
        }
    }

    @Test
    @DisplayName("a per-second 429 is waited out by its Retry-After; a spent daily limit is reported for the next sync")
    void rateLimits() {
        api.on("GET", "/v2/users/me/recordings").status(429).header("Retry-After", "2")
                .json("{'code':429,'message':'You have reached the maximum per-second rate limit for this API. Try again later.'}")
                .then().json("{'meetings':[]}");
        defaults();
        try (ConnectorSession s = session(Map.of("days", "10"), user())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).as(listing.getErrorMessage()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(2));

        api.reset();
        api.on("GET", "/v2/users/me/recordings").status(429).header("X-RateLimit-Type", "Daily-limit")
                .json("{'code':429,'message':'You have reached the maximum daily rate limit for this API.'}");
        defaults();
        try (ConnectorSession s = session(Map.of("days", "10"), user())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Zoom is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("summaries the app may not read leave transcripts listed and the listing partial, so nothing indexed is removed")
    void summariesRefused() {
        api.on("GET", "/v2/users/me/recordings").query("from", "2026-10-04")
                .json("{'meetings':[" + recording("uuid-A", "Pricing review", "2026-10-05T09:00:00Z",
                        file("TRANSCRIPT", "2026-10-05T09:45:00Z", "/rec/download/a")) + "]}");
        api.on("GET", "/v2/users/me/meeting_summaries").status(400)
                .json("{'code':4711,'message':'Invalid access token, does not contain scopes:[meeting:read:list_summaries].'}");
        defaults();
        try (ConnectorSession s = session(Map.of("days", "10"), user())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("meeting:uuid-A");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).isEqualTo(ZoomSession.SUMMARIES_REFUSED);
        }
        assertThat(api.requests("/v2/users/me/meeting_summaries")).hasSize(1);
    }

    @Test
    @DisplayName("everyone on the account: the users list is paged, and a meeting two people share is listed once")
    void everyone() {
        api.on("POST", "/oauth/token").json("{'access_token':'s2s-token','expires_in':3599}");
        api.on("GET", "/v2/users").json("{'users':[{'id':'u-ana','first_name':'Ana','last_name':'Lee','email':'ana@acme.com'}],"
                + "'next_page_token':'n2'}");
        api.on("GET", "/v2/users").query("next_page_token", "n2")
                .json("{'users':[{'id':'u-bo','first_name':'Bo','last_name':'Tran','email':'bo@acme.com'}]}");
        String shared = recording("uuid-S", "Board", "2026-10-10T09:00:00Z", file("TRANSCRIPT", "2026-10-10T10:00:00Z", "/rec/s"));
        for (String u : List.of("u-ana", "u-bo")) {
            api.on("GET", "/v2/users/" + u + "/recordings").json("{'meetings':[" + shared + "]}");
            api.on("GET", "/v2/users/" + u + "/meeting_summaries").json("{'summaries':[]}");
        }
        try (ConnectorSession s = session(Map.of("users", "*", "days", "10"), serverToServer("secret-3"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(listing.hasErrored()).as(listing.getErrorMessage()).isFalse();
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("meeting:uuid-S");
            assertThat(items.get(0).getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee (ana@acme.com)");
        }
        assertThat(api.requests("/v2/users")).extracting(r -> r.param("status")).containsOnly("active");
    }

    @Test
    @DisplayName("a VTT transcript: header, numbers and tags dropped, one speaker's cues joined, unattributed lines kept")
    void transcriptRendering() {
        String vtt = "\uFEFFWEBVTT\r\n\r\nNOTE made by Zoom\r\n\r\n1\r\n00:00:01.000 --> 00:00:02.000 align:start\r\n<v Ana Lee>Hello</v>\r\n\r\n"
                + "2\r\n00:00:02.500 --> 00:00:03.000\r\nAna Lee: again\r\n\r\n3\r\n01:02:03.000 --> 01:02:04.000\r\n[music]\r\n";
        assertThat(ZoomTranscript.render(vtt)).isEqualTo("[00:00:01] Ana Lee: Hello again\n\n[01:02:03] [music]");
        assertThat(ZoomTranscript.render("")).isEmpty();
    }

    @Test
    @DisplayName("a meeting's time moves forward whichever part arrives second")
    void changedTime() {
        Instant t = Instant.parse("2026-10-01T10:00:00Z");
        Instant s = Instant.parse("2026-10-01T09:00:00Z");
        assertThat(ZoomSession.changed(t, null, null)).isEqualTo(t);
        assertThat(ZoomSession.changed(null, s, null)).isEqualTo(s);
        assertThat(ZoomSession.changed(t, s, null)).isAfter(t).isAfter(s);
    }
}
