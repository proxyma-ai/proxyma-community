package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConfigValidator;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google Drive against a loopback Drive API: the full listing of each corpus with its change-feed
 * token, the change feed and a token Drive no longer accepts, what is exported and what is
 * downloaded, what is gone, and every refusal as a sentence.
 */
class GoogleDriveConnectorTest {

    private static final String TOKEN = "ya29.test-token";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final DriveConnectorPlugin plugin = new DriveConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, null);
    }

    private ConnectorSession session(Map<String, Object> fields, String fileTypes) {
        Map<String, String> config = ConfigValidator.validate(DriveConnectorPlugin.DESCRIPTOR.fields(), fields);
        ResolvedCredentials credentials = ResolvedCredentials.token(api.baseUrl(), TOKEN);
        return plugin.open(new SourceContext(null, ConnectorType.GOOGLE_DRIVE, null, null, config, null, null,
                credentials, new SourceScope(fileTypes, true, null), ConnectorHttp.direct(waits::add), Clock.systemUTC()));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String file(String id, String name, String mime, String extra) {
        return "{'id':'" + id + "','name':'" + name + "','mimeType':'" + mime + "','modifiedTime':'2026-10-01T10:00:00Z',"
                + "'webViewLink':'https://drive.google.com/file/d/" + id + "/view','ownedByMe':true,"
                + "'lastModifyingUser':{'displayName':'Ana Lee','emailAddress':'ana@example.com'}"
                + (extra == null ? "" : "," + extra) + "}";
    }

    private void myDriveInTwoPages() {
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").json("{'nextPageToken':'p2','files':["
                + file("doc1", "Roadmap", DriveSession.GOOGLE_DOC, "'size':'2048'") + ","
                + file("fold1", "Projects", DriveSession.FOLDER_MIME, null) + ","
                + file("sheet1", "Budget", DriveSession.GOOGLE_SHEET, null) + "]}");
        api.on("GET", "/drive/v3/files").query("pageToken", "p2").json("{'files':["
                + file("pdf1", "Contract.pdf", "application/pdf", "'size':'5000','md5Checksum':'abc123'") + ","
                + file("form1", "Survey", "application/vnd.google-apps.form", null) + ","
                + file("zip1", "Backup.zip", "application/zip", null) + ","
                + file("slides1", "Pitch", DriveSession.GOOGLE_SLIDES, null) + "]}");
    }

    @Test
    @DisplayName("a full listing reads every page of My Drive, takes the change-feed token first, and leaves out what it does not index")
    void fullListing() {
        myDriveInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly("file:doc1", "file:sheet1", "file:pdf1", "file:slides1");
            assertThat(items).extracting(ItemMetadata::getContentType)
                    .containsExactly("text/markdown", "text/csv", "application/pdf", "text/plain");
            ItemMetadata doc = items.get(0);
            assertThat(doc.getTitle()).isEqualTo("Roadmap");
            assertThat(doc.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(doc.getExtra("url")).isEqualTo("https://drive.google.com/file/d/doc1/view");
            assertThat(doc.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lee");
            assertThat(doc.getSizeBytes()).isEqualTo(2048L);
            assertThat(items.get(2).getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo("abc123");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"user\":\"100\"}");
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        // The token is asked for before the first page, so a change made during the listing is not lost.
        assertThat(api.requests().get(0).path()).isEqualTo("/drive/v3/changes/startPageToken");
        LoopbackApi.Recorded first = api.requests("/drive/v3/files").get(0);
        assertThat(first.param("corpora")).isEqualTo("user");
        assertThat(first.param("q")).isEqualTo("'me' in owners and trashed = false");
        assertThat(first.param("fields")).contains("webViewLink", "md5Checksum");
    }

    @Test
    @DisplayName("the file-type filter counts a Doc as .docx and a Sheet as .xlsx")
    void fileTypeFilter() {
        myDriveInTwoPages();
        try (ConnectorSession s = session(Map.of(), "pdf,xlsx")) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("file:sheet1", "file:pdf1");
        }
    }

    @Test
    @DisplayName("shared with me and a shared drive are separate corpora, each with its own token in the cursor")
    void sharedWithMeAndSharedDrives() {
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/changes/startPageToken").query("driveId", "0ABdrive").json("{'startPageToken':'7'}");
        api.on("GET", "/drive/v3/files").json("{'files':[" + file("own1", "Mine.txt", "text/plain", null) + "]}");
        api.on("GET", "/drive/v3/files").query("driveId", "0ABdrive").json("{'files':["
                + file("team1", "Team plan", DriveSession.GOOGLE_DOC, "'driveId':'0ABdrive'") + "]}");
        try (ConnectorSession s = session(Map.of("myDrive", true, "sharedWithMe", true,
                "sharedDrives", List.of("https://drive.google.com/drive/folders/0ABdrive")))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:own1", "file:team1");
            assertThat(listing.nextCursor()).isEqualTo("{\"user\":\"100\",\"0ABdrive\":\"7\"}");
        }
        assertThat(api.requests("/drive/v3/files").get(0).param("q"))
                .isEqualTo("('me' in owners or sharedWithMe = true) and trashed = false");
        LoopbackApi.Recorded drive = api.requests("/drive/v3/files").get(1);
        assertThat(drive.param("corpora")).isEqualTo("drive");
        assertThat(drive.param("includeItemsFromAllDrives")).isEqualTo("true");
        assertThat(drive.param("supportsAllDrives")).isEqualTo("true");
    }

    @Test
    @DisplayName("a folder is walked with every folder inside it, each once, whatever its parents")
    void folderWalk() {
        api.on("GET", "/drive/v3/files/root1").json("{'id':'root1','name':'Handbook','mimeType':'" + DriveSession.FOLDER_MIME + "'}");
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'40'}");
        api.on("GET", "/drive/v3/files").query("q", "'root1' in parents and trashed = false").json("{'files':["
                + file("sub1", "Policies", DriveSession.FOLDER_MIME, null) + ","
                + file("doc1", "Welcome", DriveSession.GOOGLE_DOC, null) + "]}");
        api.on("GET", "/drive/v3/files").query("q", "'sub1' in parents and trashed = false").json("{'nextPageToken':'n2','files':["
                + file("pdf1", "Leave.pdf", "application/pdf", null) + "]}");
        api.on("GET", "/drive/v3/files").query("q", "'sub1' in parents and trashed = false").query("pageToken", "n2")
                .json("{'files':[" + file("root1", "Handbook", DriveSession.FOLDER_MIME, null) + ","
                        + file("pdf2", "Travel.pdf", "application/pdf", null) + "]}");
        try (ConnectorSession s = session(Map.of("folder", "https://drive.google.com/drive/folders/root1?usp=sharing"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:doc1", "file:pdf1", "file:pdf2");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"user\":\"40\"}");
        }
        assertThat(api.requests("/drive/v3/files")).hasSize(3);
        assertThat(api.requests("/drive/v3/files").get(0).param("corpora")).isEqualTo("allDrives");
    }

    @Test
    @DisplayName("a changes pass reads the feed from the stored token: changed files are items, removed, trashed and unshared ones are gone")
    void changesPass() {
        api.on("GET", "/drive/v3/changes").query("pageToken", "100").json("{'nextPageToken':'101','changes':["
                + "{'changeType':'file','fileId':'doc1','time':'2026-10-12T10:00:00Z','removed':false,'file':"
                + file("doc1", "Roadmap", DriveSession.GOOGLE_DOC, null) + "},"
                + "{'changeType':'file','fileId':'gone1','time':'2026-10-12T10:01:00Z','removed':true},"
                + "{'changeType':'drive','driveId':'0ABdrive','time':'2026-10-12T10:02:00Z'}]}");
        api.on("GET", "/drive/v3/changes").query("pageToken", "101").json("{'newStartPageToken':'105','changes':["
                + "{'changeType':'file','fileId':'bin1','time':'2026-10-12T10:03:00Z','file':"
                + file("bin1", "Old notes.txt", "text/plain", "'trashed':true") + "},"
                + "{'changeType':'file','fileId':'theirs1','time':'2026-10-12T10:04:00Z','file':"
                + "{'id':'theirs1','name':'Their doc','mimeType':'" + DriveSession.GOOGLE_DOC + "','ownedByMe':false}},"
                + "{'changeType':'file','fileId':'fold9','time':'2026-10-12T10:05:00Z','file':"
                + file("fold9", "Archive", DriveSession.FOLDER_MIME, null) + "}]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), "{\"user\":\"100\"}"));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("file:doc1", "file:gone1", "file:bin1", "file:theirs1");
            assertThat(items.get(0).getContentType()).isEqualTo("text/markdown");
            // A gone file is dated by its change, so the engine fetches it again and finds it gone.
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-12T10:01:00Z"));
            assertThat(items.get(1).getContentType()).isNull();
            assertThat(listing.nextCursor()).isEqualTo("{\"user\":\"105\"}");
        }
        assertThat(api.requests("/drive/v3/files")).isEmpty();
        assertThat(api.requests("/drive/v3/changes").get(0).param("includeRemoved")).isEqualTo("true");
    }

    @Test
    @DisplayName("a change-feed token Drive no longer accepts lists that corpus in full, with a fresh token")
    void expiredToken() {
        api.on("GET", "/drive/v3/changes").status(404).json("{'error':{'code':404,'message':'Page token is not valid'}}");
        myDriveInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), "{\"user\":\"99\"}"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:doc1", "file:sheet1", "file:pdf1", "file:slides1");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("{\"user\":\"100\"}");
        }
        // A cursor that is not one at all is a full listing too.
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), "tree:abc"));
            assertThat(drain(listing)).hasSize(4);
        }
    }

    @Test
    @DisplayName("a page that fails mid-listing is an errored listing, and its cursor is not one to keep")
    void failedPage() {
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").json("{'nextPageToken':'p2','files':[" + file("doc1", "Roadmap", DriveSession.GOOGLE_DOC, null) + "]}");
        api.on("GET", "/drive/v3/files").query("pageToken", "p2").status(500);
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:doc1");
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Google Drive answered with a server error (500). The next sync will try again.");
        }
    }

    @Test
    @DisplayName("a Doc is exported as Markdown, a Sheet as CSV, and any other file is downloaded as it is")
    void fetching() throws IOException {
        api.on("GET", "/drive/v3/files/doc1").json(file("doc1", "Roadmap", DriveSession.GOOGLE_DOC, "'size':'2048'"));
        api.on("GET", "/drive/v3/files/doc1/export").query("mimeType", "text/markdown").text("# Roadmap\n\nShip it.");
        api.on("GET", "/drive/v3/files/sheet1").json(file("sheet1", "Budget", DriveSession.GOOGLE_SHEET, null));
        api.on("GET", "/drive/v3/files/sheet1/export").query("mimeType", "text/csv").text("a,b\n1,2\n");
        api.on("GET", "/drive/v3/files/pdf1").json(file("pdf1", "Contract.pdf", "application/pdf", "'size':'5000'"));
        api.on("GET", "/drive/v3/files/pdf1").query("alt", "media").body(new byte[]{'%', 'P', 'D', 'F'}, "application/pdf");

        assertThat(text(fetch("file:doc1"))).isEqualTo("# Roadmap\n\nShip it.");
        assertThat(text(fetch("file:sheet1"))).isEqualTo("a,b\n1,2\n");
        assertThat(text(fetch("file:pdf1"))).isEqualTo("%PDF");
        assertThat(api.last().header("Accept")).isEqualTo("*/*");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("a Doc Drive will not give as Markdown is exported as plain text")
    void markdownFallsBackToText() throws IOException {
        api.on("GET", "/drive/v3/files/doc1").json(file("doc1", "Roadmap", DriveSession.GOOGLE_DOC, null));
        api.on("GET", "/drive/v3/files/doc1/export").query("mimeType", "text/markdown").status(400)
                .json("{'error':{'code':400,'message':'Export only supports these formats'}}");
        api.on("GET", "/drive/v3/files/doc1/export").query("mimeType", "text/plain").text("Roadmap - ship it.");
        assertThat(text(fetch("file:doc1"))).isEqualTo("Roadmap - ship it.");
    }

    @Test
    @DisplayName("a Google file over the 10 MB export limit fails with a sentence, before or after asking")
    void exportLimit() {
        api.on("GET", "/drive/v3/files/big1").json(file("big1", "Huge", DriveSession.GOOGLE_DOC, "'size':'" + (11L * 1024 * 1024) + "'"));
        api.on("GET", "/drive/v3/files/big2").json(file("big2", "Also huge", DriveSession.GOOGLE_SHEET, null));
        api.on("GET", "/drive/v3/files/big2/export").status(403).json("{'error':{'code':403,'errors':[{'reason':"
                + "'exportSizeLimitExceeded'}],'message':'This file is too large to be exported.'}}");
        String sentence = "Google Drive exports files of up to 10 MB, and this one is larger, so it was not indexed.";
        assertThatThrownBy(() -> fetch("file:big1")).isInstanceOf(ConnectorException.class).hasMessage(sentence);
        assertThat(api.requests("/drive/v3/files/big1/export")).isEmpty();
        assertThatThrownBy(() -> fetch("file:big2")).isInstanceOf(ConnectorException.class).hasMessage(sentence);
    }

    @Test
    @DisplayName("a deleted, trashed or no longer owned file is gone at its fetch")
    void goneItems() {
        api.on("GET", "/drive/v3/files/gone1").status(404).json("{'error':{'code':404,'message':'File not found'}}");
        api.on("GET", "/drive/v3/files/bin1").json(file("bin1", "Old", "text/plain", "'trashed':true"));
        api.on("GET", "/drive/v3/files/theirs1").json("{'id':'theirs1','name':'Theirs','mimeType':'text/plain','ownedByMe':false}");
        assertThatThrownBy(() -> fetch("file:gone1")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("file:bin1")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("file:theirs1")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:1")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of Google Drive.");
    }

    @Test
    @DisplayName("a file moved out of the source's folder is gone; one in a folder inside it is not")
    void folderScopeAtFetch() throws IOException {
        api.on("GET", "/drive/v3/files/a1").json(file("a1", "In.txt", "text/plain", "'parents':['sub1']"));
        api.on("GET", "/drive/v3/files/a1").query("alt", "media").text("inside");
        api.on("GET", "/drive/v3/files/sub1").json("{'id':'sub1','parents':['root1']}");
        api.on("GET", "/drive/v3/files/b1").json(file("b1", "Out.txt", "text/plain", "'parents':['elsewhere']"));
        api.on("GET", "/drive/v3/files/elsewhere").json("{'id':'elsewhere','parents':[]}");
        ItemMetadata in = new ItemMetadata();
        in.setId("file:a1");
        ItemMetadata out = new ItemMetadata();
        out.setId("file:b1");
        try (ConnectorSession s = session(Map.of("folder", "root1"))) {
            assertThat(text(s.fetch(in))).isEqualTo("inside");
            assertThatThrownBy(() -> s.fetch(out)).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Out.txt is no longer in this part of Google Drive.");
        }
    }

    @Test
    @DisplayName("registration probes what the source reads, and a refusal says what to do")
    void accessProbe() {
        api.on("GET", "/drive/v3/about").json("{'user':{'emailAddress':'ana@example.com'}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("myDrive", false))) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("Choose what to read: My Drive, files shared with you, a shared drive or a folder.");
        }
        api.on("GET", "/drive/v3/drives/0ABnope").status(404).json("{'error':{'code':404}}");
        try (ConnectorSession s = session(Map.of("myDrive", false, "sharedDrives", "0ABnope"))) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("Google Drive has no shared drive 0ABnope, or the connection's account cannot see it.");
        }
        api.on("GET", "/drive/v3/files/file9").json(file("file9", "Notes.txt", "text/plain", null));
        try (ConnectorSession s = session(Map.of("folder", "file9"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("That is a file in Google Drive, not a folder.");
        }
        api.reset();
        api.on("GET", "/drive/v3/about").status(401).json("{'error':{'code':401}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Google did not accept the connection's sign-in. Sign in again, "
                    + "or check the service account, in Settings > Connections.");
        }
        api.reset();
        api.on("GET", "/drive/v3/about").status(403).json("{'error':{'code':403,'message':'Drive API has not been used'}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Google refused access. Check that the Google Drive API is "
                    + "enabled in the Google Cloud project, and that the connection's account may read this.");
        }
    }

    @Test
    @DisplayName("a 429 is waited out when Google says when, and reported as rate limited when it is too long to wait")
    void rateLimits() {
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").status(429).header("Retry-After", "2").then().json("{'files':[]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));

        api.reset();
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").status(429).header("Retry-After", "3600");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Google Drive is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("Google's 403 that names a rate limit in its body is backed off and retried; a spent daily quota is reported for the next pass")
    void bodyRateLimits() {
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'userRateLimitExceeded','domain':'usageLimits'}]}}")
                .then().json("{'files':[]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).hasSize(1);
        assertThat(api.requests("/drive/v3/files")).hasSize(2);

        api.reset();
        waits.clear();
        api.on("GET", "/drive/v3/changes/startPageToken").json("{'startPageToken':'100'}");
        api.on("GET", "/drive/v3/files").status(403)
                .json("{'error':{'code':403,'errors':[{'reason':'dailyLimitExceeded','domain':'usageLimits'}]}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Google Drive is limiting how often it is asked. The next sync will try again.");
        }
        assertThat(waits).isEmpty();
        assertThat(api.requests("/drive/v3/files")).hasSize(1);
    }

    @Test
    @DisplayName("the descriptor: identity is what the source reads, a link reads as its ID, and the name says it")
    void descriptor() {
        Map<String, String> mine = ConfigValidator.validate(DriveConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(mine)).isEqualTo("mydrive");
        assertThat(plugin.displayName(mine)).isEqualTo("My Drive");
        assertThat(plugin.webUrl(mine, null)).isEqualTo("https://drive.google.com/drive/my-drive");

        Map<String, String> mixed = ConfigValidator.validate(DriveConnectorPlugin.DESCRIPTOR.fields(), Map.of(
                "sharedWithMe", true, "sharedDrives", "https://drive.google.com/drive/folders/0ABzz\n0ABaa"));
        assertThat(plugin.identity(mixed)).isEqualTo("mydrive,shared,drive:0ABaa,drive:0ABzz");
        assertThat(plugin.displayName(mixed)).isEqualTo("My Drive, Shared with me, Shared drive 0ABaa, Shared drive 0ABzz");

        Map<String, String> folder = ConfigValidator.validate(DriveConnectorPlugin.DESCRIPTOR.fields(), Map.of(
                "folder", "https://drive.google.com/drive/u/0/folders/1xYz_-9?usp=sharing"));
        assertThat(plugin.identity(folder)).isEqualTo("folder:1xYz_-9");
        assertThat(plugin.displayName(folder)).isEqualTo("Drive folder 1xYz_-9");
        assertThat(plugin.webUrl(folder, null)).isEqualTo("https://drive.google.com/drive/folders/1xYz_-9");

        Map<String, String> nothing = ConfigValidator.validate(DriveConnectorPlugin.DESCRIPTOR.fields(), Map.of("myDrive", false));
        assertThat(plugin.displayName(nothing)).isEmpty();
        assertThat(DriveConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(DriveConnectorPlugin.DESCRIPTOR.capabilities().fileTypeFilter()).isTrue();
        assertThat(DriveConnectorPlugin.DESCRIPTOR.protocol()).isEqualTo(GoogleConnectionPlugin.DESCRIPTOR.protocol());
    }

    private InputStream fetch(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(Map.of())) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
