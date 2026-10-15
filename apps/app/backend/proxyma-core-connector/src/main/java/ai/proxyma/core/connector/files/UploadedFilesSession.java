package ai.proxyma.core.connector.files;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.FailedSyncIterator;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One upload source - see {@link UploadedFilesConnector}. Holds the folder, its owner and its
 * scope for one pass, and nothing another source can see.
 */
final class UploadedFilesSession implements ConnectorSession {

    private static final Logger log = LoggerFactory.getLogger(UploadedFilesSession.class);

    private final String storageRoot;
    // The source's OWNER - whose sandbox its path must be in. Null is the single local user.
    private final String ownerUserId;
    private final String configuredPath;
    private final boolean recursive;
    private final Set<String> enabledExtensions;
    private final int maxFiles;

    UploadedFilesSession(String storageRoot, String ownerUserId, String path, SourceScope scope) {
        this.storageRoot = storageRoot;
        this.ownerUserId = ownerUserId;
        this.configuredPath = path;
        this.recursive = scope.recursive();
        this.enabledExtensions = FileTypeSupport.parseFileTypes(scope.fileTypes());
        this.maxFiles = scope.maxItems() == null || scope.maxItems() <= 0 ? FileTypeSupport.UNLIMITED : scope.maxItems();
    }

    /**
     * The sandbox question, asked before anything is stored rather than at the first sync hours
     * later. A stat call and a prefix comparison - no network, no credential.
     *
     * <p>A sandbox directory that does not exist YET is verified, not refused: the upload flow
     * creates it as the first file lands, so registering the source before any bytes arrive is the
     * normal order and refusing it would block the only way to put files there. What this probe is
     * for is the path that will never be right - one outside every sandbox, or one that already
     * exists as a file.
     */
    @Override
    public AccessCheck verifyAccess() {
        String folder = this.configuredPath;
        if (folder == null || folder.isBlank()) {
            return AccessCheck.failed("This source has no upload folder - re-create it and upload the files again.");
        }
        Path root;
        try {
            root = Path.of(folder);
        } catch (InvalidPathException e) {
            return AccessCheck.failed("This source's upload folder is not a usable path on this server.");
        }
        // No path in any message below, unlike LocalFolderSession's: this edition runs on a machine
        // the user is not sitting at, so the path is the SERVER's and naming it would put an absolute
        // server path on a card in their browser. See .claude/rules/security.md.
        if (!isInsideOwnersSandbox(root)) {
            return AccessCheck.failed("This source does not point at an upload folder Proxyma owns, "
                    + "so it cannot be indexed. Re-create it and upload the files again.");
        }
        if (Files.exists(root) && !Files.isDirectory(root)) {
            return AccessCheck.failed("This source's upload folder is not a folder. "
                    + "Re-create it and upload the files again.");
        }
        return AccessCheck.verified();
    }

    // As for a local folder, every failure path returns a failed listing rather than an empty one -
    // an empty listing reads as "this source holds nothing", which marks the source ACTIVE with no
    // error and turns every document tracked here into a purge candidate. Spec 00 section 3.4. The
    // sandbox refusal is the sharpest case: a row pointing outside the sandbox would silently delete
    // that source's whole index instead of saying anything.
    //
    // None of these messages name the path. It is a server path and lastSyncError is read in a
    // browser - see verifyAccess() above.
    @Override
    public Listing list(ListRequest request) {
        String folder = this.configuredPath;
        if (folder == null || folder.isBlank()) {
            return Listings.of(new FailedSyncIterator(
                    "This source has no upload folder - re-create it and upload the files again."));
        }
        Path root;
        try {
            root = Path.of(folder);
        } catch (InvalidPathException e) {
            return Listings.of(new FailedSyncIterator("This source's upload folder is not a usable path on this server."));
        }
        if (!isInsideOwnersSandbox(root)) {
            // Logged as an error rather than a warning: a registered source pointing outside the
            // sandbox is not a user mistake, it is a row that should not exist.
            log.error("[UploadedFiles] Refusing to walk {} - outside its owner's upload sandbox", folder);
            return Listings.of(new FailedSyncIterator("This source does not point at an upload folder Proxyma owns, "
                    + "so nothing was indexed. Re-create it and upload the files again."));
        }
        // ABSENT, rather than present as something else, is not this session's to call an error: a
        // source whose every document is indexed and purged has nothing it needs on disk, and whether
        // that is so is a question about tracked documents, which only SyncService can ask. So the
        // absence is reported by TYPE - read as a plain failure by anything that does not know the
        // type - and never by a sentence a caller would have to match.
        if (!Files.exists(root)) {
            log.warn("[UploadedFiles] Upload folder is missing: {}", folder);
            return Listings.of(new MissingUploadFolder(storageRoot, root,
                    "This source's uploaded files are no longer on the server - upload them again."));
        }
        if (!Files.isDirectory(root)) {
            log.warn("[UploadedFiles] Configured path is not a directory: {}", folder);
            return Listings.of(new FailedSyncIterator(
                    "This source's uploaded files are no longer on the server - upload them again."));
        }
        int limit = this.maxFiles;
        try {
            List<ItemMetadata> items = FileTypeSupport.walk(
                    root, this.enabledExtensions, this.recursive, limit, ConnectorType.UPLOADED_FILES);
            if (limit != FileTypeSupport.UNLIMITED && items.size() >= limit) {
                log.warn("[UploadedFiles] Stopped at the {}-file limit for {} - this folder may contain more",
                        limit, folder);
                // Partial, not errored - see LocalFolderSession.list() for the same case: the files
                // past the ceiling were never listed, so nothing here is evidence they went away, and
                // purging on that evidence would delete them on every run.
                return Listings.of(new PartialFileListIterator(items,
                        "stopped at the " + limit + "-file limit, so this source may hold more"));
            }
            log.info("[UploadedFiles] Discovered {} files in {}", items.size(), folder);
            return Listings.of(items);
        } catch (IOException e) {
            log.error("[UploadedFiles] Failed to walk directory {}: {}", folder, e.getMessage());
            return Listings.of(new FailedSyncIterator(
                    "Could not read this source's uploaded files: " + SyncErrorAware.summarize(e)));
        }
    }

    // Inside the OWNER's upload sandbox - {storageRoot}/users/{owner}/uploads/** where there are
    // accounts, {storageRoot}/uploads/** for the single local user, who is a null owner.
    //
    // WHOSE sandbox is the point, since 2026-10-14. This asked isAnyUploadPath - "inside SOME
    // user's sandbox", which UploadSandbox documents as NOT an access check - so a row owned by one
    // user and pointing into another's uploads passed every sync and indexed that user's files into
    // the first one's source: the cross-tenant protection the plugin's javadoc describes, unenforced.
    // UploadSandbox.contains is the question registration asks; this is the second line behind it.
    private boolean isInsideOwnersSandbox(Path candidate) {
        try {
            return UploadSandbox.contains(storageRoot, ownerUserId, candidate);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @Override
    public InputStream fetch(ItemMetadata item) {
        return FileTypeSupport.openForReading(item.getId());
    }
}
