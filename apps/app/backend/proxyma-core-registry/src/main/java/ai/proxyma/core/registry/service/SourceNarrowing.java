package ai.proxyma.core.registry.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The sources the work running on THIS thread may read, when something has narrowed them below what
 * its user can see - or empty for "no narrowing". {@link ConnectorService#listVisibleTo} and
 * {@link ConnectorService#visibleCollectionIds}, THE places that answer "which sources may this
 * caller see" for every tool and search, intersect their answer with it.
 *
 * <p>The one implementation is the tools' (ProfileSourceNarrowing, proxyma-core-tools): a conversation
 * whose agent has a profile (a saved agent of Constellations, Enterprise Server) reads only the sources
 * that profile names. It lives there because that is where the turn's conversation and its profile
 * are both known; this module can see neither, so it asks this port, and an edition with no
 * implementation narrows nothing.
 *
 * <p>It can only NARROW. The answer is intersected with the caller's own visibility, so a source id
 * the caller cannot see is not made visible by being named here. Asked on every visibility lookup a
 * tool makes, so an implementation answers from memory or one primary-key read, and never throws -
 * a failure is answered as {@code Optional.of(Set.of())}, nothing at all, by the caller.
 */
public interface SourceNarrowing {

    /** The source ids the current work may read, or empty when nothing narrows it. */
    Optional<Set<UUID>> allowedSources();
}
