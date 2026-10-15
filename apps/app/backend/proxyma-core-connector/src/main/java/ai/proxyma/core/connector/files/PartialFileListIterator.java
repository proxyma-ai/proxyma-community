package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A file listing that stopped short of the folder's real contents - today only because it hit the
 * per-connector file ceiling the license tier sets.
 *
 * <p>Not an error: the ceiling did exactly what it is for, and the files it did list are all good.
 * But the ones past it were never looked at, and a listing that never reached a file is no evidence
 * that the file was deleted. Without this the lower tiers' ceiling would purge everything beyond it
 * on every single sync, then re-list the same first N files and call it a clean run. Spec 00
 * section 3.4 - the same distinction {@link ai.proxyma.core.connector.FailedSyncIterator} draws for
 * a listing that broke.
 *
 * <p>Package-private and shared by both file connectors, which is safe in a way their sync() bodies
 * are not: this decides nothing about what a valid root is or who owns the bytes, it only carries a
 * list and a sentence - the same reason {@link FileTypeSupport} is shared.
 */
final class PartialFileListIterator implements Iterator<ItemMetadata>, PartialSyncAware {

    private final Iterator<ItemMetadata> delegate;
    private final String reason;

    PartialFileListIterator(List<ItemMetadata> items, String reason) {
        this.delegate = items.iterator();
        this.reason = reason;
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public ItemMetadata next() {
        if (!delegate.hasNext()) throw new NoSuchElementException();
        return delegate.next();
    }

    @Override
    public boolean isPartial() {
        return true;
    }

    @Override
    public String getPartialReason() {
        return reason;
    }
}
