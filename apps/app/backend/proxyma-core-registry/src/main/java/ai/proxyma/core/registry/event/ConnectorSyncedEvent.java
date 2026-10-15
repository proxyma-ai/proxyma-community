package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fired once per completed sync (full or incremental), even when zero documents changed -
// unlike ConnectorUpdateEvent, which only fires per-document and therefore never fires at all
// for a sync that found nothing new. Lets the frontend refresh RegisteredConnector-level fields
// (lastSyncedAt in particular) that ConnectorUpdateEvent has no reason to carry.
public class ConnectorSyncedEvent extends ApplicationEvent {

    private final UUID connectorId;

    public ConnectorSyncedEvent(Object source, UUID connectorId) {
        super(source);
        this.connectorId = connectorId;
    }

    public UUID getConnectorId() { return connectorId; }
}
