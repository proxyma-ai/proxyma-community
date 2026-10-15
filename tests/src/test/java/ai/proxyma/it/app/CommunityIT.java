package ai.proxyma.it.app;

import ai.proxyma.it.harness.Corpus;
import ai.proxyma.it.harness.Env;
import ai.proxyma.it.harness.Http;
import ai.proxyma.it.harness.ManagedProcess;
import ai.proxyma.it.harness.ProxymaApp;
import ai.proxyma.it.harness.StaticSite;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Desktop is Community: free, every connector, no limits, and no license at all.
 *
 * <p>Since the two-tier model (2026-10-14) there is nothing to walk: no Personal, no Professional,
 * and Desktop loads no license code - it never contacts the license server and has no activation
 * endpoint. What this class pins is that the limits the old tiers sold are GONE, from both sides:
 * the second folder, the second website, the subfolders, the deep crawl, the hundred-and-first file
 * and the work-tool connectors are all simply allowed.
 *
 * <p>Its own instance, because each test removes every source when it ends.
 */
@DisplayName("Desktop: Community")
class CommunityIT {

    private static ProxymaApp app;

    @BeforeAll
    static void boot() {
        app = ProxymaApp.bootDesktopOwn();
        app.ensureFakeProvider();
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @AfterEach
    void removeSources() {
        if (app.api().get("/api/v1/indexing/summary").ok().json().path("totalSources").asInt() > 0) {
            Corpus.clearSources(app);
        }
    }

    private static Http api() { return app.api(); }

    // -- No license ----------------------------------------------------------------------------

    @Test
    @DisplayName("it says Community, with nothing to activate and no limit fields at all")
    void itIsCommunity() {
        JsonNode status = api().get("/api/v1/license/status").ok().json();
        assertThat(status.path("tier").asText()).isEqualTo("COMMUNITY");
        assertThat(status.path("trial").asBoolean(true)).isFalse();
        // The single-user editions have no accounts to add and no Microsoft 365 - those are
        // Enterprise Server's.
        assertThat(status.path("orgModeAllowed").asBoolean(true)).isFalse();
        for (String gone : List.of("maxLocalFolders", "maxWebPages", "maxWebCrawlDepth",
                "recursiveScanAllowed", "otherConnectorTypesAllowed")) {
            assertThat(status.has(gone)).as("the retired limit field %s", gone).isFalse();
        }
        // No license code is loaded on desktop, so there is no endpoint that could take a key.
        assertThat(api().post("/api/v1/license/activate", Map.of("licenseKey", "PXM-0000")).status())
                .isIn(404, 405);
    }

    // -- No limits -----------------------------------------------------------------------------

    @Test
    @DisplayName("any number of folders, each with its subfolders when asked for")
    void foldersHaveNoLimit() {
        String first = registerFolder(folder("first"), true).ok().text("/id");
        registerFolder(folder("second"), true).ok();
        registerFolder(folder("third"), false).ok();
        assertThat(recursiveOf(first)).as("subfolders were asked for and granted").isTrue();
    }

    @Test
    @DisplayName("any number of websites, crawled as deep as the product allows")
    void websitesHaveNoLimit() {
        JsonNode limits = api().get("/api/v1/indexing/webpage/limits").ok().json();
        assertThat(limits.path("maxDepth").asInt()).isEqualTo(50);
        try (StaticSite a = StaticSite.serve(page("a")); StaticSite b = StaticSite.serve(page("b"))) {
            registerSite(a, 10).ok();
            registerSite(b, 1).ok();
        }
    }

    @Test
    @DisplayName("the defaults stay modest: no subfolders unless asked, three levels deep, a hundred pages")
    void defaultsStayModest() {
        String id = api().post("/api/v1/indexing/register/files", Map.of(
                "path", folder("unasked").toAbsolutePath().toString())).ok().text("/id");
        assertThat(recursiveOf(id)).as("a folder added without asking for subfolders").isFalse();

        JsonNode limits = api().get("/api/v1/indexing/webpage/limits").ok().json();
        assertThat(limits.path("defaultDepth").asInt()).isEqualTo(3);
        assertThat(limits.path("defaultPages").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("a folder of a hundred and five files is indexed whole, with no cap to report")
    void noFileCap() {
        app.requireVectorStore();
        Path big = Env.scratch("hundred-and-five");
        for (int i = 0; i < 105; i++) write(big.resolve("note-" + i + ".txt"), "Note number " + i + " of the pile.");
        String id = registerFolder(big, false).ok().text("/id");

        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        JsonNode source = null;
        while (Instant.now().isBefore(deadline)) {
            source = listed(id);
            boolean idle = api().get("/api/v1/indexing/summary").ok().json().path("activeIndexingCount").asInt(1) == 0;
            if (idle && source != null && source.path("itemCount").asInt() >= 105) break;
            ManagedProcess.sleep(500);
        }
        assertThat(source).isNotNull();
        assertThat(source.path("itemCount").asInt()).as("%s", source).isEqualTo(105);
    }

    @Test
    @DisplayName("the work-tool connectors are open: a Jira token is accepted and a Jira source meets no license check")
    void workConnectorsAreOpen() {
        String id = api().post("/api/v1/connections/instances", Map.of("name", "Community Jira", "protocol", "JIRA",
                "baseUrl", "http://127.0.0.1:1", "secret", "tok-community")).ok().text("/id");
        try {
            // No Jira answers at that address, so registration fails - for REACHING it, which is
            // past any license. The assertion is that the refusal is not a license's.
            Http.Response attempt = api().post("/api/v1/indexing/register", Map.of("type", "jira", "config", Map.of("projectKey", "COM")));
            assertThat(attempt.body()).doesNotContain("tier_limit").doesNotContain("not_licensed");
        } finally {
            api().delete("/api/v1/connections/instances/" + id);
        }
    }

    // -- The edition ---------------------------------------------------------------------------

    @Test
    @DisplayName("desktop is itself: no accounts, no uploads, and a folder picker for local paths")
    void theDesktopEdition() {
        assertThat(api().get("/api/v1/health").ok().text("/edition")).isEqualTo("Desktop");
        JsonNode config = api().get("/api/v1/config").ok().json();
        assertThat(config.path("homeServer").asBoolean()).isFalse();
        assertThat(config.path("fileUpload").asBoolean()).isFalse();
        for (String path : List.of("/api/v1/auth/me", "/api/v1/setup/status", "/api/v1/admin/users",
                "/api/v1/files/quota")) {
            assertThat(api().get(path).status()).as(path).isEqualTo(404);
        }
        // The picker answers 200 for anything typed, so the folder must also be FOUND - which is
        // what proves the local path reached the desktop intact.
        JsonNode inspected = api().get("/api/v1/desktop/inspect-folder?path="
                + Http.query(folder("inspect").toAbsolutePath())).ok().json();
        assertThat(inspected.path("exists").asBoolean()).isTrue();
        assertThat(inspected.path("isDirectory").asBoolean()).isTrue();
    }

    // -- Helpers -------------------------------------------------------------------------------

    private static Http.Response registerFolder(Path folder, boolean recursive) {
        return api().post("/api/v1/indexing/register/files", Map.of(
                "path", folder.toAbsolutePath().toString(), "recursive", String.valueOf(recursive)));
    }

    private static Http.Response registerSite(StaticSite site, int depth) {
        return api().post("/api/v1/indexing/register/webpage", Map.of(
                "seedUrl", site.baseUrl() + "/", "name", "Site " + site.baseUrl(),
                "maxDepth", depth, "maxPages", 5, "sameDomainOnly", true, "renderJs", false));
    }

    private static boolean recursiveOf(String id) {
        JsonNode source = listed(id);
        assertThat(source).as("source %s is listed", id).isNotNull();
        return source.path("recursive").asBoolean();
    }

    private static JsonNode listed(String id) {
        for (JsonNode item : api().get("/api/v1/indexing/by-type/all?page=0&size=200").ok().json().path("items")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        return null;
    }

    private static Path folder(String name) {
        Path dir = Env.scratch("community-" + name);
        write(dir.resolve("readme.txt"), "A folder called " + name + ".");
        return dir;
    }

    private static Path page(String name) {
        Path dir = Env.scratch("community-site-" + name);
        write(dir.resolve("index.html"), "<!doctype html><html><head><title>" + name
                + "</title></head><body><p>Page " + name + ".</p></body></html>");
        return dir;
    }

    private static void write(Path file, String body) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
