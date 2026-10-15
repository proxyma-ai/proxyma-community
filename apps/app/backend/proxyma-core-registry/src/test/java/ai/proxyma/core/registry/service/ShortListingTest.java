package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A full listing far shorter than what is indexed (telemetry dff32853, 0.35.0).
 *
 * <p>A local folder source listed nothing against 220 indexed documents - the folder moved,
 * unmounted, or on an offline drive. The purge guard held, so nothing was deleted, but the pass
 * finished "complete" with no word on the source: it looked healthy while its folder listed
 * nothing. The guard stays; the source now says what happened, and the pass is not counted as a
 * full enumeration.
 */
class ShortListingTest {

    private TrackedDocumentRepository documents;
    private RegisteredConnectorRepository connectors;
    private EmbeddingService embeddings;
    private SyncService sync;
    private RegisteredConnector folder;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        connectors = mock(RegisteredConnectorRepository.class);
        embeddings = mock(EmbeddingService.class);
        sync = new SyncService(documents, connectors, null, embeddings, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));
        folder = new RegisteredConnector();
        folder.setId(UUID.randomUUID());
        folder.setConnectorType(ConnectorType.LOCAL_FOLDER);
        folder.setStatus(RegisteredConnector.Status.ACTIVE);
        folder.setAllItemsFetched(true);
        when(connectors.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(documents.countByConnectorIdAndStatusNotAndContentPurgedFalse(folder.getId(), TrackedDocument.Status.DELETED))
                .thenReturn(220L);
        when(documents.findBySourceIdAndConnectorId(any(), any())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static List<ItemMetadata> files(int n) {
        List<ItemMetadata> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemMetadata item = new ItemMetadata();
            item.setId("/home/ana/docs/file-" + i + ".md");
            item.setTitle("file-" + i + ".md");
            item.setLastModified(Instant.now());
            items.add(item);
        }
        return items;
    }

    @Test
    @DisplayName("a folder that lists nothing says so as an error, keeps its index, and is no full pass")
    void folderListingNothingSaysSo() {


        SyncService.PassResult result = sync.reconcile(ListedSources.listing(Collections.emptyIterator()),
                ListedSources.descriptor(ConnectorType.LOCAL_FOLDER), folder, true);

        verify(connectors).updateSyncState(eq(folder.getId()), eq(RegisteredConnector.Status.ERROR),
                eq("The folder listed no files (220 before). Nothing was removed from the index"
                        + " - check that the folder is still there."));
        assertThat(result.ok()).isFalse();
        verify(connectors).updateAllItemsFetched(folder.getId(), false);
        verify(connectors, never()).markFullySynced(any(), any(), any());
        verify(connectors, never()).markSynced(any(), any(), any());
        verify(embeddings, never()).deleteBySourceId(anyString(), anyString());
    }

    @Test
    @DisplayName("a listing of far fewer leaves a note beside ACTIVE and is not a full enumeration either")
    void shortListingLeavesANote() {

        SyncService.PassResult result = sync.reconcile(ListedSources.listing(files(40).iterator()),
                ListedSources.descriptor(ConnectorType.LOCAL_FOLDER), folder, true);

        verify(connectors).updateSyncState(eq(folder.getId()), eq(RegisteredConnector.Status.ACTIVE),
                argThat(note -> note != null && note.startsWith("This pass listed 40 of the 220 files")));
        assertThat(result.ok()).isTrue();
        verify(connectors).updateAllItemsFetched(folder.getId(), false);
        verify(connectors, never()).markFullySynced(any(), any(), any());
        verify(connectors).markSynced(eq(folder.getId()), any(), any());
        verify(embeddings, never()).deleteBySourceId(anyString(), anyString());
    }

    @Test
    @DisplayName("an upload source whose every document was indexed and purged lists nothing and is healthy")
    void purgedUploadsAreNotAShortListing() {
        // Enterprise Server deletes an upload's bytes once indexed, keeping the folder: the listing
        // is empty by design, and the count the floor compares against leaves purged documents out.
        RegisteredConnector uploads = new RegisteredConnector();
        uploads.setId(UUID.randomUUID());
        uploads.setConnectorType(ConnectorType.UPLOADED_FILES);
        uploads.setStatus(RegisteredConnector.Status.ACTIVE);
        uploads.setAllItemsFetched(true);
        when(connectors.findById(uploads.getId())).thenReturn(Optional.of(uploads));
        // Ten live documents, all purged: the count that includes them is ten, the one that does not
        // is none - and the floor must read the second.
        when(documents.countByConnectorIdAndStatusNot(uploads.getId(), TrackedDocument.Status.DELETED)).thenReturn(10L);
        when(documents.countByConnectorIdAndStatusNotAndContentPurgedFalse(uploads.getId(), TrackedDocument.Status.DELETED))
                .thenReturn(0L);

        SyncService.PassResult result = sync.reconcile(ListedSources.listing(Collections.emptyIterator()),
                ListedSources.descriptor(ConnectorType.UPLOADED_FILES), uploads, true);

        assertThat(result.ok()).isTrue();
        verify(connectors, never()).updateSyncState(eq(uploads.getId()), eq(RegisteredConnector.Status.ERROR), any());
        verify(connectors).updateAllItemsFetched(uploads.getId(), true);
        verify(embeddings, never()).deleteBySourceId(anyString(), anyString());
    }
}
