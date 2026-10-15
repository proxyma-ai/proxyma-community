package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a PARTIAL listing may remove, and what it must leave.
 *
 * <p>A listing that did not see everything cannot purge by absence, so it removed nothing - and a
 * web page source at its page limit is partial on every pass. A manual the site had stopped hosting
 * stayed a failed document for ever, and sign-in pages an earlier build had indexed stayed
 * documents. It may remove the ids it vouches for and the failed items it did not come across
 * again; an indexed page it merely did not reach stays.
 */
class PartialListingPurgeTest {

    private static final String SEEN = "https://docs.example.com/guide";
    private static final String UNREACHED = "https://docs.example.com/page-beyond-the-limit";
    private static final String SIGN_IN = "https://docs.example.com/private?ctx=1";
    private static final String DEAD_PDF = "https://docs.example.com/manuals/old.pdf";

    private TrackedDocumentRepository documents;
    private EmbeddingService embeddings;
    private SyncService sync;
    private RegisteredConnector site;
    private TrackedDocument seen;
    private TrackedDocument unreached;
    private TrackedDocument signIn;
    private TrackedDocument deadPdf;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        embeddings = mock(EmbeddingService.class);
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        sync = new SyncService(documents, connectors, null, embeddings, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));

        site = new RegisteredConnector();
        site.setId(UUID.randomUUID());
        site.setConnectorType(ConnectorType.WEB_PAGE);
        when(connectors.findById(site.getId())).thenReturn(Optional.of(site));

        seen = tracked(SEEN, TrackedDocument.Status.COMPLETED);
        seen.setTotalChunks(4);
        seen.setContentHash("hash-of-the-guide");
        unreached = tracked(UNREACHED, TrackedDocument.Status.COMPLETED);
        signIn = tracked(SIGN_IN, TrackedDocument.Status.COMPLETED);
        deadPdf = tracked(DEAD_PDF, TrackedDocument.Status.FAILED);
        when(documents.findBySourceIdAndConnectorId(SEEN, site.getId())).thenReturn(Optional.of(seen));
        when(documents.findByConnectorId(site.getId())).thenReturn(List.of(seen, unreached, signIn, deadPdf));
    }

    private TrackedDocument tracked(String url, TrackedDocument.Status status) {
        TrackedDocument doc = new TrackedDocument();
        doc.setId(UUID.randomUUID());
        doc.setSourceId(url);
        doc.setConnectorId(site.getId());
        doc.setConnectorType(ConnectorType.WEB_PAGE);
        doc.setStatus(status);
        doc.setSourceLastModified(Instant.parse("2026-09-29T00:00:00Z"));
        return doc;
    }

    // A crawl that listed one page, stopped at its limit, and vouches for `gone`.
    private static final class PartialListing implements Iterator<ItemMetadata>, PartialSyncAware {
        private final Iterator<ItemMetadata> items;
        private final Set<String> gone;

        PartialListing(Set<String> gone) {
            ItemMetadata guide = new ItemMetadata(SEEN, "Guide", ConnectorType.WEB_PAGE, Instant.now(), "text/markdown");
            guide.putExtra(ItemMetadata.CONTENT_HASH, "hash-of-the-guide");
            this.items = List.of(guide).iterator();
            this.gone = gone;
        }

        @Override public boolean hasNext() { return items.hasNext(); }
        @Override public ItemMetadata next() { return items.next(); }
        @Override public boolean isPartial() { return true; }
        @Override public String getPartialReason() { return "stopped at the 1-page limit"; }
        @Override public Set<String> getGoneItemIds() { return gone; }
    }

    private void crawl(Set<String> gone) {
        sync.reconcile(ListedSources.listing(new PartialListing(gone)), ListedSources.descriptor(ConnectorType.WEB_PAGE),
                site, true);
    }

    @Test
    @DisplayName("an id the listing vouches is gone is removed, vectors and all")
    void whatTheListingVouchesFor() {
        crawl(Set.of(SIGN_IN));

        assertEquals(TrackedDocument.Status.DELETED, signIn.getStatus());
        verify(embeddings).deleteBySourceId(any(), org.mockito.ArgumentMatchers.eq(SIGN_IN));
    }

    @Test
    @DisplayName("a failed item the listing did not come across again is removed")
    void aFailedItemNoLongerListed() {
        crawl(Set.of());

        assertEquals(TrackedDocument.Status.DELETED, deadPdf.getStatus());
    }

    @Test
    @DisplayName("an indexed page the listing merely did not reach stays, and so does what it listed")
    void whatItDidNotReachStays() {
        crawl(Set.of(SIGN_IN));

        assertEquals(TrackedDocument.Status.COMPLETED, unreached.getStatus());
        assertEquals(TrackedDocument.Status.COMPLETED, seen.getStatus());
        verify(embeddings, never()).deleteBySourceId(any(), org.mockito.ArgumentMatchers.eq(UNREACHED));
        verify(embeddings, never()).deleteBySourceId(any(), org.mockito.ArgumentMatchers.eq(SEEN));
    }

    @Test
    @DisplayName("an id vouched gone that the same listing also listed is not removed")
    void listedWins() {
        crawl(Set.of(SEEN));

        assertEquals(TrackedDocument.Status.COMPLETED, seen.getStatus());
    }
}
