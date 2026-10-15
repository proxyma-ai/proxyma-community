package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An upload source's listing that could not start because its folder is not on the server - and
 * that SAYS so by its type, so the one caller able to judge it can.
 *
 * <p><b>Why a missing folder is not simply an error.</b> Enterprise Server deletes an upload's
 * bytes once it is indexed, keeping the converted text (SyncService's post-COMPLETED purge), and
 * until 2026-10-10 deleting the last file of a source also deleted the source's folder. Every
 * fully indexed upload source on every Enterprise Server then failed its next sync with "no longer
 * on the server" while all of its documents were indexed and searchable. A missing folder means
 * two different things, and this connector cannot tell them apart: it has no access to the tracked
 * documents. SyncService does - when nothing it tracks there still expects bytes, an absent folder
 * is an empty one.
 *
 * <p>Read without knowing the type it is exactly a {@link ai.proxyma.core.connector.FailedSyncIterator}:
 * no items and an error. So the safe reading is the default one, and only a caller that has checked
 * the documents turns it into an empty listing.
 *
 * <p>Built only by {@link UploadedFilesSession}, after its sandbox check - the constructor is
 * package-private so that no instance can name a folder outside an upload sandbox. A path outside
 * one is still refused with a plain failure and never reaches this type. {@link #recreateEmpty()}
 * checks again anyway, because it creates a directory on the server.
 */
public final class MissingUploadFolder implements Iterator<ItemMetadata>, SyncErrorAware {

    private final String storageRoot;
    private final Path folder;
    private final String message;

    MissingUploadFolder(String storageRoot, Path folder, String message) {
        this.storageRoot = storageRoot;
        this.folder = folder;
        this.message = message;
    }

    /**
     * Puts the folder back, empty, so the next upload into this source has somewhere to land and
     * the next sync lists it rather than asking the documents again.
     *
     * @return whether the folder exists afterwards
     */
    public boolean recreateEmpty() {
        if (!UploadSandbox.isAnyUploadPath(storageRoot, folder)) return false;
        try {
            Files.createDirectories(folder);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public boolean hasNext() { return false; }
    @Override public ItemMetadata next() { throw new NoSuchElementException(); }
    @Override public boolean hasErrored() { return true; }
    @Override public String getErrorMessage() { return message; }
}
