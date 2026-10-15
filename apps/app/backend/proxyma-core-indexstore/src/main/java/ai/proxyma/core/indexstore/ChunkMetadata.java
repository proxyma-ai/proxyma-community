package ai.proxyma.core.indexstore;

import org.springframework.ai.document.Document;

/**
 * What a chunk RETRIEVED from an index says about where it was found.
 *
 * <p>A chunk's {@code source} metadata is its item's sourceId, and a sourceId is unique only
 * WITHIN one collection: a connector names its items as it likes, and the SaaS ones use short ids
 * such as {@code ticket:1} or {@code issue:1}, so two sources routinely hold the same one. A hit
 * identified by its {@code source} alone was resolved against whichever source answered first -
 * a Freshdesk result for {@code ticket:1} came back under the Zendesk ticket's title.
 *
 * <p>So every store stamps {@link #COLLECTION} on every chunk its search returns - ChromaDB and
 * BM25 per collection they ask, pgvector from the row's own column - and an item is
 * (collection, source), never source alone. Stamped at RETRIEVAL, not at write: nothing has to be
 * re-indexed for it, a collection renamed by a reindex swap answers under its new name, and no
 * stored chunk can carry a stale one.
 */
public final class ChunkMetadata {

    /**
     * The collection the chunk was retrieved from, exactly as the store was asked for it - a
     * model-changed reindex's shadow ({@code <id>_reindex}) included, which the registry maps back
     * to its source. The same key, and value, pgvector has always put on its rows' metadata.
     */
    public static final String COLLECTION = "collection_id";

    private ChunkMetadata() {
    }

    /** The collection {@code chunk} was retrieved from, or null when nothing stamped one. */
    public static String collectionOf(Document chunk) {
        Object c = chunk == null ? null : chunk.getMetadata().get(COLLECTION);
        return c == null || c.toString().isBlank() ? null : c.toString();
    }
}
