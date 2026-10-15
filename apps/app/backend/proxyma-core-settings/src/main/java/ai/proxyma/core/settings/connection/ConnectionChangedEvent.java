package ai.proxyma.core.settings.connection;

import java.util.UUID;

/** Published after any write to a connection, including its deletion. */
public record ConnectionChangedEvent(UUID connectionId, boolean deleted) {}
