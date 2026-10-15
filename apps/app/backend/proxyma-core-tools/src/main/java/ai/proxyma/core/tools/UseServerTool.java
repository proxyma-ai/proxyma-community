package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Lists the caller's connections for one service, or points this conversation at one. See ActiveServers. */
@Component
public class UseServerTool {

    private final ActiveServers activeServers;
    // Which products have servers to choose from - every type whose service has a server address.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    public UseServerTool(ActiveServers activeServers) {
        this.activeServers = activeServers;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    @Tool(name = "useServer", description = "List the user's connections for a product with servers of its own - "
            + "jira, confluence, bitbucket, svn and any other listSourceTypes shows with a connection - or switch "
            + "this conversation's tools for that product to one of them. Use it when an item lives on a server other "
            + "than the default - e.g. a link to another host. Omit server to list.")
    @ToolResult({
            @ToolResultField(name = "servers", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "name, baseUrl, active, isDefault, credentialSet, allowRead, allowWrite for each connection."),
    })
    public Map<String, Object> useServer(
            @ToolParam(description = "The product, e.g. jira, confluence, bitbucket, svn") String product,
            @ToolParam(required = false, description = "A connection's name or server URL, as listed") String server) {
        // Any type whose service has a server a person types - not one per line here, so a new
        // connector's servers can be chosen the day its plugin exists.
        ConnectorType type = product == null ? null : catalog.typeForWireName(product.strip().toLowerCase(Locale.ROOT));
        ConnectionProtocol protocol = type == null ? null : catalog.protocolFor(type);
        ProtocolDescriptor service = protocol == null ? null : catalog.protocol(protocol);
        if (service == null || !service.hasBaseUrl()) {
            return Map.of("success", false, "error", "'" + product + "' has no servers to choose from. "
                    + "It is one of: jira, confluence, bitbucket, svn, or another product listSourceTypes shows "
                    + "with a connection.");
        }
        List<ResolvedConnection> connections = activeServers.connections(type);
        if (server != null && !server.isBlank()) {
            String wanted = server.strip().replaceAll("/+$", "");
            ResolvedConnection pick = connections.stream()
                    .filter(c -> c.name().equalsIgnoreCase(wanted)
                            || (c.baseUrl() != null && c.baseUrl().replaceAll("/+$", "").equalsIgnoreCase(wanted)))
                    .findFirst().orElse(null);
            if (pick == null) {
                return Map.of("success", false, "error", "No " + product + " connection named " + server
                        + ". The user can add one in Settings > Connections.", "servers", describe(type, connections));
            }
            try {
                activeServers.select(type, pick.id());
            } catch (IllegalStateException e) {
                return Map.of("success", false, "error", e.getMessage());
            }
        }
        return Map.of("success", true, "servers", describe(type, connections));
    }

    private List<Map<String, Object>> describe(ConnectorType type, List<ResolvedConnection> connections) {
        UUID active = activeServers.activeId(type);
        return connections.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("baseUrl", c.baseUrl());
            m.put("active", c.id().equals(active));
            m.put("isDefault", c.isDefault());
            m.put("credentialSet", c.credential() != null);
            m.put("allowRead", c.allowRead());
            m.put("allowWrite", c.allowWrite());
            return m;
        }).toList();
    }
}
