package ai.proxyma.core.settings.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.connection.ConnectionAccessPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Guards the connector-backed chat/MCP tools.
//
// THE LICENSE IS THE ONE RULE ABOVE THE USER, since 0.10.0. A connector it does not include is
// refused here before anything else, and it is not a consent question, so isBlockedOnlyByUserConsent
// answers no for it and chat does not offer to switch it on. Since the two-tier model every
// connector these tools reach - Jira, Confluence, Bitbucket, SVN - is in every tier, so the check
// stands for the day one is not.
//
// READ AND WRITE ARE SWITCHES ON EACH CONNECTION, since the connections redesign - the agent may
// write to a test Jira and only read the production one. The gate for them is ToolConnectorResolver,
// which sees the connection a call actually resolves to, source key and useServer pick included.
// What is left here for them is the chat's PRE-FLIGHT: before a write-capable call runs, "would the
// connection this conversation writes through refuse it, and which one should the user be offered
// to switch on?" That is answered for the conversation's connection (its useServer pick, else the
// default), through ConnectionAccessPort - a courtesy that lets the user fix it in place, never the
// thing that decides.
//
// Connector management - registering and removing sources of any type - is still one per-user
// switch (connector-tools.connector-management), because it spans every type and no connection.
@Service
public class McpGuardService {

    private final SettingsService settingsService;
    private final FeatureGateService featureGateService;
    private final ObjectProvider<ConnectionAccessPort> access;

    public McpGuardService(SettingsService settingsService, FeatureGateService featureGateService,
                           ObjectProvider<ConnectionAccessPort> access) {
        this.settingsService = settingsService;
        this.featureGateService = featureGateService;
        this.access = access;
    }

    // What every connector question is asked of - its descriptor's edition decides the license.
    // Setter-injected, so the constructor tests build this with is unchanged.
    private ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    private void checkTier(ConnectorType type, String label) {
        if (!tierAllows(type)) {
            throw new IllegalStateException(label + " tools are not included in this installation's license. "
                    + "Tell the user they need an Enterprise license (Proxyma Enterprise Server), and do not retry.");
        }
    }

    private boolean tierAllows(ConnectorType type) {
        return featureGateService.getFeatures().isConnectorAllowed(catalog.edition(type));
    }

    public void checkConnectorManagement(String userId) {
        if (!settingsService.isUserConnectorManagementAllowed(userId)) {
            throw new IllegalStateException("Connector management tools are turned off in your own settings. "
                    + "Tell the user to enable them in [Settings > Connections](app://settings/connectors/connector-management), "
                    + "reproducing that markdown link exactly as written, and do not retry until they confirm.");
        }
    }

    public void checkJiraRead(String userId) { checkTier(ConnectorType.JIRA, "Jira"); }

    public void checkJiraWrite(String userId) { checkWrite(ConnectorType.JIRA, "Jira", userId); }

    public void checkConfluenceRead(String userId) { checkTier(ConnectorType.CONFLUENCE, "Confluence"); }

    public void checkConfluenceWrite(String userId) { checkWrite(ConnectorType.CONFLUENCE, "Confluence", userId); }

    // SVN has read tools and no write tools, so there is no write check to have.
    public void checkSvnRead(String userId) { checkTier(ConnectorType.SVN, "SVN"); }

    public void checkBitbucketRead(String userId) { checkTier(ConnectorType.BITBUCKET, "Bitbucket"); }

    public void checkBitbucketWrite(String userId) { checkWrite(ConnectorType.BITBUCKET, "Bitbucket", userId); }

    private void checkWrite(ConnectorType type, String label, String userId) {
        checkTier(type, label);
        target(type, userId).filter(t -> !t.allowWrite()).ifPresent(t -> {
            throw new IllegalStateException(label + " write is turned off for the connection \"" + t.name()
                    + "\". Tell the user they can allow it in [Settings > Connections > " + t.name()
                    + "](app://settings/connectors/" + t.id() + "), reproducing that markdown link exactly as written, "
                    + "and do not retry until they confirm.");
        });
    }

    /** The connection a write of this tool would go through in the current conversation, for the enable prompt. */
    public Optional<ConnectionAccessPort.Target> writeTarget(String toolName, String userId) {
        WriteToolRegistry.Connector connector = WriteToolRegistry.connectorFor(toolName);
        if (connector == null || connector.type() == null) return Optional.empty();
        return target(connector.type(), userId);
    }

    private Optional<ConnectionAccessPort.Target> target(ConnectorType type, String userId) {
        ConnectionAccessPort port = access.getIfAvailable();
        return port == null ? Optional.empty() : port.conversationTarget(type, userId);
    }

    // True when `toolName` is blocked only by a switch the calling user can flip themselves - their
    // connector-management setting, or the Write switch on the connection the call would use.
    // ChatService.awaitApprovalIfNeeded then offers to turn it on in the conversation.
    public boolean isBlockedOnlyByUserConsent(String toolName, String userId) {
        if (WriteToolRegistry.CONNECTOR_MANAGEMENT_NAMES.contains(toolName)) {
            return !settingsService.isUserConnectorManagementAllowed(userId);
        }
        WriteToolRegistry.Connector connector = WriteToolRegistry.connectorFor(toolName);
        if (connector == null || connector.type() == null) return false;
        return tierAllows(connector.type())
                && target(connector.type(), userId).map(t -> !t.allowWrite()).orElse(false);
    }

    // Non-throwing variant for pre-flight checks (see ChatService.awaitApprovalIfNeeded): the
    // rejection message, or null if the write is allowed. A toolName outside every WriteToolRegistry
    // set is not this service's concern and is treated as allowed here.
    public String checkWriteAllowed(String toolName, String userId) {
        try {
            if (WriteToolRegistry.CONNECTOR_MANAGEMENT_NAMES.contains(toolName)) checkConnectorManagement(userId);
            else if (WriteToolRegistry.JIRA_NAMES.contains(toolName)) checkJiraWrite(userId);
            else if (WriteToolRegistry.CONFLUENCE_NAMES.contains(toolName)) checkConfluenceWrite(userId);
            else if (WriteToolRegistry.BITBUCKET_NAMES.contains(toolName)) checkBitbucketWrite(userId);
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }
}
