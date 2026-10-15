package ai.proxyma.core.guardrail.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One document in the guardrail's corpus, as the manifest records it.
 *
 * <p>NO JPA ENTITY, DELIBERATELY. Persisting this would mean a new table, and a new JPA package
 * has to be added to BOTH mode-specific lists or one edition silently fails to boot - desktop's
 * {@code ChatDataSourceConfig} twice over, server's {@code ServerDatabaseConfig} in
 * {@code proxyma-core-auth}. The corpus is a handful of files in one directory; a JSON manifest
 * beside them says everything a table would, is readable by a person looking at the data
 * directory, and cannot desynchronize from the files it describes the way a table in another
 * database can.
 *
 * <p>{@code JsonIgnoreProperties(ignoreUnknown = true)} so a manifest written by a newer build
 * still loads on an older one. The alternative is a startup failure over a field nobody reads.
 *
 * @param kind          which corpus this belongs to, and therefore how it is used
 * @param id            stable identifier, also the sourceId this document is indexed under
 * @param displayName   what a person calls it - the original filename, or a page title
 * @param fileName      the file on disk under {@code files/}, or null for a URL-sourced document
 * @param sourceUrl     where it was fetched from, or null for an uploaded file
 * @param sizeBytes     size of the stored source
 * @param addedAt       when it entered the corpus
 * @param indexedAt     when it last finished indexing, or null
 * @param status        where it is in that process
 * @param failureMessage why indexing failed, or null. Safe to show: it describes OUR conversion
 *                       of an operator's own file, never the file's contents
 * @param chunkCount    how many chunks it contributed to the index
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyDocument(Kind kind,
                             String id,
                             String displayName,
                             String fileName,
                             String sourceUrl,
                             long sizeBytes,
                             Instant addedAt,
                             Instant indexedAt,
                             Status status,
                             String failureMessage,
                             int chunkCount) {

    /**
     * Which corpus a document belongs to, and therefore how it is USED.
     *
     * <p>Not a label - it selects the mechanism.
     *
     * <ul>
     *   <li>RULES documents are prose describing what people may do. Prose is judged: the clauses
     *       relevant to a message are retrieved, and a model decides whether the message breaks
     *       one.</li>
     *   <li>EXAMPLES documents are specimens of the thing to stop - jailbreak prompts, known
     *       attacks, queries that must never be answered. Those are matched by similarity alone,
     *       with no model in the loop at all.</li>
     * </ul>
     *
     * <p>The distinction is not stylistic, and it is the whole reason there are two corpora.
     * Similarity between a MESSAGE and a RULE measures topical relatedness, so "what is our policy
     * on sharing passwords?" scores at least as high as the request to share one - threshold that
     * and you block the person asking about the rule. Similarity between a MESSAGE and another
     * MESSAGE is like-for-like, which is why it works, and why every implementation that does this
     * - Rebuff, Guardrails AI's DetectJailbreak, NeMo Guardrails' canonical forms - stores
     * utterances rather than policies.
     */
    public enum Kind {
        /** Prose rules. Retrieved, then judged by the classifier. */
        RULES,
        /** Specimens of what to block. Matched by similarity, no model call. */
        EXAMPLES
    }

    public enum Status {
        /** Accepted and stored, not yet converted or embedded. */
        PENDING,
        /** In the index and retrievable. */
        INDEXED,
        /** Conversion or embedding failed; {@code failureMessage} says why. */
        FAILED
    }

    /**
     * A manifest entry written before this field existed is RULES.
     *
     * <p>Read through this rather than the accessor everywhere it matters: the examples corpus was
     * added second, so every document already on disk has a null kind, and a null here would send
     * an existing policy document into the examples collection - where it would be matched by raw
     * similarity, which is exactly the mistake the two kinds exist to prevent.
     */
    public Kind kindOrDefault() {
        return kind == null ? Kind.RULES : kind;
    }

    public PolicyDocument indexed(int chunks) {
        return new PolicyDocument(kindOrDefault(), id, displayName, fileName, sourceUrl, sizeBytes,
                addedAt, Instant.now(), Status.INDEXED, null, chunks);
    }

    // The chunk count is KEPT on a failure: it is what bounds the delete of what a partial run
    // wrote, and a zero left examples #64 and up in the index for ever.
    public PolicyDocument failed(String message) {
        return new PolicyDocument(kindOrDefault(), id, displayName, fileName, sourceUrl, sizeBytes,
                addedAt, indexedAt, Status.FAILED, message, chunkCount);
    }

    /** The same document, with a chunk count no lower than {@code n} - see PolicyLibrary.indexExamples. */
    public PolicyDocument withChunkBound(int n) {
        return new PolicyDocument(kindOrDefault(), id, displayName, fileName, sourceUrl, sizeBytes,
                addedAt, indexedAt, status, failureMessage, Math.max(chunkCount, n));
    }

    public PolicyDocument pending() {
        return new PolicyDocument(kindOrDefault(), id, displayName, fileName, sourceUrl, sizeBytes,
                addedAt, indexedAt, Status.PENDING, null, chunkCount);
    }
}
