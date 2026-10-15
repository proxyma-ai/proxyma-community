package ai.proxyma.core.graph.event;

import java.util.List;
import java.util.UUID;

/**
 * Entities the graph deleted - orphaned by a document or source going. Whatever keeps something per
 * entity outside this module (the name vectors) drops theirs.
 */
public record GraphEntitiesRemovedEvent(List<UUID> entityIds) {}
