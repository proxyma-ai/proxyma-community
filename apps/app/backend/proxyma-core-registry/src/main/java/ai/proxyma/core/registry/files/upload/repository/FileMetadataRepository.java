package ai.proxyma.core.registry.files.upload.repository;

import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    /*
     * THE THREE OWNER-SCOPED READS BELOW ARE WRITTEN OUT RATHER THAN DERIVED, and the reason is a
     * null.
     *
     * Uploads used to exist only in the server edition, where every uploader is an account and
     * uploadedByUserId is never null. The home server (0.8.0) uploads too and has no accounts at
     * all: its single local user is represented as a null owner, the same way the rest of the
     * desktop code path represents it.
     *
     * A derived query turns that null into `where uploaded_by_user_id = null`, which in SQL is
     * UNKNOWN and therefore matches nothing - silently. It is not an error and not an empty result
     * anybody would question: the uploads page would simply report no files on a deployment holding
     * plenty, and - far worse - UploadFolderGarbageCollectorService asks one of these for the list
     * of folders it must NOT delete, so an empty answer there is every uploaded folder swept once
     * the grace period passes. A data-loss bug hiding behind a query that reads correctly.
     *
     * So the null case is stated explicitly. `:userId IS NULL` is checked FIRST, so the equality is
     * only ever evaluated for a real owner.
     */

    @Query("SELECT f FROM FileMetadata f WHERE (:userId IS NULL AND f.uploadedByUserId IS NULL) "
            + "OR f.uploadedByUserId = :userId")
    List<FileMetadata> findByUploadedByUserId(String userId);

    // storagePath is deterministic per (userId, folder, relativePath, originalName) - see
    // LocalDiskStorageService.store(), which never uses the random fileId in the physical path.
    // Used by FileService.upload() to reuse the existing row for a re-uploaded file instead of
    // inserting a duplicate that would point at the same now-overwritten physical file.
    Optional<FileMetadata> findByStoragePath(String storagePath);

    @Query("SELECT DISTINCT f.folder FROM FileMetadata f WHERE f.folder IS NOT NULL "
            + "AND ((:userId IS NULL AND f.uploadedByUserId IS NULL) OR f.uploadedByUserId = :userId) "
            + "ORDER BY f.folder")
    List<String> findDistinctFoldersByUserId(String userId);

    @Query("SELECT f FROM FileMetadata f WHERE f.folder = :folder "
            + "AND ((:userId IS NULL AND f.uploadedByUserId IS NULL) OR f.uploadedByUserId = :userId)")
    List<FileMetadata> findByFolderAndUploadedByUserId(String folder, String userId);

    long countByUploadedByUserId(String userId);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileMetadata f WHERE f.uploadedByUserId = :userId")
    long sumSizeBytesByUserId(String userId);

    // Server-wide upload total for the Admin System tab's storage breakdown - a SUM query
    // instead of loading every FileMetadata row into memory just to add up sizeBytes.
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileMetadata f")
    long sumAllSizeBytes();

    // Bulk variant for the Admin Users table - one query for a whole page of users instead of
    // one sumSizeBytesByUserId() call per user (see RegisteredConnectorRepository's matching
    // bulk query for the same reasoning). Returns (userId, totalSizeBytes) pairs; users with no
    // files are simply absent from the result, not zero-valued rows.
    @Query("SELECT f.uploadedByUserId, COALESCE(SUM(f.sizeBytes), 0) FROM FileMetadata f "
            + "WHERE f.uploadedByUserId IN :userIds GROUP BY f.uploadedByUserId")
    List<Object[]> sumSizeBytesGroupedByUserId(@org.springframework.data.repository.query.Param("userIds") List<String> userIds);

    @Query("SELECT COUNT(DISTINCT f.folder) FROM FileMetadata f WHERE f.folder IS NOT NULL AND f.uploadedByUserId = :userId")
    long countDistinctFoldersByUserId(String userId);

    long countByFolderAndUploadedByUserId(String folder, String userId);

    List<FileMetadata> findByStoragePathStartingWith(String pathPrefix);

    // Bulk resolve (server filesystem path -> FileMetadata) for a page of TrackedDocument rows -
    // used to surface a per-item delete affordance in the UI (the fileId, never the raw path
    // itself, which ConnectorController deliberately withholds - see toItemMap()) without an N+1
    // findByStoragePath() call per item.
    List<FileMetadata> findByStoragePathIn(List<String> storagePaths);

    void deleteByUploadedByUserId(String userId);
}
