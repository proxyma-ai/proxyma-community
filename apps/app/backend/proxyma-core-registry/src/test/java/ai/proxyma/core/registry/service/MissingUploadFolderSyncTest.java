package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.UploadedFilesConnector;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An upload source whose folder is gone, on a server that purges every upload once it is indexed.
 *
 * <p>Until 2026-10-10 indexing a source's last file also removed the source's folder, so every
 * installation that ran such a build holds fully indexed sources with no folder. Each of their syncs
 * failed with "no longer on the server" while every document was indexed and searchable. A missing
 * folder is an empty listing when nothing tracked there still needs its bytes, and the failure it
 * always was when something does.
 *
 * <p>The real connector, against a real directory: the decision rests on the connector's TYPED
 * report of the absence, and a stub could not show that the two still agree.
 */
class MissingUploadFolderSyncTest {

    private static final String THE_ERROR =
            "This source's uploaded files are no longer on the server - upload them again.";

    @TempDir
    Path storageRoot;

    private TrackedDocumentRepository documents;
    private RegisteredConnectorRepository connectors;
    private EmbeddingService embeddings;
    private SyncService sync;
    private RegisteredConnector source;
    private UploadedFilesConnector connector;
    private ConnectorSession session;
    private Path folder;

    @BeforeEach
    void setUp() {
        documents = mock(TrackedDocumentRepository.class);
        embeddings = mock(EmbeddingService.class);
        connectors = mock(RegisteredConnectorRepository.class);
        sync = new SyncService(documents, connectors, null, embeddings, mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));

        folder = storageRoot.resolve("users").resolve("u-42").resolve("uploads").resolve("Contracts")
                .toAbsolutePath().normalize();

        source = new RegisteredConnector();
        source.setId(UUID.randomUUID());
        source.setConnectorType(ConnectorType.UPLOADED_FILES);
        source.setConfig(folder.toString());
        // As the sandbox showed it: red, with the sentence, over documents that were all indexed.
        source.setStatus(RegisteredConnector.Status.ERROR);
        source.setLastSyncError(THE_ERROR);
        when(connectors.findById(source.getId())).thenReturn(Optional.of(source));

        connector = new UploadedFilesConnector(storageRoot.toString());
        session = sessionOver(folder, "u-42");
    }

    // A session of the real connector over one folder, for one owner - what SourceContextFactory
    // builds from the source's row.
    private ConnectorSession sessionOver(Path path, String owner) {
        return connector.open(new SourceContext(source.getId(), ConnectorType.UPLOADED_FILES, owner, null,
                Map.of("path", path.toString()), path.toString(), null, null, SourceScope.defaults(), null, null));
    }

    private TrackedDocument tracked(String name, TrackedDocument.Status status, boolean contentPurged) {
        TrackedDocument doc = new TrackedDocument();
        doc.setId(UUID.randomUUID());
        doc.setSourceId(folder.resolve(name).toString());
        doc.setConnectorId(source.getId());
        doc.setConnectorType(ConnectorType.UPLOADED_FILES);
        doc.setStatus(status);
        doc.setContentPurged(contentPurged);
        return doc;
    }

    @Test
    @DisplayName("every document indexed and purged: no error, nothing deleted, the folder back")
    void allPurgedIsAnEmptyListing() {
        TrackedDocument msa = tracked("msa.pdf", TrackedDocument.Status.COMPLETED, true);
        TrackedDocument sow = tracked("sow.docx", TrackedDocument.Status.COMPLETED, true);
        TrackedDocument gone = tracked("old.txt", TrackedDocument.Status.DELETED, false);
        when(documents.findByConnectorId(source.getId())).thenReturn(List.of(msa, sow, gone));

        sync.reconcile(session, connector.descriptor(), source, true);

        // Written as a targeted update of the status columns, never by saving the row back.
        verify(connectors).updateSyncState(source.getId(), RegisteredConnector.Status.ACTIVE, null);
        assertEquals(TrackedDocument.Status.COMPLETED, msa.getStatus());
        assertEquals(TrackedDocument.Status.COMPLETED, sow.getStatus());
        verify(embeddings, never()).deleteBySourceId(any(), any());
        assertTrue(Files.isDirectory(folder), "the folder was not put back for the next upload");
    }

    @Test
    @DisplayName("one document still needing its file: the source still says its files are gone")
    void oneUnpurgedDocumentIsStillAnError() {
        TrackedDocument msa = tracked("msa.pdf", TrackedDocument.Status.COMPLETED, true);
        TrackedDocument queued = tracked("sow.docx", TrackedDocument.Status.QUEUED, false);
        when(documents.findByConnectorId(source.getId())).thenReturn(List.of(msa, queued));

        sync.reconcile(session, connector.descriptor(), source, true);

        verify(connectors).updateSyncState(source.getId(), RegisteredConnector.Status.ERROR, THE_ERROR);
        assertEquals(TrackedDocument.Status.QUEUED, queued.getStatus());
        verify(embeddings, never()).deleteBySourceId(any(), any());
        assertFalse(Files.exists(folder), "a folder whose files really are missing must not be papered over");
    }

    @Test
    @DisplayName("a source pointing outside every upload sandbox is refused, purged documents or not")
    void outsideTheSandboxIsStillRefused() {
        Path outside = storageRoot.resolve("keys").toAbsolutePath().normalize();
        session = sessionOver(outside, null);
        when(documents.findByConnectorId(source.getId()))
                .thenReturn(List.of(tracked("msa.pdf", TrackedDocument.Status.COMPLETED, true)));

        sync.reconcile(session, connector.descriptor(), source, true);

        verify(connectors).updateSyncState(any(), org.mockito.ArgumentMatchers.eq(RegisteredConnector.Status.ERROR), any());
        assertFalse(Files.exists(outside), "nothing may be created outside the sandbox");
    }
}
