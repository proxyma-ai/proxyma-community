package ai.proxyma.core.settings.connection;

/**
 * Where an OAuth connection's sign-in stands. A token or password connection has no status of its
 * own - whether it works is what Test answers.
 *
 * NEEDS_RECONNECT is recorded when the service refuses the stored refresh token outright (revoked,
 * expired, consent withdrawn, the app changed). Saying so once is better than retrying a dead grant
 * on every sync.
 */
public enum ConnectionStatus {
    CONNECTED,
    NEEDS_RECONNECT
}
