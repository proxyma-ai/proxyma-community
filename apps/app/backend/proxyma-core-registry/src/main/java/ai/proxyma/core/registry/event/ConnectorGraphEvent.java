package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fired when the knowledge-graph worker opens a document of this source and again when it closes
// it, however that ends. The worker never writes a TrackedDocument, so ConnectorUpdateEvent never
// fires for it - and without this a source card could not say "being read by the graph" until
// something else happened to refresh it. Carries no counts: the screen asks for them.
public class ConnectorGraphEvent extends ApplicationEvent {

    private final UUID connectorId;

    public ConnectorGraphEvent(Object source, UUID connectorId) {
        super(source);
        this.connectorId = connectorId;
    }

    public UUID getConnectorId() { return connectorId; }
}
