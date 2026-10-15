package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.search.domain.SearchContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What a register tool tells the model: whether it ADDED a source or found one already there.
 *
 * <p>Registration is idempotent - a source that exists is re-synced and its id returned - and every
 * register tool used to answer the same "registered" either way. Asked to remove and re-add four
 * sources, the agent re-registered them, got four ids back and reported them as "New Source ID"s
 * with a fresh index under way; they were the old sources, re-synced in place, with their old
 * counts. Saying which case happened is what lets it report what it actually did.
 */
public final class SourceRegistration {

    private SourceRegistration() {}

    public static Map<String, Object> outcome(ConnectorService connectorService, String registered, String idKey,
                                              Supplier<UUID> register) {
        String userId = SearchContext.getUserId();
        Set<UUID> before = connectorService.listVisibleTo(userId).stream()
                .map(RegisteredConnector::getId).collect(Collectors.toSet());
        UUID id = register.get();
        boolean already = before.contains(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("alreadyRegistered", already);
        out.put("message", already
                ? "Nothing was added: this source was already registered, so this is its existing id and a "
                        + "re-sync of it has started. Its indexed data was kept. To index it from scratch, remove "
                        + "it by this id and register it again."
                : registered + " - indexing has started.");
        out.put(idKey, id.toString());
        return out;
    }

    /**
     * What a remove tool does and tells the model.
     *
     * <p>ConnectorService.removeConnector returns quietly for an id it does not know and removes a
     * source of ANY type, so removeJiraProject handed a mistyped id answered "Jira project removed",
     * and handed a Confluence space's id removed the space. Both are checked here first, against the
     * caller's OWN sources - a shared source is visible to them but not theirs to remove. And the
     * teardown runs on a background thread, so the answer says it started, not that it finished.
     */
    public static Map<String, Object> removal(ConnectorService connectorService, String idText, String idKey,
                                              String label, Set<ConnectorType> types) {
        String userId = SearchContext.getUserId();
        UUID id = UUID.fromString(idText.strip());
        RegisteredConnector source = connectorService.listVisibleTo(userId).stream()
                .filter(c -> c.getId().equals(id) && Objects.equals(c.getUserId(), userId))
                .findFirst().orElse(null);
        if (source == null) {
            return Map.of("success", false, "error", "None of your sources has the id " + id
                    + ". getConnectorStatus lists them with their ids.");
        }
        if (!types.contains(source.getConnectorType())) {
            return Map.of("success", false, "error", "Source " + id + " is not a " + label + " - it is a "
                    + source.getConnectorType().wireName() + " source. Nothing was removed.");
        }
        if (source.isDeleting()) {
            return Map.of("success", true, "message", "Already being removed.", idKey, id.toString());
        }
        connectorService.removeConnector(id, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Removal started: " + label + " '" + source.getName()
                + "' is being unregistered and its search data deleted in the background.");
        out.put(idKey, id.toString());
        return out;
    }
}
