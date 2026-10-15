package ai.proxyma.core.connector.spi;

/**
 * Hands out a current access token for one connection, refreshing it when it has expired - an
 * OAuth2 sign-in, a client-credentials grant, a service account's signed assertion, or Microsoft's
 * own flow. A connector asks for the token on every request (through
 * {@link ResolvedCredentials#accessToken()}) and never stores it.
 *
 * <p>Implementations are thread-safe: one sync's requests and a drain's fetches may ask at once,
 * and a refresh that rotates the refresh token must happen once, not once per caller.
 */
public interface TokenSupplier {

    /**
     * A token valid now. Throws {@link ConnectorAuthException} when there is none to be had - the
     * sign-in was revoked, the refresh refused - with the sentence the source's card shows.
     */
    String accessToken();

    /**
     * The token just sent was refused (a 401): forget it, so the next {@link #accessToken()} asks
     * for a new one. One refusal is one refresh - never a loop.
     */
    default void invalidate() {}
}
