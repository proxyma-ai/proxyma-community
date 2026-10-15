package ai.proxyma.core.registry.event;

import ai.proxyma.core.connector.ConnectorType;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ConnectorRemovedEvent extends ApplicationEvent {

    private final UUID connectorId;
    private final ConnectorType connectorType;
    private final String config;
    private final String userId;
    private final boolean shared;

    // shared is captured here (rather than re-looked-up later) because by the time
    // EventStreamController's listener processes this event, the RegisteredConnector row is
    // already gone - there is nothing left to look up ownership/shared-status from.
    public ConnectorRemovedEvent(Object source, UUID connectorId, ConnectorType connectorType,
                                String config, String userId, boolean shared) {
        super(source);
        this.connectorId = connectorId;
        this.connectorType = connectorType;
        this.config = config;
        this.userId = userId;
        this.shared = shared;
    }

    public UUID getConnectorId() { return connectorId; }
    public ConnectorType getConnectorType() { return connectorType; }
    public String getConfig() { return config; }
    public String getUserId() { return userId; }
    public boolean isShared() { return shared; }
}
