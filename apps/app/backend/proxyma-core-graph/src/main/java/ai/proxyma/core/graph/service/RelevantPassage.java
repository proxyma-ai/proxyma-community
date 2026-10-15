package ai.proxyma.core.graph.service;

import java.util.UUID;

/**
 * A passage the search found relevant to the question being asked, as the graph knows passages:
 * a document and the index of its chunk (KgMention.chunkRef). The caller maps search results to
 * these - the graph module cannot see the search, which depends on it.
 *
 * @param score how relevant, higher is better; only the order and the proportions matter
 */
public record RelevantPassage(UUID documentId, String chunkRef, double score) {}
