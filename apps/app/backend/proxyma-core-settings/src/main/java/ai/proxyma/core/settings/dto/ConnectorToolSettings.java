package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Whether the chat agent and an attached MCP client may register and remove sources, on behalf of
// the user these settings belong to. The per-service Read and Write switches that used to live here
// are on each connection now (ConnectorConnection). Always user-scoped: there is no meaningful "global" value for
// one particular person's own permission, so this rides the same per-user settings overlay as
// the other VIEWER_PREFIXES entries (see SettingsController).
//
// This is now the ONLY gate. It used to be ANDed with a deployment-wide McpSettings toggle that
// only a super admin could flip, so enabling a write tool took two people and two pages; that
// class is gone and every tool is permitted at the deployment level. What a user can actually do
// is still bounded by their own personal access token - the remote server rejects whatever that
// token cannot see - which is what makes a per-user gate sufficient on its own.
//
// The stored prefix was "write-tool-consent" until this grew read permissions and stopped being
// about consent to write. ConnectorToolPrefixMigrator moves the old keys across on startup, in
// both stores - the global properties file AND every per-user override blob - so renaming it did
// not orphan a single already-saved permission.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectorToolSettings {

    public static final String PREFIX = "connector-tools";

    // Registering and removing connectors of any type - files and web pages included, which is why
    // this is not per-connector like the rest.
    @JsonProperty("connector-management")
    private boolean connectorManagement = false;

    public boolean isConnectorManagement() { return connectorManagement; }
    public void setConnectorManagement(boolean v) { this.connectorManagement = v; }
}
