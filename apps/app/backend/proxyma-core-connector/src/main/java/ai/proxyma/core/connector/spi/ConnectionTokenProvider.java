package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;

import java.util.UUID;

/**
 * Where the access token of a connection that SIGNS IN comes from - the generic OAuth2 flow
 * (authorization code, client credentials, a service account) or a service's own (Microsoft 365).
 * SourceContextFactory asks the provider that supports a connection's protocol and hands the
 * supplier to the session inside its {@link ResolvedCredentials}; a connector never sees a refresh
 * token.
 */
public interface ConnectionTokenProvider {

    /** Whether this provider hands out tokens for connections of {@code protocol} signed in with {@code kind}. */
    boolean supports(ConnectionProtocol protocol, AuthKind kind);

    /** The token supplier for one connection. Cheap - no request until a token is asked for. */
    TokenSupplier tokensFor(UUID connectionId);
}
