package ai.proxyma.core.registry.files.upload.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upload source's folder outlives its last file, and goes with the source.
 *
 * <p>Enterprise Server deletes an upload's bytes as soon as it is indexed. Deleting a file climbed
 * up removing every directory left empty, the named upload folder included - so indexing the last
 * file of a source deleted the folder the source is registered at, and the next sync of every fully
 * indexed upload source failed with "no longer on the server" while all of its documents were
 * searchable (the Enterprise sandbox, 2026-10-10: ten sources, every folder gone).
 */
class UploadFolderKeptTest {

    private static final String USER = "u-42";
    private static final String FOLDER = "Contracts";

    @TempDir
    Path storageRoot;

    private LocalDiskStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalDiskStorageService();
        ReflectionTestUtils.setField(storage, "storageRoot", storageRoot.toString());
    }

    private String store(String userId, String folder, String relativePath, String name) {
        return storage.store(UUID.randomUUID(), name,
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), 5,
                userId, folder, relativePath);
    }

    // Read before the delete, never after it: ensureFolder() creates the folder, so asking it
    // afterwards would put back the very directory the assertion is about.
    private Path folderOf(String userId, String folder) {
        return Path.of(storage.ensureFolder(userId, folder));
    }

    @Test
    @DisplayName("deleting a source's last file keeps the source's folder")
    void lastFileKeepsTheFolder() {
        String only = store(USER, FOLDER, null, "msa.pdf");
        Path folder = folderOf(USER, FOLDER);

        storage.delete(only, USER, FOLDER);

        assertFalse(Files.exists(Path.of(only)));
        assertTrue(Files.isDirectory(folder), "the source's folder was removed with its last file");
    }

    @Test
    @DisplayName("the directories a deletion empties INSIDE the folder go, the folder stays")
    void emptiedSubdirectoriesGo() {
        String nested = store(USER, FOLDER, "2026/q3", "renewal.docx");
        Path folder = folderOf(USER, FOLDER);

        storage.delete(nested, USER, FOLDER);

        assertFalse(Files.exists(folder.resolve("2026/q3")));
        assertFalse(Files.exists(folder.resolve("2026")));
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    @DisplayName("the single local user's folder is kept the same way")
    void localUserFolderKept() {
        String only = store(null, FOLDER, null, "notes.md");

        storage.delete(only, null, FOLDER);

        assertTrue(Files.isDirectory(storageRoot.resolve("uploads").resolve(FOLDER)));
    }

    @Test
    @DisplayName("deleting a SOURCE still removes its folder and everything in it")
    void deletingTheSourceRemovesTheFolder() {
        store(USER, FOLDER, "2026", "renewal.docx");
        Path folder = folderOf(USER, FOLDER);

        storage.deleteFolderRecursively(USER, folder.toString());

        assertFalse(Files.exists(folder));
        assertTrue(Files.isDirectory(storageRoot.resolve("users").resolve(USER).resolve("uploads")),
                "the upload root itself is never removed");
    }

    @Test
    @DisplayName("a path outside every upload sandbox is still left untouched")
    void outsideTheSandboxUntouched() throws Exception {
        Path keys = Files.createDirectories(storageRoot.resolve("keys"));
        Path key = Files.writeString(keys.resolve("signing.pem"), "secret");

        storage.delete(key.toString(), USER, FOLDER);

        assertTrue(Files.exists(key));
    }
}
