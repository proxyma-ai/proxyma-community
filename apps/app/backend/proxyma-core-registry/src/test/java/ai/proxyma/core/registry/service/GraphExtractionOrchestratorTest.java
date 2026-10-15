package ai.proxyma.core.registry.service;

import ai.proxyma.core.graph.service.GraphModelUnreachableException;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The knowledge graph builds from what is already indexed, and nothing it does reaches indexing.
 *
 * <p>Until 2026-09-15 extraction ran inside SyncService.processItem(). Switching the graph on for
 * an indexed source did nothing until every document was re-embedded, and a graph refused for
 * budget marked the DOCUMENT failed. These pin the replacement:
 * {@code alreadyIndexedDocumentIsExtractedFromTheKeywordIndex} is the backfill, and the rest are
 * the graph staying out of the index's way. No test here hands the worker an embedding service or
 * a vector store, because it has none - and {@code neverWritesADocument} holds the one write path
 * it could still reach.
 */
class GraphExtractionOrchestratorTest {

    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final String COLLECTION = CONNECTOR.toString().replace("-", "");
    private static final String SOURCE = "C:/docs/architecture.md";
    private static final Instant INDEXED = Instant.parse("2026-09-01T08:00:00Z");
    private static final List<String> CHUNKS = List.of("chunk zero", "chunk one");

    private RegisteredConnectorRepository connectors;
    private TrackedDocumentRepository documents;
    private KnowledgeGraphService graph;
    private BM25IndexService bm25;
    private SyncOrchestratorService sync;
    private GraphExtractionOrchestrator worker;

    private RegisteredConnector connector;
    private TrackedDocument document;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        documents = mock(TrackedDocumentRepository.class);
        graph = mock(KnowledgeGraphService.class);
        bm25 = mock(BM25IndexService.class);
        sync = mock(SyncOrchestratorService.class);
        worker = new GraphExtractionOrchestrator(connectors, documents, graph, bm25, sync);

        connector = new RegisteredConnector();
        connector.setId(CONNECTOR);
        connector.setGraphEnabled(true);

        // Indexed long before the graph was switched on: exactly the source the old design ignored.
        document = new TrackedDocument();
        document.setId(DOCUMENT);
        document.setConnectorId(CONNECTOR);
        document.setSourceId(SOURCE);
        document.setStatus(TrackedDocument.Status.COMPLETED);
        document.setLastIndexedAt(INDEXED);

        when(connectors.findAll()).thenReturn(List.of(connector));
        when(connectors.findById(CONNECTOR)).thenReturn(Optional.of(connector));
        when(documents.findById(DOCUMENT)).thenReturn(Optional.of(document));
        when(documents.findIdAndLastIndexedAtByConnectorIdAndStatus(CONNECTOR, TrackedDocument.Status.COMPLETED))
                .thenReturn(List.<Object[]>of(new Object[] {DOCUMENT, INDEXED}));
        when(documents.countByConnectorIdAndStatus(CONNECTOR, TrackedDocument.Status.COMPLETED)).thenReturn(1L);
        when(documents.findMaxLastIndexedAtByConnectorIdAndStatus(CONNECTOR, TrackedDocument.Status.COMPLETED))
                .thenReturn(INDEXED);
        when(graph.isExtractionAvailable()).thenReturn(true);
        when(graph.documentStates(CONNECTOR)).thenReturn(Map.of());
        when(bm25.getChunkTexts(COLLECTION, SOURCE)).thenReturn(CHUNKS);
    }

    @Test
    @DisplayName("an already-indexed document is extracted from the keyword index, with no re-index")
    void alreadyIndexedDocumentIsExtractedFromTheKeywordIndex() {
        assertEquals(1, worker.runPass());

        verify(bm25).getChunkTexts(COLLECTION, SOURCE);
        verify(graph).refreshDocument(eq(CONNECTOR), eq(DOCUMENT), eq(INDEXED), eq(CHUNKS), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        // Read, never written: the index belongs to ingest.
        verify(bm25, never()).addDocuments(anyString(), anyString(), any(), any(), any());
        verify(bm25, never()).deleteDocuments(anyString(), anyString());
    }

    @Test
    @DisplayName("metadata edges need no model: with none configured, a Jira source still gets them")
    void metadataPassRunsWithoutAModel() {
        connector.setConnectorType(ai.proxyma.core.connector.ConnectorType.JIRA);
        document.setConnectorType(ai.proxyma.core.connector.ConnectorType.JIRA);
        when(graph.isExtractionAvailable()).thenReturn(false);

        assertEquals(1, worker.runPass());

        verify(graph).refreshMetadata(eq(CONNECTOR), eq(DOCUMENT), eq(INDEXED), any(),
                eq(ai.proxyma.core.connector.facts.SourceFactsReader.VERSION));
        verify(graph, never()).refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a document whose metadata this reader version already wrote is not read again; a source type with none is not read")
    void metadataPassSkipsWhatIsCurrent() {
        connector.setConnectorType(ai.proxyma.core.connector.ConnectorType.JIRA);
        when(graph.isExtractionAvailable()).thenReturn(false);
        when(graph.documentStates(CONNECTOR)).thenReturn(Map.of(DOCUMENT, new ai.proxyma.core.graph.service.DocumentGraphState(
                DOCUMENT, ai.proxyma.core.graph.model.KgDocumentState.Status.DONE, INDEXED, 0, null,
                ai.proxyma.core.connector.facts.SourceFactsReader.VERSION, INDEXED)));
        assertEquals(0, worker.runPass());

        connector.setConnectorType(ai.proxyma.core.connector.ConnectorType.WEB_PAGE);
        when(graph.documentStates(CONNECTOR)).thenReturn(Map.of());
        assertEquals(0, worker.runPass());
        verify(graph, never()).refreshMetadata(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("the worker never writes a document - its indexing status is not the graph's to change")
    void neverWritesADocument() {
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new GlobalQuotaExceededException("extracting", 10, 10));

        worker.runPass();

        verify(documents, never()).save(any());
        assertEquals(TrackedDocument.Status.COMPLETED, document.getStatus());
    }

    @Test
    @DisplayName("a budget refusal stands the whole worker down instead of moving to the next document")
    void quotaStandsTheWorkerDown() {
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new GlobalQuotaExceededException("extracting", 10, 10));

        assertEquals(0, worker.runPass());
        assertEquals(0, worker.runPass());

        // One attempt, then standing down: the second pass did not even look for work.
        verify(graph, org.mockito.Mockito.times(1)).refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a model that answers nothing stands the worker down instead of eating the corpus")
    void unreachableModelStandsTheWorkerDown() {
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new GraphModelUnreachableException("nothing answered"));

        assertEquals(0, worker.runPass());
        assertEquals(0, worker.runPass());

        // One attempt, then it waits. Before this, a stopped Ollama marked every document of a
        // source as read in half a second and left an empty graph nobody was told about.
        verify(graph, org.mockito.Mockito.times(1)).refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(documents, never()).save(any());
        assertEquals(TrackedDocument.Status.COMPLETED, document.getStatus());
    }

    @Test
    @DisplayName("rows an outage recorded as read are re-queued once, before the first scan - no toggle")
    void outageRowsAreRequeuedOncePerProcess() {
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(graph);

        worker.runPass();
        worker.runPass();

        // Before the scan, so the rows it puts back to PENDING are picked up by that same pass.
        order.verify(graph).requeueUnreadDocuments();
        order.verify(graph).documentStates(CONNECTOR);
        // Once: every row written DONE from then on carries its answer count.
        verify(graph, org.mockito.Mockito.times(1)).requeueUnreadDocuments();
        // Progress rows only. The documents are read again from the keyword index, never re-indexed.
        verify(documents, never()).save(any());
        verify(bm25, never()).addDocuments(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("the outage repair waits until extraction can run, and a failed one is tried again")
    void outageRepairWaitsForAWorkingGraphAndRetries() {
        when(graph.isExtractionAvailable()).thenReturn(false);
        worker.runPass();
        verify(graph, never()).requeueUnreadDocuments();

        when(graph.isExtractionAvailable()).thenReturn(true);
        when(graph.requeueUnreadDocuments())
                .thenThrow(new RuntimeException("database busy"))
                .thenReturn(3);
        // The failure does not stop the pass: the document is still extracted.
        assertEquals(1, worker.runPass());
        worker.runPass();

        verify(graph, org.mockito.Mockito.times(2)).requeueUnreadDocuments();
    }

    @Test
    @DisplayName("a source whose graph is switched off is not scanned")
    void disabledSourceIsNotScanned() {
        connector.setGraphEnabled(false);

        assertEquals(0, worker.runPass());

        verify(documents, never()).findIdAndLastIndexedAtByConnectorIdAndStatus(any(), any());
        verify(bm25, never()).getChunkTexts(anyString(), anyString());
    }

    @Test
    @DisplayName("switched off DURING an extraction, that document's rows are removed after it")
    void unpluggedMidExtractionIsCleanedUp() {
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenAnswer(inv -> {
            connector.setGraphEnabled(false);   // the operator flips the switch while the model runs
            return KnowledgeGraphService.RefreshOutcome.EXTRACTED;
        });

        worker.runPass();

        verify(graph).forgetDocument(DOCUMENT);
    }

    @Test
    @DisplayName("a document re-indexed after the scan is not extracted against its old timestamp")
    void reindexedSinceTheScanIsSkipped() {
        document.setLastIndexedAt(INDEXED.plusSeconds(60));

        assertEquals(0, worker.runPass());

        verify(graph, never()).refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("a settled source is skipped without loading its documents until something changes")
    void settledSourceIsNotRescanned() {
        when(graph.documentStates(CONNECTOR)).thenReturn(Map.of(DOCUMENT,
                new ai.proxyma.core.graph.service.DocumentGraphState(DOCUMENT,
                        ai.proxyma.core.graph.model.KgDocumentState.Status.DONE, INDEXED, 0, null)));

        assertTrue(worker.collectWork().isEmpty());
        assertTrue(worker.collectWork().isEmpty());
        verify(documents, org.mockito.Mockito.times(1))
                .findIdAndLastIndexedAtByConnectorIdAndStatus(eq(CONNECTOR), any());

        // Any change to the fingerprint unsettles it - here the progress-row count, as the graph
        // being switched off and on again would move it.
        when(graph.documentStateCount(CONNECTOR)).thenReturn(7L);
        when(graph.documentStates(CONNECTOR)).thenReturn(Map.of());
        assertFalse(worker.collectWork().isEmpty());
    }

    /** A clock the test moves. */
    private static final class MovingClock extends java.time.Clock {
        Instant now = Instant.parse("2026-10-14T08:00:00Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("telemetry-dff32853: a model that keeps not answering is asked less often - 5, 10, 20, 40, then 60 minutes - and says so")
    void unreachableBackoffGrows() {
        MovingClock clock = new MovingClock();
        worker.clock = clock;
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new GraphModelUnreachableException("nothing answered", "no answer within 120 seconds"));

        List<Long> waits = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            worker.runPass();
            var outage = worker.modelOutage().orElseThrow();
            waits.add(java.time.Duration.between(clock.now, outage.retryAt()).toMinutes());
            assertEquals("no answer within 120 seconds", outage.reason());
            // Before its time the worker asks nothing.
            assertEquals(0, worker.runPass());
            clock.now = outage.retryAt();
        }

        assertEquals(List.of(5L, 10L, 20L, 40L, 60L, 60L), waits);
        assertEquals(Instant.parse("2026-10-14T08:00:00Z"), worker.modelOutage().orElseThrow().since());
    }

    @Test
    @DisplayName("telemetry-dff32853: the first document a model answers ends the outage, and the next one starts again at 5 minutes")
    void unreachableBackoffResets() {
        MovingClock clock = new MovingClock();
        worker.clock = clock;
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new GraphModelUnreachableException("nothing answered"))
                .thenThrow(new GraphModelUnreachableException("nothing answered"))
                .thenReturn(KnowledgeGraphService.RefreshOutcome.EXTRACTED)
                .thenThrow(new GraphModelUnreachableException("nothing answered"));

        worker.runPass();
        clock.now = worker.modelOutage().orElseThrow().retryAt();
        worker.runPass();
        clock.now = worker.modelOutage().orElseThrow().retryAt();

        worker.runPass();
        assertTrue(worker.modelOutage().isEmpty(), "an answered document ends the outage");

        worker.runPass();
        assertEquals(5, java.time.Duration.between(clock.now, worker.modelOutage().orElseThrow().retryAt()).toMinutes());
    }

    @Test
    @DisplayName("graph-02: an owner's own budget refusal holds that owner's sources only, and is not an unreachable model")
    void anOwnersBudgetIsTheirsAlone() {
        MovingClock clock = new MovingClock();
        worker.clock = clock;
        connector.setUserId("owner-1");
        when(graph.refreshDocument(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new ai.proxyma.core.settings.usage.UserBudgetExceededException(
                        "month", clock.now.plusSeconds(3600), "over budget"));

        assertEquals(0, worker.runPass());
        assertTrue(worker.modelOutage().isEmpty(), "a budget refusal is not the model being unreachable");
        // The owner's source waits; the worker as a whole does not stand down.
        assertTrue(worker.collectWork().isEmpty());
        clock.now = clock.now.plusSeconds(3601);
        assertFalse(worker.collectWork().isEmpty());
    }
}
