package ai.proxyma.core.connector.spi;

/**
 * How a connection proves who it is. One kind per {@link AuthMethod}; a service may offer several
 * (a token, or a sign-in).
 */
public enum AuthKind {
    /** A token the user pastes - a personal access token, an API key. Sent as a bearer by default. */
    TOKEN,
    /** A user name and a password (or an email and an API token). */
    BASIC,
    /** A key pair - a key id and a private key. */
    KEY_PAIR,
    /** A service account's JSON key, signed into a short-lived token (Google). */
    SERVICE_ACCOUNT,
    /** An OAuth2 authorization-code sign-in with PKCE, refreshed in the background. */
    OAUTH2_CODE,
    /** OAuth2 client credentials - an application, not a person. */
    OAUTH2_CLIENT,
    /** Nothing: the service is public, or reached some other way. */
    NONE
}
