package ai.proxyma.core.registry.event;

import java.util.UUID;

/**
 * A document's source metadata changed without its content changing - a sync learned who last
 * changed an item it already had indexed. Its knowledge-graph metadata edges are due again
 * (GraphExtractionOrchestrator), with no re-index.
 */
public record DocumentMetadataChangedEvent(UUID connectorId, UUID documentId) {}
