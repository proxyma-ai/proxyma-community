package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Periodic reconciliation between {storageRoot}/users/*/uploads/* on disk and the DB rows that
// are supposed to be the only reason those directories exist. FileCleanupListener already tries
// to remove a FILES connector's folder the moment it's deleted, but that's a best-effort, one-shot
// attempt (see its own comment) - a failure there (or the AccountDeletionService.wipeUserData()
// path, which bulk-deletes FileMetadata rows without ever touching disk at all) leaves the
// physical directory behind forever with nothing left to retry it. This sweep is the backstop: anything
// still on disk that neither a RegisteredConnector nor a FileMetadata row accounts for, and that
// hasn't been touched in GRACE_PERIOD (so it can never race a just-started upload or
// not-yet-committed registration), gets removed.
@Service
public class UploadFolderGarbageCollectorService {

    private static final Logger log = LoggerFactory.getLogger(UploadFolderGarbageCollectorService.class);

    // Deliberately generous - this only needs to be longer than any realistic upload+register
    // flow could ever take, not tuned for promptness. Getting it wrong in the "too short" direction
    // risks deleting a folder a user is actively mid-upload to; getting it wrong in the "too long"
    // direction just leaves a genuine orphan around for a bit longer, which is harmless.
    private static final Duration GRACE_PERIOD = Duration.ofHours(24);

    private final FileSourceMode fileSourceMode;
    private final StorageService storageService;
    private final RegisteredConnectorRepository connectorRepository;
    private final FileMetadataRepository fileMetadataRepository;

    public UploadFolderGarbageCollectorService(StorageService storageService,
                                                RegisteredConnectorRepository connectorRepository,
                                                FileMetadataRepository fileMetadataRepository,
                                                FileSourceMode fileSourceMode) {
        this.fileSourceMode = fileSourceMode;
        this.storageService = storageService;
        this.connectorRepository = connectorRepository;
        this.fileMetadataRepository = fileMetadataRepository;
    }

    // Once a day, 5 minutes after startup - matches ConversationRetentionSweep's cadence/style.
    // A tick failure (e.g. a transient disk/DB error) must never kill future scheduled runs, so
    // the whole sweep is one top-level try/catch; a per-user or per-folder failure inside it is
    // additionally isolated so one bad user/folder can't abort the rest of the sweep either.
    @Scheduled(fixedRate = 86_400_000, initialDelay = 300_000)
    public void sweep() {
        // Only where Proxyma takes uploads. On a desktop install nothing is ever written under the
        // storage root, which there defaults to /data (C:/data on Windows) - a directory of the
        // user's own that this sweep would otherwise list and delete from, one idle folder a day.
        if (!fileSourceMode.uploadsEnabled()) return;
        try {
            // WHOSE SANDBOXES THERE ARE TO SWEEP, which is not always "one per account".
            //
            // A deployment with accounts has {storageRoot}/users/{id}/uploads per uploader, and
            // listUserIdsWithUploads() finds them by listing that directory. The home server has
            // ONE sandbox, {storageRoot}/uploads, belonging to the single local user - who is a
            // null owner everywhere in this codebase, and whose directory that listing therefore
            // cannot find, because there is no account directory to find. A singleton null is the
            // whole difference, and it is spelt out here rather than smuggled into that method,
            // whose name promises accounts.
            List<String> owners = new ArrayList<>();
            if (fileSourceMode.uploadsAreAttributed()) {
                owners.addAll(storageService.listUserIdsWithUploads());
            } else {
                owners.add(null);
            }
            int removed = 0;
            for (String userId : owners) {
                removed += sweepUser(userId);
            }
            if (removed > 0) {
                log.info("[UploadGC] Removed {} orphaned upload folder(s) across {} sandbox(es)",
                        removed, owners.size());
            }
        } catch (Exception e) {
            log.error("[UploadGC] Sweep failed: {}", e.getMessage(), e);
        }
    }

    private int sweepUser(String userId) {
        int removed = 0;
        try {
            KnownFolders known = knownFolders(userId);
            for (String folderPath : storageService.listTopLevelFolders(userId)) {
                if (known.accountsFor(Path.of(folderPath))) continue;
                if (!isOlderThanGracePeriod(folderPath)) continue;
                log.warn("[UploadGC] Removing orphaned upload folder for user={}: {} (no matching "
                        + "RegisteredConnector or FileMetadata row)", userId, folderPath);
                try {
                    storageService.deleteFolderRecursively(userId, folderPath);
                    removed++;
                } catch (Exception e) {
                    log.warn("[UploadGC] Failed to remove orphaned folder {} for user={}: {}",
                            folderPath, userId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[UploadGC] Failed to sweep uploads for user={}: {}", userId, e.getMessage());
        }
        return removed;
    }

    // A folder is legitimate if it's either a registered FILES connector's own directory (taken
    // from RegisteredConnector.config - the physical path set at registerFiles() time) or a folder
    // someone has uploaded to but not yet finished registering (present via a FileMetadata row's
    // folder field even with no RegisteredConnector yet) - see FileService.listFolders(), which
    // relies on exactly this same "FileMetadata row without a connector is still valid" UX.
    // A connector mid-deletion (isDeleting=true) is excluded so FileCleanupListener's own in-flight
    // cleanup for it is never raced by this sweep running concurrently.
    //
    // A FOLDER NAME MAY HOLD A SEPARATOR. UploadSandbox.folderIn accepts "Clients/Acme", stored as
    // {sandbox}/Clients/Acme - and this sweep lists only the TOP level, "Clients". Until 2026-10-14
    // it compared that against the whole folder name and the config's LAST segment ("Acme"),
    // matched neither, and deleted a registered source's folder a day after its last write. So a
    // FileMetadata folder counts by its FIRST segment, and a registered path accounts for the
    // top-level directory it lies in (and, registered at the sandbox root, for all of them).
    private KnownFolders knownFolders(String userId) {
        Set<String> names = new HashSet<>();
        for (String folder : fileMetadataRepository.findDistinctFoldersByUserId(userId)) {
            String first = firstSegment(folder);
            if (first != null) names.add(first);
        }
        // UPLOADED_FILES by name: this sweep decides which directories under a user's upload
        // sandbox may be DELETED, so it must only ever consider connectors whose bytes live there.
        // A LOCAL_FOLDER connector cannot exist in this edition at all, and if one somehow did, its
        // config is a path on the user's own disk that has no business informing what gets removed
        // from the server's storage root.
        // The null-owner variant is a DIFFERENT query, not the same one with a null argument: a
        // derived finder turns that null into `where user_id = null`, matches nothing, and every
        // registered folder then looks orphaned to the loop below. See the note on
        // RegisteredConnectorRepository.findByUserIdIsNullAndConnectorType.
        List<RegisteredConnector> registered = userId == null
                ? connectorRepository.findByUserIdIsNullAndConnectorType(ConnectorType.UPLOADED_FILES)
                : connectorRepository.findByUserIdAndConnectorType(userId, ConnectorType.UPLOADED_FILES);
        List<Path> paths = new ArrayList<>();
        for (RegisteredConnector r : registered) {
            if (r.isDeleting()) continue;
            String config = r.getConfig();
            if (config == null || config.isBlank()) continue;
            Path path = normalizedOrNull(config);
            if (path == null) continue;
            paths.add(path);
            // The leaf name as well, which is all this used to compare - kept so a sandbox whose
            // root moved (an operator changing the storage root) still recognizes its folders by
            // name instead of deleting them for a path that no longer matches. Keeping too much
            // here costs disk; keeping too little costs a customer's files.
            Path leaf = path.getFileName();
            if (leaf != null) names.add(leaf.toString());
        }
        return new KnownFolders(names, paths);
    }

    /** What accounts for a top-level upload folder: folder names, and registered source paths. */
    record KnownFolders(Set<String> names, List<Path> registeredPaths) {
        boolean accountsFor(Path topLevelFolder) {
            Path folder = topLevelFolder.toAbsolutePath().normalize();
            Path name = folder.getFileName();
            if (name != null && names.contains(name.toString())) return true;
            for (Path registered : registeredPaths) {
                // Inside it or the folder itself, or an ancestor of it (the sandbox root).
                if (registered.startsWith(folder) || folder.startsWith(registered)) return true;
            }
            return false;
        }
    }

    private static String firstSegment(String folder) {
        if (folder == null) return null;
        for (String segment : folder.replace('\\', '/').split("/")) {
            if (!segment.isBlank()) return segment.trim();
        }
        return null;
    }

    private static Path normalizedOrNull(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Scans the whole subtree rather than just the top-level directory's own mtime, since adding
    // a file inside a nested subdirectory doesn't necessarily bump every ancestor directory's own
    // last-modified time on every filesystem - the most recent write anywhere under the folder is
    // the only activity signal that's safe to rely on.
    private boolean isOlderThanGracePeriod(String folderPath) {
        try {
            Instant cutoff = Instant.now().minus(GRACE_PERIOD);
            try (var walk = Files.walk(Path.of(folderPath))) {
                return walk.noneMatch(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant().isAfter(cutoff);
                    } catch (IOException e) {
                        return true; // unreadable entry - treat conservatively as "recent"
                    }
                });
            }
        } catch (Exception e) {
            // Can't determine age - skip rather than risk deleting something still in use.
            return false;
        }
    }
}
