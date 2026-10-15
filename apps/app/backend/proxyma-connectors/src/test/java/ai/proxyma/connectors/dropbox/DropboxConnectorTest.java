package ai.proxyma.connectors.dropbox;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Dropbox against a loopback Dropbox API: list_folder and its continue pages with the cursor, the
 * team space's path root, what changed since a cursor and a cursor Dropbox reset, Paper exports,
 * downloads with an ASCII Dropbox-API-Arg, what is gone, and every refusal as a sentence.
 */
class DropboxConnectorTest {

    private static final String TOKEN = "sl.test-token";
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final DropboxConnectorPlugin plugin = new DropboxConnectorPlugin();
    private final DropboxConnectionPlugin connection = new DropboxConnectionPlugin();

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
        Map<String, String> config = ConfigValidator.validate(DropboxConnectorPlugin.DESCRIPTOR.fields(), fields);
        ResolvedCredentials credentials = ResolvedCredentials.token(api.baseUrl(), TOKEN);
        return plugin.open(new SourceContext(null, ConnectorType.DROPBOX, null, null, config, null, null,
                credentials, new SourceScope(fileTypes, recursive, null), ConnectorHttp.direct(waits::add),
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String file(String path, String extra) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "{'.tag':'file','name':'" + name + "','id':'id:" + name.hashCode() + "','path_lower':'" + path.toLowerCase()
                + "','path_display':'" + path + "','server_modified':'2026-10-01T10:00:00Z','size':2048,"
                + "'content_hash':'hash-" + name.length() + "'" + (extra == null ? "" : "," + extra) + "}";
    }

    private static String folder(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "{'.tag':'folder','name':'" + name + "','id':'id:f" + name.length() + "','path_lower':'" + path.toLowerCase()
                + "','path_display':'" + path + "'}";
    }

    private void personalAccount() {
        api.on("POST", "/2/users/get_current_account").json("{'account_id':'dbid:1','name':{'display_name':'Ana Lee'},"
                + "'email':'ana@example.com','root_info':{'.tag':'user','root_namespace_id':'100','home_namespace_id':'100'}}");
    }

    private static JsonNode body(LoopbackApi.Recorded r) throws IOException {
        return JSON.readTree(r.body());
    }

    private void twoPages() {
        api.on("POST", "/2/files/list_folder").json("{'cursor':'c1','has_more':true,'entries':["
                + folder("/Projects") + "," + file("/Projects/Plan.docx", null) + "," + file("/Projects/movie.mp4", null) + "]}");
        api.on("POST", "/2/files/list_folder/continue").json("{'cursor':'c2','has_more':false,'entries':["
                + file("/Projects/Notes.md", null) + ","
                + file("/Projects/Roadmap.paper", "'is_downloadable':false,'export_info':{'export_as':'html','export_options':['html','markdown']}") + ","
                + file("/Projects/Sheet.gsheet", "'is_downloadable':false") + "]}");
    }

    @Test
    @DisplayName("a full listing reads every page, keeps the last page's cursor, and leaves out folders and what it does not index")
    void fullListing() throws IOException {
        personalAccount();
        twoPages();
        try (ConnectorSession s = session(Map.of("path", "/Projects"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("file:/projects/plan.docx", "file:/projects/notes.md", "file:/projects/roadmap.paper");
            assertThat(items).extracting(ItemMetadata::getContentType).containsExactly(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/markdown", "text/markdown");
            ItemMetadata plan = items.get(0);
            assertThat(plan.getTitle()).isEqualTo("Plan.docx");
            assertThat(plan.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(plan.getSizeBytes()).isEqualTo(2048L);
            assertThat(plan.getExtra("url")).isEqualTo("https://www.dropbox.com/home/Projects?preview=Plan.docx");
            assertThat(plan.getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo("hash-9");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("c2");
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        // A personal account's paths need no root of their own.
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Dropbox-API-Path-Root")).isNull());
        JsonNode first = body(api.requests("/2/files/list_folder").get(0));
        assertThat(first.path("path").asText()).isEqualTo("/Projects");
        assertThat(first.path("recursive").asBoolean()).isTrue();
        assertThat(first.path("include_non_downloadable_files").asBoolean()).isTrue();
        assertThat(body(api.requests("/2/files/list_folder/continue").get(0)).path("cursor").asText()).isEqualTo("c1");
    }

    @Test
    @DisplayName("the file-type filter counts a Paper doc as .md, and a source that does not descend keeps to its own folder")
    void filterAndSubfolders() {
        personalAccount();
        api.on("POST", "/2/files/list_folder").json("{'cursor':'c1','has_more':false,'entries':["
                + file("/a.pdf", null) + "," + file("/b.md", null) + "," + file("/Sub/c.pdf", null) + ","
                + file("/Doc.paper", "'is_downloadable':false,'export_info':{'export_as':'markdown'}") + "]}");
        try (ConnectorSession s = session(Map.of(), "pdf", true)) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("file:/a.pdf", "file:/sub/c.pdf");
        }
        try (ConnectorSession s = session(Map.of(), "md,pdf", false)) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("file:/a.pdf", "file:/b.md", "file:/doc.paper");
        }
        assertThat(api.requests("/2/files/list_folder").get(1).body()).contains("\"recursive\":false").contains("\"path\":\"\"");
    }

    @Test
    @DisplayName("a team space roots every request at the team's namespace, so its folders are listed too")
    void teamSpace() throws IOException {
        api.on("POST", "/2/users/get_current_account").json("{'name':{'display_name':'Ana Lee'},'email':'ana@example.com',"
                + "'root_info':{'.tag':'team','root_namespace_id':'7','home_namespace_id':'1','home_path':'/Ana Lee'}}");
        api.on("POST", "/2/files/list_folder").json("{'cursor':'c1','has_more':false,'entries':["
                + file("/Marketing/Brief.pdf", null) + "," + file("/Ana Lee/Draft.txt", null) + "]}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("file:/marketing/brief.pdf", "file:/ana lee/draft.txt");
        }
        LoopbackApi.Recorded listed = api.requests("/2/files/list_folder").get(0);
        assertThat(JSON.readTree(listed.header("Dropbox-API-Path-Root")))
                .isEqualTo(JSON.readTree("{\".tag\":\"root\",\"root\":\"7\"}"));
        // Asking who the account is is the one request that is not rooted.
        assertThat(api.requests("/2/users/get_current_account").get(0).header("Dropbox-API-Path-Root")).isNull();
    }

    @Test
    @DisplayName("a changes pass continues from the cursor: changed files are items, deleted paths are gone, folders are nothing")
    void changesPass() throws IOException {
        personalAccount();
        api.on("POST", "/2/files/list_folder/continue").json("{'cursor':'c3','has_more':true,'entries':["
                + file("/Projects/Plan.docx", null) + ","
                + "{'.tag':'deleted','name':'Old.pdf','path_lower':'/projects/old.pdf','path_display':'/Projects/Old.pdf'},"
                + folder("/Projects/New") + "]}")
                .then().json("{'cursor':'c4','has_more':false,'entries':["
                        + "{'.tag':'deleted','name':'Elsewhere.pdf','path_lower':'/other/elsewhere.pdf'}]}");
        try (ConnectorSession s = session(Map.of("path", "/Projects"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-13T08:00:00Z"), "c2"));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("file:/projects/plan.docx", "file:/projects/old.pdf");
            // A deleted path is dated now, so the engine fetches it and finds it gone.
            assertThat(items.get(1).getLastModified()).isEqualTo(NOW);
            assertThat(items.get(1).getContentType()).isNull();
            assertThat(listing.nextCursor()).isEqualTo("c4");
        }
        assertThat(api.requests("/2/files/list_folder")).isEmpty();
        List<LoopbackApi.Recorded> continued = api.requests("/2/files/list_folder/continue");
        assertThat(body(continued.get(0)).path("cursor").asText()).isEqualTo("c2");
        assertThat(body(continued.get(1)).path("cursor").asText()).isEqualTo("c3");
    }

    @Test
    @DisplayName("a cursor Dropbox has reset lists the folder in full, with a fresh cursor")
    void resetCursor() {
        personalAccount();
        api.on("POST", "/2/files/list_folder/continue").status(409).json("{'error_summary':'reset/..','error':{'.tag':'reset'}}")
                .then().json("{'cursor':'c2','has_more':false,'entries':[" + file("/Projects/Notes.md", null) + "]}");
        api.on("POST", "/2/files/list_folder").json("{'cursor':'c1','has_more':true,'entries':["
                + file("/Projects/Plan.docx", null) + "]}");
        try (ConnectorSession s = session(Map.of("path", "/Projects"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-13T08:00:00Z"), "stale"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId)
                    .containsExactly("file:/projects/plan.docx", "file:/projects/notes.md");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("c2");
        }
    }

    @Test
    @DisplayName("a folder that is not there, or is a file, stops the listing with a sentence")
    void missingFolder() {
        personalAccount();
        api.on("POST", "/2/files/list_folder").status(409)
                .json("{'error_summary':'path/not_found/..','error':{'.tag':'path','path':{'.tag':'not_found'}}}");
        try (ConnectorSession s = session(Map.of("path", "/Gone"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Dropbox has no folder /Gone, or the connection's account cannot see it.");
        }
    }

    @Test
    @DisplayName("a file is downloaded by its id with an ASCII Dropbox-API-Arg, and a Paper doc is exported as markdown")
    void fetching() throws IOException {
        personalAccount();
        api.on("POST", "/2/files/get_metadata").json(file("/Projects/Plan.pdf", "'id':'id:abc'"))
                .then().json(file("/Projects/Roadmap.paper", "'id':'id:pap','is_downloadable':false,"
                        + "'export_info':{'export_as':'html','export_options':['html','markdown']}"))
                .then().json(file("/Projects/K\\u1ebf ho\\u1ea1ch.txt", "'id':''"));
        api.on("GET", "/2/files/download").body(new byte[]{'%', 'P', 'D', 'F'}, "application/octet-stream");
        api.on("GET", "/2/files/export").text("# Roadmap\n\nShip it.");

        try (ConnectorSession s = session(Map.of("path", "/Projects"))) {
            assertThat(text(s.fetch(item("file:/projects/plan.pdf")))).isEqualTo("%PDF");
            LoopbackApi.Recorded download = api.last();
            assertThat(download.header("Dropbox-API-Arg")).isEqualTo("{\"path\":\"id:abc\"}");
            assertThat(download.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(download.header("Accept")).isEqualTo("*/*");
            assertThat(body(api.requests("/2/files/get_metadata").get(0)).path("path").asText()).isEqualTo("/projects/plan.pdf");

            assertThat(text(s.fetch(item("file:/projects/roadmap.paper")))).isEqualTo("# Roadmap\n\nShip it.");
            assertThat(JSON.readTree(api.last().header("Dropbox-API-Arg")))
                    .isEqualTo(JSON.readTree("{\"path\":\"id:pap\",\"export_format\":\"markdown\"}"));

            // A path in the header is ASCII, every other character escaped, as Dropbox requires.
            text(s.fetch(item("file:/projects/k\u1ebf ho\u1ea1ch.txt")));
            assertThat(api.last().header("Dropbox-API-Arg")).isEqualTo("{\"path\":\"/projects/k\\u1ebf ho\\u1ea1ch.txt\"}");
        }
        assertThat(DropboxApi.headerJson(Map.of("path", "/a\u007f.pdf"))).isEqualTo("{\"path\":\"/a\\u007f.pdf\"}");
    }

    @Test
    @DisplayName("a deleted file, a folder, and a file moved out of the source are gone at their fetch; a huge one is refused")
    void goneItems() {
        personalAccount();
        api.on("POST", "/2/files/get_metadata").status(409)
                .json("{'error_summary':'path/not_found/...','error':{'.tag':'path','path':{'.tag':'not_found'}}}")
                .then().json(folder("/Projects/Sub"))
                .then().json(file("/Elsewhere/Plan.docx", null))
                .then().json(file("/Projects/Huge.pdf", "'size':" + (200L * 1024 * 1024)));
        try (ConnectorSession s = session(Map.of("path", "/Projects"))) {
            assertThatThrownBy(() -> s.fetch(item("file:/projects/gone.pdf"))).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Dropbox no longer has /projects/gone.pdf.");
            assertThatThrownBy(() -> s.fetch(item("file:/projects/sub"))).isInstanceOf(ItemGoneException.class);
            assertThatThrownBy(() -> s.fetch(item("file:/projects/plan.docx"))).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Plan.docx is no longer in this Dropbox folder.");
            assertThatThrownBy(() -> s.fetch(item("file:/projects/huge.pdf"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("This file is larger than 100 MB, so it was not indexed.");
            assertThatThrownBy(() -> s.fetch(item("issue:1"))).isInstanceOf(ConnectorException.class)
                    .hasMessage("That is not an item of Dropbox.");
        }
        assertThat(api.requests("/2/files/download")).isEmpty();
    }

    @Test
    @DisplayName("registration probes the folder, and a refusal says what to do")
    void accessProbe() {
        personalAccount();
        api.on("POST", "/2/files/get_metadata").json(folder("/Projects"))
                .then().json(file("/Projects.pdf", null))
                .then().status(409).json("{'error_summary':'path/not_found/..'}");
        try (ConnectorSession s = session(Map.of("path", "https://www.dropbox.com/home/Projects"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("path", "/Projects.pdf"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("That is a file in Dropbox, not a folder.");
        }
        try (ConnectorSession s = session(Map.of("path", "/Nope"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Dropbox has no folder /Nope, or the connection's account cannot see it.");
        }
        api.reset();
        api.on("POST", "/2/users/get_current_account").status(401)
                .json("{'error_summary':'invalid_access_token/...','error':{'.tag':'invalid_access_token'}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Dropbox did not accept the connection's sign-in. Sign in again, "
                    + "or replace the access token, in Settings > Connections.");
        }
        api.reset();
        api.on("POST", "/2/users/get_current_account").status(401).json("{'error_summary':'missing_scope/..',"
                + "'error':{'.tag':'missing_scope','required_scope':'account_info.read'}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Dropbox app does not have the account_info.read permission. "
                    + "Turn it on on the app's Permissions tab in the Dropbox App Console, then sign in again.");
        }
    }

    @Test
    @DisplayName("a 429 is waited out for as long as Retry-After says, and reported as rate limited when it is too long to wait")
    void rateLimits() {
        personalAccount();
        api.on("POST", "/2/files/list_folder").status(429).header("Retry-After", "2")
                .json("{'error_summary':'too_many_requests/..','error':{'reason':{'.tag':'too_many_requests'},'retry_after':2}}")
                .then().json("{'cursor':'c1','has_more':false,'entries':[]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("c1");
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));

        api.reset();
        personalAccount();
        api.on("POST", "/2/files/list_folder").status(429).header("Retry-After", "3600");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Dropbox is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("Test names the account and its email; a refused token says so")
    void connectionTest() {
        personalAccount();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Lee (ana@example.com)");
        assertThat(ok.template()).isEqualTo("Signed in as {user} ({account})");

        api.reset();
        api.on("POST", "/2/users/get_current_account").status(401).json("{'error_summary':'expired_access_token/..'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Dropbox did not accept the token.");
    }

    @Test
    @DisplayName("a sign-in's expired access token is refreshed once and the request sent again")
    void refusedTokenIsRefreshedOnce() {
        api.on("POST", "/2/users/get_current_account").status(401).json("{'error_summary':'expired_access_token/..'}")
                .then().json("{'name':{'display_name':'Ana Lee'},'email':'ana@example.com','root_info':{}}");
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "old";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "fresh"; }
        };
        ResolvedCredentials signedIn = new ResolvedCredentials(null, null, AuthKind.OAUTH2_CODE, api.baseUrl(), null,
                Map.of(), Map.of(), tokens);
        assertThat(connection.test(signedIn, ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lee (ana@example.com)");
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer old", "Bearer fresh");
    }

    @Test
    @DisplayName("the sign-in: PKCE with an offline refresh token and the three read-only scopes")
    void oauthSpec() {
        OAuth2Spec spec = connection.oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://www.dropbox.com/oauth2/authorize");
        assertThat(spec.tokenUrl()).isEqualTo("https://api.dropboxapi.com/oauth2/token");
        assertThat(spec.scopes()).containsExactly("account_info.read", "files.metadata.read", "files.content.read");
        assertThat(spec.writeScopes()).isEmpty();
        assertThat(spec.extraAuthorizeParams()).containsEntry("token_access_type", "offline");
        assertThat(new DropboxConnectionPlugin(api.url("/oauth2/authorize"), " ").oauth2().tokenUrl())
                .isEqualTo(DropboxConnectionPlugin.TOKEN_URL);

        var d = DropboxConnectionPlugin.DESCRIPTOR;
        assertThat(d.authMethods()).extracting(AuthMethod::kind).containsExactly(AuthKind.OAUTH2_CODE, AuthKind.TOKEN);
        // The keys OAuth2ConnectionService reads - a renamed field would sign nothing in.
        assertThat(d.auth(AuthKind.OAUTH2_CODE).fields()).extracting(FieldSpec::key).containsExactly("clientId", "clientSecret");
        assertThat(d.auth(AuthKind.TOKEN).secretFields()).extracting(FieldSpec::key).containsExactly("token");
        assertThat(d.defaultBaseUrl()).isEqualTo("https://api.dropboxapi.com");
        assertThat(d.supportsWrite()).isFalse();
    }

    @Test
    @DisplayName("the descriptor: identity is the folder's path in lower case, a dropbox.com link reads as its path")
    void descriptor() {
        Map<String, String> all = ConfigValidator.validate(DropboxConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("/");
        assertThat(plugin.displayName(all)).isEqualTo("All of Dropbox");
        assertThat(plugin.webUrl(all, null)).isEqualTo("https://www.dropbox.com/home");

        Map<String, String> link = ConfigValidator.validate(DropboxConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("path", "https://www.dropbox.com/home/Team%20Docs/Q4?preview=x.pdf"));
        assertThat(plugin.identity(link)).isEqualTo("/team docs/q4");
        assertThat(plugin.displayName(link)).isEqualTo("Dropbox /Team Docs/Q4");
        assertThat(plugin.webUrl(link, null)).isEqualTo("https://www.dropbox.com/home/Team%20Docs/Q4");

        assertThat(DropboxConnectorPlugin.path("Projects/")).isEqualTo("/Projects");
        assertThat(DropboxConnectorPlugin.path(" / ")).isEmpty();
        var caps = DropboxConnectorPlugin.DESCRIPTOR.capabilities();
        assertThat(caps.shareable()).isFalse();
        assertThat(caps.fileTypeFilter()).isTrue();
        assertThat(caps.recursiveScope()).isTrue();
        assertThat(caps.incremental()).isTrue();
        assertThat(DropboxConnectorPlugin.DESCRIPTOR.protocol()).isEqualTo(DropboxConnectionPlugin.DESCRIPTOR.protocol());
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
