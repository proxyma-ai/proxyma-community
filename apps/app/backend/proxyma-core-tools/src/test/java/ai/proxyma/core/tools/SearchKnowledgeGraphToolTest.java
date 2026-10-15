package ai.proxyma.core.tools;

import ai.proxyma.core.graph.service.GraphNeighbourhood;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.SearchService;
import ai.proxyma.core.graph.service.RelevantPassage;
import org.springframework.ai.document.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The graph tool's half of SEC-51, and what it hands the model.
 *
 * <p>A document title is resolved only for a document of a source the asker can read: titles were
 * looked up with an unfiltered findAllById, so any document id that reached the tool was named. And
 * the strongest edges carry the passage they were drawn from, read back from the keyword index.
 */
class SearchKnowledgeGraphToolTest {

    private final UUID wiki = UUID.randomUUID();
    private final UUID hr = UUID.randomUUID();
    private final UUID onboarding = UUID.randomUUID();
    private final UUID salaries = UUID.randomUUID();

    private KnowledgeGraphService graph;
    private ConnectorService connectors;
    private BM25IndexService bm25;
    private SearchService search;
    private SearchKnowledgeGraphTool tool;

    private static RegisteredConnector connector(UUID id) {
        RegisteredConnector c = new RegisteredConnector();
        c.setId(id);
        return c;
    }

    private static TrackedDocument document(UUID id, UUID connectorId, String title) {
        TrackedDocument d = new TrackedDocument();
        d.setId(id);
        d.setConnectorId(connectorId);
        d.setTitle(title);
        d.setSourceId(title + ".md");
        return d;
    }

    @BeforeEach
    void setUp() {
        graph = mock(KnowledgeGraphService.class);
        connectors = mock(ConnectorService.class);
        bm25 = mock(BM25IndexService.class);
        TrackedDocumentRepository documents = mock(TrackedDocumentRepository.class);
        when(connectors.listVisibleTo("ana")).thenReturn(List.of(connector(wiki)));
        // Both documents come back from the lookup - the HR one included, as an id that slipped
        // past every other filter would.
        when(documents.findAllById(any())).thenReturn(List.of(
                document(onboarding, wiki, "Onboarding"), document(salaries, hr, "Salary review 2027")));
        when(bm25.getChunkTexts(wiki.toString().replace("-", ""), "Onboarding.md"))
                .thenReturn(List.of("Welcome.", " ", "Ana leads the Payments team."));
        when(graph.neighbourhood(any(), anyInt(), eq(List.of(wiki)), any(), any())).thenReturn(new GraphNeighbourhood(
                List.of(new GraphNeighbourhood.Entity(UUID.randomUUID(), "Ana", "person", "An engineer.", 0,
                        List.of(onboarding, salaries))),
                List.of(new GraphNeighbourhood.Edge("Ana", "leads", "Payments", null, 2,
                        List.of(onboarding, salaries), onboarding, "1", false)),
                List.of(), false));
        when(documents.findLiveBySourceIds(any(), any())).thenReturn(List.of(document(onboarding, wiki, "Onboarding")));
        search = mock(SearchService.class);
        tool = new SearchKnowledgeGraphTool(graph, connectors, documents, bm25, search);
        SearchContext.setUserId("ana");
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Test
    @DisplayName("titles are named only for documents of readable sources, and an edge carries its passage")
    @SuppressWarnings("unchecked")
    void titlesAreScopedAndPassagesAttached() {
        Map<String, Object> result = tool.searchKnowledgeGraph("Ana", 1, null, null);

        Map<String, Object> entity = ((List<Map<String, Object>>) result.get("entities")).get(0);
        assertEquals(List.of("Onboarding"), entity.get("documents"));
        assertEquals("An engineer.", entity.get("description"));
        Map<String, Object> edge = ((List<Map<String, Object>>) result.get("relations")).get(0);
        assertEquals(List.of("Onboarding"), edge.get("documents"));
        // Chunk 1 among the NON-BLANK chunks, as extraction numbered them.
        assertEquals("Ana leads the Payments team.", edge.get("passage"));
        assertFalse(result.toString().contains("Salary review"), result.toString());
    }

    @Test
    @DisplayName("@-mentioned sources narrow the traversal to those, and one that matches nothing says so")
    void sourceNamesNarrowTheScope() {
        when(connectors.resolveVisibleByNames("ana", List.of("Wiki"))).thenReturn(List.of(connector(wiki)));
        tool.searchKnowledgeGraph("Ana", 1, List.of("Wiki"), null);
        verify(graph).neighbourhood(any(), anyInt(), eq(List.of(wiki)), any(), any());

        when(connectors.resolveVisibleByNames("ana", List.of("Nope"))).thenReturn(List.of());
        Map<String, Object> none = tool.searchKnowledgeGraph("Bo", 1, List.of("Nope"), null);
        assertTrue(String.valueOf(none.get("note")).startsWith("No visible source matches"));
        verify(graph, never()).neighbourhood(eq(List.of("Bo")), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("the question is linked through the search: each result becomes its document and chunk number")
    void questionBecomesRelevantPassages() {
        Document hit = Document.builder().id("x").text("Ana leads the Payments team.")
                .metadata(Map.of("source", "Onboarding.md", "normalizedScore", 0.9,
                        ChunkMetadata.COLLECTION, SourceCollections.of(wiki))).build();
        when(search.search(any(), eq("who runs payments?"), anyInt(), org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(List.of(hit));

        List<RelevantPassage> passages = tool.relevantTo("who runs payments?", Map.of(wiki, connector(wiki)));

        // Index 1 among the non-blank chunks - the numbering extraction gave the passage.
        assertEquals(List.of(new RelevantPassage(onboarding, "1", 0.9)), passages);
        assertTrue(tool.relevantTo(null, Map.of(wiki, connector(wiki))).isEmpty());
    }

    @Test
    @DisplayName("one item id in two sources: a result is its own source's passage, never the other's")
    void aResultIsItsOwnSourcesDocument() {
        UUID zendesk = UUID.randomUUID();
        UUID freshdesk = UUID.randomUUID();
        UUID zendeskTicket = UUID.randomUUID();
        UUID freshdeskTicket = UUID.randomUUID();
        TrackedDocumentRepository documents = mock(TrackedDocumentRepository.class);
        TrackedDocument z = document(zendeskTicket, zendesk, "Refund request");
        z.setSourceId("ticket:1");
        TrackedDocument f = document(freshdeskTicket, freshdesk, "Printer jam");
        f.setSourceId("ticket:1");
        when(documents.findLiveBySourceIds(any(), any())).thenReturn(List.of(z, f));
        // The same templated reply in both tickets - text alone cannot tell them apart.
        when(bm25.getChunkTexts(SourceCollections.of(zendesk), "ticket:1")).thenReturn(List.of("Thanks, we are on it."));
        when(bm25.getChunkTexts(SourceCollections.of(freshdesk), "ticket:1")).thenReturn(List.of("Thanks, we are on it."));
        Document hit = Document.builder().id("x").text("Thanks, we are on it.")
                .metadata(Map.of("source", "ticket:1", "normalizedScore", 0.9,
                        ChunkMetadata.COLLECTION, SourceCollections.of(freshdesk))).build();
        when(search.search(any(), eq("printer"), anyInt(), org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(List.of(hit));
        SearchKnowledgeGraphTool scoped = new SearchKnowledgeGraphTool(graph, connectors, documents, bm25, search);

        List<RelevantPassage> passages = scoped.relevantTo("printer",
                Map.of(zendesk, connector(zendesk), freshdesk, connector(freshdesk)));

        assertEquals(List.of(new RelevantPassage(freshdeskTicket, "0", 0.9)), passages);
    }

    @Test
    @DisplayName("findGraphTimeline dates each change and names only readable documents")
    @SuppressWarnings("unchecked")
    void timelineTool() {
        when(graph.timeline(List.of("PAY-12"), List.of(wiki))).thenReturn(List.of(
                new ai.proxyma.core.graph.service.TimelineEntry(java.time.Instant.parse("2026-04-01T09:00:00Z"),
                        "PAY-12", "assigned to", "Bo", ai.proxyma.core.graph.service.TimelineEntry.Change.STARTED, onboarding),
                new ai.proxyma.core.graph.service.TimelineEntry(java.time.Instant.parse("2026-05-02T09:00:00Z"),
                        "PAY-12", "moved to", "Done", ai.proxyma.core.graph.service.TimelineEntry.Change.HAPPENED, salaries)));

        List<Map<String, Object>> events = (List<Map<String, Object>>) tool.findGraphTimeline("PAY-12", null).get("events");

        assertEquals("2026-04-01", events.get(0).get("date"));
        assertEquals("PAY-12 assigned to Bo", events.get(0).get("what"));
        assertEquals("started", events.get(0).get("change"));
        assertEquals("Onboarding", events.get(0).get("document"));
        assertFalse(events.get(1).containsKey("document"), "a document of an unreadable source is not named");
    }

    @Test
    @DisplayName("findGraphThemes hands over each group's names, statements and documents")
    @SuppressWarnings("unchecked")
    void themesTool() {
        when(graph.themes(eq(List.of(wiki)), any(), anyInt())).thenReturn(List.of(new ai.proxyma.core.graph.service.GraphTheme(
                List.of("Payments", "Ledger"), List.of("Payments owns Ledger"), 4, List.of(onboarding, salaries))));

        Map<String, Object> theme = ((List<Map<String, Object>>) tool.findGraphThemes(null, null).get("themes")).get(0);

        assertEquals(List.of("Payments", "Ledger"), theme.get("entities"));
        assertEquals(List.of("Onboarding"), theme.get("documents"));
    }

    @Test
    @DisplayName("findGraphPath answers with the paths and only the relationships they walk")
    @SuppressWarnings("unchecked")
    void pathTool() {
        when(graph.neighbourhood(eq(List.of("Ana", "Ledger")), anyInt(), eq(List.of(wiki)), any(), any()))
                .thenReturn(new GraphNeighbourhood(List.of(),
                        List.of(new GraphNeighbourhood.Edge("Ana", "leads", "Payments", null, 1, List.of(onboarding), onboarding, "1", false),
                                new GraphNeighbourhood.Edge("Payments", "owns", "Ledger", null, 1, List.of(onboarding), null, null, true),
                                new GraphNeighbourhood.Edge("Ana", "likes", "Tea", null, 1, List.of(onboarding), null, null, false)),
                        List.of(new GraphNeighbourhood.Path(List.of(
                                new GraphNeighbourhood.Step("Ana", "leads", "Payments"),
                                new GraphNeighbourhood.Step("Payments", "owns", "Ledger")))),
                        false));

        Map<String, Object> result = tool.findGraphPath("Ana", "Ledger", null);

        assertEquals(List.of("Ana leads Payments; Payments owns Ledger"), result.get("paths"));
        List<Map<String, Object>> relations = (List<Map<String, Object>>) result.get("relations");
        assertEquals(2, relations.size());
        assertEquals("metadata", relations.get(1).get("origin"));
    }

    @Test
    @DisplayName("findGraphPeople lists the people on the thing and through one item, with how")
    @SuppressWarnings("unchecked")
    void peopleTool() {
        when(graph.neighbourhood(eq(List.of("Ledger")), anyInt(), eq(List.of(wiki)), any(), any()))
                .thenReturn(new GraphNeighbourhood(
                        List.of(new GraphNeighbourhood.Entity(UUID.randomUUID(), "Ledger", "component", null, 0, List.of(onboarding)),
                                new GraphNeighbourhood.Entity(UUID.randomUUID(), "PAY-12", "issue", null, 1, List.of(onboarding)),
                                new GraphNeighbourhood.Entity(UUID.randomUUID(), "Ana", "person", null, 2, List.of(onboarding)),
                                new GraphNeighbourhood.Entity(UUID.randomUUID(), "Bo", "person", null, 1, List.of(onboarding))),
                        List.of(new GraphNeighbourhood.Edge("PAY-12", "in component", "Ledger", null, 1, List.of(onboarding), null, null, true),
                                new GraphNeighbourhood.Edge("PAY-12", "assigned to", "Ana", null, 1, List.of(onboarding), null, null, true),
                                new GraphNeighbourhood.Edge("Bo", "maintains", "Ledger", null, 1, List.of(onboarding), null, null, false)),
                        List.of(), false));

        List<Map<String, Object>> people = (List<Map<String, Object>>) tool.findGraphPeople("Ledger", null).get("people");

        assertEquals(2, people.size());
        assertTrue(people.stream().anyMatch(p -> p.get("name").equals("Ana")
                && p.get("roles").equals(List.of("PAY-12 assigned to Ana"))), people.toString());
        assertTrue(people.stream().anyMatch(p -> p.get("name").equals("Bo")
                && p.get("roles").equals(List.of("Bo maintains Ledger"))), people.toString());
    }
}
