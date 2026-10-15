package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A lost race for an entity name costs one retried chunk, not the document.
 *
 * <p>{@code kg_entities} is unique on (normalizedName, type). Two connectors syncing in parallel
 * met the same name at the same instant, one commit lost with
 * {@code UK_KG_ENTITY_NORM_TYPE_INDEX}, and the exception abandoned the whole document's graph -
 * three 200-page specifications in one overnight run (fixed 2026-09-13). The failure arrives at
 * the transaction boundary, after the resolver can do anything about it, so the fix is a single
 * retry of the chunk in {@link KnowledgeGraphService}.
 *
 * <p>Proved by breaking it: rethrowing instead of retrying fails
 * {@code lostRaceIsRetriedAndTheDocumentContinues}, and retrying until it succeeds fails
 * {@code secondFailureIsNotRetriedAgain}.
 */
class KnowledgeGraphEntityRaceTest {

    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    private GraphExtractionService extraction;
    private KnowledgeGraphWriter writer;
    private KnowledgeGraphService service;

    private static final ExtractedGraph GRAPH = new ExtractedGraph(
            List.of(new ExtractedGraph.Entity("variant-pre-compile", "product", null, null)),
            List.of());

    private static DataIntegrityViolationException lostRace() {
        return new DataIntegrityViolationException(
                "Unique index or primary key violation: UK_KG_ENTITY_NORM_TYPE_INDEX");
    }

    @BeforeEach
    void setUp() {
        extraction = mock(GraphExtractionService.class);
        writer = mock(KnowledgeGraphWriter.class);
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extractChunk(any()))
                .thenReturn(new GraphExtractionService.Attempt(GRAPH, GraphExtractionService.Outcome.OK));
        service = new KnowledgeGraphService(mock(KgEntityRepository.class), mock(ai.proxyma.core.graph.repository.KgAliasRepository.class),
                mock(KgMentionRepository.class),
                mock(KgRelationRepository.class), extraction, writer, mock(KgDocumentStateRepository.class));
    }

    private static List<KnowledgeGraphWriter.ChunkGraph> graphs(int chunks) {
        List<KnowledgeGraphWriter.ChunkGraph> out = new java.util.ArrayList<>();
        for (int i = 0; i < chunks; i++) out.add(new KnowledgeGraphWriter.ChunkGraph(String.valueOf(i), GRAPH));
        return out;
    }

    @Test
    @DisplayName("a document that loses an entity race is written again, whole")
    void lostRaceIsRetried() {
        doThrow(lostRace()).doNothing().when(writer).replaceDocument(any(), any(), any());

        service.extractDocument(CONNECTOR, DOCUMENT, List.of("chunk zero", "chunk one", "chunk two"));

        verify(writer, times(2)).replaceDocument(CONNECTOR, DOCUMENT, graphs(3));
    }

    @Test
    @DisplayName("an entity a parallel purge removed under the write is a race too, and retried")
    void purgedUnderTheWriteIsRetried() {
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("row was deleted"))
                .doNothing().when(writer).replaceDocument(any(), any(), any());

        service.extractDocument(CONNECTOR, DOCUMENT, List.of("chunk zero"));

        verify(writer, times(2)).replaceDocument(CONNECTOR, DOCUMENT, graphs(1));
    }

    @Test
    @DisplayName("a second failure is not retried again - it propagates")
    void secondFailureIsNotRetriedAgain() {
        doThrow(lostRace()).when(writer).replaceDocument(any(), any(), any());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.extractDocument(CONNECTOR, DOCUMENT, List.of("chunk zero", "chunk one")));

        verify(writer, times(2)).replaceDocument(any(), any(), any());
    }

    @Test
    @DisplayName("a document that writes cleanly is written exactly once, every chunk in the one write")
    void cleanWriteIsNotRepeated() {
        service.extractDocument(CONNECTOR, DOCUMENT, List.of("chunk zero", "chunk one"));

        verify(writer, times(1)).replaceDocument(CONNECTOR, DOCUMENT, graphs(2));
    }
}
