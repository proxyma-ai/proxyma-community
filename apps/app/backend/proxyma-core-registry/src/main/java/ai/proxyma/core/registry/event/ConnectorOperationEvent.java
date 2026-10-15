package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fires at step boundaries of a multi-step connector operation (reindex, delete) so the UI
// can show what is currently happening (e.g. "Stopping sync...") instead of one opaque
// spinner for the whole operation.
public class ConnectorOperationEvent extends ApplicationEvent {

    public enum Phase {
        STOPPING_SYNC,
        CLEARING_INDEX,
        RESETTING,
        DELETING_DATA
    }

    private final UUID connectorId;
    private final String operation;
    private final Phase phase;

    public ConnectorOperationEvent(Object source, UUID connectorId, String operation, Phase phase) {
        super(source);
        this.connectorId = connectorId;
        this.operation = operation;
        this.phase = phase;
    }

    public UUID getConnectorId() { return connectorId; }
    public String getOperation() { return operation; }
    public Phase getPhase() { return phase; }
}
