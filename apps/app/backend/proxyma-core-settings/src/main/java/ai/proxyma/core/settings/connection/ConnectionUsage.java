package ai.proxyma.core.settings.connection;

import java.util.UUID;

/**
 * How many sources read through a connection. Implemented where sources live (the registry), which
 * this module cannot see.
 *
 * Deleting a connection that sources still use is refused rather than cascaded or re-pointed: a
 * source silently moved to another server indexes the wrong thing, and one silently deleted loses
 * an index nobody asked to lose.
 */
public interface ConnectionUsage {

    long sourcesUsing(UUID connectionId);
}
