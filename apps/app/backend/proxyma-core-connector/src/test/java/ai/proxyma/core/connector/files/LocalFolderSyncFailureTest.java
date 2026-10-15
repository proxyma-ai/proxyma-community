package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The difference between "this folder is empty" and "there is no folder", which used to be no
 * difference at all.
 *
 * <p>Both answered with {@code Collections.emptyIterator()}, so SyncService saw a listing that
 * finished cleanly and found nothing, marked the source ACTIVE with no error, and every document
 * tracked against it became a purge candidate. A network drive that failed to mount would have
 * emptied its own index and reported success. Spec 00 section 3.4 calls the rule these violated the
 * single most important one in that file, so this test asserts on the CAPABILITY SyncService reads -
 * {@code hasErrored()} - and not on the iterator being empty, which both cases still are.
 */
class LocalFolderSyncFailureTest {

    private static ConnectorSession connectorFor(String path) {
        Map<String, String> config = new HashMap<>();
        if (path != null) config.put("path", path);
        return new LocalFolderConnector().open(new SourceContext(null, ConnectorType.LOCAL_FOLDER, null, null,
                config, path, null, null, new SourceScope(null, true, null), null, null));
    }

    private static SyncErrorAware errorAware(Iterator<ItemMetadata> it) {
        return assertInstanceOf(SyncErrorAware.class, it,
                "an iterator that listed nothing because it could not list at all must report how it ended");
    }

    @Test
    @DisplayName("an unconfigured folder reports an error, not an empty success")
    void unconfiguredFolderHasErrored() {
        Iterator<ItemMetadata> it = connectorFor(null).list(ListRequest.full());

        assertFalse(it.hasNext());
        SyncErrorAware aware = errorAware(it);
        assertTrue(aware.hasErrored());
        assertNotNull(aware.getErrorMessage(), "the message is what the user is shown on the source's card");
    }

    @Test
    @DisplayName("a folder that is not there reports an error, not an empty success")
    void missingFolderHasErrored(@TempDir Path tempDir) {
        Iterator<ItemMetadata> it = connectorFor(tempDir.resolve("gone").toString()).list(ListRequest.full());

        assertFalse(it.hasNext());
        assertTrue(errorAware(it).hasErrored());
    }

    @Test
    @DisplayName("a folder that really is empty is a clean, complete listing")
    void genuinelyEmptyFolderIsNotAnError(@TempDir Path tempDir) throws Exception {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));

        Iterator<ItemMetadata> it = connectorFor(empty.toString()).list(ListRequest.full());

        assertFalse(it.hasNext());
        // The other half of the rule: this one MUST stay purge-eligible, or a folder a user really
        // did empty would keep its documents in the index forever.
        assertFalse(it instanceof SyncErrorAware aware && aware.hasErrored());
    }

    @Test
    @DisplayName("verifyAccess refuses a folder that is not there and passes one that is")
    void verifyAccessProbesTheFolder(@TempDir Path tempDir) throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));

        assertTrue(connectorFor(real.toString()).verifyAccess().ok());

        AccessCheck missing = connectorFor(tempDir.resolve("gone").toString()).verifyAccess();
        assertTrue(missing.rejected());
        assertNotNull(missing.detail());

        assertTrue(connectorFor(null).verifyAccess().rejected());
    }
}
