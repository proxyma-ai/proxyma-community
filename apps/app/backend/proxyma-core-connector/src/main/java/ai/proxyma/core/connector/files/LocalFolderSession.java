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
 * One local folder source - see {@link LocalFolderConnector}. Holds the folder and its scope
 * for one pass, and nothing another source can see.
 */
final class LocalFolderSession implements ConnectorSession {

    private static final Logger log = LoggerFactory.getLogger(LocalFolderSession.class);

    private final String configuredPath;
    // Defaults to true (recursive) when absent - matches RegisteredConnector.recursive's own
    // default, so a source registered with no subfolder setting keeps the full-tree walk.
    private final boolean recursive;
    // null (absent or blank, and every source registered before fileTypes existed) means "every
    // known extension" - see FileTypeSupport.isSupportedFileType.
    private final Set<String> enabledExtensions;
    // Ceiling on how many files one pass reports, from the source's scope. UNLIMITED (the default)
    // means no ceiling. This type deliberately does not know what a tier is - it takes a number and
    // applies it.
    private final int maxFiles;

    LocalFolderSession(String path, SourceScope scope) {
        this.configuredPath = path;
        this.recursive = scope.recursive();
        this.enabledExtensions = FileTypeSupport.parseFileTypes(scope.fileTypes());
        this.maxFiles = scope.maxItems() == null || scope.maxItems() <= 0 ? FileTypeSupport.UNLIMITED : scope.maxItems();
    }

    /**
     * "Does this path exist and can I read it" - the whole probe, and it costs a stat call. No
     * network, no credential: the desktop edition is this user's own machine and the path is the one
     * they just typed into the form, so the only thing worth telling them is that it is not there.
     */
    @Override
    public AccessCheck verifyAccess() {
        String folder = this.configuredPath;
        if (folder == null || folder.isBlank()) {
            return AccessCheck.failed("No folder was chosen - pick the folder you want indexed.");
        }
        try {
            Path root = Path.of(folder);
            if (!Files.isDirectory(root)) {
                return AccessCheck.failed(
                        "There is no folder at " + folder + " - check the path and try again.");
            }
            if (!Files.isReadable(root)) {
                return AccessCheck.failed(
                        "Proxyma is not allowed to read " + folder + " - check the folder's permissions.");
            }
            return AccessCheck.verified();
        } catch (InvalidPathException e) {
            // Never throws, per the contract: a path this operating system cannot even parse is an
            // answer the person who typed it needs, not an exception for a caller to translate.
            return AccessCheck.failed(folder + " is not a valid folder path on this machine.");
        }
    }

    // Any readable directory on this machine is a valid root here - the desktop edition is
    // single-user and has no sandbox to stay inside, which is precisely why it must never write.
    // ConnectorService.requireAllowedLocalPath is the server-side counterpart and is a no-op in this
    // edition, deliberately.
    //
    // EVERY FAILURE PATH BELOW RETURNS A FAILED LISTING, NEVER AN EMPTY ONE. SyncService cannot tell
    // an empty listing that means "this folder is unconfigured / gone / unreadable" from one that
    // means "this folder is genuinely empty", so it marked the source ACTIVE with no error and every
    // document it had ever tracked here became a purge candidate - a disconnected network drive would
    // have emptied the index and reported success. Spec 00 section 3.4.
    //
    // The folder path goes into the message on purpose, and only here: this edition is single-user,
    // on the user's own machine, and the path is the one they typed. UploadedFilesSession's
    // equivalent messages deliberately name no path at all, because there the path is the server's.
    //
    // Always the whole folder: a walk is the only listing a folder has, so a CHANGES pass is a full one.
    @Override
    public Listing list(ListRequest request) {
        String folder = this.configuredPath;
        if (folder == null || folder.isBlank()) {
            return Listings.of(new FailedSyncIterator(
                    "No folder is configured for this source - pick the folder you want indexed."));
        }
        Path root;
        try {
            root = Path.of(folder);
        } catch (InvalidPathException e) {
            return Listings.of(new FailedSyncIterator(folder + " is not a valid folder path on this machine."));
        }
        if (!Files.isDirectory(root)) {
            log.warn("[LocalFolder] Configured path is not a directory: {}", folder);
            return Listings.of(new FailedSyncIterator("There is no folder at " + folder + " any more - it may have "
                    + "been moved, renamed, or be on a drive that is not connected."));
        }
        int limit = this.maxFiles;
        try {
            List<ItemMetadata> items = FileTypeSupport.walk(
                    root, this.enabledExtensions, this.recursive, limit, ConnectorType.LOCAL_FOLDER);
            if (limit != FileTypeSupport.UNLIMITED && items.size() >= limit) {
                log.warn("[LocalFolder] Stopped at the {}-file limit for {} - this folder may contain more",
                        limit, folder);
                // Partial, not errored: the ceiling is doing its job. But the files past it were
                // never listed, and a listing that did not reach them is no evidence they were
                // deleted - without this, every sync would purge whatever fell beyond the limit.
                return Listings.of(new PartialFileListIterator(items,
                        "stopped at the " + limit + "-file limit, so this folder may hold more"));
            }
            log.info("[LocalFolder] Discovered {} files in {}", items.size(), folder);
            return Listings.of(items);
        } catch (IOException e) {
            log.error("[LocalFolder] Failed to walk directory {}: {}", folder, e.getMessage());
            return Listings.of(new FailedSyncIterator(
                    "Could not read " + folder + ": " + SyncErrorAware.summarize(e)));
        }
    }

    // Resolves purely from the item's id (the absolute path the walk listed) - never from this
    // session's folder, so a fetch batch reads what the listing named.
    @Override
    public InputStream fetch(ItemMetadata item) {
        return FileTypeSupport.openForReading(item.getId());
    }
}
