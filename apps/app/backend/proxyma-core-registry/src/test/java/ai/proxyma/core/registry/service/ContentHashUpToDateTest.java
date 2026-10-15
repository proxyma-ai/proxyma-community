package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A re-crawled page whose words have not changed is not indexed again, whatever its timestamp says.
 *
 * <p>Most web servers send no Last-Modified, so the crawler stamps each page with the time it saw
 * it - later than the indexed one on every pass. Every re-crawl therefore re-embedded every page it
 * already had, and raising a site's page limit (which re-crawls from the seed) took a very long
 * time to reach the new pages. The item's content hash is what now decides.
 */
class ContentHashUpToDateTest {

    private static final String URL = "https://docs.example.com/guide";

    private TrackedDocumentRepository documents;
    private SyncService sync;
    private RegisteredConnector site;
    private TrackedDocument indexed;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        sync = new SyncService(documents, connectors, null, null, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));

        site = new RegisteredConnector();
        site.setId(UUID.randomUUID());
        site.setConnectorType(ConnectorType.WEB_PAGE);
        when(connectors.findById(site.getId())).thenReturn(Optional.of(site));

        indexed = new TrackedDocument();
        indexed.setSourceId(URL);
        indexed.setConnectorId(site.getId());
        indexed.setConnectorType(ConnectorType.WEB_PAGE);
        indexed.setStatus(TrackedDocument.Status.COMPLETED);
        indexed.setTotalChunks(4);
        indexed.setContentHash("hash-of-the-guide");
        indexed.setSourceLastModified(Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
        when(documents.findBySourceIdAndConnectorId(URL, site.getId())).thenReturn(Optional.of(indexed));
    }

    private void recrawl(String hash) {
        // Stamped "now", as the crawler does for a page whose server sent no Last-Modified.
        ItemMetadata page = new ItemMetadata(URL, "Guide", ConnectorType.WEB_PAGE, Instant.now(), "text/markdown");
        page.putExtra(ItemMetadata.CONTENT_HASH, hash);
        sync.reconcile(ListedSources.listing(List.of(page).iterator()), ListedSources.descriptor(ConnectorType.WEB_PAGE),
                site, true);
    }

    @Test
    @DisplayName("the same words under a newer timestamp stay indexed - nothing is queued again")
    void unchangedPageIsNotRequeued() {
        recrawl("hash-of-the-guide");

        assertEquals(TrackedDocument.Status.COMPLETED, indexed.getStatus());
        verify(documents, never()).save(argThat(d -> d.getStatus() == TrackedDocument.Status.QUEUED));
    }

    @Test
    @DisplayName("changed words are queued, and the new hash is what the next pass compares against")
    void changedPageIsRequeued() {
        recrawl("hash-of-the-edited-guide");

        assertEquals(TrackedDocument.Status.QUEUED, indexed.getStatus());
        assertEquals("hash-of-the-edited-guide", indexed.getContentHash());
        verify(documents, org.mockito.Mockito.atLeastOnce()).save(any());
    }
}
