package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.spi.AuthKind;

/**
 * How a connection proves who it is, in the three shapes the page has always drawn - the coarse
 * reading of the protocol's {@link AuthKind} (its descriptor's first sign-in method), kept on the
 * connection view and the configuration transfer.
 *
 * <ul>
 *   <li>{@link #TOKEN} - one secret sent as a Bearer header (a personal access token).
 *   <li>{@link #BASIC} - a username and a password (SVN).
 *   <li>{@link #OAUTH} - a sign-in with the service itself, which leaves a refresh token behind.
 *       The user never types the secret; the connection's sign-in flow stores it.
 * </ul>
 *
 * OAUTH exists from the first version because the services this model is meant to grow into -
 * Google Drive, Gmail, Dropbox - only offer it, and Microsoft 365 already uses it.
 */
public enum ConnectionAuthKind {
    TOKEN,
    BASIC,
    OAUTH;

    /** The coarse kind of a descriptor's sign-in method: a sign-in flow, a user and password, else a token. */
    public static ConnectionAuthKind of(AuthKind kind) {
        if (kind == null) return TOKEN;
        return switch (kind) {
            case OAUTH2_CODE, OAUTH2_CLIENT, SERVICE_ACCOUNT -> OAUTH;
            case BASIC -> BASIC;
            default -> TOKEN;
        };
    }
}
