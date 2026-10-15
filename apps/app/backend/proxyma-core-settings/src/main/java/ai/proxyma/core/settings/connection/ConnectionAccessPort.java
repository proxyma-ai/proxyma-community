package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectorType;

import java.util.Optional;
import java.util.UUID;

/**
 * Which connection a tool of one service would use in the CURRENT conversation when no source is
 * named: the one picked with useServer, else the caller's default. Implemented by the tools module,
 * which owns that choice; asked by McpGuardService for the chat's pre-flight - "is this write going
 * to be refused, and which switch would the user flip?".
 *
 * The pre-flight is a courtesy, not the gate: the call itself resolves its connection again, source
 * key included, and ToolConnectorResolver refuses a write the connection does not allow.
 */
public interface ConnectionAccessPort {

    Optional<Target> conversationTarget(ConnectorType type, String userId);

    record Target(UUID id, String name, boolean allowRead, boolean allowWrite) {}
}
