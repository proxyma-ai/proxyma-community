package ai.proxyma.core.graph.service;

/**
 * Every extraction call for one document failed to reach a model.
 *
 * <p><b>This exists because "the model found nothing" and "there was no model" were the same
 * answer, and the difference is everything.</b> A refused connection returned an empty graph per
 * chunk exactly as a model replying "no entities here" does, so a document extracted during an
 * outage was recorded as READ and never looked at again. On 2026-09-16 that marked all 20
 * documents of a real source as extracted, with nothing in the graph, in half a second - Ollama
 * was simply not running.
 *
 * <p>Thrown when a read was ABANDONED - a run of consecutive chunks no model answered - or when no
 * chunk at all was answered. Nothing is written for such a document: its previous graph stands and
 * it is read again once a model answers. A read where only some chunks went unanswered is not this:
 * it is written, recorded as partial, and its missing chunks are read on a later pass.
 *
 * <p>Handled like the quota refusal it resembles: the document goes back to PENDING without
 * spending a retry, and the worker stands down rather than marching through the rest of the corpus
 * failing identically.
 */
public class GraphModelUnreachableException extends RuntimeException {

    private final String lastCallFailure;

    public GraphModelUnreachableException(String message) {
        this(message, null);
    }

    /** @param lastCallFailure why the last call failed, in words - see GraphExtractionService.lastFailure */
    public GraphModelUnreachableException(String message, String lastCallFailure) {
        super(message);
        this.lastCallFailure = lastCallFailure;
    }

    /** Why the last call that reached no model failed, in words; null when nothing said. */
    public String lastCallFailure() {
        return lastCallFailure;
    }
}
