package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.FieldType;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.registry.model.IngestedDocument;
import ai.proxyma.core.registry.repository.IngestedDocumentRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A CUSTOM SOURCE: documents pushed to Proxyma - by a script, Zapier, Make or n8n over the ingestion
 * API (IngestController), or by the agent's saveToSource - rather than read from a service.
 *
 * <p><b>The stored rows are the source, and its session lists them.</b> A push stores the document
 * (IngestedDocument, and its body in IngestStore) and records it as a tracked item the way a listing
 * would (SyncOrchestratorService.recordPushed, through the same reconcileItem). From there the drain
 * fetches each body through this session and indexes it - the same keyword index, vectors, graph
 * extraction, statuses, retries and failures as every other source - and a pass lists the stored
 * rows like any connector lists its service, which catches whatever a push could not record. So a
 * custom source is synced, re-indexed, retried, shared and removed by the code every source goes
 * through, and nothing about indexing is special to it. A DELETE removes the document's index
 * entries at once (as deleting an uploaded file does) rather than waiting for a full pass to notice
 * its absence; the daily full pass still removes anything that was missed.
 *
 * <p><b>Incremental</b>: a scheduled pass lists only what arrived since the last complete one (with
 * a minute's overlap); the daily full pass lists everything, and is what purges by absence.
 * Identical content pushed again carries the same content hash and re-embeds nothing.
 *
 * <p>Every edition (Community): a single user's own sources on Desktop and Home Server, an owner's
 * on Enterprise Server - shareable for reading like any source, writable only by its owner.
 */
@Component
public class CustomSourcePlugin implements ConnectorPlugin {

    static final String NAME = "name";
    static final String DESCRIPTION = "description";

    /** An item's id in the index: the sender's id, said to be one. */
    static final String ITEM_PREFIX = "doc:";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.CUSTOM)
            .displayName("Custom source")
            .description("Documents your own scripts and tools push to Proxyma through the ingestion API, "
                    + "and notes the assistant saves. Nothing is read from elsewhere.")
            .noun("Custom source", "Custom sources")
            .addTitle("Add a custom source")
            .order(900)
            .category(Category.OTHER)
            .mark("api")
            .fields(
                    FieldSpec.text(NAME, "Name").asRequired().placeholder("Release notes")
                            .pattern("(?s).{1,100}"),
                    FieldSpec.of(DESCRIPTION, "Description", FieldType.TEXTAREA)
                            .help("What it holds, for you and for the assistant.")
                            .pattern("(?s).{0,1000}"))
            .capabilities(Capabilities.builder().incremental().shareable().liveRead().push().build())
            // A push is recorded as it arrives; the scheduled pass only catches what one missed.
            .syncEvery(Duration.ofHours(6))
            .docsAnchor("ingest")
            .build();

    private final IngestedDocumentRepository documents;
    private final IngestStore store;

    public CustomSourcePlugin(IngestedDocumentRepository documents, IngestStore store) {
        this.documents = documents;
        this.store = store;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new Session(context == null ? null : context.sourceId());
    }

    /** The sender's id of an item listed by this type, or null for an id that is not one. */
    static String externalIdOf(String itemId) {
        return itemId != null && itemId.startsWith(ITEM_PREFIX) ? itemId.substring(ITEM_PREFIX.length()) : null;
    }

    /** The listed item for one stored document. */
    static ItemMetadata item(IngestedDocument d) {
        ItemMetadata m = new ItemMetadata(ITEM_PREFIX + d.getExternalId(), d.getTitle(), ConnectorType.CUSTOM,
                d.getReceivedAt(), d.getContentType());
        m.setSizeBytes(d.getSizeBytes());
        if (d.getUrl() != null) m.putExtra("url", d.getUrl());
        if (d.getAuthor() != null) m.putExtra(ItemMetadata.AUTHOR, d.getAuthor());
        m.putExtra(ItemMetadata.CONTENT_HASH, d.getContentHash());
        return m;
    }

    private final class Session implements ConnectorSession {

        // Null while a registration checks a candidate - which has nothing to list and needs no probe.
        private final UUID sourceId;

        Session(UUID sourceId) {
            this.sourceId = sourceId;
        }

        @Override
        public Listing list(ListRequest request) {
            if (sourceId == null) return Listings.empty();
            // The overlap the connector guide asks for: a row stored in the same moment the last pass
            // started is listed again rather than missed. Listing it twice changes nothing.
            Instant since = request.mode() == ListRequest.Mode.CHANGES && request.since() != null
                    ? request.since().minus(Duration.ofMinutes(1)) : null;
            return Listings.mapped(new Pages(sourceId, since), CustomSourcePlugin::item);
        }

        @Override
        public InputStream fetch(ItemMetadata item) {
            String externalId = externalIdOf(item.getId());
            if (sourceId == null || externalId == null) {
                throw new ItemGoneException("This document is not one of the source's.");
            }
            // Twice at most: a new version may replace the body between reading the row and opening
            // the file it named, and the second read finds the new one.
            for (int attempt = 0; attempt < 2; attempt++) {
                IngestedDocument row = documents.findByConnectorIdAndExternalId(sourceId, externalId)
                        .orElseThrow(() -> new ItemGoneException("This document was deleted from the source."));
                try {
                    return store.open(sourceId, row.getStoredAs());
                } catch (NoSuchFileException replaced) {
                    // read the row again
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read the stored document: " + e.getMessage(), e);
                }
            }
            throw new ItemGoneException("This document's content is no longer stored. Push it again.");
        }
    }

    // The rows of one source by keyset on the id, two hundred at a time, read only as the listing
    // reaches them.
    private final class Pages implements Iterator<IngestedDocument> {
        private final UUID sourceId;
        private final Instant since;
        private final Deque<IngestedDocument> page = new ArrayDeque<>();
        private UUID after;
        private boolean exhausted;

        Pages(UUID sourceId, Instant since) {
            this.sourceId = sourceId;
            this.since = since;
        }

        @Override
        public boolean hasNext() {
            if (page.isEmpty() && !exhausted) fill();
            return !page.isEmpty();
        }

        @Override
        public IngestedDocument next() {
            if (!hasNext()) throw new NoSuchElementException();
            return page.removeFirst();
        }

        private void fill() {
            List<IngestedDocument> rows;
            if (since == null) {
                rows = after == null ? documents.findTop200ByConnectorIdOrderByIdAsc(sourceId)
                        : documents.findTop200ByConnectorIdAndIdGreaterThanOrderByIdAsc(sourceId, after);
            } else {
                rows = after == null ? documents.findTop200ByConnectorIdAndReceivedAtAfterOrderByIdAsc(sourceId, since)
                        : documents.findTop200ByConnectorIdAndReceivedAtAfterAndIdGreaterThanOrderByIdAsc(
                                sourceId, since, after);
            }
            if (rows.size() < 200) exhausted = true;
            if (!rows.isEmpty()) after = rows.get(rows.size() - 1).getId();
            page.addAll(rows);
        }
    }
}
