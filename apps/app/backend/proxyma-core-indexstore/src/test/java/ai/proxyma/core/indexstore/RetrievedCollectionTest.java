package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every store stamps each chunk its search returns with the collection it was found in
 * (ChunkMetadata). A chunk's {@code source} is unique only within its collection - two SaaS
 * sources both hold {@code ticket:1} - and a search spans many, so without the stamp nobody
 * downstream could tell which source's {@code ticket:1} a hit was.
 */
class RetrievedCollectionTest {

    private static final String TENANT = "SpringAiTenant";
    private static final String DATABASE = "SpringAiDatabase";

    @Test
    @DisplayName("ChromaDB: each hit carries the collection it was asked as, not the store's internal uuid")
    void chroma() {
        ChromaApi api = mock(ChromaApi.class);
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        ChromaStoreService store = new ChromaStoreService(embeddings, null, null, null, new EmbeddingConcurrency(() -> 4));
        ReflectionTestUtils.setField(store, "chromaApi", api);
        when(embeddings.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        for (String name : List.of("zendesk", "freshdesk_reindex")) {
            when(api.getCollection(TENANT, DATABASE, name)).thenReturn(new ChromaApi.Collection("uuid-" + name, name, Map.of()));
            ChromaApi.QueryResponse response = mock(ChromaApi.QueryResponse.class);
            when(api.queryCollection(eq(TENANT), eq(DATABASE), eq("uuid-" + name), any())).thenReturn(response);
            when(api.toEmbeddingResponseList(response)).thenReturn(List.of(new ChromaApi.Embedding(
                    "id-" + name, null, "Thanks, we are on it.", Map.of("source", "ticket:1"), 0.2)));
        }

        List<Document> found = store.search(List.of("zendesk", "freshdesk_reindex"), "on it", 5, 0.3);

        assertThat(found).extracting(Document::getId, ChunkMetadata::collectionOf).containsExactlyInAnyOrder(
                tuple("id-zendesk", "zendesk"), tuple("id-freshdesk_reindex", "freshdesk_reindex"));
    }

    @Test
    @DisplayName("pgvector: each row's hit carries its own collection_id column")
    void pgvector() throws Exception {
        PgVectorStoreService store = new PgVectorStoreService(mock(javax.sql.DataSource.class),
                mock(EmbeddingModel.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                new EmbeddingConcurrency(() -> 4));
        java.sql.ResultSet row = mock(java.sql.ResultSet.class);
        when(row.getString("id")).thenReturn("row-1");
        when(row.getString("content")).thenReturn("Thanks, we are on it.");
        when(row.getString("metadata")).thenReturn("{\"source\":\"ticket:1\"}");
        when(row.getString("collection_id")).thenReturn("freshdesk");
        when(row.getString("source_id")).thenReturn("ticket:1");
        when(row.getDouble("score")).thenReturn(0.8);

        Document hit = ReflectionTestUtils.invokeMethod(store, "mapRow", row);

        assertThat(ChunkMetadata.collectionOf(hit)).isEqualTo("freshdesk");
        assertThat(hit.getMetadata()).containsEntry("source", "ticket:1");
    }

    @Test
    @DisplayName("BM25: each hit carries the collection it was found in, the same item id in two kept apart")
    void keywords(@TempDir Path dir) {
        BM25IndexService index = new BM25IndexService(dir.toString(), null);
        index.addDocuments("zendesk", "ticket:1", List.of("refund for a broken printer"), null);
        index.addDocuments("freshdesk", "ticket:1", List.of("the printer jams on every page"), null);

        assertThat(index.search(List.of("zendesk", "freshdesk"), "printer", 5))
                .extracting(BM25IndexService.BM25Result::source, BM25IndexService.BM25Result::collectionId)
                .containsExactlyInAnyOrder(tuple("ticket:1", "zendesk"), tuple("ticket:1", "freshdesk"));
        assertThat(index.listAll("freshdesk", 5)).extracting(BM25IndexService.BM25Result::collectionId)
                .containsExactly("freshdesk");
    }
}
