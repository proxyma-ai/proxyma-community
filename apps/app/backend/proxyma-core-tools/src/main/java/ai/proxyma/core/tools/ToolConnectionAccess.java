package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.settings.connection.ConnectionAccessPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The conversation's connection for a service - its useServer pick, else the caller's default - for
 * McpGuardService's pre-flight. The same order ToolConnectorResolver uses when a call names no
 * source, so the prompt offers to switch on the connection such a call would actually use.
 */
@Component
class ToolConnectionAccess implements ConnectionAccessPort {

    private final ActiveServers activeServers;
    private final ConnectorEndpointResolver endpoints;

    ToolConnectionAccess(ActiveServers activeServers, ConnectorEndpointResolver endpoints) {
        this.activeServers = activeServers;
        this.endpoints = endpoints;
    }

    @Override
    public Optional<Target> conversationTarget(ConnectorType type, String userId) {
        Endpoint e = activeServers.active(type).orElseGet(() -> endpoints.forType(type, userId));
        if (!e.exists()) return Optional.empty();
        return Optional.of(new Target(e.connectionId(), e.connectionName(), e.allowRead(), e.allowWrite()));
    }
}
