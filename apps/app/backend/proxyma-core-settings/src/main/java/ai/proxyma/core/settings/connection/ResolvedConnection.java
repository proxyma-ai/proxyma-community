package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;

import java.util.Map;
import java.util.UUID;

/**
 * A connection with its secret DECRYPTED, for the code that actually reaches the service - sync,
 * the agent's tools, Test. Server-side only; it never becomes a response body.
 *
 * @param secret   the stored secret as it decrypts: the one secret of a method with one, else the
 *                 JSON object of all of them
 * @param authKind how the connection signs in (its descriptor's method)
 * @param secrets  every secret field by its key - for a method with one secret field, that field
 *                 keyed to {@code secret}
 * @param settings the connection's non-secret settings (a workspace id, a region), by key
 */
public record ResolvedConnection(
        UUID id,
        String ownerId,
        String name,
        ConnectionProtocol protocol,
        String baseUrl,
        String username,
        String secret,
        boolean isDefault,
        boolean allowRead,
        boolean allowWrite,
        ConnectionStatus status,
        String statusDetail,
        /** OAuth only - see ConnectorConnection.accountJson. */
        String accountJson,
        AuthKind authKind,
        Map<String, String> secrets,
        Map<String, String> settings) {

    public ResolvedConnection {
        if (authKind == null) authKind = defaultAuthKind(protocol);
        secrets = secrets == null ? singleSecret(protocol, authKind, secret) : Map.copyOf(secrets);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /**
     * The shape every connection had before multi-field secrets: one secret, no settings, signed in
     * the protocol's first way.
     */
    public ResolvedConnection(UUID id, String ownerId, String name, ConnectionProtocol protocol, String baseUrl,
                              String username, String secret, boolean isDefault, boolean allowRead, boolean allowWrite,
                              ConnectionStatus status, String statusDetail, String accountJson) {
        this(id, ownerId, name, protocol, baseUrl, username, secret, isDefault, allowRead, allowWrite, status,
                statusDetail, accountJson, null, null, null);
    }

    public boolean hasBaseUrl() { return baseUrl != null && !baseUrl.isBlank(); }

    public boolean hasSecret() { return secret != null && !secret.isBlank(); }

    /**
     * The credential in the one-string shape the older connectors take: the token for TOKEN auth,
     * and {@code user:password} for BASIC - which is how SVN's credential has always been passed,
     * split again on the FIRST colon by whatever consumes it. Null when half of a pair is missing, so
     * a username with no password is never sent as though it were a credential.
     */
    public String credential() {
        if (authKind == AuthKind.BASIC) {
            String password = secrets.getOrDefault("password", secret);
            if (username == null || username.isBlank() || password == null || password.isBlank()) return null;
            return username + ":" + password;
        }
        return hasSecret() ? secret : null;
    }

    private static AuthKind defaultAuthKind(ConnectionProtocol protocol) {
        ProtocolDescriptor d = CoreDescriptors.of(protocol);
        return d == null ? AuthKind.TOKEN : d.primaryAuth().kind();
    }

    // One stored secret, keyed to the single secret field of the protocol's method ("token",
    // "password") so a connector can ask for it by name.
    private static Map<String, String> singleSecret(ConnectionProtocol protocol, AuthKind kind, String secret) {
        if (secret == null || secret.isBlank()) return Map.of();
        ProtocolDescriptor d = CoreDescriptors.of(protocol);
        AuthMethod method = d == null ? null : d.auth(kind);
        String key = method == null || method.secretFields().isEmpty() ? "token"
                : method.secretFields().stream().map(FieldSpec::key).findFirst().orElse("token");
        return Map.of(key, secret);
    }

    @Override
    public String toString() {
        // Never the secret - a record's generated toString would print it into any log line that
        // touches one of these.
        return "ResolvedConnection[" + name + " (" + protocol + "), id=" + id + "]";
    }
}
