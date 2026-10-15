package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the upload connector reports when a source's folder is not there - by TYPE, so the caller
 * that can see the tracked documents decides, and nobody has to match a sentence.
 *
 * <p>A fully indexed source on Enterprise Server had no folder (indexing its last file removed it,
 * until 2026-10-10), and the plain failure this used to return made every such source red. Only
 * ABSENCE gets the typed report: a path outside every sandbox, or one that is a file, stays a plain
 * failure no caller can turn into an empty listing.
 */
class MissingUploadFolderTest {

    @TempDir
    Path storageRoot;

    private UploadedFilesConnector connector;
    private ConnectorSession session;
    private Path folder;

    @BeforeEach
    void setUp() {
        connector = new UploadedFilesConnector(storageRoot.toString());
        folder = storageRoot.resolve("users").resolve("u-42").resolve("uploads").resolve("Contracts")
                .toAbsolutePath().normalize();
    }

    private Iterator<ItemMetadata> syncOf(Path path) {
        return syncOf(path, "u-42");
    }

    private Iterator<ItemMetadata> syncOf(Path path, String owner) {
        session = connector.open(new SourceContext(null, ConnectorType.UPLOADED_FILES, owner, null,
                Map.of("path", path.toString()), path.toString(), null, null, SourceScope.defaults(), null, null));
        return session.list(ListRequest.full());
    }

    // What SyncService recognizes the listing by - the iterator the session built it over.
    private static Iterator<ItemMetadata> typed(Iterator<ItemMetadata> listing) {
        return Listings.delegateOf(listing);
    }

    /**
     * The per-sync check asked whether a path was inside SOME user's sandbox, so a source owned by
     * one user and pointing into another's uploads was walked and indexed into the first user's
     * source. It is the owner's own sandbox now, as at registration.
     */
    @Test
    @DisplayName("a source pointing into another user's uploads is refused at sync and at the probe")
    void anotherUsersUploadsAreRefused() throws Exception {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("msa.pdf"), "theirs");

        Iterator<ItemMetadata> items = syncOf(folder, "u-1");
        assertFalse(items.hasNext());
        assertTrue(((SyncErrorAware) items).hasErrored());
        assertTrue(session.verifyAccess().rejected());

        assertTrue(syncOf(folder, "u-42").hasNext(), "the owner's own folder still syncs");
    }

    @Test
    @DisplayName("a missing folder inside the sandbox is reported as missing, and still reads as an error")
    void missingFolderIsTyped() {
        Iterator<ItemMetadata> items = syncOf(folder);

        MissingUploadFolder missing = assertInstanceOf(MissingUploadFolder.class, typed(items));
        assertFalse(items.hasNext());
        assertFalse(missing.hasNext());
        assertTrue(((SyncErrorAware) items).hasErrored(), "a caller that does not know the type must see a failure");
    }

    @Test
    @DisplayName("putting it back creates the folder, empty")
    void recreateEmpty() {
        MissingUploadFolder missing = assertInstanceOf(MissingUploadFolder.class, typed(syncOf(folder)));

        assertTrue(missing.recreateEmpty());
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    @DisplayName("a path outside every upload sandbox is a plain failure, never a missing folder")
    void outsideTheSandboxIsNotMissing() {
        Iterator<ItemMetadata> items = syncOf(storageRoot.resolve("keys").toAbsolutePath());

        assertFalse(items.hasNext());
        assertFalse(typed(items) instanceof MissingUploadFolder);
        assertTrue(((SyncErrorAware) items).hasErrored());
    }

    @Test
    @DisplayName("a file where the folder should be is a plain failure, never a missing folder")
    void aFileIsNotMissing() throws Exception {
        Files.createDirectories(folder.getParent());
        Files.writeString(folder, "not a folder");

        Iterator<ItemMetadata> items = syncOf(folder);

        assertFalse(items.hasNext());
        assertFalse(typed(items) instanceof MissingUploadFolder);
        assertTrue(((SyncErrorAware) items).hasErrored());
    }
}
