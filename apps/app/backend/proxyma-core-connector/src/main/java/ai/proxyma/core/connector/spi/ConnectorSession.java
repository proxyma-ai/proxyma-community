package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ItemMetadata;

import java.io.InputStream;

/**
 * One source, opened for one pass or one batch of fetches - opened by the sync engine in a
 * try-with-resources and closed when the pass or batch ends. It holds the source's own
 * configuration and credentials (its {@link SourceContext}) and nothing any other source can see,
 * so sessions of one type run side by side with no lock.
 */
public interface ConnectorSession extends AutoCloseable {

    /**
     * Exactly one cheap, authenticated, read-only request against the RESOURCE being registered -
     * the repository, the project, the folder - not merely against the server. Run at registration
     * so a wrong credential or a misspelled name is refused while the person who typed it is still
     * there to fix it.
     *
     * <p>MUST NOT throw: a failure is an answer, {@link AccessCheck#failed(String)}, whose detail is
     * shown to that person. The default, {@link AccessCheck#unsupported()}, lets registration
     * proceed unchecked.
     */
    default AccessCheck verifyAccess() {
        return AccessCheck.unsupported();
    }

    /**
     * The source's items, lazily: {@link ListRequest.Mode#FULL} lists everything (and a complete
     * full listing is what purges items gone upstream); {@link ListRequest.Mode#CHANGES} lists what
     * changed since {@link ListRequest#since()}, asked only where the descriptor says incremental.
     *
     * <p>Never throws for a failure to list and never returns an empty listing for one - see
     * {@link Listings}.
     */
    Listing list(ListRequest request);

    /**
     * One item's content: the bytes of a file (with its content type on the item), or UTF-8 markdown
     * a connector rendered from an API record. Resolved from the item's id alone - the drain rebuilds
     * the item from its stored row, without the extras the listing set. Throws
     * {@link ai.proxyma.core.connector.ItemGoneException} when the item no longer exists, so it is
     * removed at once rather than retried. The caller closes the stream.
     */
    InputStream fetch(ItemMetadata item);

    @Override
    default void close() {}
}
