package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.connector.SyncErrorAware;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ready-made {@link Listing}s, so a connector says WHAT it lists and never re-implements how a
 * listing ends.
 *
 * <p>The one rule every listing here keeps: <b>a failure is an answer, never an exception and never
 * an empty iterator.</b> A page that could not be read stops the listing and marks it errored, with
 * the sentence the source's card shows ({@link ConnectorHttpException#getMessage()} for an HTTP
 * failure). An empty iterator is indistinguishable from a source that genuinely holds nothing, and
 * an exception escaping {@code hasNext()} used to leave the source looking healthy with zero items.
 */
public final class Listings {

    private Listings() {}

    /** A listing of these items, complete. */
    public static Listing of(List<ItemMetadata> items) {
        return of(items.iterator());
    }

    /**
     * A listing over an iterator that may throw - the usual case: pages fetched lazily. An iterator
     * that already reports errors or partiality (a file walk's, a crawl's) keeps reporting them.
     */
    public static Listing of(Iterator<ItemMetadata> items) {
        if (items instanceof Listing listing) return listing;
        return new Guarded(List.of(() -> items), items);
    }

    /** Nothing listed, and the reason - what a connector returns when it cannot list at all. */
    public static Listing failed(String message) {
        Guarded g = new Guarded(List.of(), null);
        g.errorMessage = message == null || message.isBlank() ? "The source could not be listed." : message;
        return g;
    }

    public static Listing empty() {
        return new Guarded(List.of(), null);
    }

    /**
     * Several listings, one after another - issues, then pull requests, then files. Each part is
     * started only once the one before it is exhausted, and the first failure ends the whole
     * listing: a pass that saw the issues but not the files is not complete, and must not purge the
     * files.
     */
    @SafeVarargs
    public static Listing concat(Supplier<Iterator<ItemMetadata>>... parts) {
        return new Guarded(List.of(parts), null);
    }

    public static Listing concat(List<Supplier<Iterator<ItemMetadata>>> parts) {
        return new Guarded(parts, null);
    }

    /** {@code listing}, handing back {@code cursor.get()} as its next cursor once it is exhausted. */
    public static Listing withCursor(Listing listing, Supplier<String> cursor) {
        return new Decorated(listing, cursor, null, null);
    }

    /**
     * {@code listing}, reported as complete but knowingly not the whole source - a cap reached, a
     * part skipped by choice. It does not purge by absence; {@code goneIds} are the absences it
     * established and may remove.
     */
    public static Listing partial(Listing listing, String reason, Set<String> goneIds) {
        return new Decorated(listing, null, reason, goneIds == null ? Set.of() : Set.copyOf(goneIds));
    }

    /**
     * {@code listing}, partial if {@code reason} - asked once the listing is exhausted - names a
     * reason, complete if it answers null. For what a listing only learns as it goes: a tree the
     * service answered truncated, a cap reached on the last page.
     */
    public static Listing partialIf(Listing listing, Supplier<String> reason) {
        return new Listing() {
            @Override public boolean hasNext() { return listing.hasNext(); }
            @Override public ItemMetadata next() { return listing.next(); }
            @Override public boolean hasErrored() { return listing.hasErrored(); }
            @Override public String getErrorMessage() { return listing.getErrorMessage(); }
            @Override public boolean isPartial() { return reason.get() != null || listing.isPartial(); }

            @Override
            public String getPartialReason() {
                String r = reason.get();
                return r != null ? r : listing.getPartialReason();
            }

            @Override public Set<String> getGoneItemIds() { return listing.getGoneItemIds(); }
            @Override public String nextCursor() { return listing.nextCursor(); }
        };
    }

    /** Items of a paged API, turned into listed items one by one. */
    public static <T> Listing mapped(Iterator<T> source, Function<T, ItemMetadata> toItem) {
        Iterator<ItemMetadata> mapped = new Iterator<>() {
            private ItemMetadata next;

            @Override
            public boolean hasNext() {
                while (next == null && source.hasNext()) next = toItem.apply(source.next());
                return next != null;
            }

            @Override
            public ItemMetadata next() {
                if (!hasNext()) throw new NoSuchElementException();
                ItemMetadata out = next;
                next = null;
                return out;
            }
        };
        return of(mapped);
    }

    /**
     * The iterator a listing was built over, for the one caller that has to recognize a
     * session's typed iterator (SyncService and MissingUploadFolder). Itself for anything else.
     */
    public static Iterator<ItemMetadata> delegateOf(Iterator<ItemMetadata> listing) {
        if (listing instanceof Guarded g && g.origin != null) return g.origin;
        if (listing instanceof Decorated d) return delegateOf(d.inner);
        return listing;
    }

    /** The user-facing sentence for a failure while listing. */
    static String describe(Exception e) {
        if (e instanceof ConnectorHttpException http) return http.getMessage();
        return SyncErrorAware.summarize(e);
    }

    private static final class Guarded implements Listing {
        private final List<Supplier<Iterator<ItemMetadata>>> parts;
        // The single iterator this was built over, when there was one - see delegateOf.
        private final Iterator<ItemMetadata> origin;
        private int part = -1;
        private Iterator<ItemMetadata> current;
        private String errorMessage;
        private ItemMetadata next;

        Guarded(List<Supplier<Iterator<ItemMetadata>>> parts, Iterator<ItemMetadata> origin) {
            this.parts = new ArrayList<>(parts);
            this.origin = origin;
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            while (errorMessage == null) {
                try {
                    if (current == null || !current.hasNext()) {
                        if (current instanceof SyncErrorAware aware && aware.hasErrored()) {
                            errorMessage = aware.getErrorMessage() == null
                                    ? "The source stopped listing on an error." : aware.getErrorMessage();
                            return false;
                        }
                        if (++part >= parts.size()) return false;
                        current = parts.get(part).get();
                        continue;
                    }
                    next = current.next();
                    if (next != null) return true;
                } catch (RuntimeException e) {
                    errorMessage = describe(e);
                }
            }
            return false;
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            ItemMetadata out = next;
            next = null;
            return out;
        }

        @Override
        public boolean hasErrored() {
            return errorMessage != null;
        }

        @Override
        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public boolean isPartial() {
            return origin instanceof PartialSyncAware p && p.isPartial();
        }

        @Override
        public String getPartialReason() {
            return origin instanceof PartialSyncAware p ? p.getPartialReason() : null;
        }

        @Override
        public Set<String> getGoneItemIds() {
            return origin instanceof PartialSyncAware p ? p.getGoneItemIds() : Set.of();
        }
    }

    private static final class Decorated implements Listing {
        private final Listing inner;
        private final Supplier<String> cursor;
        private final String partialReason;
        private final Set<String> gone;

        Decorated(Listing inner, Supplier<String> cursor, String partialReason, Set<String> gone) {
            this.inner = inner;
            this.cursor = cursor;
            this.partialReason = partialReason;
            this.gone = gone;
        }

        @Override public boolean hasNext() { return inner.hasNext(); }
        @Override public ItemMetadata next() { return inner.next(); }
        @Override public boolean hasErrored() { return inner.hasErrored(); }
        @Override public String getErrorMessage() { return inner.getErrorMessage(); }

        @Override
        public boolean isPartial() {
            return partialReason != null || inner.isPartial();
        }

        @Override
        public String getPartialReason() {
            return partialReason != null ? partialReason : inner.getPartialReason();
        }

        @Override
        public Set<String> getGoneItemIds() {
            return gone != null ? gone : inner.getGoneItemIds();
        }

        @Override
        public String nextCursor() {
            return cursor != null ? cursor.get() : inner.nextCursor();
        }
    }
}
