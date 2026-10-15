package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.connector.SyncErrorAware;

import java.util.Iterator;

/**
 * One listing of a source: a lazy iterator of items that also says how it ended - complete, stopped
 * on an error ({@link SyncErrorAware}), or complete but knowingly not the whole source
 * ({@link PartialSyncAware}). SyncService reads both after the iterator is exhausted, and only a
 * complete full listing may purge by absence - the rule that keeps an item a listing did not reach
 * from being deleted as if it were gone upstream.
 *
 * <p>Build one with {@link Listings}; a connector rarely implements this by hand.
 */
public interface Listing extends Iterator<ItemMetadata>, SyncErrorAware, PartialSyncAware {

    @Override
    default boolean hasErrored() {
        return false;
    }

    @Override
    default boolean isPartial() {
        return false;
    }

    /**
     * What the next pass should resume from - a change token, a tree hash, the newest timestamp seen.
     * Read once the iterator is exhausted, and stored only when the pass completed, so a pass that
     * failed half way is repeated from where the last good one ended.
     */
    default String nextCursor() {
        return null;
    }
}
