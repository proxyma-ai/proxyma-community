package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgDocumentState;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import ai.proxyma.core.graph.service.KnowledgeGraphService.RefreshOutcome;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bringing one document up to date in the graph, from chunks that already exist.
 *
 * <p>What these pin is the cost and the blast radius of the graph now that it runs on its own:
 * unchanged chunks cost no model call, a budget refusal does not burn a document's retries, and an
 * unreadable document is recorded as a GRAPH failure - this module has no way to touch the
 * document's indexing status, and the tests keep it that way by never giving it one.
 */
class KnowledgeGraphRefreshTest {

    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final Instant INDEXED = Instant.parse("2026-09-15T10:00:00Z");
    private static final List<String> CHUNKS = List.of("Auth Service depends on Postgres.", "Team Blue owns it.");

    /** The model answered, and said there is nothing here. A real answer. */
    private static final GraphExtractionService.Attempt ANSWERED_NOTHING =
            new GraphExtractionService.Attempt(ExtractedGraph.empty(), GraphExtractionService.Outcome.EMPTY);

    /** No answer at all - refused, reset or timed out. */
    private static final GraphExtractionService.Attempt NO_ANSWER =
            new GraphExtractionService.Attempt(ExtractedGraph.empty(), GraphExtractionService.Outcome.UNREACHABLE);

    private GraphExtractionService extraction;
    private KnowledgeGraphWriter writer;
    private KgDocumentStateRepository states;
    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        extraction = mock(GraphExtractionService.class);
        writer = mock(KnowledgeGraphWriter.class);
        states = mock(KgDocumentStateRepository.class);
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extractChunk(any())).thenReturn(ANSWERED_NOTHING);
        when(states.findById(DOCUMENT)).thenReturn(Optional.empty());
        service = new KnowledgeGraphService(mock(KgEntityRepository.class), mock(ai.proxyma.core.graph.repository.KgAliasRepository.class),
                mock(KgMentionRepository.class),
                mock(KgRelationRepository.class), extraction, writer, states);
    }

    private KgDocumentState stored(KgDocumentState.Status status, String hash) {
        KgDocumentState s = new KgDocumentState(DOCUMENT, CONNECTOR);
        s.setStatus(status);
        s.setSourceIndexedAt(INDEXED);
        s.setChunkHash(hash);
        when(states.findById(DOCUMENT)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("a document never extracted is extracted from the chunks it is given, and recorded DONE")
    void extractsAndRecordsDone() {
        RefreshOutcome outcome = service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS);

        assertEquals(RefreshOutcome.EXTRACTED, outcome);
        verify(extraction).extractChunk(CHUNKS.get(0));
        verify(extraction).extractChunk(CHUNKS.get(1));
        verify(states, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                s -> s.getStatus() == KgDocumentState.Status.DONE
                        && KnowledgeGraphService.chunkHash(CHUNKS).equals(s.getChunkHash())));
    }

    @Test
    @DisplayName("progress is written AS IT GOES, so a long document is not a frozen zero for hours")
    void progressIsVisibleDuringExtraction() {
        KgDocumentState state = stored(KgDocumentState.Status.PENDING, null);
        List<Integer> seen = new java.util.ArrayList<>();
        // Captured off the row at each save: what a screen polling the progress endpoint would see.
        org.mockito.Mockito.doAnswer(inv -> { seen.add(state.getChunksDone()); return state; })
                .when(states).save(any());

        service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, List.of("a", "b", "c"));

        // The total is known from the first save, and the count climbs rather than jumping from 0
        // to done - the complaint that prompted this was a source that read "0 of 20" for hours
        // while working perfectly.
        assertEquals(3, state.getChunkCount());
        assertTrue(seen.contains(1) && seen.contains(2), "expected intermediate progress, saw " + seen);
        assertEquals(3, state.getChunksDone());
    }

    @Test
    @DisplayName("chunks identical to the last extraction cost no model call - a re-embed is not a re-extract")
    void unchangedChunksAreNotReExtracted() {
        stored(KgDocumentState.Status.DONE, KnowledgeGraphService.chunkHash(CHUNKS));

        RefreshOutcome outcome = service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED.plusSeconds(3600), CHUNKS);

        assertEquals(RefreshOutcome.UNCHANGED, outcome);
        verify(extraction, never()).extractChunk(any());
        verify(writer, never()).replaceDocument(any(), any(), any());
    }

    @Test
    @DisplayName("a budget refusal puts the document back to PENDING without using a retry, and propagates")
    void quotaRefusalIsNotAFailure() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);
        when(extraction.extractChunk(any())).thenThrow(new GlobalQuotaExceededException("extracting", 10, 10));

        assertThrows(GlobalQuotaExceededException.class,
                () -> service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        assertEquals(KgDocumentState.Status.PENDING, s.getStatus());
        assertEquals(0, s.getAttempts());
    }

    @Test
    @DisplayName("a document no call reached is NOT recorded as read - it stays PENDING and propagates")
    void unreachableModelIsNotAFinishedDocument() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);
        when(extraction.extractChunk(any())).thenReturn(NO_ANSWER);

        assertThrows(GraphModelUnreachableException.class,
                () -> service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        // The whole bug of 2026-09-16 in three assertions: not DONE, no chunk hash to match against
        // next time, and no retry spent on an outage that was nobody's fault.
        assertEquals(KgDocumentState.Status.PENDING, s.getStatus());
        assertEquals(null, s.getChunkHash());
        assertEquals(0, s.getAttempts());
    }

    @Test
    @DisplayName("abandoned with nothing to show is a failure, even if an early chunk said 'nothing here'")
    void abandonedWithNothingIsNotFinished() {
        KgDocumentState state = stored(KgDocumentState.Status.PENDING, null);
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 235; i++) many.add("chunk " + i);
        // Straight from a live log: chunk 0 answered EMPTY - a real answer, so not every attempt
        // was unreachable - then the model stopped answering. The document was recorded as READ
        // with 230 of its chunks never looked at.
        when(extraction.extractChunk("chunk 0")).thenReturn(ANSWERED_NOTHING);
        when(extraction.extractChunk(org.mockito.ArgumentMatchers.argThat(
                c -> c != null && !c.equals("chunk 0")))).thenReturn(NO_ANSWER);

        assertThrows(GraphModelUnreachableException.class,
                () -> service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, many));

        assertEquals(KgDocumentState.Status.PENDING, state.getStatus());
        verify(extraction, org.mockito.Mockito.times(4)).extractChunk(any());
    }

    @Test
    @DisplayName("a document nothing answers is abandoned after a few chunks, not after all of them")
    void givesUpEarlyWhenNothingAnswers() {
        stored(KgDocumentState.Status.PENDING, null);
        when(extraction.extractChunk(any())).thenReturn(NO_ANSWER);
        // A real document from a real install: 278 chunks, and a graph model timing out at 120s
        // each. Reading all of them proves nothing the first three did not, and takes nine hours.
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 278; i++) many.add("chunk " + i);

        assertThrows(GraphModelUnreachableException.class,
                () -> service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, many));

        verify(extraction, org.mockito.Mockito.times(3)).extractChunk(any());
    }

    @Test
    @DisplayName("one early answer does not buy 277 more timeouts, and a document abandoned part way is not read")
    void givesUpEarlyEvenAfterOneAnswer() {
        stored(KgDocumentState.Status.PENDING, null);
        ExtractedGraph found = new ExtractedGraph(
                List.of(new ExtractedGraph.Entity("Auth Service", "service", null, null)), List.of());
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 278; i++) many.add("chunk " + i);
        // Exactly what the live install did: chunk 0 answered, then the model stopped answering.
        // The first version of this guard only fired while NOTHING had been extracted, so this
        // document walked all 278 chunks at 120s each.
        when(extraction.extractChunk("chunk 0"))
                .thenReturn(new GraphExtractionService.Attempt(found, GraphExtractionService.Outcome.OK));
        when(extraction.extractChunk(org.mockito.ArgumentMatchers.argThat(
                c -> c != null && !c.equals("chunk 0")))).thenReturn(NO_ANSWER);

        // NOT READ (since 2026-10-13): a document given up on part way used to be recorded DONE
        // with what its first chunks gave, and DONE is never offered again - so the other 277
        // stayed out of the graph until the document changed. It goes back to PENDING, and its
        // previous graph is left exactly as it was.
        assertThrows(GraphModelUnreachableException.class,
                () -> service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, many));

        // chunk 0, then three that reached nothing, and it stops.
        verify(extraction, org.mockito.Mockito.times(4)).extractChunk(any());
        verify(writer, never()).replaceDocument(any(), any(), any());
    }

    @Test
    @DisplayName("a chunk no model answers is asked again; still unanswered, the document is PARTIAL with it on record")
    void unansweredChunkIsRecordedMissing() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);
        ExtractedGraph found = new ExtractedGraph(
                List.of(new ExtractedGraph.Entity("Auth Service", "service", null, null)), List.of());
        when(extraction.extractChunk(CHUNKS.get(0))).thenReturn(NO_ANSWER);
        when(extraction.extractChunk(CHUNKS.get(1)))
                .thenReturn(new GraphExtractionService.Attempt(found, GraphExtractionService.Outcome.OK));

        assertEquals(RefreshOutcome.PARTIAL, service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        // Asked twice - once in the read, once at its end - and then left for a later pass. It used
        // to be one timeout, and a hole in a document recorded DONE for good.
        verify(extraction, org.mockito.Mockito.times(2)).extractChunk(CHUNKS.get(0));
        verify(writer).replaceDocument(org.mockito.ArgumentMatchers.eq(CONNECTOR),
                org.mockito.ArgumentMatchers.eq(DOCUMENT),
                org.mockito.ArgumentMatchers.eq(List.of(new KnowledgeGraphWriter.ChunkGraph("1", found))));
        assertEquals(KgDocumentState.Status.FAILED, s.getStatus());
        assertEquals("0", s.getMissingChunks());
        assertEquals(KnowledgeGraphService.chunkHash(CHUNKS), s.getChunkHash());
        assertEquals(1, s.getAttempts());
    }

    @Test
    @DisplayName("the next offer of the same chunks reads only the missing ones, and ADDS them")
    void resumeReadsOnlyTheMissingChunks() {
        KgDocumentState s = stored(KgDocumentState.Status.FAILED, KnowledgeGraphService.chunkHash(CHUNKS));
        s.setMissingChunks("0");
        s.setChunksAnswered(1);
        s.setAttempts(1);

        assertEquals(RefreshOutcome.EXTRACTED, service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        verify(extraction).extractChunk(CHUNKS.get(0));
        verify(extraction, never()).extractChunk(CHUNKS.get(1));
        verify(writer).addChunks(any(), any(), any());
        verify(writer, never()).replaceDocument(any(), any(), any());
        assertEquals(KgDocumentState.Status.DONE, s.getStatus());
        assertEquals(null, s.getMissingChunks());
        assertEquals(Integer.valueOf(2), s.getChunksAnswered());
    }

    @Test
    @DisplayName("different chunks are a new version: read whole, whatever was missing before")
    void changedChunksAreReadWhole() {
        KgDocumentState s = stored(KgDocumentState.Status.FAILED, "an older hash");
        s.setMissingChunks("0");

        service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS);

        verify(extraction).extractChunk(CHUNKS.get(1));
        verify(writer).replaceDocument(any(), any(), any());
    }

    @Test
    @DisplayName("a model that answers 'nothing here' for every chunk IS finished - that is an answer")
    void answeringNothingIsStillAnAnswer() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);
        when(extraction.extractChunk(any())).thenReturn(ANSWERED_NOTHING);

        assertEquals(RefreshOutcome.EXTRACTED, service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));
        assertEquals(KgDocumentState.Status.DONE, s.getStatus());
    }

    @Test
    @DisplayName("a chunk that times out once and answers when asked again leaves the document DONE, both counted")
    void doneRecordsTheAnswers() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);
        ExtractedGraph found = new ExtractedGraph(
                List.of(new ExtractedGraph.Entity("Auth Service", "service", null, null)), List.of());
        when(extraction.extractChunk(CHUNKS.get(0))).thenReturn(NO_ANSWER, ANSWERED_NOTHING);
        when(extraction.extractChunk(CHUNKS.get(1)))
                .thenReturn(new GraphExtractionService.Attempt(found, GraphExtractionService.Outcome.OK));

        assertEquals(RefreshOutcome.EXTRACTED, service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        assertEquals(KgDocumentState.Status.DONE, s.getStatus());
        assertEquals(Integer.valueOf(2), s.getChunksAnswered());
        assertEquals(null, s.getMissingChunks());
    }

    @Test
    @DisplayName("'nothing here' for every chunk is recorded as answered, so it is never re-queued")
    void emptyButAnsweredIsOnRecord() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);

        service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS);

        // What separates a genuinely empty document from an outage row once both say DONE with
        // nothing in the graph - without it the repair would re-read this one on every start.
        assertEquals(KgDocumentState.Status.DONE, s.getStatus());
        assertEquals(Integer.valueOf(CHUNKS.size()), s.getChunksAnswered());
    }

    @Test
    @DisplayName("the outage repair asks for DONE rows to go back to PENDING, and touches nothing else")
    void requeueUnreadDocumentsReQueuesDoneAsPending() {
        when(states.requeueDoneWithoutAnswers(any(), any(), any())).thenReturn(20);

        assertEquals(20, service.requeueUnreadDocuments());

        verify(states).requeueDoneWithoutAnswers(
                org.mockito.ArgumentMatchers.eq(KgDocumentState.Status.DONE),
                org.mockito.ArgumentMatchers.eq(KgDocumentState.Status.PENDING), any());
        // No model call and no graph write: the documents are read again by the worker, later.
        verify(extraction, never()).extractChunk(any());
        verify(writer, never()).replaceDocument(any(), any(), any());
    }

    @Test
    @DisplayName("no readable chunks is a graph failure with a retry budget, and no model call")
    void emptyChunksFailWithoutCallingTheModel() {
        KgDocumentState s = stored(KgDocumentState.Status.PENDING, null);

        RefreshOutcome outcome = service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, List.of());

        assertEquals(RefreshOutcome.FAILED, outcome);
        assertEquals(KgDocumentState.Status.FAILED, s.getStatus());
        assertEquals(1, s.getAttempts());
        verify(extraction, never()).extractChunk(any());
    }

    @Test
    @DisplayName("nothing is recorded while extraction is switched off or has no model")
    void unavailableRecordsNothing() {
        when(extraction.isAvailable()).thenReturn(false);

        assertEquals(RefreshOutcome.SKIPPED, service.refreshDocument(CONNECTOR, DOCUMENT, INDEXED, CHUNKS));

        verify(states, never()).save(any());
    }

    @Test
    @DisplayName("the chunk hash depends on where chunks split, not only on their concatenation")
    void hashSeparatesChunks() {
        assertEquals(false, KnowledgeGraphService.chunkHash(List.of("ab", "c"))
                .equals(KnowledgeGraphService.chunkHash(List.of("a", "bc"))));
    }
}
