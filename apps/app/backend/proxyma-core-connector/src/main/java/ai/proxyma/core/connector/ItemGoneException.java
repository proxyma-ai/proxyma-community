package ai.proxyma.core.connector;

/**
 * The item a fetch asked for no longer exists at the source - an issue, a page, a pull request or a
 * commit answered 404.
 *
 * <p>Its own type so SyncService can remove the document at once. As a plain ConnectorException it
 * was a FAILED item, retried three times five minutes apart - each retry fetching the gone item
 * again - and removed only by the next complete full listing, up to a day later, while it stayed
 * searchable.
 */
public class ItemGoneException extends ConnectorException {

    public ItemGoneException(String message) {
        super(message);
    }
}
