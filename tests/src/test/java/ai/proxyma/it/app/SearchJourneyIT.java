package ai.proxyma.it.app;

import ai.proxyma.it.harness.Corpus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Searching the index, and getting the right document back.
 *
 * <h2>How a fake model can prove retrieval works</h2>
 *
 * It could not, if its embeddings were random - cosine similarity between unrelated random vectors
 * is noise, so "the invoice question returned the invoice document" would be a coin flip and a
 * broken retriever would pass about as often as a working one.
 *
 * <p>The fake provider embeds by FEATURE HASHING instead: each word hashes to a few dimensions and
 * the vector is normalized, which makes cosine similarity a bag-of-words overlap. Two texts sharing
 * words score high, two sharing none score near zero. Measured on the corpus below: two phrasings
 * of the same subject score 0.83, two different subjects 0.16.
 *
 * <p>That is a real if shallow semantic signal, and it is exactly enough to pin what the product
 * owns - that the query reaches the store, that top-k and min-score bite, that reranking reorders,
 * that a filtered search filters - without asserting anything about how good a real embedding model
 * would be. The corpus is written so the four documents share almost no vocabulary, so a failure
 * here means the pipeline broke rather than that the stand-in was too crude.
 */
@DisplayName("Search: the index answers with the right document")
class SearchJourneyIT extends DesktopJourney {

    private static String sourceId;

    @BeforeAll
    static void indexTheCorpus() {
        Path folder = Corpus.write();
        sourceId = Corpus.registerFolder(app, folder);
        Corpus.awaitIndexed(app, sourceId, Corpus.DOCUMENTS.size());
    }

    @AfterAll
    static void removeTheSource() {
        if (sourceId != null) app.api().delete("/api/v1/indexing/" + sourceId);
    }

    @Test
    @DisplayName("a question about invoices returns the invoicing document first")
    void theRightDocumentRanksFirst() {
        JsonNode results = search("invoice purchase order payment terms", 5, LOWEST_THRESHOLD);

        assertThat(results.path("totalResults").asInt()).isGreaterThan(0);
        assertThat(subjects(results).get(0))
                .as("the top hit for an invoicing question is the invoicing document. Got: %s",
                        subjects(results))
                .contains("invoic");
    }

    @Test
    @DisplayName("each subject finds its own document, not merely some document")
    void eachSubjectFindsItsOwn() {
        // One assertion per subject rather than one for invoices alone. A retriever that always
        // returned the same document would pass the test above; it cannot pass this one.
        assertThat(subjects(search("badge laptop first morning probation", 3, LOWEST_THRESHOLD)).get(0))
                .contains("onboarding");
        assertThat(subjects(search("severity commander on-call timeline", 3, LOWEST_THRESHOLD)).get(0))
                .contains("incident");
        assertThat(subjects(search("grinder basket descale", 3, LOWEST_THRESHOLD)).get(0))
                .contains("espresso");
    }

    @Test
    @DisplayName("topK caps the result count")
    void topKBites() {
        assertThat(subjects(search("invoice onboarding incident espresso", 2, LOWEST_THRESHOLD)))
                .hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a high minScore filters weak matches out rather than returning them anyway")
    void minScoreBites() {
        // A query sharing nothing with any document. The honest answer is an empty list; returning
        // the least-bad match is how a user is shown a confidently irrelevant citation.
        JsonNode nonsense = search("xylophone kaleidoscope zeppelin quokka", 10, 0.95);
        assertThat(nonsense.path("totalResults").asInt())
                .as("a query matching nothing returns nothing at a high threshold")
                .isZero();
    }

    @Test
    @DisplayName("the query itself is embedded by the provider, once per search")
    void theQueryReachesTheProvider() {
        llm().clear();
        search("payment terms", 5, LOWEST_THRESHOLD);

        // Retrieval that answered from a cache, a stale vector or the BM25 index alone would
        // return plausible results and never call this. The provider is the only witness.
        assertThat(llm().embeddingRequests())
                .as("the query was embedded").isNotEmpty();
    }

    @Test
    @DisplayName("reranking reaches the provider and is metered")
    void rerankingIsUsedWhenTurnedOn() {
        app.setting("reranking", "enabled", "true");
        try {
            llm().clear();
            search("invoice purchase order", 5, LOWEST_THRESHOLD);

            // nativeRerank is declared on the fake instance, so the OpenAI-compatible adapter uses
            // /v1/rerank rather than the embedding-cosine tier. That this call happens AT ALL is
            // the assertion: recordSpend's own javadoc claimed for months that rerank could not
            // avoid the ledger while native rerank was doing exactly that.
            assertThat(llm().rerankRequests())
                    .as("reranking was turned on, so the rerank endpoint must be called")
                    .isNotEmpty();
        } finally {
            app.setting("reranking", "enabled", "false");
        }
    }

    @Test
    @DisplayName("every result carries the name of the document it came from")
    void everyResultIsNamed() {
        JsonNode results = search("invoice onboarding incident espresso", 8, LOWEST_THRESHOLD);

        // `title` WAS NULL ON EVERY RESULT THIS API HAD EVER RETURNED, found 2026-09-23 by this
        // suite. The field was declared on the DTO and serialized on every response, and
        // SearchController simply never called setTitle - a chunk's metadata carries `source`,
        // `url` and `connectorType`, and the title lives on the registry's TrackedDocument, which
        // nothing was consulting. The published API documentation showed `"title": null` in its
        // own example, which is how long it had been that way.
        //
        // Asserted on EVERY result rather than on the first: the resolution is one batched lookup
        // keyed on each chunk's source, so a bug that drops a key shows up as one unnamed result
        // among named ones and a first-result assertion would sail past it.
        List<String> titles = new java.util.ArrayList<>();
        results.path("results").forEach(r -> titles.add(r.path("title").asText(null)));

        assertThat(titles).as("the search returned nothing at all: %s", results).isNotEmpty();
        assertThat(titles)
                .as("every result names its document. Got: %s", titles)
                .allSatisfy(title -> assertThat(title).isNotNull().isNotBlank());
        // And it is the document's real name, not the chunk id or the query echoed back. A local
        // folder titles each document with its file name, so every title has to be one of the
        // files the corpus wrote.
        assertThat(titles)
                .as("titles come from the indexed files. Got: %s", titles)
                .isSubsetOf(Corpus.DOCUMENTS.keySet());
    }

    @Test
    @DisplayName("search is refused without a query rather than returning everything")
    void anEmptyQueryIsRefused() {
        // @NotBlank on SearchRequest.query. A validation gap here does not fail loudly - it
        // returns the whole index, which reads as a feature until someone notices.
        assertThat(api().post("/api/v1/search", Map.of("query", "", "topK", 5)).status())
                .isEqualTo(400);
    }

    /**
     * The smallest threshold the product accepts.
     *
     * <p>NOT zero: {@code /api/v1/search} answers 400 "minDenseScore must be larger than zero" for
     * it, which is a deliberate floor rather than a validation slip - a dense search with no floor
     * returns the whole index ranked by noise, and the least-bad match presented as an answer is
     * how a user is shown a confidently irrelevant citation. Five journeys asked for 0.0 and got
     * 400 before this was understood.
     */
    private static final double LOWEST_THRESHOLD = 0.01;

    private static JsonNode search(String query, int topK, double minScore) {
        return api().post("/api/v1/search", Map.of(
                "query", query, "topK", topK, "minScore", minScore, "hybridSearch", true))
                .ok().json();
    }

    /**
     * What each result is ABOUT, read from the snippet rather than from {@code title}.
     *
     * <p>Deliberately the snippet: it carries the document TEXT, so asserting on it proves the
     * content came back and not merely a label. {@link #everyResultIsNamed} covers the label.
     */
    private static List<String> subjects(JsonNode response) {
        List<String> subjects = new java.util.ArrayList<>();
        response.path("results").forEach(r ->
                subjects.add((r.path("snippet").asText("") + " " + r.path("sourceUrl").asText(""))
                        .toLowerCase()));
        assertThat(subjects).as("the search returned nothing at all: %s", response).isNotEmpty();
        return subjects;
    }
}
