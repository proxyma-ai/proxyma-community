package ai.proxyma.core.ingest.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.ingest.service.ChunkingService.ContentType;
import ai.proxyma.core.indexstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ChunkingService chunkingService;
    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;

    public EmbeddingService(ChunkingService chunkingService,
                            VectorStoreService vectorStoreService,
                            BM25IndexService bm25IndexService) {
        this.chunkingService = chunkingService;
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
    }

    @FunctionalInterface
    public interface ChunkProgressCallback {
        void onProgress(int processedChunks, int totalChunks);
    }

    /** One short Markdown text, written whole - see the full form below. */
    public List<Document> chunkAndEmbed(String collectionId, String sourceId, String markdown) {
        return chunkAndEmbed(collectionId, sourceId, markdown, null, 0, null, null, null);
    }

    /**
     * Chunk and embed a document into the collection identified by collectionId, REPLACING whatever
     * that document had there before. The sourceId identifies the document within the collection
     * (used as metadata).
     *
     * <p><b>Every write is a replace</b>, since 2026-10-14. Chunk ids are random, so a write that
     * did not first remove the document's previous chunks ADDED to them: a changed document kept
     * its old text in the vector store beside the new one, and dense search could cite either. Only
     * the keyword index was right, because it has always replaced by source. So a write from the
     * first chunk ({@code startOffset} 0) removes the document's previous vectors first, and a
     * RESUMED write (a crash mid-embedding, {@code startOffset} > 0) removes nothing - the chunks
     * before the offset are the ones the interrupted run already wrote, and deleting them left a
     * document COMPLETED with its first chunks missing from dense search for good.
     *
     * <p>Returns the chunks it produced. They were already computed here, and knowledge-graph
     * extraction needs exactly the same ones the vector store holds - re-deriving them at the
     * caller would run the splitter twice and, the first time chunking parameters changed, would
     * silently start attributing graph mentions to chunk indexes the index does not share.
     *
     * @param contentType the MIME type the text came in as, or null for Markdown. Anything that is
     *                    plain text rather than Markdown (code, configuration, CSV) is chunked
     *                    without looking for headings - a shell script's {@code # comment} is not
     *                    a section (see {@link ChunkingService.ContentType#of}). It must be the
     *                    same on a resumed write as on the first, or the offsets disagree.
     */
    public List<Document> chunkAndEmbed(String collectionId, String sourceId, String text, String contentType,
                                        int startOffset, String url, String connectorType,
                                        ChunkProgressCallback progressCallback) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", sourceId);
        if (url != null && !url.isBlank()) meta.put("url", url);
        if (connectorType != null && !connectorType.isBlank()) meta.put("connectorType", connectorType);
        List<Document> chunks = chunkingService.chunk(sourceId, text, meta, ContentType.of(contentType));
        int totalChunks = chunks.size();
        int safeOffset = Math.min(Math.max(0, startOffset), totalChunks);
        if (safeOffset == 0) {
            // Before anything is written, and on the caller's thread: the keyword write below
            // replaces by source on its own, the vector store does not.
            vectorStoreService.deleteBySourceMetadata(collectionId, sourceId);
        }
        if (chunks.isEmpty()) {
            log.warn("[EmbeddingService] No chunks produced for sourceId={}", sourceId);
            // Nothing new to say, so nothing old may keep saying it either.
            bm25IndexService.deleteDocuments(collectionId, sourceId);
            if (progressCallback != null) progressCallback.onProgress(0, 0);
            return List.of();
        }
        if (safeOffset > 0) {
            log.info("[EmbeddingService] Resuming from chunk {} of {} for sourceId={}", safeOffset, totalChunks, sourceId);
        }
        if (progressCallback != null) progressCallback.onProgress(safeOffset, totalChunks);

        // THE KEYWORD INDEX AND THE VECTORS ARE WRITTEN SIDE BY SIDE, since 2026-10-02 - the same
        // chunks, on two threads, neither waiting for the other. They were written one after the
        // other, keywords last, so a document was unfindable by its exact words for as long as its
        // embedding took (minutes, on a slow model) although the keyword write needs no model at
        // all.
        //
        // addDocuments replaces a source's chunks rather than adding to them, so a resumed
        // document (startOffset > 0) writing them a second time is harmless.
        List<String> texts = chunks.stream().map(Document::getText).toList();
        CompletableFuture<Void> keywords = CompletableFuture.runAsync(
                () -> bm25IndexService.addDocuments(collectionId, sourceId, texts, url, connectorType),
                KEYWORD_WRITER);
        try {
            int batchSize = 50;
            for (int i = safeOffset; i < totalChunks; i += batchSize) {
                int end = Math.min(i + batchSize, totalChunks);
                vectorStoreService.upsert(collectionId, chunks.subList(i, end));
                if (progressCallback != null) progressCallback.onProgress(end, totalChunks);
            }
        } catch (RuntimeException embeddingFailed) {
            // What held before still holds: a document whose embedding failed is in neither index.
            // Wait for the keyword write that is under way, then take it back out, and the batches
            // already embedded with it, so a FAILED document is not half findable; the retry starts
            // from the first chunk and writes both again.
            try {
                keywords.join();
            } catch (RuntimeException alsoFailed) {
                embeddingFailed.addSuppressed(alsoFailed);
            }
            try {
                bm25IndexService.deleteDocuments(collectionId, sourceId);
                vectorStoreService.deleteBySourceMetadata(collectionId, sourceId);
            } catch (RuntimeException cleanupFailed) {
                embeddingFailed.addSuppressed(cleanupFailed);
            }
            throw embeddingFailed;
        }
        try {
            keywords.join();
        } catch (CompletionException e) {
            // A keyword write that failed fails the document, so it is retried rather than marked
            // COMPLETED with no keyword entries - unfindable by a ticket key or an error code.
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
        log.debug("[EmbeddingService] chunkAndEmbed: collectionId={} sourceId={} chunks={} startOffset={}",
                collectionId, sourceId, chunks.size(), safeOffset);
        return chunks;
    }

    // One short-lived thread per document for the keyword write: it is local disk I/O with no model
    // call, and a virtual thread costs nothing to park while Lucene holds its per-collection lock.
    private static final Executor KEYWORD_WRITER =
            task -> Thread.ofVirtual().name("keyword-index").start(task);

    /**
     * A document's chunks out of both indexes. Each store is tried even when the other fails, and a
     * failure is logged rather than thrown: the callers are removing a document whose row already
     * says DELETED, and an exception here would only stop them removing the next one.
     */
    public void deleteBySourceId(String collectionId, String sourceId) {
        try {
            vectorStoreService.deleteBySourceMetadata(collectionId, sourceId);
        } catch (RuntimeException e) {
            log.warn("[EmbeddingService] vectors of sourceId={} left in collection {}: {}",
                    sourceId, collectionId, e.getMessage());
        }
        try {
            bm25IndexService.deleteDocuments(collectionId, sourceId);
        } catch (RuntimeException e) {
            log.warn("[EmbeddingService] keyword entries of sourceId={} left in collection {}: {}",
                    sourceId, collectionId, e.getMessage());
        }
        log.debug("[EmbeddingService] deleteBySourceId: collectionId={} sourceId={}", collectionId, sourceId);
    }
}
