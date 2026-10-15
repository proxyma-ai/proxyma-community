package ai.proxyma.core.graph.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One dated moment in the history of a named thing, as a source's metadata dates it.
 *
 * @param change     whether the statement started holding, stopped holding, or is a one-off event
 * @param documentId the document whose metadata states it
 */
public record TimelineEntry(Instant at, String subject, String predicate, String object, Change change,
                            UUID documentId) {

    public enum Change { STARTED, ENDED, HAPPENED }
}
