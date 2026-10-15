package ai.proxyma.core.graph.service;

import java.util.UUID;

/**
 * An entity whose NAME means something close to a term - from the entity name vectors the registry
 * module keeps (EntityVectorService). The graph module cannot reach the vector store, which depends
 * on it; the caller asks and hands the answer in.
 *
 * @param score similarity, 0 to 1
 */
public record SimilarEntity(UUID id, double score) {}
