package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SyncOrchestratorService;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetStatusTool {

    private final SyncOrchestratorService syncOrchestratorService;
    private final ConnectorService connectorService;
    private final ConnectorEndpointResolver endpointResolver;

    // What each type reads through and where its config says it reads from. Setter-injected, so the
    // constructor tests build this with is unchanged.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    public GetStatusTool(SyncOrchestratorService syncOrchestratorService,
                         ConnectorService connectorService,
                         ConnectorEndpointResolver endpointResolver) {
        this.syncOrchestratorService = syncOrchestratorService;
        this.connectorService = connectorService;
        this.endpointResolver = endpointResolver;
    }

    // The id, the target and the connection are what the remove and register tools need. Without
    // them the agent could not remove a source it had just listed, and had to ask the user for a
    // website's URL it had indexed a thousand pages of.
    @Tool(description = "List indexed sources: id (what the remove tools take), name, type, where it reads from, "
            + "its connection, document counts, status, the last sync error and whether it is syncing now. "
            + "Shows what 'searchIndexedSources' covers, or why something is missing.")
    @ToolResult(
            value = {
                @ToolResultField(name = "paused", type = ToolFieldType.BOOLEAN, always = true,
                        description = "True if the sync orchestrator is paused, so counts are not currently "
                                + "advancing."),
                @ToolResultField(name = "totalConnectors", type = ToolFieldType.INTEGER, always = true,
                        description = "Number of registered connectors."),
                @ToolResultField(name = "totalDocuments", type = ToolFieldType.INTEGER, always = true,
                        description = "Total items discovered across all connectors."),
                @ToolResultField(name = "totalProcessed", type = ToolFieldType.INTEGER, always = true,
                        description = "Total items indexed successfully."),
                @ToolResultField(name = "totalFailed", type = ToolFieldType.INTEGER, always = true,
                        description = "Total items that failed to index."),
                @ToolResultField(name = "connectorsByType", type = ToolFieldType.OBJECT, always = true,
                        description = "Connector counts keyed by connector type."),
                @ToolResultField(name = "connectors", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "One entry per source: id, name, type, target (the site, repository or "
                                + "server it reads - absent for folders and for a source somebody else owns), "
                                + "connection (its name, or null when it has none and so cannot sync), items, "
                                + "processed, failed, status, error (why the last sync failed, when it did), "
                                + "syncing.")
            },
            envelope = false,
            description = "Indexing state of every registered connector. Reports no success flag: it reads "
                    + "local state and has no upstream call to fail.")
    public Map<String, Object> getConnectorStatus() {
        List<RegisteredConnector> connectors = connectorService.listVisibleTo(SearchContext.getUserId());

        int totalConnectors = 0;
        int totalItems = 0;
        int totalProcessed = 0;
        int totalFailed = 0;
        Map<String, Integer> countByType = new LinkedHashMap<>();
        List<Map<String, Object>> connectorList = new ArrayList<>();

        for (RegisteredConnector r : connectors) {
            if (r.isDeleting()) continue;

            totalConnectors++;
            totalItems += r.getItemCount();
            totalProcessed += r.getProcessedCount();
            totalFailed += r.getFailedCount();

            String typeName = r.getConnectorType().wireName();
            countByType.merge(typeName, 1, Integer::sum);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r.getId().toString());
            entry.put("name", sanitizeName(r));
            entry.put("type", typeName);
            boolean own = java.util.Objects.equals(r.getUserId(), SearchContext.getUserId());
            String target = own ? target(r) : null;
            if (target != null) entry.put("target", target);
            if (own && catalog.protocolFor(r.getConnectorType()) != null) {
                ConnectorEndpointResolver.Endpoint endpoint = endpointResolver.resolve(r);
                entry.put("connection", endpoint.exists() ? endpoint.connectionName() : null);
            }
            entry.put("items", r.getItemCount());
            entry.put("processed", r.getProcessedCount());
            entry.put("failed", r.getFailedCount());
            entry.put("status", r.getStatus().name().toLowerCase());
            if (r.getLastSyncError() != null && !r.getLastSyncError().isBlank()) entry.put("error", r.getLastSyncError());
            entry.put("syncing", syncOrchestratorService.isSyncing(r.getId()));
            connectorList.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paused", syncOrchestratorService.isPaused());
        result.put("totalConnectors", totalConnectors);
        result.put("totalDocuments", totalItems);
        result.put("totalProcessed", totalProcessed);
        result.put("totalFailed", totalFailed);
        result.put("connectorsByType", countByType);
        result.put("connectors", connectorList);
        return result;
    }

    /**
     * What the source reads, in the form its register tool takes back: a website's seed URL, an SVN
     * repository's URL, a SharePoint site's URL, a share's UNC path, and the server for a Jira,
     * Confluence or Bitbucket key (whose NAME is already the key). Never a local folder's path, which
     * would put an absolute path of this machine into the conversation.
     */
    private String target(RegisteredConnector r) {
        ConnectorDescriptor descriptor = catalog.descriptor(r.getConnectorType());
        if (descriptor == null || r.getConnectorType().isFileBacked()) return null;
        // A type that names a field says what it reads (a repository URL, a share path, a seed URL).
        String fromConfig = descriptor.target(r.getConfig());
        if (fromConfig != null) return fromConfig;
        // Otherwise the server it is read from - a Jira key's server, a GitHub repository's host -
        // whose NAME is already the key.
        if (descriptor.protocol() != null) {
            ConnectorEndpointResolver.Endpoint endpoint = endpointResolver.resolve(r);
            return endpoint.hasBaseUrl() ? endpoint.baseUrl() : null;
        }
        return null;
    }

    private static String sanitizeName(RegisteredConnector r) {
        String name = r.getName();
        if (name == null) return "";
        try {
            Path p = Path.of(name);
            if (p.isAbsolute()) {
                return p.getFileName() != null ? p.getFileName().toString() : name;
            }
        } catch (Exception ignored) {}
        return name;
    }
}
