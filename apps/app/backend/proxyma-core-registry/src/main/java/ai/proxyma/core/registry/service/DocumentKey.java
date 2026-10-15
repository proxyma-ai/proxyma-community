package ai.proxyma.core.registry.service;

import ai.proxyma.core.indexstore.ChunkMetadata;
import org.springframework.ai.document.Document;

import java.util.UUID;

/**
 * Which indexed document a search hit belongs to: its SOURCE (the RegisteredConnector) and its
 * sourceId within that source - the pair TrackedDocument is unique on.
 *
 * <p>Never the sourceId alone. It is unique only within one source, and the SaaS connectors' short
 * ids ({@code ticket:1}, {@code issue:1}) collide across sources as a matter of course: resolving a
 * hit by its sourceId gave a Freshdesk result the Zendesk ticket's title. The source is read off the
 * collection the store stamped on the hit (ChunkMetadata), a reindex shadow mapped to its base.
 */
public record DocumentKey(UUID connectorId, String sourceId) {

    /**
     * The document {@code hit} came from, or null when it names none: no {@code source}, no
     * collection stamp, or a collection that belongs to no source (guardrail policies, memory).
     */
    public static DocumentKey of(Document hit) {
        if (hit == null) return null;
        Object source = hit.getMetadata().get("source");
        if (source == null || source.toString().isEmpty()) return null;
        return SourceCollections.sourceOf(ChunkMetadata.collectionOf(hit))
                .map(id -> new DocumentKey(id, source.toString()))
                .orElse(null);
    }
}
