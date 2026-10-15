package ai.proxyma.core.connector.bitbucket;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;

import java.io.InputStream;

/**
 * One Bitbucket Data Center repository, as its owner - see {@link BitbucketConnector}. The client it
 * reads through is this source's alone (BitbucketConnector.forSource), so two repositories sync side
 * by side with no lock between them.
 *
 * <p>PULL REQUESTS AND COMMITS. A full pass lists every pull request and every commit reachable from
 * any branch, and only a full pass purges what is gone. A CHANGES pass lists the pull requests
 * updated since the last complete one - newest first, stopping at the cutoff - and the commits newer
 * than it, widened by fifteen minutes; it can never show a deletion, so it never purges, and the
 * daily full pass does.
 */
final class BitbucketSession implements ConnectorSession {

    private final BitbucketConnector repository;

    BitbucketSession(BitbucketConnector repository) {
        this.repository = repository;
    }

    @Override
    public AccessCheck verifyAccess() {
        return repository.verifyAccess();
    }

    @Override
    public Listing list(ListRequest request) {
        return Listings.of(request.mode() == ListRequest.Mode.CHANGES && request.since() != null
                ? repository.syncSince(request.since())
                : repository.sync());
    }

    @Override
    public InputStream fetch(ItemMetadata item) {
        return repository.getContent(item);
    }
}
