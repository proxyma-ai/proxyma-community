package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import ai.proxyma.core.connector.spi.ProtocolDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connection as the API shows it. Has no field that could hold the secret - only a masked form
 * and whether one is stored - so the plaintext cannot reach a client by being added to a response.
 */
public record ConnectorConnectionView(
        UUID id,
        String name,
        ConnectionProtocol protocol,
        String protocolLabel,
        ConnectionAuthKind authKind,
        String baseUrl,
        String username,
        String secretMasked,
        boolean secretConfigured,
        boolean isDefault,
        boolean allowRead,
        boolean allowWrite,
        boolean supportsWrite,
        ConnectionStatus status,
        String statusDetail,
        String accountLabel,
        Instant connectedAt,
        McpView mcp,
        /** The sign-in method's AuthKind name - which of the service's ways it signs in. */
        String authMethod,
        /** Its non-secret settings - a workspace id, a region, an OAuth client id. */
        java.util.Map<String, String> settings,
        /** Which of the method's secret fields hold a value - never the values. */
        List<String> secretsConfigured,
        /**
         * The source types (wire names) this connection can serve, for its sign-in - its service's,
         * and another service's where its sign-in reaches that too (an Atlassian Cloud site serves
         * Jira and Confluence). What a source's Connection field offers.
         */
        List<String> sourceTypes) {

    /** Every field but {@code sourceTypes}, which the service fills in from the catalog. */
    public ConnectorConnectionView(UUID id, String name, ConnectionProtocol protocol, String protocolLabel,
                                   ConnectionAuthKind authKind, String baseUrl, String username,
                                   String secretMasked, boolean secretConfigured, boolean isDefault,
                                   boolean allowRead, boolean allowWrite, boolean supportsWrite,
                                   ConnectionStatus status, String statusDetail, String accountLabel,
                                   Instant connectedAt, McpView mcp, String authMethod,
                                   java.util.Map<String, String> settings, List<String> secretsConfigured) {
        this(id, name, protocol, protocolLabel, authKind, baseUrl, username, secretMasked, secretConfigured,
                isDefault, allowRead, allowWrite, supportsWrite, status, statusDetail, accountLabel, connectedAt, mcp,
                authMethod, settings, secretsConfigured, List.of());
    }

    /** The shape before sign-in methods: the service's first method, no settings. */
    public ConnectorConnectionView(UUID id, String name, ConnectionProtocol protocol, String protocolLabel,
                                   ConnectionAuthKind authKind, String baseUrl, String username,
                                   String secretMasked, boolean secretConfigured, boolean isDefault,
                                   boolean allowRead, boolean allowWrite, boolean supportsWrite,
                                   ConnectionStatus status, String statusDetail, String accountLabel,
                                   Instant connectedAt, McpView mcp) {
        this(id, name, protocol, protocolLabel, authKind, baseUrl, username, secretMasked, secretConfigured,
                isDefault, allowRead, allowWrite, supportsWrite, status, statusDetail, accountLabel, connectedAt, mcp,
                null, java.util.Map.of(), List.of());
    }

    /** Every field but {@code mcp}: the shape of a connection to anything that is not an MCP server. */
    public ConnectorConnectionView(UUID id, String name, ConnectionProtocol protocol, String protocolLabel,
                                   ConnectionAuthKind authKind, String baseUrl, String username,
                                   String secretMasked, boolean secretConfigured, boolean isDefault,
                                   boolean allowRead, boolean allowWrite, boolean supportsWrite,
                                   ConnectionStatus status, String statusDetail, String accountLabel,
                                   Instant connectedAt) {
        this(id, name, protocol, protocolLabel, authKind, baseUrl, username, secretMasked, secretConfigured,
                isDefault, allowRead, allowWrite, supportsWrite, status, statusDetail, accountLabel, connectedAt, null);
    }

    /**
     * An MCP server connection's settings as its owner may read them back: names, never values.
     *
     * @param signedIn whether an OAuth connection holds a grant
     */
    public record McpView(McpServerConfig.Transport transport, String command, List<String> args,
                          McpServerConfig.Auth auth, List<String> headerNames, List<String> envNames,
                          boolean shared, boolean signedIn, String oauthClientId, String oauthScope,
                          boolean oauthClientRegistered) {}

    /** First four and last three characters of a secret long enough to spare them, else a bare mask. */
    public static String mask(String secret) {
        return ai.proxyma.core.settings.service.SecretMask.mask(secret);
    }

    /** The protocol catalogue the Add-connection form renders. */
    public record ProtocolView(String value, String label, ConnectionAuthKind authKind,
                               boolean hasBaseUrl, boolean supportsWrite, List<String> sourceTypes) {}

    public static List<ProtocolView> protocols(List<ProtocolDescriptor> offered) {
        return offered.stream()
                .map(p -> new ProtocolView(p.protocol().name(), p.displayName(),
                        ConnectionAuthKind.of(p.primaryAuth().kind()), p.hasBaseUrl(),
                        p.supportsWrite(), p.sourceTypes().stream().map(Enum::name).sorted().toList()))
                .toList();
    }
}
