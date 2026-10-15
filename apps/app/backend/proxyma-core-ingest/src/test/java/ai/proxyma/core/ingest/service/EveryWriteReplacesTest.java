package ai.proxyma.core.ingest.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every write of a document replaces what it wrote before - and a resumed one keeps it.
 *
 * <p>Chunk ids are random, so a write that did not delete first ADDED: a changed document kept its
 * old text in the vector store beside the new, and dense search could cite either (ingest-01). And
 * the one path that did delete - a reindex - deleted on a crash-resume too, after which only the
 * chunks past the resume offset were written back, so the document's first chunks were gone from
 * dense search for good (ingest-04).
 */
class EveryWriteReplacesTest {

    private final ChunkingService chunking = mock(ChunkingService.class);
    private final VectorStoreService vectors = mock(VectorStoreService.class);
    private final BM25IndexService keywords = mock(BM25IndexService.class);
    private final EmbeddingService service = new EmbeddingService(chunking, vectors, keywords);

    @BeforeEach
    void hundredAndTwentyChunks() {
        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < 120; i++) chunks.add(new Document("chunk " + i));
        when(chunking.chunk(anyString(), anyString(), any(), any())).thenReturn(chunks);
    }

    @Test
    @DisplayName("ingest-01: a write from the first chunk removes the document's previous vectors before writing any")
    void aFreshWriteReplaces() {
        service.chunkAndEmbed("collection", "invoicing-policy.md", "sixty days", null, 0, null, "files", null);

        InOrder order = inOrder(vectors);
        order.verify(vectors).deleteBySourceMetadata("collection", "invoicing-policy.md");
        order.verify(vectors, times(3)).upsert(eq("collection"), anyList());
    }

    @Test
    @DisplayName("ingest-04: a resumed write removes nothing, and writes only from where the interrupted one stopped")
    @SuppressWarnings("unchecked")
    void aResumedWriteKeepsWhatWasWritten() {
        service.chunkAndEmbed("collection", "manual.pdf", "text", null, 100, null, "files", null);

        verify(vectors, never()).deleteBySourceMetadata(anyString(), anyString());
        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        verify(vectors).upsert(eq("collection"), batches.capture());
        assertThat(batches.getValue()).extracting(Document::getText).first().isEqualTo("chunk 100");
        assertThat(batches.getValue()).hasSize(20);
    }

    @Test
    @DisplayName("a failed embedding takes back the batches it already wrote, so a FAILED document is in neither index")
    void aFailedWriteLeavesNothingHalfWritten() {
        // The second of three batches fails; the first is already in.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("the embedding provider is down");
            return null;
        }).when(vectors).upsert(anyString(), anyList());

        assertThatThrownBy(() -> service.chunkAndEmbed("collection", "doc", "text", null, 0, null, "files", null))
                .isInstanceOf(IllegalStateException.class);

        // Once before the write, once to take the failed write back.
        verify(vectors, times(2)).deleteBySourceMetadata("collection", "doc");
        verify(keywords).deleteDocuments("collection", "doc");
    }

    @Test
    @DisplayName("ingest-13: a keyword write that failed fails the document instead of completing it unfindable by its words")
    void aFailedKeywordWriteFailsTheDocument() {
        doThrow(new UncheckedIOException("disk full", new java.io.IOException("disk full")))
                .when(keywords).addDocuments(anyString(), anyString(), anyList(), any(), any());

        assertThatThrownBy(() -> service.chunkAndEmbed("collection", "doc", "text", null, 0, null, "files", null))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("a document that now says nothing loses what it said before")
    void anEmptiedDocumentIsRemoved() {
        when(chunking.chunk(anyString(), anyString(), any(), any())).thenReturn(List.of());

        assertThat(service.chunkAndEmbed("collection", "doc", "   ", null, 0, null, "files", null)).isEmpty();

        verify(vectors).deleteBySourceMetadata("collection", "doc");
        verify(keywords).deleteDocuments("collection", "doc");
        verify(vectors, never()).upsert(anyString(), anyList());
    }

    @Test
    @DisplayName("removing a deleted document tries both indexes and does not throw at the caller")
    void deletingIsTolerant() {
        doThrow(new IllegalStateException("chroma is restarting"))
                .when(vectors).deleteBySourceMetadata("collection", "doc");

        service.deleteBySourceId("collection", "doc");

        verify(keywords).deleteDocuments("collection", "doc");
    }
}
