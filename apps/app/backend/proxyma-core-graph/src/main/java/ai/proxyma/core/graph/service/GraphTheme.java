package ai.proxyma.core.graph.service;

import java.util.List;
import java.util.UUID;

/**
 * One theme of a corpus: a group of entities more connected to each other than to the rest, as the
 * readable sources' current relationships connect them (KnowledgeGraphService.themes).
 *
 * @param entities    its most connected entities, most connected first
 * @param statements  its most corroborated relationships, as sentences
 * @param size        how many entities it holds
 * @param documentIds the documents its relationships come from, most used first
 */
public record GraphTheme(List<String> entities, List<String> statements, int size, List<UUID> documentIds) {}
