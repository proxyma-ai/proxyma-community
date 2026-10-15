package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ConnectorUpdateEvent extends ApplicationEvent {

    private final UUID connectorId;
    private final UUID documentId;
    private final String documentTitle;
    private final String status;
    private final int currentChunks;
    private final int totalChunks;

    public ConnectorUpdateEvent(Object source, UUID connectorId, UUID documentId,
                               String documentTitle, String status) {
        this(source, connectorId, documentId, documentTitle, status, 0, 0);
    }

    public ConnectorUpdateEvent(Object source, UUID connectorId, UUID documentId,
                               String documentTitle, String status,
                               int currentChunks, int totalChunks) {
        super(source);
        this.connectorId = connectorId;
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        this.status = status;
        this.currentChunks = currentChunks;
        this.totalChunks = totalChunks;
    }

    public UUID getConnectorId() { return connectorId; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentTitle() { return documentTitle; }
    public String getStatus() { return status; }
    public int getCurrentChunks() { return currentChunks; }
    public int getTotalChunks() { return totalChunks; }
}
