package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fired once per document that finishes indexing, carrying enough to act on it without a database
// read: which source it came from, which row it is, the id it has at the source, its title and the
// collection its vectors went into.
//
// Distinct from ConnectorUpdateEvent, which fires on every status change and exists for the
// frontend's progress display. This one marks the single transition a downstream module cares
// about - the document is now searchable - and is published unconditionally. Gating a publish on
// whether some feature is switched on leaves that feature's derived data silently behind when it is
// switched back on.
public class DocumentIndexedEvent extends ApplicationEvent {

    private final UUID connectorId;
    private final UUID documentId;
    private final String sourceId;
    private final String title;
    private final String collectionId;

    public DocumentIndexedEvent(Object source, UUID connectorId, UUID documentId,
                                String sourceId, String title, String collectionId) {
        super(source);
        this.connectorId = connectorId;
        this.documentId = documentId;
        this.sourceId = sourceId;
        this.title = title;
        this.collectionId = collectionId;
    }

    public UUID getConnectorId() { return connectorId; }
    public UUID getDocumentId() { return documentId; }
    public String getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public String getCollectionId() { return collectionId; }
}
