package ai.proxyma.core.indexstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Profile("server")
@Service
@org.springframework.context.annotation.Primary
public class PgVectorStoreService implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreService.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    // Dimensions we've already ensured a partial HNSW index for - unlike a single global dimension,
    // multiple entries can be present at once while a connector is mid model-changed reindex (old
    // and new dimension rows coexist under the same collection_id; see ensureAnnIndex()).
    private final Set<Integer> indexedDimensions = ConcurrentHashMap.newKeySet();

    // Gates how many batch-embed HTTP calls may be in flight to the provider at once - a global,
    // chunk-level limit shared by every caller of upsert() regardless of which connector/thread it
    // came from. Live from the setting since 2026-10-01 - see EmbeddingConcurrency. The embedding
    // provider's real capacity (e.g. Ollama's NUM_PARALLEL) is a request-level limit, not a
    // connector-level one - see SyncOrchestratorService.MAX_CONCURRENT_CONNECTOR_DRAINS for the
    // (separate) cap on concurrent connector fetch/convert work.
    private final EmbeddingConcurrency embeddingConcurrency;

    // Whether this pgvector build exposes the hnsw.ef_search GUC (0.5.0+). Probed once at init -
    // issuing an unknown SET inside the search transaction would abort the whole transaction.
    private volatile boolean efSearchSupported;

    // Whether this pgvector build can keep an HNSW scan going until a FILTERED query has its rows
    // (hnsw.iterative_scan, 0.8.0+). Probed the same way, for the same reason.
    //
    // WHY IT MATTERS. An HNSW scan yields its nearest candidates across the WHOLE table and the
    // collection filter is applied to what comes back. On a server where one user's sources are a
    // small part of that table, the nearest ef_search candidates can all be somebody else's, and
    // the query returns fewer rows than asked for - or none - while the right chunks exist. With
    // iterative scan the index is asked for more until the limit is met (bounded by
    // hnsw.max_scan_tuples). relaxed_order, because the outer query sorts by score anyway.
    private volatile boolean iterativeScanSupported;

    public PgVectorStoreService(DataSource dataSource,
                                EmbeddingModel embeddingModel,
                                ObjectMapper objectMapper,
                                EmbeddingConcurrency embeddingConcurrency) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.embeddingConcurrency = embeddingConcurrency;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vector_documents (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    collection_id VARCHAR(64) NOT NULL,
                    source_id TEXT NOT NULL,
                    content TEXT,
                    embedding vector,
                    metadata JSONB DEFAULT '{}'
                )
                """);

        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_vector_documents_collection
                ON vector_documents (collection_id)
                """);

        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_vector_documents_source
                ON vector_documents (collection_id, source_id)
                """);

        log.info("[PgVector] Initialized vector_documents table");

        // Extension GUCs only become visible to a backend once the extension's library is loaded
        // there - force-load it before probing ON THE SAME CONNECTION (JdbcTemplate hands each
        // call a pooled connection, so two separate calls may hit different backends), or SHOW
        // fails with "unrecognized configuration parameter" even on pgvector versions that have
        // the GUC.
        try {
            Boolean supported = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) con -> {
                try (var st = con.createStatement()) {
                    st.execute("SELECT '[1]'::vector");
                    st.execute("SHOW hnsw.ef_search");
                } catch (SQLException e) {
                    log.info("[PgVector] hnsw.ef_search not available (pgvector < 0.5?): {}", e.getMessage());
                    return false;
                }
                // Its own statement and its own catch: an older pgvector has the first and not this.
                try (var st = con.createStatement()) {
                    st.execute("SHOW hnsw.iterative_scan");
                    iterativeScanSupported = true;
                } catch (SQLException e) {
                    log.info("[PgVector] hnsw.iterative_scan not available (pgvector < 0.8) - a search filtered to "
                            + "a small part of a large table may return fewer results than asked for: {}",
                            e.getMessage());
                }
                return true;
            });
            efSearchSupported = Boolean.TRUE.equals(supported);
        } catch (Exception e) {
            log.info("[PgVector] hnsw.ef_search probe failed: {}", e.getMessage());
        }

        // Recover already-indexed dimensions cheaply from existing index names first (fast - no
        // table scan). Only fall back to scanning actual data (a full-table DISTINCT) if the table
        // has rows but no index yet, e.g. first startup after upgrading from the old single-index
        // scheme.
        try {
            List<String> existing = jdbc.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'vector_documents' "
                            + "AND indexname LIKE 'idx_vector_documents_hnsw_%'", String.class);
            for (String idx : existing) {
                try {
                    int dims = Integer.parseInt(idx.substring(idx.lastIndexOf('_') + 1));
                    if (indexedDimensions.add(dims)) {
                        log.info("[PgVector] Recovered existing ANN index for dimension {}", dims);
                    }
                } catch (NumberFormatException ignored) {
                    // Not a dimension-suffixed index name - ignore.
                }
            }
        } catch (Exception e) {
            log.debug("[PgVector] Could not enumerate existing ANN indexes: {}", e.getMessage());
        }

        if (indexedDimensions.isEmpty()) {
            try {
                List<Integer> dims = jdbc.queryForList(
                        "SELECT DISTINCT vector_dims(embedding) AS dims FROM vector_documents "
                                + "WHERE embedding IS NOT NULL", Integer.class);
                for (Integer dim : dims) {
                    if (dim != null && dim > 0) onDimensionDiscovered(dim);
                }
            } catch (Exception e) {
                log.debug("[PgVector] Could not detect existing embedding dimension: {}", e.getMessage());
            }
        }
    }

    @Override
    public void upsert(String collectionId, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;

        List<float[]> embeddings;
        embeddingConcurrency.acquire();
        try {
            // Batched, token-limit-aware call - TokenCountBatchingStrategy groups the whole list
            // into the fewest sub-batches that stay under the model's token ceiling (preserving
            // input order) and issues one HTTP call per sub-batch, instead of one call per chunk.
            embeddings = embeddingModel.embed(
                    documents, EmbeddingOptions.builder().build(), new TokenCountBatchingStrategy());
        } finally {
            embeddingConcurrency.release();
        }

        // ONE BATCH, not a statement per chunk: a document of a few hundred chunks was that many
        // round trips to the database, each its own commit.
        List<Object[]> rows = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] embedding = embeddings.get(i);
            onDimensionDiscovered(embedding.length);

            String vecLiteral = toVectorLiteral(embedding);
            String metaJson = toJson(doc.getMetadata());

            String docId = doc.getId();
            if (docId == null || docId.isBlank()) {
                docId = UUID.randomUUID().toString();
            }
            String sourceId = doc.getMetadata() != null
                    ? Objects.toString(doc.getMetadata().get("source"), docId)
                    : docId;

            rows.add(new Object[]{docId, collectionId, sourceId, doc.getText(), vecLiteral, metaJson});
        }
        jdbc.batchUpdate("""
                INSERT INTO vector_documents (id, collection_id, source_id, content, embedding, metadata)
                VALUES (?::uuid, ?, ?, ?, ?::vector, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata
                """, rows);

        log.debug("[PgVector] upsert: collection={} docs={}", collectionId, documents.size());
    }

    @Override
    public List<Document> search(List<String> collectionIds, String query, int topK, double minScore) {
        return search(collectionIds, query, topK, minScore, embeddingModel);
    }

    @Override
    public List<Document> search(List<String> collectionIds, String query, int topK, double minScore,
                                  EmbeddingModel embeddingModelOverride) {
        if (collectionIds == null || collectionIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        float[] queryEmbedding = embeddingModelOverride.embed(query);
        String vecLiteral = toVectorLiteral(queryEmbedding);
        String distanceExpr = distanceExpression(queryEmbedding.length);

        String placeholders = collectionIds.stream().map(id -> "?").collect(Collectors.joining(","));

        // Inner query: ORDER BY distance + LIMIT is the shape the HNSW expression index can
        // serve. The minScore filter lives in the OUTER query - putting it in the inner WHERE
        // would force a sequential scan, discarding the index entirely.
        // vector_dims(embedding) = ? both lets the planner pick the dimension-matching partial
        // index (see ensureAnnIndex()) and guards correctness: a collection_id can hold a mix of
        // old- and new-dimension rows while a connector is mid model-changed reindex, and casting a
        // wrong-dimension row to this query's cast type would error.
        String sql = String.format("""
                SELECT * FROM (
                    SELECT id, collection_id, source_id, content, metadata,
                           1 - (%s) AS score
                    FROM vector_documents
                    WHERE collection_id IN (%s) AND vector_dims(embedding) = ?
                    ORDER BY %s
                    LIMIT ?
                ) candidates
                WHERE score >= ?
                ORDER BY score DESC
                """, distanceExpr, placeholders, distanceExpr);

        List<Object> params = new ArrayList<>();
        params.add(vecLiteral);
        params.addAll(collectionIds);
        params.add(queryEmbedding.length);
        params.add(vecLiteral);
        params.add(Math.min(topK, MAX_CANDIDATES));
        params.add(minScore);

        // hnsw.ef_search (default 40) caps how many candidates an HNSW scan yields; a LIMIT
        // above it silently truncates results. SET LOCAL needs the query in the same
        // transaction, hence the single-connection callback.
        int efSearch = efSearchFor(topK);
        List<Document> results = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<List<Document>>) con -> {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                if (efSearchSupported) {
                    try (var st = con.createStatement()) {
                        st.execute("SET LOCAL hnsw.ef_search = " + efSearch);
                    }
                }
                if (iterativeScanSupported) {
                    try (var st = con.createStatement()) {
                        st.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                    }
                }
                try (var ps = con.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    List<Document> docs = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) docs.add(mapRow(rs));
                    }
                    con.commit();
                    return docs;
                }
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(previousAutoCommit);
            }
        });
        log.debug("[PgVector] search: collections={} query='{}' topK={} results={}",
                collectionIds.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query,
                topK, results != null ? results.size() : 0);
        return results != null ? results : List.of();
    }

    /**
     * pgvector's own range for hnsw.ef_search is 1..1000, and a SET outside it is an ERROR that
     * aborts the search: SearchService asks for ten candidates per result wanted, so a request for
     * 51 results asked for 1,020 and failed with a 500. Clamped, and so is the candidate LIMIT - past
     * the scan's own ceiling more rows cannot come back anyway.
     */
    static final int MAX_EF_SEARCH = 1000;
    static final int MAX_CANDIDATES = MAX_EF_SEARCH;

    static int efSearchFor(int topK) {
        return Math.max(100, Math.min(MAX_EF_SEARCH, Math.min(topK, MAX_CANDIDATES) * 2));
    }

    @Override
    public void deleteBySourceMetadata(String collectionId, String sourceId) {
        int deleted = jdbc.update(
                "DELETE FROM vector_documents WHERE collection_id = ? AND source_id = ?",
                collectionId, sourceId);
        log.debug("[PgVector] deleteBySourceMetadata: collection={} source={} rows={}", collectionId, sourceId, deleted);
    }

    /** Sources per DELETE - one bind parameter each, well under any driver's limit. */
    private static final int DELETE_BATCH = 500;

    @Override
    public void deleteBySourceMetadata(String collectionId, Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return;
        List<String> all = List.copyOf(sourceIds);
        int deleted = 0;
        for (int i = 0; i < all.size(); i += DELETE_BATCH) {
            List<String> batch = all.subList(i, Math.min(i + DELETE_BATCH, all.size()));
            List<Object> params = new ArrayList<>(batch.size() + 1);
            params.add(collectionId);
            params.addAll(batch);
            deleted += jdbc.update("DELETE FROM vector_documents WHERE collection_id = ? AND source_id IN ("
                    + batch.stream().map(id -> "?").collect(Collectors.joining(",")) + ")", params.toArray());
        }
        log.debug("[PgVector] deleteBySourceMetadata: collection={} sources={} rows={}", collectionId, all.size(), deleted);
    }

    @Override
    public void deleteCollection(String collectionId) {
        int deleted = jdbc.update(
                "DELETE FROM vector_documents WHERE collection_id = ?", collectionId);
        log.info("[PgVector] deleteCollection: {} ({} rows)", collectionId, deleted);
    }

    // One transaction: delete whatever currently occupies collection_id=liveId, then re-key every
    // shadowId row over to liveId. Wrapped in a single JDBC connection with manual commit/rollback
    // (this class has no injected PlatformTransactionManager - it only ever talks to the DB via
    // the plain JdbcTemplate already used everywhere else here) so the two statements commit or
    // roll back together rather than leaving liveId rows deleted with the shadow rows not yet
    // moved.
    @Override
    public void swapCollection(String liveId, String shadowId) {
        try {
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                boolean previousAutoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try (var deleteLive = con.prepareStatement(
                            "DELETE FROM vector_documents WHERE collection_id = ?");
                     var moveShadow = con.prepareStatement(
                            "UPDATE vector_documents SET collection_id = ? WHERE collection_id = ?")) {
                    deleteLive.setString(1, liveId);
                    int deleted = deleteLive.executeUpdate();

                    moveShadow.setString(1, liveId);
                    moveShadow.setString(2, shadowId);
                    int moved = moveShadow.executeUpdate();

                    con.commit();
                    log.info("[PgVector] swapCollection: {} -> {} (deleted {} live rows, moved {} shadow rows)",
                            shadowId, liveId, deleted, moved);
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(previousAutoCommit);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("[PgVector] swapCollection failed shadow={} -> live={}: {}", shadowId, liveId, e.getMessage(), e);
            throw new VectorStoreException("PgVector collection swap failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String backendName() { return "pgvector"; }

    @Override
    public boolean isHealthy() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.debug("[PgVector] health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public long getTotalSizeBytes() {
        try {
            Long size = jdbc.queryForObject("SELECT pg_total_relation_size('vector_documents')", Long.class);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.debug("[PgVector] getTotalSizeBytes failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getSizeBytesForCollections(List<String> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) return 0;
        String placeholders = collectionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        try {
            Long size = jdbc.queryForObject(String.format("""
                    SELECT COALESCE(SUM(pg_column_size(content) + pg_column_size(embedding) + pg_column_size(metadata)), 0)
                    FROM vector_documents WHERE collection_id IN (%s)
                    """, placeholders), Long.class, collectionIds.toArray());
            return size != null ? size : 0;
        } catch (Exception e) {
            log.debug("[PgVector] getSizeBytesForCollections failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public Map<String, Long> getSizeBytesGroupedByCollection(List<String> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) return Map.of();
        String placeholders = collectionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        try {
            Map<String, Long> result = new HashMap<>();
            jdbc.query(String.format("""
                    SELECT collection_id, COALESCE(SUM(pg_column_size(content) + pg_column_size(embedding) + pg_column_size(metadata)), 0) AS bytes
                    FROM vector_documents WHERE collection_id IN (%s) GROUP BY collection_id
                    """, placeholders), rs -> {
                result.put(rs.getString("collection_id"), rs.getLong("bytes"));
            }, collectionIds.toArray());
            return result;
        } catch (Exception e) {
            log.debug("[PgVector] getSizeBytesGroupedByCollection failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Document mapRow(ResultSet rs) throws SQLException {
        String content = rs.getString("content");
        String metaJson = rs.getString("metadata");
        double score = rs.getDouble("score");

        Map<String, Object> metadata = fromJson(metaJson);
        // The collection the row belongs to - ChunkMetadata.COLLECTION, which every store stamps:
        // its source_id is unique only within it.
        metadata.put(ChunkMetadata.COLLECTION, rs.getString("collection_id"));
        metadata.put("source_id", rs.getString("source_id"));

        return Document.builder()
                .id(rs.getString("id"))
                .text(content)
                .metadata(metadata)
                .score(score)
                .build();
    }

    private void onDimensionDiscovered(int dim) {
        if (indexedDimensions.add(dim)) {
            log.info("[PgVector] Detected new embedding dimension: {}", dim);
            ensureAnnIndex(dim);
        }
    }

    // Postgres can hold rows of more than one embedding dimension in the same table (and even the
    // same collection_id, mid model-changed reindex) - see ensureAnnIndex()'s partial-index scheme
    // below. ChromaDB cannot: a collection's dimension is fixed at creation.
    @Override
    public boolean supportsMixedDimensionCollection() {
        return true;
    }

    // pgvector's HNSW index caps at 2000 dims for the vector type and 4000 for halfvec. The
    // embedding column stays untyped (dimension is a runtime property of the configured model),
    // so the index is built over a cast expression and queries must use the exact same
    // expression to be served by it - distanceExpression() below is that single source of truth.
    private static String castType(int dims) {
        if (dims <= 2000) return "vector(" + dims + ")";
        if (dims <= 4000) return "halfvec(" + dims + ")";
        return null;
    }

    private String distanceExpression(int dims) {
        String cast = castType(dims);
        if (cast == null) return "embedding <=> ?::vector";
        return "embedding::" + cast + " <=> ?::" + cast;
    }

    // Builds a PARTIAL index, scoped to rows of exactly this dimension via pgvector's own
    // vector_dims() function. This is what lets two different embedding-model dimensions coexist
    // safely in the same table (and even the same collection_id, mid model-changed reindex): a
    // partial index's WHERE predicate is evaluated before its indexed expression, so a row of a
    // different actual dimension never hits this index's cast - unlike the old scheme, other
    // dimensions' indexes are never dropped here.
    private void ensureAnnIndex(int dims) {
        String cast = castType(dims);
        if (cast == null) {
            log.warn("[PgVector] Embedding dimension {} exceeds pgvector's HNSW limit (4000 with "
                    + "halfvec) - no ANN index possible, every search will sequentially scan the "
                    + "whole table. Configure an embedding model with <= 4000 output dimensions "
                    + "and reindex.", dims);
            return;
        }
        String opClass = dims <= 2000 ? "vector_cosine_ops" : "halfvec_cosine_ops";
        String indexName = "idx_vector_documents_hnsw_" + dims;
        try {
            log.info("[PgVector] Ensuring partial HNSW index {} over embedding::{} WHERE "
                    + "vector_dims(embedding) = {} (may take a while on a populated table)",
                    indexName, cast, dims);
            jdbc.execute(String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON vector_documents USING hnsw ((embedding::%s) %s) "
                            + "WHERE vector_dims(embedding) = %d",
                    indexName, cast, opClass, dims));
        } catch (Exception e) {
            log.error("[PgVector] Failed to ensure ANN index for dimension {}: {} - searches for "
                    + "this dimension will fall back to sequential scans", dims, e.getMessage());
        }
    }

    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
