package ai.proxyma.core.registry.service;

import ai.proxyma.core.graph.service.RelevantPassage;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A graph passage - a document and its chunk index, as KgMention.chunkRef has it - turned back into
 * what search deals in: the chunk's text and the document's source id, url and type. The chunks
 * are read from the keyword index the extraction read them from, non-blank and in order, which is
 * how extraction numbered them.
 *
 * <p>Only a COMPLETED document of a live source resolves; anything else is dropped, never an error.
 */
@Component
public class GraphPassageResolver {

    private final TrackedDocumentRepository documents;
    private final RegisteredConnectorRepository connectors;
    private final BM25IndexService bm25IndexService;

    public GraphPassageResolver(TrackedDocumentRepository documents, RegisteredConnectorRepository connectors,
                                BM25IndexService bm25IndexService) {
        this.documents = documents;
        this.connectors = connectors;
        this.bm25IndexService = bm25IndexService;
    }

    /**
     * One passage as search knows it. {@code connectorId} is the source the document belongs to:
     * its {@code sourceId} names it only within that source (DocumentKey).
     */
    public record Resolved(UUID documentId, String chunkRef, UUID connectorId, String sourceId, String text,
                           String url, String connectorType, double score) {}

    public List<Resolved> resolve(List<RelevantPassage> passages) {
        if (passages == null || passages.isEmpty()) return List.of();
        Set<UUID> ids = new LinkedHashSet<>();
        passages.forEach(p -> ids.add(p.documentId()));
        Map<UUID, TrackedDocument> docs = new HashMap<>();
        documents.findAllById(ids).forEach(d -> {
            if (d.getStatus() == TrackedDocument.Status.COMPLETED) docs.put(d.getId(), d);
        });
        Map<UUID, RegisteredConnector> sources = new HashMap<>();
        Set<UUID> connectorIds = new LinkedHashSet<>();
        docs.values().forEach(d -> connectorIds.add(d.getConnectorId()));
        connectors.findAllById(connectorIds).forEach(c -> { if (!c.isDeleting()) sources.put(c.getId(), c); });

        Map<UUID, List<String>> chunksOf = new HashMap<>();
        List<Resolved> out = new ArrayList<>();
        for (RelevantPassage p : passages) {
            TrackedDocument doc = docs.get(p.documentId());
            RegisteredConnector source = doc == null ? null : sources.get(doc.getConnectorId());
            if (source == null) continue;
            int index;
            try {
                index = Integer.parseInt(p.chunkRef().trim());
            } catch (RuntimeException e) {
                continue;
            }
            List<String> chunks = chunksOf.computeIfAbsent(doc.getId(), id -> usableChunks(doc, source));
            if (index < 0 || index >= chunks.size()) continue;
            out.add(new Resolved(doc.getId(), p.chunkRef(), source.getId(), doc.getSourceId(), chunks.get(index),
                    doc.getSourceUrl(),
                    doc.getConnectorType() == null ? null : doc.getConnectorType().wireName(), p.score()));
        }
        return out;
    }

    private List<String> usableChunks(TrackedDocument doc, RegisteredConnector source) {
        try {
            List<String> chunks = bm25IndexService.getChunkTexts(source.getId().toString().replace("-", ""), doc.getSourceId());
            if (chunks.isEmpty() && source.getReindexShadowCollectionId() != null) {
                chunks = bm25IndexService.getChunkTexts(source.getReindexShadowCollectionId(), doc.getSourceId());
            }
            return chunks.stream().filter(c -> c != null && !c.isBlank()).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
