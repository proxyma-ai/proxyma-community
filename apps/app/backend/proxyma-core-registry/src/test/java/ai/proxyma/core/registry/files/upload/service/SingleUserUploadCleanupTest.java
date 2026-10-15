package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.registry.event.ConnectorRemovedEvent;
import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.files.upload.storage.LocalDiskStorageService;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an upload source removes its uploaded files on the Home Server too.
 *
 * <p>The Home Server takes uploads with no accounts: the single local user is a null owner and the
 * sandbox is {@code {storageRoot}/uploads}. FileCleanupListener returned early unless
 * {@code proxyma.multi-user} was set, so there a deleted source's files and their rows stayed - the
 * bytes until the daily garbage sweep, the rows for good. The first case fails on that code.
 *
 * <p>The rest pin what must NOT change with it: a desktop install, which registers the user's own
 * folders by reference, deletes nothing; a local folder source deletes nothing anywhere; and a
 * source whose folder is not inside the sandbox is refused on its path.
 */
class SingleUserUploadCleanupTest {

    private static final String FOLDER = "Contracts";

    @TempDir
    Path storageRoot;

    @TempDir
    Path usersOwnDisk;

    private LocalDiskStorageService storage;
    private final FileMetadataRepository rows = mock(FileMetadataRepository.class);
    private FileService fileService;

    @BeforeEach
    void setUp() {
        storage = new LocalDiskStorageService();
        ReflectionTestUtils.setField(storage, "storageRoot", storageRoot.toString());
        fileService = new FileService(rows, storage, mock(TrackedDocumentRepository.class), null,
                mock(SyncService.class), null);
        when(rows.findByStoragePathStartingWith(anyString())).thenReturn(List.of());
    }

    private static FileSourceMode homeServer() {
        return new FileSourceMode(new MockEnvironment().withProperty(FileSourceMode.FILE_UPLOAD_PROPERTY, "true"));
    }

    private static FileSourceMode desktop() {
        return new FileSourceMode(new MockEnvironment());
    }

    private String upload(String relativePath, String name) {
        return storage.store(UUID.randomUUID(), name,
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), 5,
                null, FOLDER, relativePath);
    }

    private static ConnectorRemovedEvent removed(ConnectorType type, String config) {
        return new ConnectorRemovedEvent(new Object(), UUID.randomUUID(), type, config, null, false);
    }

    @Test
    @DisplayName("Home Server: deleting an upload source removes its folder, its files and their rows")
    void homeServerRemovesTheUploads() {
        String file = upload(null, "msa.pdf");
        String nested = upload("2026/q3", "renewal.docx");
        Path folder = Path.of(storage.ensureFolder(null, FOLDER));
        assertThat(folder).startsWith(storageRoot.resolve("uploads"));
        FileMetadata row = new FileMetadata();
        row.setStoragePath(file);
        row.setFolder(FOLDER);
        when(rows.findByStoragePathStartingWith(folder.toString())).thenReturn(List.of(row));

        new FileCleanupListener(fileService, homeServer())
                .onConnectorRemoved(removed(ConnectorType.UPLOADED_FILES, folder.toString()));

        assertThat(Path.of(file)).doesNotExist();
        assertThat(Path.of(nested)).doesNotExist();
        assertThat(folder).doesNotExist();
        verify(rows).delete(row);
        // The sandbox itself stays, ready for the next source.
        assertThat(storageRoot.resolve("uploads")).isDirectory();
    }

    @Test
    @DisplayName("Home Server: another source's folder is untouched")
    void otherSourcesStay() {
        upload(null, "msa.pdf");
        Path folder = Path.of(storage.ensureFolder(null, FOLDER));
        String other = storage.store(UUID.randomUUID(), "plan.xlsx",
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), 5, null, "Plans", null);

        new FileCleanupListener(fileService, homeServer())
                .onConnectorRemoved(removed(ConnectorType.UPLOADED_FILES, folder.toString()));

        assertThat(folder).doesNotExist();
        assertThat(Path.of(other)).exists();
    }

    @Test
    @DisplayName("Home Server: a source whose folder is outside the sandbox deletes nothing")
    void outsideTheSandboxIsRefused() throws Exception {
        Path mine = Files.writeString(usersOwnDisk.resolve("notes.txt"), "mine");

        new FileCleanupListener(fileService, homeServer())
                .onConnectorRemoved(removed(ConnectorType.UPLOADED_FILES, usersOwnDisk.toString()));

        assertThat(mine).exists();
        // The upload root itself is never a source's folder to remove.
        Files.createDirectories(storageRoot.resolve("uploads"));
        new FileCleanupListener(fileService, homeServer())
                .onConnectorRemoved(removed(ConnectorType.UPLOADED_FILES, storageRoot.resolve("uploads").toString()));
        assertThat(storageRoot.resolve("uploads")).isDirectory();
    }

    @Test
    @DisplayName("a local folder source deletes nothing, whatever the edition")
    void localFolderIsNeverTouched() throws Exception {
        Path mine = Files.writeString(usersOwnDisk.resolve("notes.txt"), "mine");

        for (FileSourceMode mode : List.of(homeServer(), desktop())) {
            new FileCleanupListener(fileService, mode)
                    .onConnectorRemoved(removed(ConnectorType.LOCAL_FOLDER, usersOwnDisk.toString()));
        }

        assertThat(mine).exists();
    }

    @Test
    @DisplayName("Desktop: takes no uploads, so it deletes nothing")
    void desktopDeletesNothing() {
        String file = upload(null, "msa.pdf");
        Path folder = Path.of(file).getParent();

        new FileCleanupListener(fileService, desktop())
                .onConnectorRemoved(removed(ConnectorType.UPLOADED_FILES, folder.toString()));

        assertThat(Path.of(file)).exists();
    }
}
