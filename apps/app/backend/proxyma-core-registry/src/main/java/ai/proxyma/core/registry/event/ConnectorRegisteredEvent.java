package ai.proxyma.core.registry.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

// Fired once when a NEW source is registered - not when an existing one is re-synced by
// registering the same name again, which adds no row to any list.
//
// It exists because nothing else says a list changed. Registration published no event at all, so a
// source added by the agent in chat (or by an MCP client) never reached a Sources page that had
// already cached its list: the first event that did - a per-document ConnectorUpdateEvent or the
// ConnectorSyncedEvent at the end of the first sync - arrives seconds to minutes later, and only to
// whoever is listening then. The UI's own register modals hid the gap by invalidating the list
// themselves on success. Found 2026-09-14.
//
// userId is carried rather than looked up for the same reason ConnectorRemovedEvent carries it: the
// SSE scope must be decided from the event alone. A newly registered source is never shared.
public class ConnectorRegisteredEvent extends ApplicationEvent {

    private final UUID connectorId;
    private final String userId;

    public ConnectorRegisteredEvent(Object source, UUID connectorId, String userId) {
        super(source);
        this.connectorId = connectorId;
        this.userId = userId;
    }

    public UUID getConnectorId() { return connectorId; }
    public String getUserId() { return userId; }
}
