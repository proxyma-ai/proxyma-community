package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.config.ConnectorDefaultsProperties;
import ai.proxyma.core.system.util.UrlUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

// The tool-facing half of ConnectorEndpointResolver: the part that knows the CALLER and the
// CONVERSATION, enforces each connection's Read and Write switches, and turns an unusable
// connection into a message a model can act on.
//
// A tool call resolves, in order, to: the connection this conversation picked with useServer; the
// connection of the source the call NAMED (the project, space or repository key it was given,
// which is the same string registration stores as the source's name); the caller's default
// connection for the service.
//
// Credential rule. A caller only ever uses their own connections. A source somebody else owns
// contributes neither its server nor its credential - lending the owner's credential would let
// anyone the source was shared with act AS its owner on the remote server. Background sync
// (SyncOrchestratorService) is unaffected; it legitimately indexes as the source's owner.
//
// READ AND WRITE ARE CHECKED HERE, ON THE CONNECTION THE CALL WILL ACTUALLY USE. Each is a switch on
// the connection, so the check has to see the connection - and the only place that knows which
// connection a call reaches, source key and conversation pick included, is this resolution. A
// write tool asks with Access.WRITE; everything else asks with READ. McpGuardService keeps the
// license tier and answers the chat's pre-flight question, but this is the gate.
@Service
public class ToolConnectorResolver {

    /** What a call is about to do through the connection it resolves to. */
    public enum Access { READ, WRITE }

    // The key each service's tools pass in is its type's WIRE NAME ("jira", "svn") - still the
    // identifier the tool services speak, and still the focus segment that opens the Add-connection
    // form for that service. A map of four types until the connector framework v2.

    private final ConnectorDefaultsProperties connectorDefaults;
    private final ConnectorEndpointResolver endpointResolver;
    private final ActiveServers activeServers;

    ToolConnectorResolver(ConnectorDefaultsProperties connectorDefaults,
                          ConnectorEndpointResolver endpointResolver,
                          ActiveServers activeServers) {
        this.connectorDefaults = connectorDefaults;
        this.endpointResolver = endpointResolver;
        this.activeServers = activeServers;
    }

    /**
     * The connection a call about {@code sourceKey} uses, checked for {@code access} (null checks
     * nothing). Throws with a message for the model when there is none, or when its switch for this
     * access is off.
     */
    public Endpoint resolve(ConnectorType type, String label, String sourceKey, Access access) {
        Endpoint endpoint = activeServers.active(type)
                .orElseGet(() -> endpointResolver.forSource(type, sourceKey, SearchContext.getUserId()));
        if (!endpoint.exists()) {
            throw new IllegalArgumentException("You have no " + label + " connection. Tell the user to add one in "
                    + link("Settings > Connections", key(type)) + ", reproducing that markdown link exactly as "
                    + "written. They can do this themselves. Do not retry until they confirm it is saved.");
        }
        if (access == Access.READ && !endpoint.allowRead()) throw denied(label, "read", endpoint);
        if (access == Access.WRITE && !endpoint.allowWrite()) throw denied(label, "write", endpoint);
        return endpoint;
    }

    /**
     * A connection's server and the credential to use on it - from ONE resolution - and how it signs
     * in, which for Jira and Confluence says which product line the server is.
     */
    public record Target(String baseUrl, String credential, AuthKind authKind) {

        public Target(String baseUrl, String credential) {
            this(baseUrl, credential, null);
        }

        /**
         * An Atlassian Cloud site - signed in with an email and an API token, the rule the indexing
         * sessions use (AtlassianApi.isCloud) - as opposed to a Data Center or Server instance.
         */
        public boolean atlassianCloud() {
            return authKind == AuthKind.BASIC;
        }
    }

    /**
     * The server and the credential a call uses, resolved ONCE and checked for {@code access}.
     * Resolving them separately, as resolveBaseUrl and resolveAccessToken do, lets a useServer
     * running in the same batch of parallel tool calls land between the two, pairing one
     * connection's token with another connection's host. Every connector tool service resolves
     * through this.
     */
    public Target target(ConnectorType type, String label, String sourceKey, Access access) {
        Endpoint endpoint = resolve(type, label, sourceKey, access);
        String baseUrl = baseUrlOf(endpoint, label);
        if (!endpoint.hasCredential()) {
            throw new IllegalArgumentException(missingTokenMessage(key(type), label, endpoint));
        }
        return new Target(baseUrl, endpoint.credential(), endpoint.authKind());
    }

    public String resolveBaseUrl(ConnectorType type, String label) {
        return resolveBaseUrl(type, label, null);
    }

    // No access check here, deliberately: every remote call also resolves its TOKEN, with the access
    // it is about to use, and that is where the switch is enforced. Checking READ here as well
    // would refuse a write through a connection that allows writing but not reading.
    public String resolveBaseUrl(ConnectorType type, String label, String sourceKey) {
        return baseUrlOf(resolve(type, label, sourceKey, null), label);
    }

    private static String baseUrlOf(Endpoint endpoint, String label) {
        if (!endpoint.hasBaseUrl()) {
            throw new IllegalArgumentException("The " + label + " connection \"" + endpoint.connectionName()
                    + "\" has no server URL. Tell the user to set it in " + connectionLink(endpoint)
                    + ", reproducing that markdown link exactly as written. Do not retry until they confirm.");
        }
        return UrlUtils.normalizeBaseUrl(endpoint.baseUrl());
    }

    public String resolveAccessToken(String settingsPrefix, String label) {
        return resolveAccessToken(settingsPrefix, label, null, Access.READ);
    }

    public String resolveAccessToken(String settingsPrefix, String label, String sourceKey) {
        return resolveAccessToken(settingsPrefix, label, sourceKey, Access.READ);
    }

    /** The token for a call about {@code sourceKey}; a write asks with {@link Access#WRITE}. */
    public String resolveAccessToken(String settingsPrefix, String label, String sourceKey, Access access) {
        Endpoint endpoint = resolve(typeFor(settingsPrefix), label, sourceKey, access);
        if (!endpoint.hasCredential()) throw new IllegalArgumentException(missingTokenMessage(settingsPrefix, label, endpoint));
        return endpoint.credential();
    }

    /**
     * The SVN credential as {@code [username, password]}.
     *
     * <p><b>A missing credential throws rather than falling through to anonymous or single-sign-on
     * access.</b> The svn CLI, given no username and a Negotiate-capable server, authenticates over
     * SSPI as whatever account the JVM runs as - on a server deployment a service account that may
     * see far more than the person chatting. Silently borrowing it would break the rule this class
     * exists for, invisibly, because the call would SUCCEED.
     */
    public String[] resolveSvnCredential(String label) {
        return resolveSvnCredential(label, null);
    }

    public String[] resolveSvnCredential(String label, String sourceKey) {
        Endpoint endpoint = resolve(ConnectorType.SVN, label, sourceKey, Access.READ);
        String[] parsed = splitSvnCredential(endpoint.credential());
        if (parsed == null) {
            throw new IllegalArgumentException("The " + label + " connection \"" + endpoint.connectionName()
                    + "\" has no username and password. Tell the user to add them in " + connectionLink(endpoint)
                    + ", reproducing that markdown link exactly as written as a clickable link. For a VisualSVN "
                    + "server using Windows Authentication the username is domain-qualified; for Subversion "
                    + "Authentication it is the name from the server's own user list. Do not retry this tool "
                    + "until the user confirms they have saved it.");
        }
        return parsed;
    }

    /**
     * Splits a {@code user:password} credential on the FIRST colon - a password may contain one, a
     * username may not. A missing colon or an empty half is rejected rather than half-used, because
     * "username with an empty password" reaches the svn CLI as an argument list it treats as anonymous.
     */
    static String[] splitSvnCredential(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) return null;
        return new String[] { raw.substring(0, colon), raw.substring(colon + 1) };
    }

    private static ConnectorType typeFor(String settingsPrefix) {
        return ConnectorType.forWireName(settingsPrefix).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown connector: " + settingsPrefix));
    }

    private static String key(ConnectorType type) {
        return type.wireName();
    }

    // Reaches the model verbatim as the tool result (see ChatService), so it names the connection
    // and hands over the link that opens it, where the switch is.
    private static IllegalStateException denied(String label, String what, Endpoint endpoint) {
        return new IllegalStateException(label + " " + what + " is turned off for the connection \""
                + endpoint.connectionName() + "\". Tell the user they can allow it in " + connectionLink(endpoint)
                + ", reproducing that markdown link exactly as written, and do not retry until they confirm.");
    }

    private static String connectionLink(Endpoint endpoint) {
        return link("Settings > Connections > " + endpoint.connectionName(), endpoint.connectionId().toString());
    }

    private static String link(String text, String focus) {
        return "[" + text + "](" + SettingsLinks.connectors(focus) + ")";
    }

    // A user cannot act on "add your token" without two things: the connection to put it in, and
    // the page on their server that mints one. Both are handed over as real links - the token page
    // derived from the connection's own server.
    private String missingTokenMessage(String settingsPrefix, String label, Endpoint endpoint) {
        StringBuilder msg = new StringBuilder("The " + label + " connection \"" + endpoint.connectionName()
                + "\" has no access token.");
        String tokenPage = tokenPageUrl(settingsPrefix, endpoint);
        if (tokenPage != null) {
            msg.append(" Tell the user to create one at [Create your ").append(label)
                    .append(" access token](").append(tokenPage).append("), then paste it into ")
                    .append(connectionLink(endpoint)).append('.');
        } else {
            msg.append(" Tell the user to add their own ").append(label).append(" personal access token in ")
                    .append(connectionLink(endpoint)).append('.');
        }
        msg.append(" Reproduce every markdown link above exactly as written, as clickable links - never flatten "
                + "one into plain text. Do not retry this tool until the user confirms they have saved a token.");
        return msg.toString();
    }

    // Well-known Atlassian Server/Data Center token pages, on the connection's own server. A
    // deployment can override the derivation per connector (ConnectorDefaultsProperties'
    // token-page-url) for a Jira fronted by a token plugin that serves the page elsewhere.
    private String tokenPageUrl(String settingsPrefix, Endpoint endpoint) {
        String configured = connectorDefaults.tokenPageUrlFor(settingsPrefix);
        if (configured != null) return configured;
        String path = switch (settingsPrefix) {
            case "jira" ->
                    "/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens";
            case "confluence" -> "/plugins/personalaccesstokens/usertokens.action";
            // Bitbucket's page is per user (/plugins/servlet/access-tokens/users/<slug>/manage) and
            // the slug is not known here - a derived link would 404 at exactly the wrong moment.
            default -> null;
        };
        if (path == null || !endpoint.hasBaseUrl()) return null;
        return UrlUtils.normalizeBaseUrl(endpoint.baseUrl()) + path;
    }
}
