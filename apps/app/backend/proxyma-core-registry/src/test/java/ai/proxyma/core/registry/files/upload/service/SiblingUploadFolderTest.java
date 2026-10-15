package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.files.upload.storage.LocalDiskStorageService;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.SyncService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Removing an upload source deletes ITS files and nobody else's.
 *
 * <p>The rows are found with a text prefix, and {@code .../uploads/Reports} is a text prefix of
 * {@code .../uploads/Reports 2024/q1.pdf}. Until 2026-10-14 removing "Reports" deleted the bytes,
 * the row and the indexed document of every file in "Reports 2024" as well. Run against H2 with the
 * repository that ships, because the over-match is the query's, and a stubbed query cannot show it.
 */
class SiblingUploadFolderTest {

    @TempDir
    Path storageRoot;

    private SessionFactory sessions;
    private LocalDiskStorageService storage;
    private final SyncService sync = mock(SyncService.class);

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(FileMetadata.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:sibling-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        storage = new LocalDiskStorageService();
        ReflectionTestUtils.setField(storage, "storageRoot", storageRoot.toString());
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private void tx(Consumer<FileService> work) {
        EntityManager em = sessions.createEntityManager();
        try {
            em.getTransaction().begin();
            FileMetadataRepository rows = new JpaRepositoryFactory(em).getRepository(FileMetadataRepository.class);
            work.accept(new FileService(rows, storage, mock(TrackedDocumentRepository.class), null, sync, null));
            em.getTransaction().commit();
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    private List<FileMetadata> allRows() {
        return sessions.fromTransaction(s -> s.createSelectionQuery("from FileMetadata", FileMetadata.class).getResultList());
    }

    private String upload(FileService files, String owner, String folder, String name) {
        FileMetadata meta = files.upload(name, "text/plain", 5,
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), owner, folder, null);
        return meta.getStoragePath();
    }

    @Test
    @DisplayName("removing 'Reports' leaves 'Reports 2024' - its bytes, its row and its document")
    void siblingWithTheSamePrefixIsKept() {
        String[] paths = new String[3];
        tx(files -> {
            paths[0] = upload(files, "u1", "Reports", "a.pdf");
            paths[1] = upload(files, "u1", "Reports 2024", "q1.pdf");
            paths[2] = upload(files, "u1", "Q10", "x.pdf");
        });
        String reportsFolder = storage.ensureFolder("u1", "Reports");

        tx(files -> files.deleteFilesInFolder(reportsFolder, "u1"));

        assertThat(Path.of(paths[0])).doesNotExist();
        assertThat(Path.of(paths[1])).exists();
        assertThat(allRows()).extracting(FileMetadata::getStoragePath).containsExactlyInAnyOrder(paths[1], paths[2]);
        verify(sync).deleteTrackedDocumentBySourceId(paths[0]);
        verify(sync, never()).deleteTrackedDocumentBySourceId(paths[1]);
    }

    /**
     * The path columns had no length - 255 characters - while a stored path is the sandbox, the
     * folder, its subfolders and the name: a folder dropped a few levels deep failed the row's
     * INSERT after its bytes were on disk.
     */
    @Test
    @DisplayName("a file several folders deep is stored and recorded; one too deep is refused before any byte lands")
    void deepFoldersAreRecorded() {
        String deep = "Quarterly reports/2026/Q3/Customer correspondence/Harrowmere Freight Group/"
                + "Contract amendments and side letters/Signed copies received from legal/Archive/"
                // Long enough past 255 under the short temp folder of a Linux host too, not only
                // under Windows' long one (it came to 238 on the CI runner).
                + "Second review by the finance team";
        String[] stored = new String[1];
        tx(files -> {
            stored[0] = files.upload("amendment-no-4-signed.pdf", "application/pdf", 5,
                    new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), "u1", "Contracts", deep)
                    .getStoragePath();
        });
        assertThat(stored[0].length()).isGreaterThan(255);
        assertThat(allRows()).extracting(FileMetadata::getStoragePath).containsExactly(stored[0]);

        String tooDeep = "x".repeat(FileMetadata.NAME_MAX + 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> tx(files ->
                files.upload("a.pdf", "application/pdf", 5,
                        new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), "u1", "Contracts", tooDeep)));
        assertThat(allRows()).hasSize(1);
    }

    @Test
    @DisplayName("a file uploaded by somebody else is never deleted with this owner's source")
    void anotherOwnersFileIsKept() {
        String folder = storage.ensureFolder("u1", "Shared");
        FileMetadata foreign = new FileMetadata();
        foreign.setStoragePath(Path.of(folder).resolve("theirs.pdf").toString());
        foreign.setOriginalName("theirs.pdf");
        foreign.setMimeType("application/pdf");
        foreign.setUploadedAt(Instant.now());
        foreign.setUploadedByUserId("u2");
        sessions.inTransaction(s -> s.persist(foreign));

        tx(files -> files.deleteFilesInFolder(folder, "u1"));

        assertThat(allRows()).hasSize(1);
    }
}
