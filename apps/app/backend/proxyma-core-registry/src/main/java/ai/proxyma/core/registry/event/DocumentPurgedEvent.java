package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fired when a document is marked DELETED because a full sync no longer found it at the source.
//
// The vectors and the knowledge-graph entries are dropped on the spot by the purge itself; this
// event is for everything else that derives from an indexed document and would otherwise keep
// answering with something that no longer exists. Published unconditionally, for the reason given
// on DocumentIndexedEvent.
public class DocumentPurgedEvent extends ApplicationEvent {

    private final UUID connectorId;
    private final UUID documentId;

    public DocumentPurgedEvent(Object source, UUID connectorId, UUID documentId) {
        super(source);
        this.connectorId = connectorId;
        this.documentId = documentId;
    }

    public UUID getConnectorId() { return connectorId; }
    public UUID getDocumentId() { return documentId; }
}
