package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.dto.StorageSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sync work stops before what it stands on (0.36.0 feedback). Passes and drains ran on into the
 * shutdown: the event executor refused their notices, ChromaDB was stopped under an upsert, H2
 * closed under a pass, and each item in flight was recorded as a FAILED document - an ERROR with a
 * stack trace, a retry spent, the source put in ERROR for a pass the shutdown had cut short.
 */
class SyncShutdownTest {

    @TempDir
    Path temp;

    private TrackedDocumentRepository documents;
    private RegisteredConnectorRepository connectors;
    private EmbeddingService embeddings;
    private DocumentFetcher converter;
    private ApplicationEventPublisher events;
    private SyncService sync;
    private RegisteredConnector source;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        connectors = mock(RegisteredConnectorRepository.class);
        embeddings = mock(EmbeddingService.class);
        converter = mock(DocumentFetcher.class);
        events = mock(ApplicationEventPublisher.class);
        AppPaths paths = mock(AppPaths.class);
        when(paths.tempDir()).thenReturn(temp);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(StorageSettings.PREFIX), eq(StorageSettings.class))).thenReturn(new StorageSettings());
        sync = new SyncService(documents, connectors, converter, embeddings, mock(KnowledgeGraphService.class),
                events, paths, settings, null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));
        source = new RegisteredConnector();
        source.setId(UUID.randomUUID());
        source.setConnectorType(ConnectorType.JIRA);
        when(connectors.findById(source.getId())).thenReturn(Optional.of(source));
        when(converter.convertToMarkdown(any(), any())).thenReturn("An issue worth indexing.");
    }

    private TrackedDocument queued(String key) {
        TrackedDocument doc = new TrackedDocument();
        doc.setId(UUID.randomUUID());
        doc.setConnectorId(source.getId());
        doc.setConnectorType(ConnectorType.JIRA);
        doc.setSourceId(key);
        doc.setStatus(TrackedDocument.Status.QUEUED);
        doc.setSourceLastModified(Instant.now());
        return doc;
    }

    private void process(TrackedDocument doc) {
        ItemMetadata meta = sync.toMinimalItemMetadata(doc);
        sync.processItem(ConnectorType.JIRA, doc, "c1", meta,
                () -> new java.io.ByteArrayInputStream("raw".getBytes()));
    }

    private void embedding(org.mockito.stubbing.Answer<?> answer) {
        doAnswer(answer).when(embeddings).chunkAndEmbed(anyString(), anyString(), anyString(), any(), anyInt(),
                any(), any(), any());
    }

    @Test
    @DisplayName("is not failed by a progress notice the closing event executor refuses")
    void refusedNoticeDoesNotFailTheItem() {
        // RH-17200: the notice of its own status change was refused, and the item recorded FAILED.
        // Only the page's notices go to an @Async listener (EventStreamController).
        doThrow(new TaskRejectedException("ExecutorService in shutdown state did not accept task"))
                .when(events).publishEvent(any(ai.proxyma.core.registry.event.ConnectorUpdateEvent.class));
        embedding(inv -> {
            EmbeddingService.ChunkProgressCallback progress = inv.getArgument(7);
            progress.onProgress(1, 1);
            return List.of();
        });
        TrackedDocument doc = queued("RH-17200");

        process(doc);

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.COMPLETED);
        assertThat(doc.getFailureMessage()).isNull();
    }

    @Test
    @DisplayName("stopped between embedding batches keeps its place to resume, and is no failure")
    void stoppedEmbeddingResumes() {
        embedding(inv -> {
            EmbeddingService.ChunkProgressCallback progress = inv.getArgument(7);
            progress.onProgress(0, 3);
            sync.stopForShutdown();
            progress.onProgress(1, 3);   // the batch just written is recorded, then the stop
            return List.of();
        });
        TrackedDocument doc = queued("RH-1");

        process(doc);

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.EMBEDDING);
        assertThat(doc.getCurrentChunks()).isEqualTo(1);
        assertThat(doc.getFailureMessage()).isNull();
        assertThat(doc.getNextRetryAt()).isNull();
        assertThat(doc.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("failed by the vector store stopping under it goes back to the queue, not to FAILED")
    void failureDuringShutdownRequeues() {
        embedding(inv -> {
            sync.stopForShutdown();
            throw new RuntimeException("ChromaDB upsert failed for collection c1",
                    new java.io.IOException("HTTP/1.1 header parser received no bytes"));
        });
        TrackedDocument doc = queued("PR-494");

        process(doc);

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.QUEUED);
        assertThat(doc.getCurrentChunks()).isZero();
        assertThat(doc.getFailureMessage()).isNull();
        assertThat(doc.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("failed while the application runs is still FAILED")
    void realFailureStillFails() {
        embedding(inv -> {
            throw new RuntimeException("ChromaDB upsert failed for collection c1");
        });
        TrackedDocument doc = queued("PR-1");

        process(doc);

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.FAILED);
        assertThat(doc.getFailureMessage()).contains("ChromaDB upsert failed");
    }


    @Test
    @DisplayName("cut short by the shutdown records nothing: no ERROR, no checkpoint, no purge")
    void stoppedPassRecordsNothing() {
        TrackedDocumentRepository documents = mock(TrackedDocumentRepository.class);
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        SyncService sync = new SyncService(documents, connectors, null, embeddings, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));
        RegisteredConnector folder = new RegisteredConnector();
        folder.setId(UUID.randomUUID());
        folder.setConnectorType(ConnectorType.LOCAL_FOLDER);
        folder.setStatus(RegisteredConnector.Status.ACTIVE);
        when(connectors.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(documents.findBySourceIdAndConnectorId(any(), any())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // The shutdown arrives while the third item is being listed.
        Iterator<ItemMetadata> items = new Iterator<>() {
            int next;

            @Override
            public boolean hasNext() {
                return next < 5;
            }

            @Override
            public ItemMetadata next() {
                if (next == 2) sync.stopForShutdown();
                ItemMetadata item = new ItemMetadata();
                item.setId("/home/ana/docs/file-" + next++ + ".md");
                item.setLastModified(Instant.now());
                return item;
            }
        };

        SyncService.PassResult result = sync.reconcile(ListedSources.listing(items),
                ListedSources.descriptor(ConnectorType.LOCAL_FOLDER), folder, true);

        assertThat(result).isNull();
        verify(connectors, never()).updateSyncState(any(), any(), any());
        verify(connectors, never()).markSynced(any(), any(), any());
        verify(connectors, never()).markFullySynced(any(), any(), any());
        verify(embeddings, never()).deleteBySourceId(anyString(), anyString());
    }
}
