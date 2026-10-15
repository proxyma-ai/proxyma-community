package ai.proxyma.core.connector;

// Optional capability for the Iterator<ItemMetadata> a session lists (every spi.Listing
// carries it): reports whether pagination stopped because of a genuine end-of-data condition or
// because an unrecoverable fetch error occurred partway through. Iterators that swallow
// exceptions internally (e.g. to keep Iterator.hasNext() from throwing) and simply stop -
// as JiraConnector/ConfluenceConnector do on a transient API error - must implement this so
// SyncService can tell "the remote list is exhausted" apart from "we gave up after an error".
// This distinction matters because SyncService only purges (marks tracked documents DELETED
// and removes their vectors) when a sync is known to have seen every remote item; treating an
// error-truncated fetch as a complete one causes items simply not reached yet to be wrongly
// purged.
public interface SyncErrorAware {
    boolean hasErrored();

    // Optional short, user-facing summary of why hasErrored() is true - null if never captured.
    // Surfaced directly to end users via RegisteredConnector.lastSyncError, so implementations
    // must use summarize() below rather than a raw exception message.
    default String getErrorMessage() { return null; }

    // Turns a caught fetch exception into a short, safe summary. Never returns a raw exception
    // message as-is: for an HTTP error (e.g. an expired SSO session), the response body can be
    // an entire embedded HTML redirect page rather than a useful message - see
    // ../proxyma-docs/LESSONS_LEARNED.md "Connector status never reflected persistent sync failures".
    static String summarize(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            return httpEx.getStatusCode().value() + " " + httpEx.getStatusText();
        }
        // A FileSystemException's message is only the path for the commonest cases (access
        // denied, file gone) - the reason is the exception's type. Failures words it the same
        // way a chat error does.
        String msg = e instanceof java.nio.file.FileSystemException fse
                ? ai.proxyma.core.system.util.Failures.describeFileSystem(fse)
                : e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    // The HTTP status error anywhere in a cause chain, or null. Connectors wrap a fetch's status
    // exception in their own RuntimeException, and the sentence a user reads depends on the
    // status - matched on the TYPE here, never on the wrapper's wording. Bounded like
    // Failures.describe, so a hand-built cycle cannot hang an error handler.
    static org.springframework.web.client.HttpStatusCodeException httpStatusIn(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof org.springframework.web.client.HttpStatusCodeException httpEx) return httpEx;
            if (t.getCause() == t) break;
        }
        return null;
    }
}
