package ai.proxyma.core.registry.service;

import ai.proxyma.core.graph.event.GraphEntitiesRemovedEvent;
import ai.proxyma.core.graph.service.EntityText;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.graph.service.SimilarEntity;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Entity name vectors: made per embedding model, in a collection of that model's own, re-made when
 * the model changes, deleted with their entity - and nothing at all without an embedding model.
 */
class EntityVectorServiceTest {

    private VectorStoreService store;
    private KnowledgeGraphService graph;
    private SettingsService settings;
    private EntityVectorService vectors;

    @BeforeEach
    void setUp() {
        store = mock(VectorStoreService.class);
        graph = mock(KnowledgeGraphService.class);
        settings = mock(SettingsService.class);
        when(settings.getEmbeddingProvider()).thenReturn("p-1");
        when(settings.getEmbeddingModel()).thenReturn("embed-small");
        vectors = new EntityVectorService(store, graph, settings);
    }

    @Test
    @DisplayName("pending entities are embedded under their own UUID, into the current model's collection, and marked")
    @SuppressWarnings("unchecked")
    void embedsPending() {
        UUID id = UUID.randomUUID();
        String key = vectors.currentKey();
        when(graph.entitiesNeedingVectors(eq(key), anyInt())).thenReturn(List.of(new EntityText(id, "auth service (service)")));

        assertEquals(1, vectors.embedPending(50));

        ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
        verify(store).upsert(eq(EntityVectorService.collectionFor(key)), docs.capture());
        assertEquals(id.toString(), docs.getValue().get(0).getId());
        assertEquals(id.toString(), docs.getValue().get(0).getMetadata().get("source"));
        verify(graph).markVectorized(List.of(id), key);
    }

    @Test
    @DisplayName("a model change gets a collection of its own, and the old one goes once nothing is left on it")
    void modelChange() {
        String oldKey = vectors.currentKey();
        when(settings.getEmbeddingModel()).thenReturn("embed-large");
        String newKey = vectors.currentKey();
        assertNotEquals(oldKey, newKey);
        assertNotEquals(EntityVectorService.collectionFor(oldKey), EntityVectorService.collectionFor(newKey));

        // First pass: entities still on the old model - its collection stays.
        when(graph.otherVectorKeys(newKey)).thenReturn(List.of(oldKey));
        vectors.embedPending(50);
        verify(store, never()).deleteCollection(anyString());
        // Once every entity is on the new model, the old collection is deleted.
        when(graph.otherVectorKeys(newKey)).thenReturn(List.of());
        vectors.embedPending(50);
        verify(store).deleteCollection(EntityVectorService.collectionFor(oldKey));
    }

    @Test
    @DisplayName("with no embedding model nothing is embedded or searched")
    void noModel() {
        when(settings.getEmbeddingModel()).thenReturn("");
        assertEquals(0, vectors.embedPending(50));
        assertTrue(vectors.similar("auth", 5, 0.7).isEmpty());
        verify(store, never()).upsert(anyString(), any());
    }

    @Test
    @DisplayName("a similarity search answers entity ids and scores; a deleted entity's vector goes with it")
    void similarAndRemoved() {
        UUID id = UUID.randomUUID();
        String collection = EntityVectorService.collectionFor(vectors.currentKey());
        when(store.search(eq(List.of(collection)), eq("authentication"), anyInt(), any(Double.class)))
                .thenReturn(List.of(Document.builder().id(id.toString()).text("auth service")
                        .metadata(Map.of("source", id.toString())).score(0.91).build()));

        assertEquals(List.of(new SimilarEntity(id, 0.91)), vectors.similar("authentication", 5, 0.7));

        vectors.onEntitiesRemoved(new GraphEntitiesRemovedEvent(List.of(id)));
        verify(store).deleteBySourceMetadata(collection, List.of(id.toString()));
    }

    @Test
    @DisplayName("graph-05: the vectors of deleted entities go in one batch, and a refused batch is tried again on the next pass")
    void removedInOneBatchAndRetried() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String collection = EntityVectorService.collectionFor(vectors.currentKey());
        doThrow(new IllegalStateException("chroma is restarting"))
                .doNothing()
                .when(store).deleteBySourceMetadata(eq(collection), anyCollection());

        vectors.onEntitiesRemoved(new GraphEntitiesRemovedEvent(List.of(a, b)));
        verify(store, times(1)).deleteBySourceMetadata(eq(collection), anyCollection());

        vectors.embedPending(50);
        verify(store, times(2)).deleteBySourceMetadata(eq(collection), anyCollection());
        // Removed now: the pass after that has nothing left to delete.
        vectors.embedPending(50);
        verify(store, times(2)).deleteBySourceMetadata(eq(collection), anyCollection());
    }
}
