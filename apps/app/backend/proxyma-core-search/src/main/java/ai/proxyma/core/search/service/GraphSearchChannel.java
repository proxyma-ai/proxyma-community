package ai.proxyma.core.search.service;

import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.graph.service.RelevantPassage;
import ai.proxyma.core.graph.service.SimilarEntity;
import ai.proxyma.core.registry.service.EntityVectorService;
import ai.proxyma.core.registry.service.GraphPassageResolver;
import ai.proxyma.core.registry.service.SourceCollections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The knowledge graph as a third ranked list of hybrid search (KnowledgeGraphService.graphPassages),
 * in the shape SearchService fuses: chunk text and source. Off unless GraphSettings.searchChannel is
 * on - see SearchService.
 *
 * <p>Scoped by the COLLECTIONS the search was given, read back as the sources they belong to: a
 * connector's collection is its id without dashes, and its reindex shadow the same with a suffix.
 * Any other collection (guardrail policies, agent memory) names no source and adds nothing.
 */
@Component
public class GraphSearchChannel {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchChannel.class);

    private final KnowledgeGraphService knowledgeGraph;
    private final GraphPassageResolver passages;
    private final EntityVectorService entityVectors;

    public GraphSearchChannel(KnowledgeGraphService knowledgeGraph, GraphPassageResolver passages,
                              EntityVectorService entityVectors) {
        this.knowledgeGraph = knowledgeGraph;
        this.passages = passages;
        this.entityVectors = entityVectors;
    }

    /** The passages the graph ranks for {@code query}, best first. Empty on any failure. */
    public List<GraphPassageResolver.Resolved> rank(List<String> collectionIds, String query, int limit) {
        List<UUID> sources = sourcesOf(collectionIds);
        if (sources.isEmpty()) return List.of();
        try {
            List<SimilarEntity> similar = entityVectors.similar(query, 8, 0.8);
            List<RelevantPassage> ranked = knowledgeGraph.graphPassages(query, similar, sources, limit);
            return passages.resolve(ranked);
        } catch (RuntimeException e) {
            log.debug("[Search] the graph channel added nothing: {}", e.getMessage());
            return List.of();
        }
    }

    static List<UUID> sourcesOf(List<String> collectionIds) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String id : collectionIds == null ? List.<String>of() : collectionIds) {
            SourceCollections.sourceOf(id).ifPresent(out::add);
        }
        return new ArrayList<>(out);
    }
}
