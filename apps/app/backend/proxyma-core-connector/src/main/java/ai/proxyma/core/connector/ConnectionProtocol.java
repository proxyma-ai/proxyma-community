package ai.proxyma.core.connector;

import java.util.List;
import java.util.Locale;

/**
 * What a connection talks to. One value per SERVICE a source can be read from, not per source
 * type: a Microsoft 365 sign-in serves OneDrive, SharePoint, Teams, Teams chats and Outlook alike, so
 * all five source types point at the same connection.
 *
 * <p>IDENTITY ONLY, since the connector framework v2 (2026-10-14). It lived in proxyma-core-settings
 * and carried the service's display name, its kind of sign-in, its Write switch and the source types
 * it serves; all of that is now the service's {@code ProtocolDescriptor}, declared by its
 * {@code ConnectionPlugin} and read through {@code ConnectorCatalog}. It moved here because a plugin
 * has to name its service, and plugins depend on this module, not on settings.
 *
 * <p>Adding a service is ONE line here and a {@code ConnectionPlugin} in its connector's package.
 * The {@code connector_connections} table does not change: the value is stored by name.
 */
public enum ConnectionProtocol {

    JIRA,
    CONFLUENCE,
    BITBUCKET,
    SVN,
    // A Windows file server, since 2026-10-01: its "base URL" is the file server's HOST
    // (FileServerHost), and that host is the only server its sources may reach.
    NETWORK_SHARE,
    MICROSOFT_365,
    // An external MCP server whose TOOLS the agent may call (2026-10-13). It backs no source - it is
    // reached live, never indexed.
    MCP_SERVER,

    // -- Built on the connector framework v2 (ai.proxyma.connectors.*): one line each. --------

    GITHUB,
    SLACK,
    GITLAB,
    LINEAR,
    // Google Drive, Gmail and Google Calendar: one Google sign-in or service account serves all three.
    GOOGLE,
    BITBUCKET_CLOUD,
    NOTION,
    AIRTABLE,
    ZENDESK,
    FRESHDESK,
    HUBSPOT,
    SALESFORCE,
    ASANA,
    MONDAY,
    TRELLO,
    CLICKUP,
    DROPBOX,
    BOX,
    ZOOM,
    // Any mail host, over IMAP: its "server" is a host, a port and a TLS mode - no URL.
    IMAP;

    /** Case-insensitive; null for anything unknown rather than throwing, as ProviderProtocol does. */
    public static ConnectionProtocol fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<ConnectionProtocol> all() { return List.of(values()); }
}
