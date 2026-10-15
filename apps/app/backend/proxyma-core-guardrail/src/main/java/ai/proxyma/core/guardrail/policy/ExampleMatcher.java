package ai.proxyma.core.guardrail.policy;

import ai.proxyma.core.indexstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * "Is this message close to something we already know to be an attack?"
 *
 * <p>NO MODEL. One embedding of the incoming message, one nearest-neighbour lookup, a threshold.
 * That is the whole mechanism, and it is the standard one: Rebuff stores embeddings of previous
 * attacks and recognises similar ones; Guardrails AI's DetectJailbreak embeds the message with a
 * sentence transformer and takes cosine distance against a known-jailbreak set; NeMo Guardrails
 * does KNN against stored example utterances per canonical form. All of them compare a MESSAGE to
 * other MESSAGES.
 *
 * <p>WHY THIS IS SOUND WHERE THRESHOLDING A POLICY IS NOT. The comparison is like-for-like. A
 * paraphrase of a known jailbreak lands near the original because they are the same kind of text
 * saying the same thing - which is exactly where a regular expression fails, since paraphrase is
 * the thing patterns cannot follow. Compare a message to a RULE instead and the nearest neighbours
 * are questions ABOUT the rule, which is why the rules corpus is judged by a model rather than
 * measured. See {@code PolicyDocument.Kind}.
 *
 * <p>WHAT IT CANNOT DO. It only knows what it has been shown. A genuinely novel attack is not near
 * anything in the corpus and passes; the corpus is a list of what has already been seen, and it
 * has to be fed. It is also not a lock - "Bypassing LLM Guardrails" (arXiv 2504.11168) reports up
 * to 100% evasion against production detectors using character injection and adversarial ML, and
 * the character-injection half of that is what {@code GuardrailText} normalization answers on the
 * pattern side. Treat this as one layer among several, which is how every implementation named
 * above ships it.
 */
@Component
public class ExampleMatcher {

    private static final Logger log = LoggerFactory.getLogger(ExampleMatcher.class);

    /**
     * How much of the message is embedded.
     *
     * <p>Same reasoning as the policy retriever's: a very long query retrieves worse, not better,
     * because one vector averaged over many paragraphs sits near none of them - and an attack
     * announces itself early.
     */
    private static final int MAX_QUERY_CHARS = 2000;

    /** A matched example: which document it came from, and how close it was. */
    public record ExampleMatch(String documentId, String documentName, double score) {}

    private final VectorStoreService vectorStoreService;
    private final PolicyLibrary library;

    public ExampleMatcher(VectorStoreService vectorStoreService, PolicyLibrary library) {
        this.vectorStoreService = vectorStoreService;
        this.library = library;
    }

    /**
     * The nearest known example at or above {@code threshold}, or empty.
     *
     * <p>Empty for every failure as well as for "nothing close" - no corpus, no embedding model,
     * a store that is down. None of those are an attack, and none should refuse a turn. Same
     * fail-open stance as the rest of this module, and for the same reason: a guardrail dependency
     * being unavailable must not take the product down with it.
     *
     * <p>topK is 1 deliberately. The decision is "is anything close enough", and the answer is
     * either the nearest neighbour or nothing - retrieving more would cost more and change no
     * outcome.
     */
    public Optional<ExampleMatch> nearest(String text, double threshold) {
        if (text == null || text.isBlank()) return Optional.empty();
        // Asked first: an empty corpus is the common case, and it has to cost nothing per turn -
        // no embedding call, no store round-trip.
        if (!library.hasIndexedDocuments(PolicyDocument.Kind.EXAMPLES)) return Optional.empty();

        String query = text.length() > MAX_QUERY_CHARS ? text.substring(0, MAX_QUERY_CHARS) : text;
        try {
            List<Document> hits = vectorStoreService.search(
                    List.of(PolicyLibrary.EXAMPLES_COLLECTION), query, 1, threshold);
            if (hits.isEmpty()) return Optional.empty();

            Document top = hits.get(0);
            String sourceId = String.valueOf(top.getMetadata().getOrDefault("source", ""));
            String name = library.displayNameForSource(sourceId).orElse("Known example");
            double score = top.getScore() == null ? threshold : top.getScore();
            return Optional.of(new ExampleMatch(sourceId, name, score));
        } catch (Exception e) {
            // Message only. The query IS the user's message, and this class must never be what
            // writes conversation content into a log.
            log.warn("[GuardrailExamples] match failed, allowing by default: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
