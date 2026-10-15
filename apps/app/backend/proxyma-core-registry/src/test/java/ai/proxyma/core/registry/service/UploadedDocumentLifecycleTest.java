package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.files.upload.service.FileService;
import ai.proxyma.core.registry.files.upload.service.StorageService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An uploaded file replaced, and an uploaded file deleted, reach every document of it.
 *
 * <ul>
 *   <li>Uploading a file again over one whose bytes were purged after indexing re-embedded the OLD
 *       text kept from it: nothing cleared contentPurged, so the new bytes were never read.</li>
 *   <li>Deleting a file tracked by two sources (the sandbox root and a folder inside it) threw from
 *       an Optional lookup, after the bytes were gone, and left both documents searchable.</li>
 *   <li>Deleting a file a drain was indexing at that moment was undone when the run finished and
 *       saved its own copy of the row as COMPLETED, with fresh vectors.</li>
 * </ul>
 */
class UploadedDocumentLifecycleTest {

    private static final String OWNER = "u1";

    @TempDir
    Path storageRoot;

    @TempDir
    Path temp;

    private TrackedDocumentRepository documents;
    private RegisteredConnectorRepository connectors;
    private EmbeddingService embeddings;
    private DocumentFetcher converter;
    private SyncService sync;
    private RegisteredConnector source;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        connectors = mock(RegisteredConnectorRepository.class);
        embeddings = mock(EmbeddingService.class);
        converter = mock(DocumentFetcher.class);
        AppPaths paths = mock(AppPaths.class);
        when(paths.tempDir()).thenReturn(temp);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(StorageSettings.PREFIX), eq(StorageSettings.class))).thenReturn(new StorageSettings());
        sync = new SyncService(documents, connectors, converter, embeddings, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), paths, settings, null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));
        ReflectionTestUtils.setField(sync, "storageRoot", storageRoot.toString());

        source = new RegisteredConnector();
        source.setId(UUID.randomUUID());
        source.setUserId(OWNER);
        source.setConnectorType(ConnectorType.UPLOADED_FILES);
        when(connectors.findById(source.getId())).thenReturn(Optional.of(source));
    }

    private TrackedDocument tracked(UUID connectorId, String sourceId, TrackedDocument.Status status) {
        TrackedDocument doc = new TrackedDocument();
        doc.setId(UUID.randomUUID());
        doc.setConnectorId(connectorId);
        doc.setConnectorType(ConnectorType.UPLOADED_FILES);
        doc.setSourceId(sourceId);
        doc.setStatus(status);
        doc.setSourceLastModified(Instant.now());
        return doc;
    }

    private String collection() {
        return source.getId().toString().replace("-", "");
    }

    @Test
    @DisplayName("a file uploaded again over purged bytes is read again, and its kept text is dropped")
    void reuploadOverPurgedBytesReadsTheNewBytes() throws Exception {
        String path = storageRoot.resolve("users/u1/uploads/Specs/plan.md").toString();
        TrackedDocument doc = tracked(source.getId(), path, TrackedDocument.Status.COMPLETED);
        doc.setContentPurged(true);
        Path kept = Files.createDirectories(storageRoot.resolve("users/u1/markdown-cache"))
                .resolve(doc.getId() + ".md");
        Files.writeString(kept, "old text");
        when(documents.findAllBySourceId(path)).thenReturn(List.of(doc));

        FileMetadataRepository rows = mock(FileMetadataRepository.class);
        FileMetadata purged = new FileMetadata();
        purged.setStoragePath(path);
        purged.setBytesPurged(true);
        when(rows.findByStoragePath(path)).thenReturn(Optional.of(purged));
        when(rows.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StorageService storage = mock(StorageService.class);
        when(storage.store(any(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()))
                .thenReturn(path);
        FileService files = new FileService(rows, storage, documents, null, sync, null);

        FileMetadata saved = files.upload("plan.md", "text/markdown", 8,
                new ByteArrayInputStream("new text".getBytes(StandardCharsets.UTF_8)), OWNER, "Specs", null);

        assertThat(saved.isBytesPurged()).isFalse();
        assertThat(doc.isContentPurged()).isFalse();
        assertThat(kept).doesNotExist();
        verify(documents).save(doc);
    }

    @Test
    @DisplayName("a file tracked by two sources is deleted from both")
    void fileTrackedTwiceIsDeletedFromBoth() {
        RegisteredConnector root = new RegisteredConnector();
        root.setId(UUID.randomUUID());
        root.setUserId(OWNER);
        when(connectors.findById(root.getId())).thenReturn(Optional.of(root));
        String path = "/data/users/u1/uploads/Specs/plan.md";
        TrackedDocument inFolder = tracked(source.getId(), path, TrackedDocument.Status.COMPLETED);
        TrackedDocument inRoot = tracked(root.getId(), path, TrackedDocument.Status.COMPLETED);
        when(documents.findAllBySourceId(path)).thenReturn(List.of(inFolder, inRoot));

        sync.deleteTrackedDocumentBySourceId(path);

        assertThat(inFolder.getStatus()).isEqualTo(TrackedDocument.Status.DELETED);
        assertThat(inRoot.getStatus()).isEqualTo(TrackedDocument.Status.DELETED);
        verify(embeddings).deleteBySourceId(collection(), path);
        verify(embeddings).deleteBySourceId(root.getId().toString().replace("-", ""), path);
    }

    /**
     * An issue, page or pull request that answers 404 is gone. It was a FAILED item, fetched again
     * three times and searchable until the next complete listing - up to a day.
     */
    @Test
    @DisplayName("an item gone at the source is removed at once, not failed and retried")
    void goneItemIsRemoved() {
        String key = "ISSUE-PROJ-7";
        TrackedDocument doc = tracked(source.getId(), key, TrackedDocument.Status.QUEUED);
        sync.processItem(ConnectorType.JIRA, doc, collection(), sync.toMinimalItemMetadata(doc), () -> {
            throw new ai.proxyma.core.connector.ItemGoneException("Issue PROJ-7 no longer exists");
        });

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.DELETED);
        assertThat(doc.getNextRetryAt()).isNull();
        verify(embeddings).deleteBySourceId(collection(), key);
    }

    @Test
    @DisplayName("a file deleted while a drain indexes it is not saved back as indexed")
    void deletedDuringIndexingStaysDeleted() {
        String path = "https://example.test/page";
        TrackedDocument doc = tracked(source.getId(), path, TrackedDocument.Status.QUEUED);
        when(documents.findAllBySourceId(path)).thenReturn(List.of(doc));
        when(converter.convertToMarkdown(any(), any())).thenReturn("Some text worth indexing.");
        // The owner deletes the file while its chunks are being written.
        doAnswer(inv -> {
            EmbeddingService.ChunkProgressCallback progress = inv.getArgument(7);
            progress.onProgress(1, 1);
            sync.deleteTrackedDocumentBySourceId(path);
            return List.of();
        }).when(embeddings).chunkAndEmbed(anyString(), eq(path), anyString(), any(), anyInt(), any(), any(), any());

        ItemMetadata meta = sync.toMinimalItemMetadata(doc);
        sync.processItem(ConnectorType.WEB_PAGE, doc, collection(), meta,
                () -> new ByteArrayInputStream("<p>Some text</p>".getBytes(StandardCharsets.UTF_8)));

        assertThat(doc.getStatus()).isEqualTo(TrackedDocument.Status.DELETED);
        // Once when deleted mid-run, once more for what the run wrote after it.
        verify(embeddings, org.mockito.Mockito.times(2)).deleteBySourceId(collection(), path);
    }
}
