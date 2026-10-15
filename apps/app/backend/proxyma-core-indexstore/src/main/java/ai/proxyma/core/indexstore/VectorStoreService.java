package ai.proxyma.core.indexstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface VectorStoreService {

    void upsert(String collectionId, List<Document> documents);

    /**
     * The chunks nearest {@code query} across {@code collectionIds}, best first - each stamped with
     * the collection it was found in under {@link ChunkMetadata#COLLECTION}, because its
     * {@code source} names an item only within that collection.
     */
    List<Document> search(List<String> collectionIds, String query, int topK, double minScore);

    /**
     * Same as {@link #search(List, String, int, double)}, but embeds the query with the given
     * model instead of whatever the current global settings model is. Used by SearchService to
     * query a connector still mid model-changed reindex with the OLD model it was actually indexed
     * with (see ConnectorService.getInFlightReindexInfo()) - the default implementation ignores the
     * override and falls back to the current-model search, which is only correct for backends
     * that override this properly (both PgVectorStoreService and ChromaStoreService do).
     */
    default List<Document> search(List<String> collectionIds, String query, int topK, double minScore,
                                   EmbeddingModel embeddingModelOverride) {
        return search(collectionIds, query, topK, minScore);
    }

    /**
     * Every vector whose {@code source} metadata is {@code sourceId}. A collection that does not
     * exist has nothing to delete and is not created; a delete the store refused THROWS, so a
     * replace that could not remove a document's previous chunks fails rather than leaving them
     * beside the new ones.
     */
    void deleteBySourceMetadata(String collectionId, String sourceId);

    /**
     * The same for many sources at once - one request per few hundred rather than one per source.
     * What the knowledge graph uses to drop the name vectors of entities it deleted, which can be
     * tens of thousands when a source is switched off.
     */
    void deleteBySourceMetadata(String collectionId, Collection<String> sourceIds);

    void deleteCollection(String collectionId);

    /**
     * Replaces the live collection's contents with the shadow collection's contents: whatever is
     * at {@code liveId} goes and {@code shadowId}'s data takes its name. Used to swap in a
     * freshly-rebuilt "shadow" collection (see RegisteredConnector.reindexShadowCollectionId)
     * after a model-changed reindex completes. pgvector does it in one transaction. ChromaDB
     * renames the live collection aside before renaming the shadow over it and deletes it only
     * then, so the live name is never DELETED before its replacement exists - there is a moment,
     * between the two renames, in which a search finds no collection by that name and answers
     * nothing from it, and nothing recreates it.
     */
    void swapCollection(String liveId, String shadowId);

    /**
     * Whether this backing store can hold rows of more than one embedding dimension under the same
     * collection id at once. ChromaDB cannot (a collection's dimension is fixed at creation, hence
     * the shadow-collection-and-swap flow above); Postgres/pgvector can, via dimension-scoped
     * partial indexes - see {@code PgVectorStoreService.ensureAnnIndex}. When true, callers may
     * skip the shadow-collection dance for a model-changed reindex and replace documents in place
     * (old vector deleted, new one inserted under the current model) as each one completes,
     * instead of waiting for the whole connector to finish before anything new becomes visible.
     * Default: false (assume the stricter, ChromaDB-like constraint).
     */
    default boolean supportsMixedDimensionCollection() { return false; }

    /**
     * A short, stable name for the store actually backing this deployment - "chromadb" or
     * "pgvector". Which one is in play is decided by the Spring profile at boot, so nothing a
     * caller holds can tell them apart; a diagnostic that has to name it must ask.
     */
    default String backendName() { return "unknown"; }

    /**
     * Returns true when the backing store is reachable.
     * Used as a pre-flight check before dispatching a sync cycle to avoid
     * marking all items FAILED when the store is obviously down.
     * Implementations should make a lightweight probe call (e.g. heartbeat endpoint).
     */
    boolean isHealthy();

    /**
     * Total on-disk size in bytes of all vector data across every collection.
     * Default: 0 (unsupported by this implementation).
     */
    default long getTotalSizeBytes() { return 0; }

    /**
     * Approximate on-disk size in bytes of vector data across the given collections combined -
     * used for a single owner's (e.g. one user's) storage total.
     * Default: 0 (unsupported by this implementation).
     */
    default long getSizeBytesForCollections(List<String> collectionIds) { return 0; }

    /**
     * Approximate on-disk size in bytes of vector data, grouped per collection id - used to
     * compute many owners' totals in one call instead of one getSizeBytesForCollections() call
     * per owner. Collections absent from the result have no vector data (not zero-valued entries).
     * Default: empty map (unsupported by this implementation).
     */
    default Map<String, Long> getSizeBytesGroupedByCollection(List<String> collectionIds) { return Map.of(); }

    /**
     * Start the backing infrastructure process if managed by this service.
     * Called on @PostConstruct. Default: no-op (externally managed stores skip this).
     */
    default void startInfrastructure() throws Exception {}

    /**
     * Stop the backing infrastructure process if started by this service.
     * Called on @PreDestroy. Default: no-op.
     */
    default void stopInfrastructure() {}
}
