package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;

import java.util.Map;
import java.util.UUID;

/**
 * A source's connection as its session needs it: the server, and what proves who is asking - with
 * every secret DECRYPTED. Built for one pass or one fetch batch and dropped with the session; it is
 * never stored, never logged ({@link #toString()} names no secret) and never becomes a response.
 *
 * <p>A connector sends the credential to {@link #baseUrl()}'s host and nowhere else - ConnectorHttp
 * pins every request to it - which is what keeps a token from reaching a host a document named.
 */
public final class ResolvedCredentials {

    private static final ResolvedCredentials NONE = new ResolvedCredentials(null, null, AuthKind.NONE, null, null,
            Map.of(), Map.of(), null, Map.of());

    private final UUID connectionId;
    private final ConnectionProtocol protocol;
    private final AuthKind authKind;
    private final String baseUrl;
    private final String username;
    private final Map<String, String> secrets;
    private final Map<String, String> settings;
    private final TokenSupplier tokens;
    private final Map<String, String> grant;

    public ResolvedCredentials(UUID connectionId, ConnectionProtocol protocol, AuthKind authKind, String baseUrl,
                               String username, Map<String, String> secrets, Map<String, String> settings,
                               TokenSupplier tokens) {
        this(connectionId, protocol, authKind, baseUrl, username, secrets, settings, tokens, Map.of());
    }

    /**
     * @param grant what the connection's sign-in recorded about the account, beside its tokens - the
     *              fields its OAuth2Spec names as grantFields (Salesforce's {@code instance_url})
     */
    public ResolvedCredentials(UUID connectionId, ConnectionProtocol protocol, AuthKind authKind, String baseUrl,
                               String username, Map<String, String> secrets, Map<String, String> settings,
                               TokenSupplier tokens, Map<String, String> grant) {
        this.connectionId = connectionId;
        this.protocol = protocol;
        this.authKind = authKind == null ? AuthKind.NONE : authKind;
        this.baseUrl = baseUrl;
        this.username = username;
        this.secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
        this.tokens = tokens;
        this.grant = grant == null ? Map.of() : Map.copyOf(grant);
    }

    /** No connection - a source type that needs none, or one whose connection is gone. */
    public static ResolvedCredentials none() {
        return NONE;
    }

    /** A token connection, for tests and simple callers: the token is the one secret, keyed "token". */
    public static ResolvedCredentials token(String baseUrl, String token) {
        return new ResolvedCredentials(null, null, AuthKind.TOKEN, baseUrl, null,
                token == null ? Map.of() : Map.of("token", token), Map.of(), null);
    }

    public boolean exists() {
        return connectionId != null || !secrets.isEmpty() || tokens != null;
    }

    public UUID connectionId() { return connectionId; }

    public ConnectionProtocol protocol() { return protocol; }

    public AuthKind authKind() { return authKind; }

    public String baseUrl() { return baseUrl; }

    public boolean hasBaseUrl() { return baseUrl != null && !baseUrl.isBlank(); }

    public String username() { return username; }

    /** One secret field by its key, or null. */
    public String secret(String key) { return secrets.get(key); }

    /** The only secret, whatever its key - the token of a token connection, the password of a basic one. */
    public String soleSecret() {
        return secrets.size() == 1 ? secrets.values().iterator().next() : null;
    }

    /** One non-secret setting by its key, or null. */
    public String setting(String key) { return settings.get(key); }

    public Map<String, String> settings() { return settings; }

    /**
     * One field the sign-in's token answer stated and the service's OAuth2Spec keeps
     * ({@code grantFields}), or null - not a secret, and not something a person typed: only a
     * sign-in writes it.
     */
    public String grant(String key) { return grant.get(key); }

    /**
     * The bearer token to send: the sign-in's current access token where the connection signs in
     * (refreshed when it has expired), else the connection's only secret. Throws
     * {@link ConnectorAuthException} when there is none, with the sentence that says what to add.
     */
    public String accessToken() {
        if (tokens != null) return tokens.accessToken();
        String token = secret("token");
        if (token == null) token = soleSecret();
        if (token == null || token.isBlank()) {
            throw new ConnectorAuthException("This connection has no token. Add one in Settings > Connections.");
        }
        return token;
    }

    /** The token just sent was refused: the next {@link #accessToken()} asks for a new one. */
    public void invalidateToken() {
        if (tokens != null) tokens.invalidate();
    }

    public boolean signsIn() {
        return tokens != null;
    }

    @Override
    public String toString() {
        return "ResolvedCredentials[" + protocol + ", connection=" + connectionId + ", baseUrl=" + baseUrl
                + ", secrets=" + secrets.keySet() + "]";
    }
}
