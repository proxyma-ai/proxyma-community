package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgEntity;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import ai.proxyma.core.graph.service.KnowledgeGraphService.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The graph view delivered a page at a time.
 *
 * <p>Paging a PICTURE is not paging a list, and the difference is where this can go wrong
 * silently. A list page that drops a row shows a short list; a graph page that drops an EDGE shows
 * a complete-looking graph with a relation missing from it, and nothing on screen says so. Two
 * rules keep that from happening, and both are tested here rather than described:
 *
 * <ul>
 *   <li><b>Every edge arrives exactly once</b>, with the page holding the LATER of its endpoints -
 *       never with the earlier one, where its far end does not exist yet and a client would drop
 *       it for good.</li>
 *   <li><b>The cap is decided before the first page</b>, so a node's degree counts the edges it
 *       will be drawn with and does not grow as later pages land.</li>
 * </ul>
 */
class KnowledgeGraphViewPagingTest {

    private static final UUID CONNECTOR = UUID.randomUUID();

    private KgEntityRepository entities;
    private KgMentionRepository mentions;
    private KgRelationRepository relations;
    private KnowledgeGraphService service;

    /** Ids in a fixed order, so a failure names the same node twice in a row. */
    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        entities = mock(KgEntityRepository.class);
        mentions = mock(KgMentionRepository.class);
        relations = mock(KgRelationRepository.class);
        service = new KnowledgeGraphService(entities, mock(ai.proxyma.core.graph.repository.KgAliasRepository.class), mentions, relations,
                mock(GraphExtractionService.class), mock(KnowledgeGraphWriter.class),
                mock(KgDocumentStateRepository.class));
    }

    /**
     * A hub-and-spokes source: one node joined to everything, plus a chain among the spokes, which
     * is the shape that makes the edge rule matter - the hub ranks first and most of its edges
     * reach nodes several pages away.
     */
    private void graphOf(int nodeCount) {
        ids.clear();
        for (int i = 0; i < nodeCount; i++) ids.add(UUID.randomUUID());

        List<Object[]> edges = new ArrayList<>();
        for (int i = 1; i < nodeCount; i++) edges.add(edge(ids.get(0), ids.get(i)));
        for (int i = 1; i < nodeCount - 1; i++) edges.add(edge(ids.get(i), ids.get(i + 1)));

        List<Object[]> counts = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) counts.add(new Object[]{ids.get(i), nodeCount - i});

        when(mentions.mentionCountsByConnector(CONNECTOR)).thenReturn(counts);
        when(relations.viewEdgesOfConnector(any(), any(), any(Pageable.class))).thenReturn(edges);
        when(relations.countByConnectorId(CONNECTOR)).thenReturn((long) edges.size());
        when(entities.findAllById(any())).thenAnswer(inv -> {
            List<KgEntity> out = new ArrayList<>();
            for (UUID id : (Iterable<UUID>) inv.getArgument(0)) out.add(entity(id));
            return out;
        });
    }

    // One grouped view row: subject, object, predicate, documents stating it, description, document.
    private static Object[] edge(UUID subject, UUID object) {
        return new Object[]{subject, object, "relates to", 1L, null, UUID.randomUUID().toString()};
    }

    private KgEntity entity(UUID id) {
        KgEntity e = new KgEntity();
        e.setId(id);
        e.setCanonicalName("Entity " + id);
        e.setNormalizedName("entity " + id);
        return e;
    }

    /** Every page of one view, at the given cap and page size. */
    private List<GraphView> pages(int cap, int pageSize) {
        List<GraphView> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            GraphView page = service.viewForConnector(CONNECTOR, new Window(cap, offset, pageSize));
            out.add(page);
            if (!page.hasMore()) return out;
            offset += pageSize;
            // A guard rather than a loop condition: hasMore() staying true forever is one of the
            // failures this test exists to catch, and it should fail as an assertion, not a hang.
            assertTrue(out.size() < 100, "paging did not terminate");
        }
    }

    @Test
    @DisplayName("every node and every edge arrives exactly once across the pages")
    void pagesCoverTheViewWithoutRepeatingIt() {
        graphOf(40);
        List<GraphView> pages = pages(40, 7);

        List<UUID> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        for (GraphView p : pages) {
            p.nodes().forEach(n -> nodes.add(n.id()));
            p.edges().forEach(e -> edges.add(e.sourceId() + "->" + e.targetId()));
        }

        assertEquals(40, nodes.size(), "a node was sent twice or not at all");
        assertEquals(40, new HashSet<>(nodes).size());
        // 39 spokes plus a 38-link chain: the whole drawable graph, delivered once.
        assertEquals(77, edges.size(), "an edge was sent twice or not at all");
        assertEquals(77, new HashSet<>(edges).size());
    }

    @Test
    @DisplayName("no page carries an edge to a node that page has not yet delivered")
    void anEdgeArrivesWithItsLaterEndpoint() {
        graphOf(40);
        Set<UUID> known = new HashSet<>();
        for (GraphView p : pages(40, 7)) {
            p.nodes().forEach(n -> known.add(n.id()));
            for (GraphView.Edge e : p.edges()) {
                // THE RULE THIS TEST EXISTS FOR. The hub is on page one and most of its edges
                // reach nodes on later pages; sending them with the hub would hand a client a line
                // to a node it has never heard of, and a client that drops those drops them
                // permanently - a graph that looks whole and is not.
                assertTrue(known.contains(e.sourceId()) && known.contains(e.targetId()),
                        "edge " + e.sourceId() + "->" + e.targetId() + " arrived before both its ends");
            }
        }
    }

    @Test
    @DisplayName("a node's degree is the same whichever page size fetched it")
    void degreeDoesNotGrowAsLaterPagesLand() {
        graphOf(40);
        Map<UUID, Integer> whole = new HashMap<>();
        for (GraphView.Node n : service.viewForConnector(CONNECTOR, new Window(40, 0, 40)).nodes()) {
            whole.put(n.id(), n.degree());
        }
        for (GraphView p : pages(40, 7)) {
            for (GraphView.Node n : p.nodes()) {
                assertEquals(whole.get(n.id()), n.degree(),
                        "degree changed with the page size, so the first page lied about a node");
            }
        }
    }

    @Test
    @DisplayName("the pages after the first reuse its ranking instead of recomputing the whole scope")
    void laterPagesReuseTheRanking() {
        graphOf(40);
        List<GraphView> pages = pages(40, 7);

        assertEquals(6, pages.size());
        // ONE ranking for six pages. Each page used to re-read every entity id, every edge and a
        // mention count over the lot to return seven nodes of it.
        verify(mentions, times(1)).mentionCountsByConnector(CONNECTOR);
        verify(relations, times(1)).viewEdgesOfConnector(any(), any(), any(Pageable.class));

        // And opening the view again ranks afresh, so a new look never shows an old graph.
        service.viewForConnector(CONNECTOR, new Window(40, 0, 7));
        verify(mentions, times(2)).mentionCountsByConnector(CONNECTOR);
    }

    @Test
    @DisplayName("the cap decides which nodes are in the picture, and says the rest were left out")
    void theCapLimitsTheWholeViewRatherThanThePage() {
        graphOf(40);
        List<GraphView> pages = pages(10, 4);
        int nodes = pages.stream().mapToInt(p -> p.nodes().size()).sum();

        assertEquals(10, nodes, "the cap must bound the whole view, not each page");
        assertTrue(pages.get(0).truncated(), "a capped view has to say so");
        assertEquals(40, pages.get(0).totalNodes(), "the reader is told how much was left out");
        assertFalse(pages.get(pages.size() - 1).hasMore(), "the last page must not ask for another");
    }

    @Test
    @DisplayName("asking for no window at all still returns the whole graph in one response")
    void theUnpagedCallIsUnchanged() {
        graphOf(40);
        GraphView all = service.viewForConnector(CONNECTOR);

        assertEquals(40, all.nodes().size());
        assertEquals(77, all.edges().size());
        assertFalse(all.hasMore(), "one response that holds everything has nothing more to offer");
    }
}
