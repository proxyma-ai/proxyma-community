package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorException;

/**
 * A connection cannot authenticate at all - no credential, a sign-in revoked, a refresh the service
 * refused. Its message is the sentence the source's card and the connection's row show, so it says
 * what to do: sign in again, or replace the token.
 */
public class ConnectorAuthException extends ConnectorException {

    public ConnectorAuthException(String message) {
        super(message);
    }

    public ConnectorAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
