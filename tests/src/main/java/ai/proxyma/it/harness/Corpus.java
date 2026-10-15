package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documents on disk, registered as a source, and indexed - the thing a user does first.
 *
 * <p>The local folder connector is the right vehicle for every retrieval journey in this suite,
 * because it is the only connector whose source of truth a test can WRITE. Jira, Confluence,
 * Bitbucket, SharePoint and the rest all require a remote server to exist; a test that needed one
 * would either talk to a real tenant (not reproducible, not safe) or to a stub of the vendor's API
 * (a test of the stub). The folder connector needs a directory, and the indexing pipeline
 * downstream of it - detection, conversion, chunking, embedding, the vector store, BM25 and the
 * tracked-document state machine - is the same code for every connector type.
 *
 * <p>So: the connector-specific half is covered by the unit tests that already exist next to each
 * connector, and the PIPELINE is covered here, once, through the one connector a test can control.
 */
public final class Corpus {

    private Corpus() {}

    /**
     * The standing corpus every retrieval journey shares.
     *
     * <p>Written to be discriminable by the fake provider's bag-of-words embedding: each document
     * is about a clearly different subject and shares few words with the others, so "the invoice
     * question returned the invoice document" is an assertion that can actually fail. Documents
     * that were all plausible answers would make every retrieval test pass by accident.
     *
     * <p>Deliberately ordinary business text. A corpus of lorem ipsum embeds to noise, and one
     * containing anything that looks like a credential would trip the guardrail in tests that are
     * not about the guardrail.
     */
    public static final Map<String, String> DOCUMENTS = new LinkedHashMap<>(Map.of(
            "invoicing-policy.md", """
                    # Invoicing Policy

                    Invoices are issued on the last working day of each month. Every invoice carries a
                    purchase order number, the billing period and the agreed currency. An invoice
                    without a purchase order number is returned to the supplier unpaid.

                    Payment terms are thirty days from the invoice date. A late invoice accrues no
                    interest during the first fifteen days.
                    """,
            "onboarding-checklist.md", """
                    # Onboarding Checklist

                    A new joiner receives a laptop, a badge and a mailbox on their first morning. The
                    hiring manager books an introduction with each neighbouring team during the first
                    week, and the probation review is scheduled for the end of the third month.

                    Equipment is ordered by the office manager two weeks before the start date.
                    """,
            "incident-response.md", """
                    # Incident Response

                    An incident is declared by whoever notices it. The responder opens a channel,
                    records a timeline and names one commander. Severity one wakes the on-call
                    engineer; severity three waits for working hours.

                    Every incident gets a written review within five working days, and the review is
                    about the system rather than the person.
                    """,
            "espresso-notes.md", """
                    # Espresso Notes

                    The grinder is set to fourteen clicks. A double basket takes eighteen grams and
                    should pour thirty six grams in about twenty eight seconds. Descale the machine
                    when the light blinks twice.
                    """
    ));

    /** Writes {@link #DOCUMENTS} into a fresh directory and returns it. */
    public static Path write() {
        return write(DOCUMENTS);
    }

    public static Path write(Map<String, String> documents) {
        Path folder = Env.scratch("corpus");
        documents.forEach((name, body) -> {
            try {
                Files.writeString(folder.resolve(name), body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return folder;
    }

    /**
     * Registers a folder as a source and returns its connector id.
     *
     * <p>Registration starts the first sync on its own, exactly as it does for a user who has just
     * picked a folder - so nothing here calls {@code /sync} afterwards. A test that wants to
     * observe a SECOND sync (a changed file, a deletion) calls {@link #resync}.
     */
    public static String registerFolder(ProxymaApp app, Path folder) {
        // Asked here rather than in each test, so a journey that indexes cannot forget to ask and
        // then fail deep inside the pipeline with a null vector store.
        app.requireVectorStore();
        // AN UNLICENSED INSTALL GETS ONE LOCAL FOLDER, AND THAT IS THE PRODUCT BEING CORRECT.
        // Community tier caps local folders at 1, so the second registration anywhere in the suite
        // comes back 403 {"error":"tier_limit","message":"License limit reached for feature 'local
        // folders': max 1"} - which is right, and which made four journeys fail on the host for a
        // reason that had nothing to do with what they were testing. Freeing the slot here keeps
        // that constraint honoured rather than worked around: there is never more than one.
        clearSources(app);
        return app.api().post("/api/v1/indexing/register/files", Map.of(
                "path", folder.toAbsolutePath().toString(),
                "recursive", "true"
        )).ok().text("/id");
    }

    /**
     * Removes every registered source.
     *
     * <p>Safe because the shared instance belongs to this suite alone - see ProxymaApp.shared -
     * and because a journey that wants a source creates it.
     */
    public static void clearSources(ProxymaApp app) {
        JsonNode listing = app.api().get("/api/v1/indexing/by-type/all?page=0&size=200").ok().json();
        for (JsonNode item : listing.path("items")) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) app.api().delete("/api/v1/indexing/" + id).ok();
        }
        // Wait for the count to reach zero rather than trusting the 204. Removing a source takes
        // its documents, its vectors and its shadow collection with it, and the tier counter is
        // read from the rows - so registering again too soon is refused with tier_limit and the
        // next test blames a cap it has not actually reached.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            int remaining = app.api().get("/api/v1/indexing/summary").ok().json()
                    .path("totalSources").asInt(-1);
            if (remaining == 0) return;
            ManagedProcess.sleep(500);
        }
        throw new AssertionError("sources were deleted but /summary still reports "
                + app.api().get("/api/v1/indexing/summary").ok().json().path("totalSources").asInt()
                + " of them after a minute - the next registration will be refused with"
                + " tier_limit for a reason that has nothing to do with the test that hits it");
    }

    public static void resync(ProxymaApp app, String connectorId) {
        app.api().post("/api/v1/indexing/" + connectorId + "/sync", Map.of()).ok();
    }

    /**
     * Blocks until the source has finished indexing, then asserts how many documents landed.
     *
     * <p>Waits on {@code activeIndexingCount} reaching zero AND the document count reaching the
     * expected number, because either alone lies at a different moment: the count is zero before
     * the first document is queued, and the active count is zero in the gap between two phases.
     *
     * <p>A timeout prints the per-document state rather than just the count, because the
     * interesting failure is almost never "it was slow" - it is one document stuck in CONVERTING,
     * or three of them FAILED with a message that names the real problem.
     */
    public static void awaitIndexed(ProxymaApp app, String connectorId, int expectedDocuments) {
        awaitIndexed(app.api(), connectorId, expectedDocuments);
    }

    /**
     * {@link #awaitIndexed(ProxymaApp, String, int)} as a particular caller - on a server the
     * source belongs to whoever registered it, and only its owner may read its documents.
     */
    public static void awaitIndexed(Http owner, String connectorId, int expectedDocuments) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            JsonNode summary = owner.get("/api/v1/indexing/summary").ok().json();
            boolean idle = summary.path("activeIndexingCount").asInt(1) == 0;
            long completed = countByStatus(owner, connectorId, "COMPLETED");
            if (idle && completed >= expectedDocuments) return;
            ManagedProcess.sleep(500);
        }
        throw new AssertionError("source " + connectorId + " did not index " + expectedDocuments
                + " documents within three minutes. Document states: " + states(owner, connectorId));
    }

    /** How many of a source's documents are in a given state. */
    public static long countByStatus(ProxymaApp app, String connectorId, String status) {
        return countByStatus(app.api(), connectorId, status);
    }

    public static long countByStatus(Http owner, String connectorId, String status) {
        return items(owner, connectorId).stream()
                .filter(item -> status.equals(item.path("status").asText()))
                .count();
    }

    /** Every tracked document of a source, as the Sources page lists them. */
    public static List<JsonNode> items(ProxymaApp app, String connectorId) {
        return items(app.api(), connectorId);
    }

    public static List<JsonNode> items(Http owner, String connectorId) {
        JsonNode body = owner.get("/api/v1/indexing/" + connectorId + "/items?size=200").ok().json();
        List<JsonNode> items = new java.util.ArrayList<>();
        body.path("items").forEach(items::add);
        return items;
    }

    /** A readable title-to-status map, for a failure message. */
    public static Map<String, String> states(ProxymaApp app, String connectorId) {
        return states(app.api(), connectorId);
    }

    public static Map<String, String> states(Http owner, String connectorId) {
        Map<String, String> states = new LinkedHashMap<>();
        for (JsonNode item : items(owner, connectorId)) {
            states.put(item.path("title").asText("?"),
                    item.path("status").asText("?")
                            + optional(item.path("failureMessage").asText("")));
        }
        return states;
    }

    private static String optional(String message) {
        return message.isBlank() ? "" : " (" + message + ")";
    }
}
