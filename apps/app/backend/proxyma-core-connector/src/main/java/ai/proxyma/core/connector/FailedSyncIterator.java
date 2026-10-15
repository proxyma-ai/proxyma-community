package ai.proxyma.core.connector;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A sync that could not start: no items, and an error SyncService will record.
 *
 * <p>What a connector returns instead of {@code Collections.emptyIterator()} when it cannot list at
 * all - a missing URL or token, an svn command that exited non-zero, a 401 on the first page. An
 * empty iterator is indistinguishable from a source that genuinely holds nothing, so SyncService
 * marked the connector ACTIVE with 0 items and no error, and a user with a wrong URL or token was
 * told everything was fine. Found 2026-09-14 for SVN and Bitbucket.
 *
 * <p>The message reaches the user through RegisteredConnector.lastSyncError, so pass it through
 * {@link SyncErrorAware#summarize} when it comes from an exception.
 */
public final class FailedSyncIterator implements Iterator<ItemMetadata>, SyncErrorAware {

    private final String message;

    public FailedSyncIterator(String message) {
        this.message = message;
    }

    @Override public boolean hasNext() { return false; }
    @Override public ItemMetadata next() { throw new NoSuchElementException(); }
    @Override public boolean hasErrored() { return true; }
    @Override public String getErrorMessage() { return message; }
}
