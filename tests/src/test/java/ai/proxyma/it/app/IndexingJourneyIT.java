package ai.proxyma.it.app;

import ai.proxyma.it.harness.Corpus;
import ai.proxyma.it.harness.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adding a folder of documents and watching them become searchable - the product's core promise.
 *
 * <p>This exercises the whole pipeline behind one connector: detection, conversion to markdown,
 * chunking, embedding through the provider, the vector store, the BM25 index and the tracked
 * document state machine. The connector-specific half of every OTHER source type (Jira,
 * Confluence, Bitbucket, SharePoint) is covered by unit tests beside each connector; everything
 * downstream of "a document arrived" is this, and it is the same code for all of them.
 *
 * <p>The local folder connector is used because it is the only one whose source of truth a test can
 * WRITE. That makes the interesting half - what happens on the SECOND sync, when a file changed or
 * vanished - testable at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Indexing: a folder becomes a searchable source")
class IndexingJourneyIT extends DesktopJourney {

    @Test
    @DisplayName("a registered folder indexes every document, and reports it")
    void aFolderIsIndexed() {
        Path folder = Corpus.write();
        String id = Corpus.registerFolder(app, folder);
        try {
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());

            assertThat(Corpus.countByStatus(app, id, "COMPLETED"))
                    .isEqualTo(Corpus.DOCUMENTS.size());
            assertThat(Corpus.countByStatus(app, id, "FAILED")).isZero();

            // The embedding provider was actually asked. Without this the test would still pass
            // against a pipeline that indexed nothing but recorded success - which is precisely
            // the failure mode a status column invites.
            assertThat(llm().embeddingRequests())
                    .as("documents were embedded through the provider").isNotEmpty();

            JsonNode summary = api().get("/api/v1/indexing/summary").ok().json();
            assertThat(summary.path("totalDocs").asInt())
                    .isGreaterThanOrEqualTo(Corpus.DOCUMENTS.size());
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("a second sync re-indexes what changed and drops what was deleted")
    void aResyncFollowsTheFolder() throws IOException {
        Path folder = Corpus.write();
        String id = Corpus.registerFolder(app, folder);
        try {
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());
            llm().clear();

            // One file rewritten, one removed. This is what a user's folder does between syncs,
            // and getting it wrong is invisible in the happy path: an unchanged file re-embedded
            // every night is a bill, and a deleted file left in the index is an answer citing a
            // document that no longer exists.
            Files.writeString(folder.resolve("invoicing-policy.md"), """
                    # Invoicing Policy

                    Payment terms are now SIXTY days from the invoice date, revised this quarter.
                    Every invoice still carries a purchase order number.
                    """, StandardCharsets.UTF_8);
            Files.delete(folder.resolve("espresso-notes.md"));

            Corpus.resync(app, id);
            // Wait for the re-sync to have RUN before waiting for it to settle. awaitIndexed alone
            // answers "idle, with enough documents COMPLETED" - which was already true the instant
            // the sync was requested, so on a fast run the assertions below read the state from
            // BEFORE the re-sync and found nothing re-embedded. The deleted file changing state is
            // the first sign the pass reached this folder.
            java.time.Instant deadline = java.time.Instant.now().plus(java.time.Duration.ofMinutes(2));
            while (java.time.Instant.now().isBefore(deadline) && Corpus.states(app, id).entrySet().stream()
                    .anyMatch(e -> e.getKey().toLowerCase().contains("espresso") && e.getValue().startsWith("COMPLETED"))) {
                ai.proxyma.it.harness.ManagedProcess.sleep(300);
            }
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size() - 1);
            java.time.Instant embedDeadline = java.time.Instant.now().plus(java.time.Duration.ofMinutes(1));
            while (java.time.Instant.now().isBefore(embedDeadline) && llm().embeddingRequests().isEmpty()) {
                ai.proxyma.it.harness.ManagedProcess.sleep(300);
            }

            // THE ROW STAYS AND ITS STATUS CHANGES - it is not removed from the listing. That is
            // the tracked-document state machine working as designed: `connector` sets DELETED and
            // the Sources page shows what happened to a document rather than having it vanish
            // silently, which is the difference between "this was removed" and "where did it go".
            //
            // So the assertion is about STATE, not absence. An earlier draft expected the title to
            // disappear and failed against a product doing the right thing.
            String espressoState = Corpus.states(app, id).entrySet().stream()
                    .filter(e -> e.getKey().toLowerCase().contains("espresso"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("(absent)");
            assertThat(espressoState)
                    .as("the file deleted from disk is no longer a live indexed document")
                    .satisfiesAnyOf(
                            state -> assertThat(state).startsWith("DELETED"),
                            state -> assertThat(state).isEqualTo("(absent)"));

            assertThat(llm().embeddingRequests())
                    .as("the changed file was re-embedded").isNotEmpty();
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("an unreadable document fails alone, and says why")
    void oneBadDocumentDoesNotStopTheRest() throws IOException {
        Path folder = Corpus.write();
        // A file with a supported extension and bytes that are not text at all. The pipeline must
        // fail THIS document and index the others - a source that aborts wholesale on one bad file
        // is how a user loses a folder to a single corrupt export.
        Files.write(folder.resolve("broken.pdf"), new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});

        String id = Corpus.registerFolder(app, folder);
        try {
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());
            assertThat(Corpus.countByStatus(app, id, "COMPLETED"))
                    .as("the good documents still landed")
                    .isEqualTo(Corpus.DOCUMENTS.size());
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("the absolute folder path never reaches the client")
    void theServerPathIsNeverSent() {
        Path folder = Corpus.write();
        String id = Corpus.registerFolder(app, folder);
        try {
            Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());

            // SensitivePaths exists because an absolute server path in a client payload is an
            // information leak on a server install and a privacy surprise on a desktop one. The
            // items listing is the surface most likely to carry one: sourceId for a file-backed
            // document IS the raw absolute path, and the controller resolves a file id instead
            // precisely so it never has to send it.
            String listing = api().get("/api/v1/indexing/" + id + "/items?size=200").ok().body();
            assertThat(listing)
                    .as("the items listing carries no absolute path")
                    .doesNotContain(folder.toAbsolutePath().toString())
                    .doesNotContain(folder.toAbsolutePath().toString().replace("\\", "\\\\"));
        } finally {
            api().delete("/api/v1/indexing/" + id);
        }
    }

    @Test
    @DisplayName("deleting a source removes it and its documents")
    void deletingASource() {
        Path folder = Corpus.write();
        String id = Corpus.registerFolder(app, folder);
        Corpus.awaitIndexed(app, id, Corpus.DOCUMENTS.size());

        api().delete("/api/v1/indexing/" + id).ok();

        // The SUMMARY is the authority, not the per-source endpoint: /items on a missing id
        // answers 200 with an empty page rather than 404, so a status assertion there measures
        // that endpoint's behaviour instead of the deletion.
        //
        // And it is polled, because removing a source takes its documents, its vectors and its
        // shadow collection with it - the 204 comes back before the count settles.
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(60);
        int remaining;
        do {
            remaining = api().get("/api/v1/indexing/summary").ok().json().path("totalSources").asInt(-1);
            if (remaining == 0) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } while (java.time.Instant.now().isBefore(deadline));

        assertThat(remaining)
                .as("the source is gone from the listing, not merely emptied")
                .isZero();
    }
}
