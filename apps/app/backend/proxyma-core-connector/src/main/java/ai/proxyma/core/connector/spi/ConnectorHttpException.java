package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorException;

/**
 * A request a connector made did not succeed. Its message is ALREADY the sentence a person reads -
 * on the source's card, the item's row, the connection's Test result - never a status line or a
 * response body, which for an expired single sign-on is a whole HTML page. ConnectorHttp writes it,
 * once, for every connector (see {@link ApiClient}), so the page translates a handful of sentences
 * instead of one per connector.
 *
 * @see #status() the HTTP status, 0 when the server was not reached at all
 */
public class ConnectorHttpException extends ConnectorException {

    private final int status;
    private final String path;

    public ConnectorHttpException(String message, int status, String path) {
        super(message);
        this.status = status;
        this.path = path;
    }

    public ConnectorHttpException(String message, int status, String path, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.path = path;
    }

    /** The HTTP status; 0 when no answer came back. */
    public int status() {
        return status;
    }

    /** The path asked for, without the server - for a connector's own wording of a 404. */
    public String path() {
        return path;
    }

    public boolean isNotFound() {
        return status == 404 || status == 410;
    }

    public boolean isAuthFailure() {
        return status == 401 || status == 403;
    }
}
