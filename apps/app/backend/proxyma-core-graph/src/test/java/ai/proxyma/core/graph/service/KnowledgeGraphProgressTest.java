package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgDocumentState;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the graph is reading RIGHT NOW, item by item.
 *
 * <p>The progress figures used to be totals only, and a total cannot answer the question somebody
 * looking at a list of twenty items actually has - which of them is being read. "37 of 581
 * passages" is unmatchable against a list of titles, so the screen could say the source was busy
 * and not which part of it.
 */
class KnowledgeGraphProgressTest {

    private static final UUID CONNECTOR = UUID.randomUUID();

    private KgDocumentStateRepository states;
    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        states = mock(KgDocumentStateRepository.class);
        service = new KnowledgeGraphService(mock(KgEntityRepository.class), mock(ai.proxyma.core.graph.repository.KgAliasRepository.class),
                mock(KgMentionRepository.class),
                mock(KgRelationRepository.class), mock(GraphExtractionService.class),
                mock(KnowledgeGraphWriter.class), states);
    }

    private KgDocumentState extracting(UUID documentId, int done, int total) {
        KgDocumentState s = new KgDocumentState(documentId, CONNECTOR);
        s.setStatus(KgDocumentState.Status.EXTRACTING);
        s.setChunksDone(done);
        s.setChunkCount(total);
        return s;
    }

    @Test
    @DisplayName("each document being read is reported on its own, with its own passage count")
    void everyOpenDocumentIsNamed() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(states.findByConnectorIdAndStatus(CONNECTOR, KgDocumentState.Status.EXTRACTING))
                .thenReturn(List.of(extracting(first, 37, 581), extracting(second, 4, 12)));

        KnowledgeGraphService.DocumentProgress p = service.progressFor(CONNECTOR);

        assertEquals(2, p.reading().size(), "both open documents have to be reported");
        assertEquals(first, p.reading().get(0).documentId());
        assertEquals(37, p.reading().get(0).chunksDone());
        assertEquals(581, p.reading().get(0).chunkCount());
        assertEquals(second, p.reading().get(1).documentId());
        // The totals stay what they were: a screen with no room for a list still has its summary.
        assertEquals(41, p.chunksDone());
        assertEquals(593, p.chunkTotal());
    }

    @Test
    @DisplayName("a source with nothing open reports nothing being read")
    void nothingOpenIsAnEmptyList() {
        when(states.findByConnectorIdAndStatus(CONNECTOR, KgDocumentState.Status.EXTRACTING))
                .thenReturn(List.of());
        when(states.countByConnectorIdAndStatus(any(), any())).thenReturn(9L);

        KnowledgeGraphService.DocumentProgress p = service.progressFor(CONNECTOR);

        // An empty list, not a null and not a stale entry: the indicator this drives is "is this
        // row being read", and a row that is left marked after extraction moves on is a spinner
        // that never stops.
        assertTrue(p.reading().isEmpty());
        assertEquals(0, p.chunkTotal());
        assertEquals(9, p.extracted());
    }
}
