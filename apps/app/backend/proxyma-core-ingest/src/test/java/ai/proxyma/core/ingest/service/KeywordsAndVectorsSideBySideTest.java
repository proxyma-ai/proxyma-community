package ai.proxyma.core.ingest.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The keyword index and the vectors are written side by side.
 *
 * <p>Until 2026-10-02 they were not: the keyword write came after the last embedding batch, so a
 * document could not be found by its exact words for as long as its embedding took - minutes, on a
 * slow model - although the keyword write needs no model at all. The first case here cannot pass
 * if one write waits for the other.
 */
class KeywordsAndVectorsSideBySideTest {

    private final ChunkingService chunking = mock(ChunkingService.class);
    private final VectorStoreService vectors = mock(VectorStoreService.class);
    private final BM25IndexService keywords = mock(BM25IndexService.class);
    private final EmbeddingService service = new EmbeddingService(chunking, vectors, keywords);

    @BeforeEach
    void twoChunks() {
        when(chunking.chunk(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(new Document("the first chunk"), new Document("the second chunk")));
    }

    @Test
    @DisplayName("each write is under way before the other has finished")
    void neitherWaitsForTheOther() {
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch keywordsStarted = new CountDownLatch(1);
        AtomicBoolean embeddingSawKeywords = new AtomicBoolean();
        AtomicBoolean keywordsSawEmbedding = new AtomicBoolean();
        // Each holds its write open until it has seen the other begin. Written one after the
        // other, whichever goes first waits out its five seconds and records that it saw nothing.
        doAnswer(inv -> {
            embeddingStarted.countDown();
            embeddingSawKeywords.set(keywordsStarted.await(5, TimeUnit.SECONDS));
            return null;
        }).when(vectors).upsert(anyString(), anyList());
        doAnswer(inv -> {
            keywordsStarted.countDown();
            keywordsSawEmbedding.set(embeddingStarted.await(5, TimeUnit.SECONDS));
            return null;
        }).when(keywords).addDocuments(anyString(), anyString(), anyList(), any(), any());

        List<Document> chunks = service.chunkAndEmbed("collection", "doc-1", "text", null, 0, null, "files", null);

        assertThat(chunks).hasSize(2);
        assertThat(embeddingSawKeywords).as("the keyword write began while embedding was under way").isTrue();
        assertThat(keywordsSawEmbedding).as("embedding began while the keyword write was under way").isTrue();
    }

    @Test
    @DisplayName("both stores get the same chunks")
    void bothGetTheSameChunks() {
        service.chunkAndEmbed("collection", "doc-1", "text", null, 0, "https://example/doc", "files", null);

        verify(vectors).upsert(eq("collection"), anyList());
        verify(keywords).addDocuments("collection", "doc-1",
                List.of("the first chunk", "the second chunk"), "https://example/doc", "files");
    }

    @Test
    @DisplayName("a document whose embedding failed is taken back out of the keyword index")
    void aFailedEmbeddingLeavesNoKeywords() {
        doThrow(new IllegalStateException("the embedding provider is down"))
                .when(vectors).upsert(anyString(), anyList());

        assertThatThrownBy(() -> service.chunkAndEmbed("collection", "doc-1", "text", null, 0, null, "files", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the embedding provider is down");

        // Removed AFTER the write that was under way finished - never before it, which would leave
        // the chunks in.
        InOrder order = inOrder(keywords);
        order.verify(keywords).addDocuments(anyString(), anyString(), anyList(), any(), any());
        order.verify(keywords).deleteDocuments("collection", "doc-1");
    }

    @Test
    @DisplayName("a document that produced no chunks writes to neither store")
    void noChunksNoWrites() {
        when(chunking.chunk(anyString(), anyString(), any(), any())).thenReturn(List.of());

        assertThat(service.chunkAndEmbed("collection", "doc-1", "", null, 0, null, "files", null)).isEmpty();

        verify(vectors, never()).upsert(anyString(), anyList());
        verify(keywords, never()).addDocuments(anyString(), anyString(), anyList(), any(), any());
    }
}
