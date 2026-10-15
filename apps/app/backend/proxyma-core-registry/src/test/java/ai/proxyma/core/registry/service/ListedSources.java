package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;

import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;

/**
 * Stand-in sources for the sync engine's tests, which are about what is done WITH a listing - its
 * purge, its counts, its schedule - and not about how a connector produced it.
 */
final class ListedSources {

    private ListedSources() {}

    /** A session that lists exactly these items, and fetches nothing. */
    static ConnectorSession listing(Iterator<ItemMetadata> items) {
        return new ConnectorSession() {
            @Override
            public Listing list(ListRequest request) {
                return Listings.of(items);
            }

            @Override
            public InputStream fetch(ItemMetadata item) {
                throw new UnsupportedOperationException("not fetched in this test");
            }
        };
    }

    /** The type's own descriptor, as the engine reads it. */
    static ConnectorDescriptor descriptor(ConnectorType type) {
        return CoreDescriptors.of(type);
    }

    /** A plugin of the type whose every session lists nothing - for the scheduler's tests. */
    static ConnectorPlugin plugin(ConnectorType type) {
        return new ConnectorPlugin() {
            @Override
            public ConnectorDescriptor descriptor() {
                return CoreDescriptors.of(type);
            }

            @Override
            public ConnectorSession open(SourceContext context) {
                return listing(Collections.emptyIterator());
            }
        };
    }
}
