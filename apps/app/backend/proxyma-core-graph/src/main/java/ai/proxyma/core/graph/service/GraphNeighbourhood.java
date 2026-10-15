package ai.proxyma.core.graph.service;

import java.util.List;
import java.util.UUID;

/**
 * What a traversal found around the names in a query, scoped to what the asking user can see.
 *
 * <p>Every name, type and description here was written by a document the asker can read - see
 * KnowledgeGraphService.displayOf. None is the entity's stored canonical name, which is whatever
 * the first document anywhere called it.
 */
public record GraphNeighbourhood(List<Entity> entities, List<Edge> edges, List<Path> paths, boolean truncated) {

    public static GraphNeighbourhood empty() {
        return new GraphNeighbourhood(List.of(), List.of(), List.of(), false);
    }

    public boolean isEmpty() {
        return entities.isEmpty() && edges.isEmpty();
    }

    /**
     * @param hop         0 for an entity the query itself named, 1 for a neighbour, 2 for a
     *                    neighbour's neighbour. Kept because it is the difference between "the
     *                    document says this" and "the document says this about something adjacent",
     *                    which a reader weighing an answer needs.
     * @param description   what a readable passage said the entity is, or null
     * @param documentIds   some of the documents mentioning it, within what the asking user can
     *                      see - at most KnowledgeGraphService.DOCUMENTS_PER_ENTITY, those the
     *                      question is about first, then those mentioning it most
     * @param documentCount how many documents mention it in all, within what the user can see
     */
    public record Entity(UUID id, String name, String type, String description, int hop,
                         List<UUID> documentIds, long documentCount) {

        /** Every document listed - for a caller that built the whole list itself. */
        public Entity(UUID id, String name, String type, String description, int hop, List<UUID> documentIds) {
            this(id, name, type, description, hop, documentIds, documentIds.size());
        }
    }

    /**
     * One edge - every visible document stating the same triple, grouped - with both endpoint
     * NAMES already substituted, so the caller does not join back through the entity list.
     *
     * @param weight            how many distinct documents state it
     * @param documentIds       some of those documents - at most
     *                          KnowledgeGraphService.DOCUMENTS_PER_EDGE - the first being the one
     *                          whose passage is cited
     * @param passageDocumentId the document of the passage the edge was drawn from
     * @param passageChunkRef   that passage's chunk reference (its index in the document)
     * @param fromMetadata      every document states it in its source METADATA (an assignee, a
     *                          parent, a link), not in its text - exact, and with no passage
     */
    public record Edge(String subject, String predicate, String object, String description,
                       int weight, List<UUID> documentIds, UUID passageDocumentId, String passageChunkRef,
                       boolean fromMetadata) {}

    /** How two names the query asked about connect: the edges walked from one to the other. */
    public record Path(List<Step> steps) {}

    /** One edge of a path, in the direction it was stated. */
    public record Step(String subject, String predicate, String object) {}
}
