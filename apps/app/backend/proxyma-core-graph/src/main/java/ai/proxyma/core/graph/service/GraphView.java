package ai.proxyma.core.graph.service;

import java.util.List;
import java.util.UUID;

/**
 * A graph as a PICTURE, as opposed to {@link GraphNeighbourhood}, which is a graph as an ANSWER.
 *
 * <p>The two are separate records rather than one shared shape, and the difference is not
 * cosmetic. A neighbourhood starts from names in a question and expands by hops, so it carries a
 * {@code hop} per entity - the thing a model weighing an answer needs, and the thing a person
 * looking at a whole source has no use for. A view starts from a SCOPE, has no centre, and needs
 * the two numbers a layout is drawn from: how big each node should be, and how heavy each edge is.
 *
 * <p>EDGES REFERENCE NODE IDS, NOT NAMES. The neighbourhood substitutes endpoint names so a model
 * can read a triple without joining; a force layout needs the opposite - stable identity, so two
 * entities that happen to share a display name do not collapse into one circle.
 *
 * @param truncated whether a cap was hit. Reported for the same reason the neighbourhood reports
 *                  it: a graph clipped at the limit looks exactly like a complete one, and a
 *                  reader who is not told will read absence of an edge as absence of a relation.
 * @param totalNodes what the scope actually holds, before the cap. Without it "truncated: true"
 *                   tells a reader they are missing something but not whether it is ten nodes or
 *                   ten thousand.
 * @param hasMore    whether another PAGE of this same view exists. A view is delivered in
 *                   descending order of importance, most connected first, so a client can draw
 *                   the first page while the rest is still arriving - see
 *                   {@link KnowledgeGraphService#viewForConnector} for what a page contains.
 *                   Distinct from {@code truncated}, which says the view will never hold
 *                   everything the scope does however many pages are fetched.
 */
public record GraphView(List<Node> nodes, List<Edge> edges,
                        boolean truncated, long totalNodes, long totalEdges, boolean hasMore) {

    public static GraphView empty() {
        return new GraphView(List.of(), List.of(), false, 0, 0, false);
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty();
    }

    /**
     * @param type     may be null. The extractor could not classify it, and the view says so
     *                 rather than inventing a bucket - see KgEntity.type for why that matters at
     *                 the unique constraint.
     * @param mentions how many times the scope names this entity. The node's size.
     * @param degree   how many edges in THIS VIEW touch it. Not the entity's degree in the whole
     *                 graph: a node drawn with two visible edges must not claim forty.
     */
    public record Node(UUID id, String name, String type, int mentions, int degree) {}

    /**
     * @param weight     how many distinct documents state this triple. The edge's thickness.
     * @param documentId one document that asserted it, so the view can offer to open it.
     */
    public record Edge(UUID sourceId, UUID targetId, String predicate, String description,
                       int weight, UUID documentId) {}
}
