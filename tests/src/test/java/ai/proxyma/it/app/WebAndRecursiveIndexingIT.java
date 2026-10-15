package ai.proxyma.it.app;

import ai.proxyma.it.harness.Corpus;
import ai.proxyma.it.harness.Env;
import ai.proxyma.it.harness.StaticSite;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two source-side features a user has that nothing was exercising: a folder with subfolders,
 * and a website.
 *
 * <p>Both were missed for the same reason and it is worth naming, because it is the failure mode
 * this whole suite is supposed to prevent. The corpus is flat and the folder journeys only ever
 * registered flat folders, so "recursive" was a parameter the tests passed and never observed; and
 * the web connector was filed mentally with Jira and SharePoint as "needs a remote server", which
 * it does not - it needs an HTTP server, and this suite already has one.
 *
 * <p>The first one also could not have passed while Community forced {@code recursive=false} at
 * registration; since 2026-10-14 Community has no limits, and the desktop holds no license at all.
 */
@DisplayName("Sources: subfolders, and a website")
class WebAndRecursiveIndexingIT extends DesktopJourney {

    @Test
    @DisplayName("a folder's subfolders are indexed too, when asked for")
    void recursiveFoldersAreIndexed() {
        Path root = Corpus.write(Map.of("top-level.md", """
                # Top Level Note
                This file sits at the root of the folder the user picked.
                """));
        Path nested = root.resolve("archive").resolve("2026");
        write(nested.resolve("buried.md"), """
                # Buried Quarterly Memo
                This memo is two directories down and must be indexed all the same.
                """);

        String id = Corpus.registerFolder(app, root);
        try {
            Corpus.awaitIndexed(app, id, 2);

            // THE ASSERTION IS THAT THE DEEP ONE ARRIVED. A recursive walk that silently stopped
            // at the first level would index the top-level file, report success, and leave the
            // user's archive invisible - with a green source and a document count nobody checks
            // against the filesystem.
            assertThat(Corpus.states(app, id).keySet())
                    .as("the file two directories down was indexed: %s", Corpus.states(app, id))
                    .anyMatch(title -> title.toLowerCase().contains("buried"));

            assertThat(Corpus.countByStatus(app, id, "COMPLETED")).isEqualTo(2);
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("a website is crawled, indexed and answerable")
    void aWebsiteIsCrawled() {
        app.requireVectorStore();

        // A real crawl over real HTTP, of a site this test writes. WEB_PAGE is allowed on EVERY
        // tier - isConnectorAllowed is `isFileBacked() || WEB_PAGE || otherConnectorTypesAllowed`
        // - so unlike the test above this one needs no license, only somewhere to index to.
        Path site = Env.scratch("site");
        write(site.resolve("index.html"), """
                <!doctype html><html><head><title>Field Service Handbook</title></head><body>
                <h1>Field Service Handbook</h1>
                <p>Engineers carry a calibrated torque wrench to every site visit.</p>
                <a href="/parts.html">Replacement parts</a>
                </body></html>
                """);
        write(site.resolve("parts.html"), """
                <!doctype html><html><head><title>Replacement Parts</title></head><body>
                <h1>Replacement Parts</h1>
                <p>A gasket is ordered by its cabinet number, never by its diameter.</p>
                </body></html>
                """);

        try (StaticSite served = StaticSite.serve(site)) {
            String id = api().post("/api/v1/indexing/register/webpage", Map.of(
                    "seedUrl", served.baseUrl() + "/",
                    "name", "Field Service Handbook",
                    // Depth 2 so the linked page is reached; Community allows depth 3 and 100
                    // pages, so this stays inside the free tier deliberately.
                    "maxDepth", 2,
                    "maxPages", 10,
                    "sameDomainOnly", true,
                    "renderJs", false)).ok().text("/id");
            try {
                // The seed and the page it links to. A crawler that fetched only the seed would
                // look like a working connector on a one-page site, which is what most tests of
                // one would be.
                Corpus.awaitIndexed(app, id, 2);

                assertThat(Corpus.states(app, id).keySet())
                        .as("the linked page was followed: %s", Corpus.states(app, id))
                        .anyMatch(title -> title.toLowerCase().contains("part"));

                // And it is retrievable, which is the only reason to index anything. The phrase is
                // unique to the crawled page, so a hit proves the CONTENT was extracted from HTML
                // rather than the URL being recorded.
                JsonNode results = api().post("/api/v1/search", Map.of(
                        "query", "gasket cabinet number ordered",
                        "topK", 5, "minScore", 0.01, "hybridSearch", true)).ok().json();
                assertThat(results.toString().toLowerCase())
                        .as("the crawled text is searchable: %s", results)
                        .contains("gasket");
            } finally {
                api().delete("/api/v1/indexing/" + id);
            }
        }
    }

    @Test
    @DisplayName("a website removed and added straight back is indexed again, as a new source")
    void aWebsiteCanBeAddedBackAfterRemoval() {
        Path site = Env.scratch("readded-site");
        write(site.resolve("index.html"), """
                <!doctype html><html><head><title>Boiler Room Log</title></head><body>
                <p>The pressure relief valve is tested on the first of every month.</p>
                </body></html>
                """);
        try (StaticSite served = StaticSite.serve(site)) {
            Map<String, Object> registration = Map.of("seedUrl", served.baseUrl() + "/",
                    "name", "Boiler Room Log", "maxDepth", 0, "maxPages", 1,
                    "sameDomainOnly", true, "renderJs", false);
            String first = api().post("/api/v1/indexing/register/webpage", registration).ok().text("/id");
            Corpus.awaitIndexed(app, first, 1);

            // What a user does after removing the wrong source: add it back at once. The website is
            // matched by its seed URL, so the new registration must not resolve to the source whose
            // removal is still in progress - that one is about to disappear, taking the "new" one
            // with it.
            api().delete("/api/v1/indexing/" + first).ok();
            String second = api().post("/api/v1/indexing/register/webpage", registration).ok().text("/id");
            try {
                assertThat(second).as("a fresh source, not the one being removed").isNotEqualTo(first);
                Corpus.awaitIndexed(app, second, 1);
            } finally {
                api().delete("/api/v1/indexing/" + second);
            }
        }
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
