package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.registry.event.ConnectorRemovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes the bytes an UPLOADED Files connector owns, once that connector is deleted.
 *
 * Only where Proxyma takes uploads at all - Enterprise Server, and the Home Server, which has no
 * accounts and keeps the single local user's uploads under {@code {storageRoot}/uploads}
 * (UploadSandbox). On a desktop install a Files connector is a folder that already exists on the
 * user's own disk, registered by reference and indexed in place - Proxyma never copies it, and
 * deleting the connector means "stop indexing this", never "delete these files". The user's own
 * documents are not ours to remove, and a connector deletion is far too ordinary an action to sit
 * one bug away from destroying them.
 *
 * <p>Until 2026-10-10 the second gate below was {@code proxyma.multi-user}, which the Home Server
 * does not set - so deleting one of its upload sources left its files and their FileMetadata rows
 * behind: the bytes until UploadFolderGarbageCollectorService's daily sweep found the folder
 * unclaimed, the rows for good.
 */
@Component
public class FileCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupListener.class);

    private final FileService fileService;
    // The same rule that gates the upload path itself (UploadedFilesSession, the upload
    // endpoints): true wherever Proxyma stores a copy of anyone's files, with or without accounts.
    private final FileSourceMode fileSourceMode;

    public FileCleanupListener(FileService fileService, FileSourceMode fileSourceMode) {
        this.fileService = fileService;
        this.fileSourceMode = fileSourceMode;
    }

    @EventListener
    public void onConnectorRemoved(ConnectorRemovedEvent event) {
        // UPLOADED_FILES by name, never isFileBacked(). This method deletes bytes, so it must match
        // only the type whose bytes Proxyma owns - a LOCAL_FOLDER connector points at the user's own
        // documents, and reclaiming storage there would mean destroying them. This is the single
        // most important line in the class, and after the type split it is enforced by the type
        // system rather than by a mode flag: a desktop connector cannot BE this type.
        if (event.getConnectorType() != ConnectorType.UPLOADED_FILES) return;
        // Kept as a second, redundant layer even though the type check above is sufficient. Both
        // this and LocalDiskStorageService's own uploads-sandbox jail are cheap, and the cost of
        // being wrong here is a user's documents.
        if (!fileSourceMode.uploadsEnabled()) return;
        String config = event.getConfig();
        if (config == null || config.isBlank()) return;
        // Two independent try/catches, not one wrapping both - deleteFilesInFolder() only removes
        // files that HAVE a FileMetadata row and can throw partway through that per-row loop; a
        // single shared catch previously meant that throw skipped deleteConnectorFolder()'s
        // recursive sweep entirely, leaving the directory (and any failed-upload leftovers that
        // never had a row at all) orphaned on disk with no further attempt to remove them (the
        // "orphaned top-level folders" lesson, 2026-07-27) - UploadFolderGarbageCollectorService
        // now catches whatever still slips past this.
        try {
            fileService.deleteFilesInFolder(config, event.getUserId());
        } catch (Exception e) {
            log.warn("[FileCleanup] Failed to delete tracked file rows for connector={}: {}",
                    event.getConnectorId(), e.getMessage());
        }
        try {
            // A null userId is the single local user, whose sandbox is {storageRoot}/uploads -
            // deleteFolderRecursively() refuses anything outside that owner's upload root.
            fileService.deleteConnectorFolder(event.getUserId(), config);
            log.info("[FileCleanup] Cleaned up uploaded files for connector={} path={}",
                    event.getConnectorId(), config);
        } catch (Exception e) {
            log.warn("[FileCleanup] Failed to remove folder for connector={} path={}: {}",
                    event.getConnectorId(), config, e.getMessage());
        }
    }
}
