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
 * all; with nothing picked, a tool uses the source it named, else the connection the conversation's
 * saved agent is pinned to ({@link #pinned}), else the default connection.
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

    // A saved agent's profile (AgentProfiles), for its pinned connections. Setter-injected, so where there is
    // none - a test that builds this by hand - nothing is pinned.
    private ai.proxyma.core.chat.module.AgentProfiles profiles;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentProfiles(ai.proxyma.core.chat.module.AgentProfiles profiles) {
        this.profiles = profiles;
    }

    /**
     * The connection this conversation's AGENT is pinned to for {@code type} (AgentProfile.connectionPins) -
     * a saved agent's, or its root's for an agent it deployed - if that connection is still the caller's own
     * and serves the type. A pin to a connection that was deleted, or that is somebody else's (the agent's
     * pin was set for its run-as person, and another member is talking to it), is empty: the caller's
     * default is used, and nobody's credential is ever lent.
     */
    public Optional<Endpoint> pinned(ConnectorType type) {
        String conversationId = SearchContext.getConversationId();
        if (profiles == null || conversationId == null || conversationId.isBlank()) return Optional.empty();
        String userId = SearchContext.getUserId();
        Optional<UUID> pin = profiles.of(conversationId, userId).flatMap(p -> p.connectionPin(type.wireName()));
        if (pin.isEmpty()) return Optional.empty();
        Endpoint e = endpoints.byId(type, pin.get(), userId);
        return e.exists() ? Optional.of(e) : Optional.empty();
    }

    /**
     * The id of the connection tools of {@code type} use in this conversation when no source is named: its
     * useServer pick, else its agent's pin, else the caller's default.
     */
    public UUID activeId(ConnectorType type) {
        return active(type).or(() -> pinned(type)).map(Endpoint::connectionId)
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
