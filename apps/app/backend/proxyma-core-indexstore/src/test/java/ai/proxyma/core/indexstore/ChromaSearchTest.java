package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the ChromaDB store searches and deletes (ingest-12, -20): the query embedded once for every
 * collection, a collection that does not exist neither asked nor created, and a failure said out
 * loud.
 */
class ChromaSearchTest {

    private static final String TENANT = "SpringAiTenant";
    private static final String DATABASE = "SpringAiDatabase";

    private ChromaApi api;
    private EmbeddingModel embeddings;
    private ChromaStoreService store;

    @BeforeEach
    void setUp() {
        api = mock(ChromaApi.class);
        embeddings = mock(EmbeddingModel.class);
        store = new ChromaStoreService(embeddings, null, null, null, new EmbeddingConcurrency(() -> 4));
        ReflectionTestUtils.setField(store, "chromaApi", api);
        when(embeddings.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(api.getCollection(TENANT, DATABASE, "a")).thenReturn(new ChromaApi.Collection("uuid-a", "a", Map.of()));
        when(api.getCollection(TENANT, DATABASE, "b")).thenReturn(new ChromaApi.Collection("uuid-b", "b", Map.of()));
        when(api.getCollection(TENANT, DATABASE, "missing")).thenReturn(null);
    }

    private void answers(String uuid, String id, double distance) {
        ChromaApi.QueryResponse response = mock(ChromaApi.QueryResponse.class);
        when(api.queryCollection(eq(TENANT), eq(DATABASE), eq(uuid), any())).thenReturn(response);
        when(api.toEmbeddingResponseList(response)).thenReturn(List.of(
                new ChromaApi.Embedding(id, null, "text of " + id, Map.of("source", id + ".md"), distance)));
    }

    @Test
    @DisplayName("ingest-12: one embedding for the query, whatever the number of collections; a missing one is skipped")
    void embedsOnce() {
        answers("uuid-a", "near", 0.1);
        answers("uuid-b", "far", 0.6);

        List<Document> found = store.search(List.of("a", "b", "missing"), "invoices", 5, 0.3);

        verify(embeddings, times(1)).embed("invoices");
        assertThat(found).extracting(Document::getId).containsExactly("near", "far");
        assertThat(found.get(0).getScore()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
        // Asked about, never created.
        verify(api, never()).createCollection(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a hit below the threshold is dropped, as Spring AI's own store drops it")
    void threshold() {
        answers("uuid-a", "near", 0.1);
        answers("uuid-b", "far", 0.8);

        assertThat(store.search(List.of("a", "b"), "q", 5, 0.5)).extracting(Document::getId).containsExactly("near");
    }

    @Test
    @DisplayName("ingest-20: a failed embedding throws, so the caller knows dense retrieval is down")
    void aFailedEmbeddingThrows() {
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("provider unreachable"));

        assertThatThrownBy(() -> store.search(List.of("a"), "q", 5, 0.3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("one failing collection is skipped; every collection failing throws")
    void collectionFailures() {
        answers("uuid-a", "near", 0.1);
        when(api.queryCollection(eq(TENANT), eq(DATABASE), eq("uuid-b"), any()))
                .thenThrow(new IllegalStateException("dimension mismatch"));

        assertThat(store.search(List.of("a", "b"), "q", 5, 0.3)).extracting(Document::getId).containsExactly("near");
        assertThatThrownBy(() -> store.search(List.of("b"), "q", 5, 0.3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a delete in a collection that does not exist does nothing, and a refused delete throws")
    void deletes() {
        store.deleteBySourceMetadata("missing", "doc.md");
        verify(api, never()).deleteEmbeddings(anyString(), anyString(), anyString(), any());
        verify(api, never()).createCollection(anyString(), anyString(), any());

        when(api.deleteEmbeddings(eq(TENANT), eq(DATABASE), eq("uuid-a"), any()))
                .thenThrow(new IllegalStateException("chroma refused"));
        assertThatThrownBy(() -> store.deleteBySourceMetadata("a", "doc.md")).isInstanceOf(IllegalStateException.class);
    }
}
