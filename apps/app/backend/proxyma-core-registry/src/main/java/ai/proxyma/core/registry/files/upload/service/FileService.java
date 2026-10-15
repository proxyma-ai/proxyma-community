package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.quota.UserQuotaPort;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileService.class);

    private final FileMetadataRepository repository;
    private final StorageService storageService;
    private final TrackedDocumentRepository trackedDocumentRepository;
    private final UserQuotaPort quotaPort;
    private final ai.proxyma.core.registry.service.SyncService syncService;
    private final UploadFilterWidener filterWidener;

    public FileService(FileMetadataRepository repository, StorageService storageService,
                        TrackedDocumentRepository trackedDocumentRepository,
                        @Autowired(required = false) UserQuotaPort quotaPort,
                        ai.proxyma.core.registry.service.SyncService syncService,
                        @Autowired(required = false) UploadFilterWidener filterWidener) {
        this.repository = repository;
        this.storageService = storageService;
        this.trackedDocumentRepository = trackedDocumentRepository;
        this.quotaPort = quotaPort;
        this.syncService = syncService;
        this.filterWidener = filterWidener;
    }

    public FileMetadata upload(String originalName, String mimeType,
                               long sizeBytes, InputStream content, String userId,
                               String folder, String relativePath) {
        if (quotaPort != null && userId != null && !quotaPort.hasCapacityFor(userId, sizeBytes)) {
            throw new IllegalArgumentException("Storage quota exceeded for this account");
        }
        // Checked before a byte is written, for the reason FileMetadata.STORAGE_PATH_MAX gives.
        if ((folder != null && folder.trim().length() > FileMetadata.NAME_MAX)
                || (relativePath != null && relativePath.trim().length() > FileMetadata.NAME_MAX)) {
            throw new IllegalArgumentException("The folder path of " + originalName + " is too long to store.");
        }
        UUID fileId = UUID.randomUUID();
        String storagePath = storageService.store(fileId, originalName, content, sizeBytes, userId, folder, relativePath);
        // store() writes to a path derived deterministically from (userId, folder, relativePath,
        // originalName) - fileId plays no role in the physical path (see
        // LocalDiskStorageService.store()). Re-uploading the same logical file (retrying a
        // drag-drop, or choosing "overwrite" in the duplicate-resolution flow) overwrites the
        // SAME bytes on disk, but an insert-only upload() left the OLD row behind every time -
        // the same physical file accumulated one orphaned FileMetadata row per re-upload, and
        // findDuplicates() then reported that single file as N separate "already exists" hits.
        // Reuse the existing row for this exact path if one exists, instead of inserting again.
        Optional<FileMetadata> existing = repository.findByStoragePath(storagePath);
        long previousSizeBytes = existing.map(FileMetadata::getSizeBytes).orElse(0L);
        FileMetadata meta = existing.orElseGet(FileMetadata::new);
        // New bytes over a file whose old ones were purged after indexing (server mode): they are
        // on disk again, and its documents must read them rather than the text kept from before.
        boolean replacingPurged = meta.isBytesPurged();
        meta.setBytesPurged(false);
        meta.setOriginalName(originalName);
        meta.setMimeType(mimeType);
        meta.setSizeBytes(sizeBytes);
        meta.setStoragePath(storagePath);
        meta.setUploadedAt(Instant.now());
        meta.setUploadedByUserId(userId);
        if (folder != null && !folder.isBlank()) meta.setFolder(folder.trim());
        if (relativePath != null && !relativePath.isBlank()) meta.setRelativePath(relativePath.trim());
        FileMetadata saved = repository.save(meta);
        if (quotaPort != null && userId != null) quotaPort.addUsedBytes(userId, sizeBytes - previousSizeBytes);
        if (replacingPurged) syncService.resetPurgedContent(storagePath);
        // A type the folder's source filters out is switched on for it, so the file is indexed
        // rather than stored and skipped (UploadFilterWidener). Never at the upload's expense.
        if (filterWidener != null) {
            try {
                filterWidener.widenFor(userId, folder, originalName);
            } catch (RuntimeException e) {
                log.warn("[Files] could not widen the file types of the source for {}: {}", originalName, e.getMessage());
            }
        }
        return saved;
    }

    public String ensureFolder(String userId, String folder) {
        return storageService.ensureFolder(userId, folder);
    }

    public List<String> listFolders(String userId) {
        return repository.findDistinctFoldersByUserId(userId).stream()
                .filter(folder -> storageService.folderExists(userId, folder))
                .toList();
    }

    // relativePaths entries are the full path within the destination folder, e.g.
    // "subdir/file.txt" for a nested file or "file.txt" for a root-level one.
    public List<FileMetadata> findDuplicates(String folder, List<String> relativePaths, String userId) {
        if (folder == null || folder.isBlank() || relativePaths == null || relativePaths.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(relativePaths);
        // No storageService.fileExists() filter - a purged-after-processing file (see
        // TrackedDocument.contentPurged) legitimately has no bytes on disk anymore but is still a
        // real, indexed duplicate; the row only disappears from this table on genuine deletion
        // (FileService.delete()), which is the correct signal for "this no longer exists".
        return repository.findByFolderAndUploadedByUserId(folder.trim(), userId).stream()
                .filter(meta -> wanted.contains(fullPath(meta)))
                .toList();
    }

    private static String fullPath(FileMetadata meta) {
        String rel = meta.getRelativePath();
        return (rel != null && !rel.isBlank()) ? rel + "/" + meta.getOriginalName() : meta.getOriginalName();
    }

    // client-side-computed identity of a file the caller is about to upload - see
    // ChatPage-equivalent upload flow, which hashes each file with Web Crypto before ever
    // sending its bytes.
    public record DuplicateCandidate(String relativePath, long sizeBytes, String sha256) {}

    // matchedConnectorName is safe to show regardless of whose connector matched - a connector only
    // ever matches here if it's shared (visible tenant-wide already) or the requester's own.
    public record SharedDuplicateMatch(String relativePath, String matchedConnectorName, boolean ownedByRequester) {}

    // Cross-user/cross-connector duplicate check by content hash - deliberately never matches
    // another user's PRIVATE connector (see TrackedDocumentRepository.findSharedOrOwnByContentHashAndSize),
    // so this cannot be used to probe whether a given file exists somewhere private to someone else.
    public List<SharedDuplicateMatch> findSharedDuplicates(List<DuplicateCandidate> candidates, String requesterId) {
        List<SharedDuplicateMatch> matches = new ArrayList<>();
        for (DuplicateCandidate candidate : candidates) {
            if (candidate.sha256() == null || candidate.sha256().isBlank()) continue;
            List<Object[]> rows = trackedDocumentRepository.findSharedOrOwnByContentHashAndSize(
                    candidate.sha256(), candidate.sizeBytes(), requesterId);
            if (rows.isEmpty()) continue;

            // Prefer surfacing a match the requester already owns themselves (a more useful
            // signal - "you already have this exact file") over a merely-shared one from
            // someone else, when both happen to exist.
            RegisteredConnector best = null;
            boolean ownedByRequester = false;
            for (Object[] row : rows) {
                RegisteredConnector connector = (RegisteredConnector) row[1];
                if (requesterId != null && requesterId.equals(connector.getUserId())) {
                    best = connector;
                    ownedByRequester = true;
                    break;
                }
                if (best == null) best = connector;
            }
            matches.add(new SharedDuplicateMatch(candidate.relativePath(), best.getName(), ownedByRequester));
        }
        return matches;
    }

    public Optional<FileMetadata> findById(UUID fileId) {
        return repository.findById(fileId);
    }

    public List<FileMetadata> listByUser(String userId) {
        return repository.findByUploadedByUserId(userId);
    }

    // Bulk upload-size totals for a page of users (Admin Users table) - one query instead of
    // one sumSizeBytesByUserId(uid) call per user. Users absent from the returned map have no
    // uploaded files.
    public Map<String, Long> getStorageStatsForUsers(List<String> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : repository.sumSizeBytesGroupedByUserId(userIds)) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }

    // Explicit, immediate deletion - strips the TrackedDocument + vectors synchronously via
    // SyncService instead of relying on the owning connector's next scheduled reconcile to notice
    // the file is gone from disk and infer a deletion from that (up to an hour later, and
    // ambiguous with the post-processing purge step, which deliberately removes bytes for the
    // opposite reason - see TrackedDocument.contentPurged).
    public void delete(UUID fileId) {
        repository.findById(fileId).ifPresent(meta -> {
            deleteOne(meta);
        });
    }

    /**
     * Deletes every file this owner uploaded INSIDE {@code folderPath} - the bytes, the rows and
     * their documents - when the source registered there is removed.
     *
     * <p>Inside by PATH, compared segment by segment, and the owner's own files only. The query
     * underneath is a text prefix, and {@code .../uploads/Reports} is a text prefix of
     * {@code .../uploads/Reports 2024/q1.pdf}: until 2026-10-14 removing a source called "Reports"
     * deleted the files, rows and index entries of every sibling whose name began the same way
     * ("Reports 2024", "HR-archive" under "HR", "Q10" under "Q1").
     */
    public void deleteFilesInFolder(String folderPath, String userId) {
        Path folder;
        try {
            folder = Path.of(folderPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return;
        }
        for (FileMetadata meta : repository.findByStoragePathStartingWith(folder.toString())) {
            if (!Objects.equals(userId, meta.getUploadedByUserId())) continue;
            if (!isInside(folder, meta.getStoragePath())) continue;
            deleteOne(meta);
        }
    }

    private static boolean isInside(Path folder, String storagePath) {
        if (storagePath == null) return false;
        try {
            Path file = Path.of(storagePath).toAbsolutePath().normalize();
            return file.startsWith(folder) && !file.equals(folder);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Called after deleteFilesInFolder() on connector removal - that call only deletes
    // paths with a FileMetadata row, so a failed upload's leftover directory/file (created on
    // disk, but never persisted since FileService.upload() throws before repository.save())
    // survives forever otherwise. See LocalDiskStorageService.deleteFolderRecursively() for the
    // safety scoping that makes this a no-op for non-uploads FILES connectors.
    public void deleteConnectorFolder(String userId, String physicalPath) {
        storageService.deleteFolderRecursively(userId, physicalPath);
    }

    private void deleteOne(FileMetadata meta) {
        // Already purged after processing (see SyncService's post-COMPLETED step) - bytes are
        // already gone and already excluded from quota, so don't double-subtract or attempt to
        // delete a file that was never there.
        if (!meta.isBytesPurged()) {
            storageService.delete(meta.getStoragePath(), meta.getUploadedByUserId(), meta.getFolder());
            if (quotaPort != null && meta.getUploadedByUserId() != null) {
                quotaPort.addUsedBytes(meta.getUploadedByUserId(), -meta.getSizeBytes());
            }
        }
        repository.delete(meta);
        syncService.deleteTrackedDocumentBySourceId(meta.getStoragePath());
    }
}
