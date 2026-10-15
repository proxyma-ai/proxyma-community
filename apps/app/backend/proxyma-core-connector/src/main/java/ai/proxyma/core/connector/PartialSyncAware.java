package ai.proxyma.core.connector;

/**
 * Optional capability for a source's listing (every {@link ai.proxyma.core.connector.spi.Listing}
 * carries it): the listing completed without error but is KNOWN not to be the whole remote set -
 * a crawl that stopped at its page cap, a listing that skipped items it could not read.
 *
 * <p>Distinct from {@link SyncErrorAware}, and the distinction decides whether items get deleted.
 * An errored sync is a failure; a partial one is a success that simply did not see everything. Both
 * must suppress purge-by-absence, because an item the listing never reached is indistinguishable
 * from one that was removed upstream - but only the errored case should put the source in ERROR.
 * A partial sync that purged would delete every page beyond the crawl cap on every run.
 */
public interface PartialSyncAware {

    boolean isPartial();

    /** Short reason for the log line explaining why a purge was skipped - null if not captured. */
    default String getPartialReason() {
        return null;
    }

    /**
     * Ids this listing LOOKED AT and established are not items of the source any more: the server
     * said nothing is there, or the address turned out to be a copy of another item or a sign-in
     * page. Never an id the listing merely did not reach, and never one that failed to load.
     *
     * <p>A partial listing cannot purge by absence, so without this an item that has gone stayed
     * for as long as the listing stayed partial - which for a crawl capped at its page limit is
     * for ever. These are the absences it can vouch for.
     */
    default java.util.Set<String> getGoneItemIds() {
        return java.util.Set.of();
    }
}
