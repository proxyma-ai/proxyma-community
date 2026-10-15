package ai.proxyma.core.guardrail.policy;

import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls the policy clauses that are actually relevant to one piece of text.
 *
 * <p>WHY RETRIEVAL AND NOT THE WHOLE POLICY. A written policy is long, and a model asked to judge
 * a message against forty pages does worse than one asked to judge it against the three clauses
 * that bear on it - and costs the forty pages in tokens on every single turn. Retrieval is what
 * makes a real policy document usable as a guardrail at all.
 *
 * <p>HYBRID, because neither half is sufficient on its own. Dense retrieval finds the clause about
 * "sharing credentials" when the message says "here's my login"; BM25 finds the clause that names
 * a specific system, a project code name or a regulation number, which is exactly the vocabulary
 * an embedding flattens. {@link SearchService} already fuses the two with RRF, so this asks it
 * rather than growing a second fusion that would drift from the first.
 *
 * <p><b>Retrieval only.</b> {@link SearchService.SearchOptions#retrievalOnly()} switches off query
 * expansion and reranking, which are the two model-backed stages. Left on, this call would make
 * roughly twenty completions per turn - the guardrail would cost more than the answer it is
 * guarding.
 */
@Component
public class PolicyRetriever {

    private static final Logger log = LoggerFactory.getLogger(PolicyRetriever.class);

    /**
     * How much of the text is used as the query.
     *
     * <p>The query is embedded, so its length is a per-turn cost, and a very long query retrieves
     * WORSE rather than better - one vector averaged over ten unrelated paragraphs sits near none
     * of them. A message long enough to hit this is one whose opening already says what it is
     * about, and the deterministic rules read all of it regardless.
     */
    private static final int MAX_QUERY_CHARS = 2000;

    /** One retrieved clause, and which document it came from. */
    public record PolicyExcerpt(String documentId, String documentName, String text, double score) {}

    private final SearchService searchService;
    private final VectorStoreService vectorStoreService;
    private final PolicyLibrary library;

    public PolicyRetriever(SearchService searchService,
                           VectorStoreService vectorStoreService,
                           PolicyLibrary library) {
        this.searchService = searchService;
        this.vectorStoreService = vectorStoreService;
        this.library = library;
    }

    /**
     * Whether the corpus has anything semantically near this text at all.
     *
     * <p>THIS EXISTS BECAUSE HYBRID SEARCH CANNOT SAY "NOTHING". {@code minDenseScore} gates the
     * DENSE arm only - {@code BM25IndexService.search} takes no floor and returns its best N for
     * any query with any token in it. RRF then fuses a non-empty list, so
     * {@link SearchService#search} comes back populated for a message about the boiling point of
     * water just as readily as for one about sharing passwords. That is right for chat search,
     * where an agent asked to look something up wants best-effort results and empty is a failure.
     * It is wrong here, where "no policy bears on this" is the common case and has to cost
     * nothing.
     *
     * <p>Measured, not assumed: before this gate existed, an unrelated message retrieved policy
     * clauses and reached the classifier on every single turn.
     *
     * <p>So the dense arm decides WHETHER anything applies, and the full hybrid search then decides
     * WHICH clauses - keeping BM25's ability to find the clause that names a specific system or
     * regulation, which is exactly the vocabulary an embedding flattens. One extra embedding call,
     * and only on turns that pass, which were going to make a completion anyway.
     */
    private boolean anythingRelevant(String query, double minScore) {
        return !vectorStoreService.search(List.of(PolicyLibrary.RULES_COLLECTION), query, 1, minScore).isEmpty();
    }

    /**
     * The clauses that bear on this text, best first.
     *
     * <p>Returns EMPTY for every failure as well as for "nothing relevant". A corpus that is not
     * there, an embedding model that is not configured, a vector store that is down - none of
     * those are a policy violation, and none of them should refuse a turn. This is the same
     * fail-open stance the classifier takes, and for the same reason.
     */
    public List<PolicyExcerpt> retrieve(String text, int topK, double minScore) {
        if (text == null || text.isBlank()) return List.of();
        // Asked before searching: an empty corpus is the common case on a deployment that has not
        // written any policy down, and it must cost nothing per turn - no embedding call, no
        // store round-trip.
        if (!library.hasIndexedDocuments(PolicyDocument.Kind.RULES)) return List.of();

        String query = text.length() > MAX_QUERY_CHARS ? text.substring(0, MAX_QUERY_CHARS) : text;
        try {
            // The gate, before the hybrid search rather than after it - see anythingRelevant().
            // On a turn no policy bears on, this is the whole cost: one embedding and one store
            // lookup, no fusion and no classifier call.
            if (!anythingRelevant(query, minScore)) return List.of();

            List<Document> hits = searchService.search(
                    List.of(PolicyLibrary.RULES_COLLECTION), query, topK, minScore,
                    SearchService.SearchOptions.retrievalOnly());

            List<PolicyExcerpt> out = new ArrayList<>();
            for (Document d : hits) {
                String sourceId = String.valueOf(d.getMetadata().getOrDefault("source", ""));
                String name = library.displayNameForSource(sourceId).orElse("Policy");
                double score = toScore(d.getMetadata().get("score"));
                String body = d.getText();
                if (body == null || body.isBlank()) continue;
                out.add(new PolicyExcerpt(sourceId, name, body, score));
            }
            return out;
        } catch (Exception e) {
            // Message only. The query IS the user's message, and this class must never be the
            // thing that writes conversation content into a log.
            log.warn("[GuardrailPolicy] retrieval failed, allowing by default: {}", e.getMessage());
            return List.of();
        }
    }

    private static double toScore(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return raw == null ? 0.0 : Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
