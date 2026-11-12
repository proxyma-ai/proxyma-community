package ai.proxyma.core.settings.connection;

import java.util.List;
import java.util.UUID;

/**
 * The sources that read through a connection. Implemented where sources live (the registry), which
 * this module cannot see.
 *
 * <p>DELETING A CONNECTION SOURCES STILL USE IS ALLOWED, since 2026-11-03 (the maintainer: "It should
 * allow to delete any connector even if it's being used, and show a warning about it"). It used to be
 * refused. Nothing is cascaded and nothing is re-pointed: a source silently moved to another server
 * indexes the wrong thing, and one silently deleted loses an index nobody asked to lose. The sources
 * are KEPT, with everything they indexed, and are left naming a connection that no longer exists - so
 * they stop syncing and say why ({@link #connectionDeleted}), until another connection is chosen in
 * their settings or they are registered again (ConnectorService.adoptConnectionIfMissing).
 */
public interface ConnectionUsage {

    long sourcesUsing(UUID connectionId);

    /** The names of the sources reading through the connection, by name, at most {@code limit} of them. */
    default List<String> sourceNamesUsing(UUID connectionId, int limit) {
        return List.of();
    }

    /**
     * The connection was deleted: every source that read through it keeps its items and is marked as
     * needing another connection, so its card says so before its next sync would.
     */
    default void connectionDeleted(UUID connectionId) {
    }
}
