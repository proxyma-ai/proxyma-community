package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import ai.proxyma.it.harness.Corpus;
import ai.proxyma.it.harness.Env;
import ai.proxyma.it.harness.Http;
import ai.proxyma.it.harness.ManagedProcess;
import ai.proxyma.it.harness.ProxymaApp;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Home Server: one person's Proxyma, reached from a browser on another machine.
 *
 * <p>The desktop jar booted as the Home Server image boots it. What makes it a different product is
 * that its user is not sitting at the machine: there is no folder picker to open, so documents
 * arrive by UPLOAD, and registering a folder by path is confined to the upload root. It has no
 * accounts - one person, like desktop - and like desktop it is Community, with no license at all.
 */
@DisplayName("Home Server")
class HomeServerIT {

    private static final String PHRASE = "quince preserve jar labelling";

    private static ProxymaApp app;

    @BeforeAll
    static void boot() {
        app = ProxymaApp.shared(ProxymaApp.Mode.HOME_SERVER);
        app.ensureFakeProvider();
    }

    private static Http api() { return app.api(); }

    @Test
    @DisplayName("it says what it is: Home Server, uploads on, single user")
    void itIdentifiesItself() {
        assertThat(api().get("/api/v1/health").ok().text("/edition")).isEqualTo("Home Server");
        JsonNode config = api().get("/api/v1/config").ok().json();
        assertThat(config.path("homeServer").asBoolean()).isTrue();
        assertThat(config.path("fileUpload").asBoolean()).isTrue();
        assertThat(config.has("orgMode")).as("no org-mode switch on a single-user edition").isFalse();
    }

    @Test
    @DisplayName("it has no accounts, no setup and no Administration - one person, like desktop")
    void noAccounts() {
        for (String path : List.of("/api/v1/auth/me", "/api/v1/setup/status", "/api/v1/admin/users")) {
            assertThat(api().get(path).status()).as(path).isEqualTo(404);
        }
        // One person with no account still owns settings and connections - under the one local
        // owner. A setting of a per-user prefix is the write that proves it; a connection is
        // proven below.
        // Jira's max-results was that setting until it was deleted as unread on 2026-10-14;
        // skip-custom-fields is in the same per-user prefix and still read.
        String before = api().get("/api/v1/settings/jira").ok().text("/skip-custom-fields");
        try {
            api().patchText("/api/v1/settings/jira/skip-custom-fields", "Story Points").ok();
            assertThat(api().get("/api/v1/settings/jira").ok().text("/skip-custom-fields")).isEqualTo("Story Points");
        } finally {
            if (before == null || before.isBlank()) api().delete("/api/v1/settings/jira/skip-custom-fields").ok();
            else api().patchText("/api/v1/settings/jira/skip-custom-fields", before).ok();
        }
        assertThat(api().get("/api/v1/connections/instances").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("there is no folder picker: the desktop's local-file API does not exist here")
    void noLocalFolderPicker() {
        assertThat(api().get("/api/v1/desktop/inspect-folder?path="
                + Http.query(Env.scratch("probe").toAbsolutePath())).status())
                .isEqualTo(404);
        assertThat(api().get("/api/v1/files/quota").status()).as("uploads are how files arrive").isEqualTo(200);
    }

    @Test
    @DisplayName("an uploaded folder is indexed, searchable, listed with the file sources, and gone with its source")
    void uploadedDocumentsAreIndexed() {
        app.requireVectorStore();
        String folder = "home-notes-" + UUID.randomUUID().toString().substring(0, 6);
        Path file = write("pantry.txt", "The " + PHRASE + " happens before the first frost.");
        api().postMultipart("/api/v1/files", "file", file, Map.of("folder", folder)).ok();
        String id = api().post("/api/v1/files/folders/register", Map.of("folder", folder)).ok().text("/id");
        try {
            Corpus.awaitIndexed(app, id, 1);

            assertThat(api().post("/api/v1/search", Map.of("query", PHRASE, "topK", 5, "minScore", 0.01))
                    .ok().json().path("results").toString()).containsIgnoringCase("quince");

            // The Sources page's "Files" tab asks for type "files". On an edition whose files are
            // uploads, that has to mean the uploaded folders - or the tab is empty while the
            // source it should list is indexing happily.
            assertThat(api().get("/api/v1/indexing/by-type/files?page=0&size=50").ok().body())
                    .as("the uploaded folder is listed under Files").contains(id);
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }

        // Deleting an uploaded source deletes the upload - unlike a local folder, whose files are
        // the user's own and never touched.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String files = api().get("/api/v1/files").ok().body();
        while (files.contains("pantry.txt") && Instant.now().isBefore(deadline)) {
            ManagedProcess.sleep(500);
            files = api().get("/api/v1/files").ok().body();
        }
        assertThat(files).as("the uploaded file went with its source").doesNotContain(folder);
    }

    @Test
    @DisplayName("a folder path outside the upload root is refused - the server's own disk is not a source")
    void pathsOutsideTheUploadRootAreRefused() {
        Http.Response refused = api().post("/api/v1/indexing/register/files", Map.of(
                "path", Env.scratch("outside").toAbsolutePath().toString(), "recursive", "true"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("upload directory");
    }

    @Test
    @DisplayName("it is Community: no license, and nowhere to activate one")
    void licensing() {
        assertThat(api().get("/api/v1/license/status").ok().text("/tier")).isEqualTo("COMMUNITY");
        assertThat(api().post("/api/v1/license/activate", Map.of("licenseKey", "PXM-0000")).status())
                .isIn(404, 405);
    }

    @Test
    @DisplayName("the work-tool connectors are open: a Jira connection is accepted and a Jira source meets no license check")
    void workConnectorsAreOpen() {
        String id = api().post("/api/v1/connections/instances", Map.of("name", "Work",
                "protocol", "JIRA", "baseUrl", "http://127.0.0.1:1", "secret", "tok-home")).ok().text("/id");
        try {
            // Nothing answers at that address, so registration fails for REACHING it - past any
            // license.
            Http.Response attempt = api().post("/api/v1/indexing/register", Map.of("type", "jira", "config", Map.of("projectKey", "HOME")));
            assertThat(attempt.body()).doesNotContain("tier_limit").doesNotContain("not_licensed");
        } finally {
            api().delete("/api/v1/connections/instances/" + id);
        }
    }

    @Test
    @DisplayName("Microsoft 365 sources are Enterprise Server only")
    void noMicrosoftSources() {
        assertThat(api().post("/api/v1/indexing/register", Map.of("type", "sharepoint", "config", Map.of(
                "siteUrl", "https://contoso.sharepoint.com/sites/x", "siteName", "x"))).status())
                .isEqualTo(400);
        assertThat(api().get("/api/v1/microsoft/status").status()).isEqualTo(404);
        assertThat(api().get("/api/v1/connections/protocols").ok().body()).doesNotContain("MICROSOFT_365");
    }

    @Test
    @DisplayName("chat works and keeps its history, with no sign-in")
    void chatWorks() {
        Chat.Turn turn = Chat.send(app, "@@text home answer").succeeded();
        assertThat(turn.text()).isEqualTo("home answer");
        assertThat(api().get("/api/v1/chat/conversations?page=0&size=50").ok().body())
                .contains(turn.conversationId());
    }

    @Test
    @DisplayName("a conversation started on one device opens on another - one person, every device")
    void conversationsFollowThePersonNotTheBrowser() {
        // The laptop's browser starts it; the phone's is a different browser with its own cookies.
        // Each conversation used to be bound to the browser that started it, as a visitor's is on a
        // server - so the history listed it, and opening it anywhere else answered 404.
        String id = Chat.send(app, "@@text started on the laptop").succeeded().conversationId();
        Http phone = app.newClient();
        assertThat(phone.get("/api/v1/chat/conversations/" + id).status())
                .as("the conversation the history lists also opens").isEqualTo(200);
        assertThat(phone.get("/api/v1/chat/conversations?page=0&size=50").ok().body()).contains(id);
    }

    private static Path write(String name, String body) {
        Path file = Env.scratch("home-upload").resolve(name);
        try {
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
