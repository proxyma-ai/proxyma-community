package ai.proxyma.connectors.box;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
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
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.spi.TokenSupplier;
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
 * Box against a loopback Box API: the folder walk with its marker pages and the stream position
 * taken first, the event stream since a position and one Box no longer accepts, downloads through
 * Box's redirect, Box Notes as text, what is gone, every refusal as a sentence - and a sign-in whose
 * refresh token rotates.
 */
class BoxConnectorTest {

    private static final String TOKEN = "box-test-token";
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final BoxConnectorPlugin plugin = new BoxConnectorPlugin();
    private final BoxConnectionPlugin connection = new BoxConnectionPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, null, true);
    }

    private ConnectorSession session(Map<String, Object> fields, String fileTypes, boolean recursive) {
        Map<String, String> config = ConfigValidator.validate(BoxConnectorPlugin.DESCRIPTOR.fields(), fields);
        ResolvedCredentials credentials = ResolvedCredentials.token(api.baseUrl(), TOKEN);
        return plugin.open(new SourceContext(null, ConnectorType.BOX, null, null, config, null, null,
                credentials, new SourceScope(fileTypes, recursive, null), ConnectorHttp.direct(waits::add),
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    // A path from All Files down, as Box gives it: "0:All Files", "11:Handbook", ...
    private static String path(String... folders) {
        StringBuilder out = new StringBuilder("{'total_count':" + folders.length + ",'entries':[");
        for (int i = 0; i < folders.length; i++) {
            String[] f = folders[i].split(":");
            if (i > 0) out.append(',');
            out.append("{'type':'folder','id':'").append(f[0]).append("','name':'").append(f[1]).append("'}");
        }
        return out.append("]}").toString();
    }

    private static String file(String id, String name, String path, String extra) {
        int dot = name.lastIndexOf('.');
        return "{'type':'file','id':'" + id + "','name':'" + name + "','extension':'" + (dot < 0 ? "" : name.substring(dot + 1))
                + "','size':2048,'modified_at':'2026-10-01T10:00:00-07:00','sha1':'sha-" + id + "','item_status':'active',"
                + "'modified_by':{'type':'user','id':'9','name':'Ana Lee','login':'ana@example.com'},"
                + "'path_collection':" + path + (extra == null ? "" : "," + extra) + "}";
    }

    private static String folder(String id, String name) {
        return "{'type':'folder','id':'" + id + "','name':'" + name + "'}";
    }

    private void handbook() {
        api.on("GET", "/events").query("stream_position", "now").json("{'chunk_size':0,'next_stream_position':'5000','entries':[]}");
        String in = path("0:All Files", "11:Handbook");
        api.on("GET", "/folders/11/items").json("{'next_marker':'m2','entries':["
                + folder("12", "Policies") + "," + file("101", "Welcome.docx", in, null) + ","
                + "{'type':'web_link','id':'77','name':'Intranet'}]}");
        api.on("GET", "/folders/11/items").query("marker", "m2").json("{'next_marker':null,'entries':["
                + file("102", "photo.png", in, null) + "," + file("103", "Standup.boxnote", in, null) + "]}");
        api.on("GET", "/folders/12/items").json("{'entries':["
                + file("104", "Leave.pdf", path("0:All Files", "11:Handbook", "12:Policies"), null) + "]}");
    }

    @Test
    @DisplayName("a full listing takes the stream position first, walks every folder's marker pages, and leaves out what it does not index")
    void fullListing() {
        handbook();
        try (ConnectorSession s = session(Map.of("folder", "https://app.box.com/folder/11"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly("file:101", "file:103", "file:104");
            assertThat(items).extracting(ItemMetadata::getContentType).containsExactly(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/markdown", "application/pdf");
            ItemMetadata welcome = items.get(0);
            assertThat(welcome.getTitle()).isEqualTo("Welcome.docx");
            assertThat(welcome.getLastModified()).isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
            assertThat(welcome.getSizeBytes()).isEqualTo(2048L);
            assertThat(welcome.getExtra("url")).isEqualTo("https://app.box.com/file/101");
            assertThat(welcome.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee");
            assertThat(welcome.getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo("sha-101");
            assertThat(items.get(1).getExtra("url")).isEqualTo("https://app.box.com/notes/103");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("5000");
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        // The position is asked for before the first page, so a change made during the walk is not lost.
        assertThat(api.requests().get(0).path()).isEqualTo("/events");
        assertThat(api.requests().get(0).param("stream_type")).isEqualTo("changes");
        LoopbackApi.Recorded first = api.requests("/folders/11/items").get(0);
        assertThat(first.param("usemarker")).isEqualTo("true");
        assertThat(first.param("limit")).isEqualTo("1000");
        assertThat(first.param("fields")).contains("sha1", "path_collection", "modified_by");
    }

    @Test
    @DisplayName("the file-type filter counts a Box Note as .md, and a source that does not descend keeps to its own folder")
    void filterAndSubfolders() {
        handbook();
        try (ConnectorSession s = session(Map.of("folder", "11"), "pdf", true)) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("file:104");
        }
        try (ConnectorSession s = session(Map.of("folder", "11"), "md,docx,pdf", false)) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("file:101", "file:103");
        }
        assertThat(api.requests("/folders/12/items")).hasSize(1);
    }

    @Test
    @DisplayName("a changes pass reads the event stream from the stored position, once per file, as its last event left it")
    void changesPass() {
        String in = path("0:All Files", "11:Handbook");
        String out = path("0:All Files", "90:Elsewhere");
        api.on("GET", "/events").query("stream_position", "5000").json("{'chunk_size':4,'next_stream_position':'5004','entries':["
                + "{'event_id':'e1','event_type':'ITEM_UPLOAD','source':" + file("201", "Plan.pdf", in, null) + "},"
                + "{'event_id':'e2','event_type':'ITEM_TRASH','source':" + file("101", "Welcome.docx",
                        path("1:Trash"), "'item_status':'trashed'") + "},"
                + "{'event_id':'e3','event_type':'ITEM_MOVE','source':" + file("104", "Leave.pdf", out, null) + "},"
                + "{'event_id':'e1','event_type':'ITEM_UPLOAD','source':" + file("201", "Plan.pdf", in, null) + "}]}");
        api.on("GET", "/events").query("stream_position", "5004").json("{'chunk_size':3,'next_stream_position':'5007','entries':["
                + "{'event_id':'e4','event_type':'ITEM_MOVE','source':{'type':'folder','id':'13','name':'Forms',"
                + "'item_status':'active','path_collection':" + in + "}},"
                + "{'event_id':'e5','event_type':'ITEM_RENAME','source':{'type':'file','id':'202','name':'Notes.txt'}},"
                + "{'event_id':'e6','event_type':'ITEM_PREVIEW','source':" + file("203", "Elsewhere.pdf", out, null) + "}]}");
        api.on("GET", "/events").query("stream_position", "5007").json("{'chunk_size':0,'next_stream_position':'5007','entries':[]}");
        // An event without the file's place in the tree is asked about once more.
        api.on("GET", "/files/202").json(file("202", "Notes.txt", in, null));
        api.on("GET", "/folders/13/items").json("{'entries':[" + file("205", "Expense.pdf", path("0:All Files", "11:Handbook", "13:Forms"), null)
                + "," + file("201", "Plan.pdf", in, null) + "]}");
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-13T08:00:00Z"), "5000"));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("file:201", "file:101", "file:104", "file:202", "file:203", "file:205");
            assertThat(items.get(0).getContentType()).isEqualTo("application/pdf");
            // Trashed, moved out, or outside the folder altogether: gone, dated now, so the fetch finds it so.
            assertThat(items.get(1).getContentType()).isNull();
            assertThat(items.get(1).getLastModified()).isEqualTo(NOW);
            assertThat(items.get(2).getContentType()).isNull();
            assertThat(items.get(4).getContentType()).isNull();
            assertThat(items.get(3).getContentType()).isEqualTo("text/plain");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("5007");
        }
        assertThat(api.requests("/events")).allSatisfy(r -> assertThat(r.param("stream_type")).isEqualTo("changes"));
        assertThat(api.requests("/events").get(0).param("limit")).isEqualTo("500");
    }

    @Test
    @DisplayName("a stream position Box no longer accepts lists the source in full, with a fresh position")
    void expiredPosition() {
        api.on("GET", "/events").query("stream_position", "17").status(400)
                .json("{'type':'error','status':400,'code':'bad_request','message':'Invalid stream position'}");
        handbook();
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-13T08:00:00Z"), "17"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:101", "file:103", "file:104");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("5000");
        }
        // A cursor that is not a position at all is a full listing too.
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            assertThat(drain(s.list(ListRequest.changes(Instant.parse("2026-10-13T08:00:00Z"), "{\"user\":\"1\"}")))).hasSize(3);
        }
    }

    @Test
    @DisplayName("the source's folder going is a failed listing; a subfolder going during the walk is skipped")
    void missingFolders() {
        api.on("GET", "/events").query("stream_position", "now").json("{'next_stream_position':'5000','entries':[]}");
        try (ConnectorSession s = session(Map.of("folder", "66"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Box has no folder 66, or the connection's account cannot see it.");
        }
        api.on("GET", "/folders/11/items").json("{'entries':[" + folder("12", "Gone") + ","
                + file("101", "Welcome.docx", path("0:All Files", "11:Handbook"), null) + "]}");
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:101");
            assertThat(listing.hasErrored()).isFalse();
        }
    }

    @Test
    @DisplayName("a file is downloaded through Box's redirect, which is followed without the token")
    void fetching() throws IOException {
        String in = path("0:All Files", "11:Handbook");
        api.on("GET", "/files/101").json(file("101", "Welcome.docx", in, null));
        api.on("GET", "/files/101/content").status(302)
                .header("Location", "http://localhost:" + api.baseUrl().substring(api.baseUrl().lastIndexOf(':') + 1) + "/d/1/signed");
        api.on("GET", "/d/1/signed").body(new byte[]{'P', 'K', 3, 4}, "application/octet-stream");
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            assertThat(text(s.fetch(item("file:101")))).isEqualTo("PK\u0003\u0004");
        }
        assertThat(api.requests("/files/101/content").get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(api.requests("/d/1/signed").get(0).header("Authorization")).isNull();
    }

    @Test
    @DisplayName("a Box Note is read as markdown, in the current editor's shape and the older one's")
    void boxNotes() throws IOException {
        String in = path("0:All Files", "11:Handbook");
        api.on("GET", "/files/103").json(file("103", "Standup.boxnote", in, null));
        api.on("GET", "/files/103/content").rawJson("{\"version\":1,\"doc\":{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"Standup\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Ship the \"},"
                + "{\"type\":\"text\",\"marks\":[{\"type\":\"strong\"}],\"text\":\"beta\"}]},"
                + "{\"type\":\"bullet_list\",\"content\":[{\"type\":\"list_item\",\"content\":[{\"type\":\"paragraph\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"one\"}]}]},{\"type\":\"list_item\",\"content\":"
                + "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"two\"}]}]}]},"
                + "{\"type\":\"table\",\"content\":[{\"type\":\"table_row\",\"content\":[{\"type\":\"table_cell\",\"content\":"
                + "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}]},{\"type\":\"table_cell\","
                + "\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"b\"}]}]}]}]}]}}");
        api.on("GET", "/files/106").json(file("106", "Old.boxnote", in, null));
        api.on("GET", "/files/106/content").rawJson("{\"atext\":{\"text\":\"Old note text\\n\",\"attribs\":\"*0+d\"}}");
        api.on("GET", "/files/107").json(file("107", "Broken.boxnote", in, null));
        api.on("GET", "/files/107/content").text("not json");
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            assertThat(text(s.fetch(item("file:103")))).isEqualTo("## Standup\n\nShip the beta\n\n- one\n- two\n\n| a | b |\n");
            assertThat(text(s.fetch(item("file:106")))).isEqualTo("Old note text\n");
            assertThatThrownBy(() -> s.fetch(item("file:107"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("Box did not give this Box Note in a form Proxyma reads, so it was not indexed.");
        }
    }

    @Test
    @DisplayName("a deleted, trashed or moved-out file is gone at its fetch; one Box has not finished taking in is said so")
    void goneItems() {
        api.on("GET", "/files/404").status(404).json("{'type':'error','status':404,'code':'not_found'}");
        api.on("GET", "/files/105").json(file("105", "Old.pdf", path("1:Trash"), "'item_status':'trashed'"));
        api.on("GET", "/files/106").json(file("106", "Moved.pdf", path("0:All Files", "90:Elsewhere"), null));
        api.on("GET", "/files/107").json(file("107", "Fresh.pdf", path("0:All Files", "11:Handbook"), null));
        api.on("GET", "/files/107/content").status(202).header("Retry-After", "5");
        api.on("GET", "/files/108").json(file("108", "Huge.pdf", path("0:All Files", "11:Handbook"), null)
                .replace("'size':2048", "'size':" + (200L * 1024 * 1024)));
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            assertThatThrownBy(() -> s.fetch(item("file:404"))).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Box no longer has 404.");
            assertThatThrownBy(() -> s.fetch(item("file:105"))).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Box has moved Old.pdf to the trash.");
            assertThatThrownBy(() -> s.fetch(item("file:106"))).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Moved.pdf is no longer in this Box folder.");
            assertThatThrownBy(() -> s.fetch(item("file:107"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("Box is still preparing this file. The next sync will try again.");
            assertThatThrownBy(() -> s.fetch(item("file:108"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("This file is larger than 100 MB, so it was not indexed.");
            assertThatThrownBy(() -> s.fetch(item("issue:1"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("That is not an item of Box.");
        }
        assertThat(api.requests("/files/108/content")).isEmpty();
    }

    @Test
    @DisplayName("registration probes the folder, and a refusal says what to do")
    void accessProbe() {
        api.on("GET", "/folders/11").json("{'type':'folder','id':'11','name':'Handbook'}");
        api.on("GET", "/folders/0").json("{'type':'folder','id':'0','name':'All Files'}");
        try (ConnectorSession s = session(Map.of("folder", "https://acme.app.box.com/folder/11"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("folder", "Handbook"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("That is not a Box folder ID or link.");
        }
        try (ConnectorSession s = session(Map.of("folder", "66"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Box has no folder 66, or the connection's account cannot see it.");
        }
        api.reset();
        api.on("GET", "/folders/11").status(401);
        try (ConnectorSession s = session(Map.of("folder", "11"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Box did not accept the connection's sign-in. Sign in again, "
                    + "or replace the developer token, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("a 429 is waited out for as long as Retry-After says, and reported as rate limited when it is too long to wait")
    void rateLimits() {
        api.on("GET", "/events").query("stream_position", "now").json("{'next_stream_position':'5000','entries':[]}");
        api.on("GET", "/folders/0/items").status(429).header("Retry-After", "2")
                .json("{'type':'error','status':429,'code':'rate_limit_exceeded'}").then().json("{'entries':[]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));

        api.reset();
        api.on("GET", "/events").query("stream_position", "now").json("{'next_stream_position':'5000','entries':[]}");
        api.on("GET", "/folders/0/items").status(429).header("Retry-After", "3600");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Box is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("Test names the user and their login; a refused token says so")
    void connectionTest() {
        api.on("GET", "/users/me").json("{'type':'user','id':'9','name':'Ana Lee','login':'ana@example.com'}");
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Lee (ana@example.com)");
        assertThat(ok.template()).isEqualTo("Signed in as {user} ({account})");
        assertThat(api.last().param("fields")).isEqualTo("name,login");

        api.reset();
        api.on("GET", "/users/me").status(401);
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Box did not accept the token.");
    }

    @Test
    @DisplayName("a sign-in whose refresh token rotates: each refused access token is refreshed once, with the newest refresh token")
    void rotatingRefreshToken() {
        // Box's refresh tokens are single use - each refresh spends one and hands back the next. The
        // framework (OAuth2ConnectionService, see its refreshWithRotation test) stores the new one; a
        // session only has to ask again after a 401 and send what it is given.
        api.on("GET", "/users/me").status(401).then().json("{'name':'Ana Lee','login':'ana@example.com'}");
        List<String> spent = new ArrayList<>();
        TokenSupplier tokens = new TokenSupplier() {
            private String access = "at-1";
            private String refresh = "rt-1";
            private int issued = 1;
            @Override public String accessToken() { return access; }
            @Override public void invalidate() {
                spent.add(refresh);
                issued++;
                access = "at-" + issued;
                refresh = "rt-" + issued;
            }
        };
        ResolvedCredentials signedIn = new ResolvedCredentials(null, null, AuthKind.OAUTH2_CODE, api.baseUrl(), null,
                Map.of(), Map.of(), tokens);
        assertThat(connection.test(signedIn, ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lee (ana@example.com)");

        api.reset();
        api.on("GET", "/users/me").status(401).then().json("{'name':'Ana Lee','login':'ana@example.com'}");
        connection.test(signedIn, ConnectorHttp.direct());
        assertThat(spent).containsExactly("rt-1", "rt-2");
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer at-2", "Bearer at-3");
    }

    @Test
    @DisplayName("the sign-in: Box's authorize and token addresses, read-only, client in the form body")
    void oauthSpec() {
        OAuth2Spec spec = connection.oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://account.box.com/api/oauth2/authorize");
        assertThat(spec.tokenUrl()).isEqualTo("https://api.box.com/oauth2/token");
        assertThat(spec.scopes()).containsExactly("root_readonly");
        assertThat(spec.writeScopes()).isEmpty();
        assertThat(spec.clientAuth()).isEqualTo(OAuth2Spec.ClientAuth.POST);
        assertThat(new BoxConnectionPlugin(api.url("/oauth2/authorize"), api.url("/oauth2/token")).oauth2().tokenUrl())
                .isEqualTo(api.url("/oauth2/token"));

        var d = BoxConnectionPlugin.DESCRIPTOR;
        assertThat(d.authMethods()).extracting(AuthMethod::kind)
                .containsExactly(AuthKind.OAUTH2_CODE, AuthKind.OAUTH2_CLIENT, AuthKind.TOKEN);
        // The keys OAuth2ConnectionService reads - a renamed field would sign nothing in.
        assertThat(d.auth(AuthKind.OAUTH2_CODE).fields()).extracting(FieldSpec::key).containsExactly("clientId", "clientSecret");
        assertThat(d.auth(AuthKind.TOKEN).secretFields()).extracting(FieldSpec::key).containsExactly("token");
        // Client credentials: Box's grant names whose token it is, from the connection's settings,
        // and takes no scope.
        assertThat(d.auth(AuthKind.OAUTH2_CLIENT).fields()).extracting(FieldSpec::key)
                .containsExactly("clientId", "clientSecret", "subjectType", "subjectId");
        assertThat(spec.clientGrantParams()).containsExactly(Map.entry("box_subject_type", "subjectType"),
                Map.entry("box_subject_id", "subjectId"));
        assertThat(spec.clientGrantScopes()).isFalse();
        assertThat(d.defaultBaseUrl()).isEqualTo("https://api.box.com/2.0");
        assertThat(d.supportsWrite()).isFalse();
    }

    @Test
    @DisplayName("the descriptor: identity is the folder's ID, a link reads as its ID, and nothing chosen is All Files")
    void descriptor() {
        Map<String, String> all = ConfigValidator.validate(BoxConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("folder:0");
        assertThat(plugin.displayName(all)).isEqualTo("All of Box");
        assertThat(plugin.webUrl(all, null)).isEqualTo("https://app.box.com/folder/0");

        Map<String, String> link = ConfigValidator.validate(BoxConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("folder", "https://acme.app.box.com/folder/123456?s=abc"));
        assertThat(plugin.identity(link)).isEqualTo("folder:123456");
        assertThat(plugin.displayName(link)).isEqualTo("Box folder 123456");
        assertThat(plugin.webUrl(link, null)).isEqualTo("https://app.box.com/folder/123456");

        var caps = BoxConnectorPlugin.DESCRIPTOR.capabilities();
        assertThat(caps.shareable()).isFalse();
        assertThat(caps.fileTypeFilter()).isTrue();
        assertThat(caps.recursiveScope()).isTrue();
        assertThat(caps.incremental()).isTrue();
        assertThat(BoxConnectorPlugin.DESCRIPTOR.protocol()).isEqualTo(BoxConnectionPlugin.DESCRIPTOR.protocol());
    }

    private static ItemMetadata item(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        return item;
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
