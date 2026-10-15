package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which of the caller's connections THIS conversation uses for a service - picked with the
 * useServer tool. Every tool of that service then resolves to it (ToolConnectorResolver), keys and
 * all; with nothing picked, a tool uses the source it named, else the default connection.
 *
 * <p>A conversation-level choice rather than a parameter on each tool: there are about fifty of
 * those tools, and a connection parameter on every one would be sent on every call. Stored on the
 * conversation (Conversation.activeServers) as {@code TYPE=connectionId} lines, so it survives a
 * restart and a rename of the connection, and never applies outside one - over MCP there is no
 * conversation and nothing is picked.
 */
@Component
public class ActiveServers {

    private final SmartChatMemory chatMemory;
    private final ConnectorEndpointResolver endpoints;

    public ActiveServers(SmartChatMemory chatMemory, ConnectorEndpointResolver endpoints) {
        this.chatMemory = chatMemory;
        this.endpoints = endpoints;
    }

    /** The connection this conversation picked for {@code type}, if it picked one that is still the caller's. */
    public Optional<Endpoint> active(ConnectorType type) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        String picked = read(conversationId).get(type.name());
        if (picked == null) return Optional.empty();
        try {
            Endpoint e = endpoints.byId(type, UUID.fromString(picked), SearchContext.getUserId());
            return e.exists() ? Optional.of(e) : Optional.empty();
        } catch (IllegalArgumentException e) {
            // A value from before connections - a server NAME. Nothing to resolve it to any more.
            return Optional.empty();
        }
    }

    /** The id of the connection tools of {@code type} use in this conversation when no source is named. */
    public UUID activeId(ConnectorType type) {
        return active(type).map(Endpoint::connectionId)
                .orElseGet(() -> endpoints.forType(type, SearchContext.getUserId()).connectionId());
    }

    public List<ResolvedConnection> connections(ConnectorType type) {
        return endpoints.connections(type, SearchContext.getUserId());
    }

    public void select(ConnectorType type, UUID connectionId) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("A connection can only be chosen inside a conversation.");
        }
        // One read-modify-write at a time per conversation: a turn's tool calls run in parallel, and
        // useServer('jira', ...) beside useServer('confluence', ...) each read the old value and the
        // second write dropped the first pick - later Jira calls went to the default connection.
        synchronized (LOCKS[Math.floorMod(conversationId.hashCode(), LOCKS.length)]) {
            Map<String, String> all = read(conversationId);
            all.put(type.name(), connectionId.toString());
            StringBuilder sb = new StringBuilder();
            all.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            chatMemory.setActiveServers(conversationId, sb.toString());
        }
    }

    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private Map<String, String> read(String conversationId) {
        Map<String, String> out = new LinkedHashMap<>();
        String stored = chatMemory.activeServers(conversationId);
        if (stored == null) return out;
        for (String line : stored.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }
}
