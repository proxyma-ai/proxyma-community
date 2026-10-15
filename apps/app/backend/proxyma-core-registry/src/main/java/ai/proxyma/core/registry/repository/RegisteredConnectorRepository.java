package ai.proxyma.core.registry.repository;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegisteredConnectorRepository extends JpaRepository<RegisteredConnector, UUID> {

    // One-shot rewrite of rows written before ConnectorType.FILES was split - see
    // LegacyFilesTypeMigrator, which owns the reasoning and is the only caller. A bulk UPDATE
    // rather than a find-mutate-save loop because a long-lived install can carry a large connector
    // table, and this runs on the startup path.
    @Modifying
    @Query("update RegisteredConnector r set r.connectorType = :target where r.connectorType = :legacy")
    int rewriteConnectorType(@Param("legacy") ConnectorType legacy, @Param("target") ConnectorType target);

    // ── The columns sync owns, written one statement each ─────────────────────────────────────
    //
    // NEVER find-mutate-save for these. save() on an entity read earlier merges EVERY column, so a
    // background write that read its row before a person's edit and saved after it put the edit
    // back - sharing, priority, the graph switch, the scope, the connection, even a deletion in
    // progress - silently, because the count it meant to write was right. The sync and drain paths
    // write the counters after every item and every two seconds while a source is listed, which
    // made that window permanent. Each statement below names only its own columns.
    //
    // @Transactional on the method: a @Modifying query needs one, and on Desktop it is the
    // repository's own transaction manager (LocalDataSourceConfig's transactionManagerRef) that
    // Spring Data applies to it.

    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.itemCount = :items, r.processedCount = :processed, "
            + "r.failedCount = :failed where r.id = :id")
    int updateCounts(@Param("id") UUID id, @Param("items") int items, @Param("processed") int processed,
                     @Param("failed") int failed);

    /** The error is bounded by the caller - see RegisteredConnector.boundedSyncError. */
    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.status = :status, r.lastSyncError = :error where r.id = :id")
    int updateSyncState(@Param("id") UUID id, @Param("status") RegisteredConnector.Status status,
                        @Param("error") String error);

    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.status = :status where r.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") RegisteredConnector.Status status);

    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.allItemsFetched = :fetched where r.id = :id")
    int updateAllItemsFetched(@Param("id") UUID id, @Param("fetched") boolean fetched);

    // The cursor with the checkpoint, in one statement - see RegisteredConnector.syncCursor.
    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.lastSyncedAt = :at, r.syncCursor = :cursor where r.id = :id")
    int markSynced(@Param("id") UUID id, @Param("at") java.time.Instant at, @Param("cursor") String cursor);

    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.lastSyncedAt = :at, r.lastFullSyncAt = :at, r.syncCursor = :cursor where r.id = :id")
    int markFullySynced(@Param("id") UUID id, @Param("at") java.time.Instant at, @Param("cursor") String cursor);

    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.lastFullSyncAt = null where r.id = :id")
    int clearLastFullSyncAt(@Param("id") UUID id);

    // The model a source's index was built with, recorded the first time it is - and only then.
    // See SyncService.applyReindexSwapIfDue for why a later drain must never restamp it.
    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.indexedWithEmbeddingModel = :model, "
            + "r.indexedWithEmbeddingProvider = :provider where r.id = :id and r.indexedWithEmbeddingModel is null")
    int stampFirstEmbeddingModel(@Param("id") UUID id, @Param("model") String model,
                                 @Param("provider") String provider);

    // A reindex finished: the index is now the new model's, in place or by the shadow swap.
    @Modifying
    @Transactional
    @Query("update RegisteredConnector r set r.indexedWithEmbeddingModel = :model, "
            + "r.indexedWithEmbeddingProvider = :provider, r.reindexInProgress = false, "
            + "r.reindexShadowCollectionId = null where r.id = :id")
    int finishReindex(@Param("id") UUID id, @Param("model") String model, @Param("provider") String provider);

    List<RegisteredConnector> findByConnectorType(ConnectorType connectorType);

    Page<RegisteredConnector> findByConnectorType(ConnectorType connectorType, Pageable pageable);

    // Sources page's search box - filters by connector title (name), not document content.
    // Scoped by connector type when a specific tab is active, or across all types on the "All"
    // tab (type == null uses the *WithoutType variant below).
    Page<RegisteredConnector> findByConnectorTypeAndNameContainingIgnoreCase(
            ConnectorType connectorType, String name, Pageable pageable);

    Page<RegisteredConnector> findByUserIdAndConnectorTypeAndNameContainingIgnoreCase(
            String userId, ConnectorType connectorType, String name, Pageable pageable);

    Page<RegisteredConnector> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<RegisteredConnector> findByUserIdAndNameContainingIgnoreCase(String userId, String name, Pageable pageable);

    Optional<RegisteredConnector> findByConnectorTypeAndName(ConnectorType connectorType, String name);

    // the upstream server, so "PROJ" and "proj" resolve to the identical remote project/space -
    // registering both as separate RegisteredConnector rows makes every synced item's sourceId
    // appear under two different connectorIds. Used by registerConnector() instead of the
    // case-sensitive exact-match variant above to catch that before a duplicate is created.
    Optional<RegisteredConnector> findByConnectorTypeAndNameIgnoreCase(ConnectorType connectorType, String name);

    boolean existsByConnectorTypeAndName(ConnectorType connectorType, String name);

    List<RegisteredConnector> findByUserId(String userId);

    Page<RegisteredConnector> findByUserId(String userId, Pageable pageable);

    List<RegisteredConnector> findByUserIdAndConnectorType(String userId, ConnectorType connectorType);

    Page<RegisteredConnector> findByUserIdAndConnectorType(String userId, ConnectorType connectorType, Pageable pageable);

    Optional<RegisteredConnector> findByUserIdAndConnectorTypeAndName(String userId, ConnectorType connectorType, String name);

    // See findByConnectorTypeAndNameIgnoreCase() above for why this ignores case.
    Optional<RegisteredConnector> findByUserIdAndConnectorTypeAndNameIgnoreCase(String userId, ConnectorType connectorType, String name);

    // List variants of the two lookups above, for the server-based types where a name is NOT an
    // identity: the same project key on two Jira servers is two different projects, so more than
    // one row can legitimately carry it and the Optional variants would throw on the second one.
    // The caller picks the row whose resolved server matches (see ConnectorService.findExisting).
    List<RegisteredConnector> findAllByUserIdAndConnectorTypeAndNameIgnoreCase(String userId, ConnectorType connectorType, String name);

    List<RegisteredConnector> findAllByConnectorTypeAndNameIgnoreCase(ConnectorType connectorType, String name);

    boolean existsByUserIdAndConnectorTypeAndName(String userId, ConnectorType connectorType, String name);

    List<RegisteredConnector> findByUserIdIsNull();

    // The single local user's connectors of one type. Needed since 0.8.0, when a deployment with
    // no accounts gained uploads: UploadFolderGarbageCollectorService asks which folders belong to
    // a registered source and must not be deleted, and passing a null owner to the derived
    // findByUserIdAndConnectorType above compiles to `where user_id = null`, which matches nothing.
    // An empty answer there means every uploaded folder is orphaned and swept. See the note on
    // FileMetadataRepository, which is the same null and the same consequence.
    List<RegisteredConnector> findByUserIdIsNullAndConnectorType(ConnectorType connectorType);

    List<RegisteredConnector> findBySharedTrue();

    /** How many sources read through a connection - see ConnectionUsage. */
    long countByConnectionId(java.util.UUID connectionId);

    // Single-holder invariant: ConnectorService.setPriority() clears every other holder in the
    // same operation, so this returns at most one connector in practice.
    List<RegisteredConnector> findByPriorityTrue();

    // What the drain tick has anything to do for: sources with items still to process, and paused
    // ones (re-examined for resume whether or not they have a backlog). See
    // SyncOrchestratorService.drainQueuedConnectors, which runs every two seconds.
    @Query("SELECT r FROM RegisteredConnector r WHERE r.deleting = false "
            + "AND (r.status = :paused OR r.itemCount > r.processedCount + r.failedCount)")
    List<RegisteredConnector> findWithDrainWork(@Param("paused") RegisteredConnector.Status paused);

    // Connectors currently mid model-changed reindex on a mixed-dimension-capable store (Postgres) -
    // used by SearchService to know which collections need an extra old-model search pass, and by
    // the settings-changed guard to re-trigger a reindex if the model changes again mid-flight.
    List<RegisteredConnector> findByReindexInProgressTrue();

    // "Show shared connectors from other users" toggle on the Sources page - own connectors plus
    // anything anyone has marked shared, as opposed to the strictly-own-only variants above.
    @Query("SELECT r FROM RegisteredConnector r WHERE r.connectorType = :type AND (r.userId = :userId OR r.shared = true)")
    Page<RegisteredConnector> findByConnectorTypeAndUserIdOrShared(
            @Param("type") ConnectorType type, @Param("userId") String userId, Pageable pageable);

    @Query("SELECT r FROM RegisteredConnector r WHERE r.userId = :userId OR r.shared = true")
    Page<RegisteredConnector> findByUserIdOrShared(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT r FROM RegisteredConnector r WHERE r.connectorType = :type AND (r.userId = :userId OR r.shared = true) "
            + "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    Page<RegisteredConnector> findByConnectorTypeAndUserIdOrSharedAndNameContainingIgnoreCase(
            @Param("type") ConnectorType type, @Param("userId") String userId, @Param("name") String name, Pageable pageable);

    @Query("SELECT r FROM RegisteredConnector r WHERE (r.userId = :userId OR r.shared = true) "
            + "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    Page<RegisteredConnector> findByUserIdOrSharedAndNameContainingIgnoreCase(
            @Param("userId") String userId, @Param("name") String name, Pageable pageable);

    @Query("SELECT COUNT(r), COALESCE(SUM(r.itemCount), 0) FROM RegisteredConnector r "
            + "WHERE r.connectorType = :type AND (r.userId = :userId OR r.shared = true)")
    List<Object[]> countAndSumItemCountByUserIdOrSharedAndConnectorType(
            @Param("userId") String userId, @Param("type") ConnectorType type);

    @Query("SELECT r FROM RegisteredConnector r WHERE r.connectorType = :type AND (r.userId = :userId OR r.shared = true) "
            + "ORDER BY r.createdAt DESC")
    List<RegisteredConnector> findByUserIdOrSharedAndConnectorTypeOrderByCreatedAtDesc(
            @Param("userId") String userId, @Param("type") ConnectorType type, Pageable pageable);

    void deleteByUserId(String userId);

    // Connector count + summed item count in one query per type (instead of two) - the /summary
    // endpoint calls this once per connector type, so halving the query count here halves its
    // total DB round trips. Return type must be List<Object[]> (one element per result row), NOT
    // a bare Object[] - Spring Data treats a query method's return type as collection-like based
    // on that declared type, and Object[] is itself array-shaped, so it wraps the single-row
    // result in another array. The bare-Object[] version compiles fine but throws
    // ClassCastException at runtime when the caller casts row[0] to Long (it's actually the
    // inner Object[]{count, sum} row, not the count).
    @Query("SELECT COUNT(r), COALESCE(SUM(r.itemCount), 0) FROM RegisteredConnector r WHERE r.connectorType = :type")
    List<Object[]> countAndSumItemCountByConnectorType(@Param("type") ConnectorType type);

    @Query("SELECT COUNT(r), COALESCE(SUM(r.itemCount), 0) FROM RegisteredConnector r WHERE r.userId = :userId AND r.connectorType = :type")
    List<Object[]> countAndSumItemCountByUserIdAndConnectorType(@Param("userId") String userId, @Param("type") ConnectorType type);

    List<RegisteredConnector> findTop5ByConnectorTypeOrderByCreatedAtDesc(ConnectorType connectorType);

    List<RegisteredConnector> findTop5ByUserIdAndConnectorTypeOrderByCreatedAtDesc(String userId, ConnectorType connectorType);

    // Bulk variant for the Admin Users table - one query for connector count + summed item count
    // across a whole page of users, instead of one listByUser(uid) call per user (which was
    // O(users x connectors) per page load). Returns (userId, connectorCount, itemCountSum) triples;
    // users with no connectors are simply absent from the result.
    @Query("SELECT r.userId, COUNT(r), COALESCE(SUM(r.itemCount), 0) FROM RegisteredConnector r "
            + "WHERE r.userId IN :userIds GROUP BY r.userId")
    List<Object[]> countAndSumItemCountGroupedByUserId(@Param("userIds") List<String> userIds);

    // (connectorId, userId) pairs for a page of users - used by StorageStatsService to resolve
    // each user's ChromaDB/BM25 collection ids in one query instead of one findByUserId() call
    // per user.
    @Query("SELECT r.id, r.userId FROM RegisteredConnector r WHERE r.userId IN :userIds")
    List<Object[]> findIdAndUserIdByUserIdIn(@Param("userIds") List<String> userIds);

    // SVN/SharePoint/Teams) - see ConnectorController's single-register endpoints. Candidates are
    // limited to connectors the requester owns (any sharing state) or that ANYONE has marked
    // shared - never another user's private connector, which would leak its existence. config is
    // null-safe-compared since Teams registers with config=null.
    // name comparison is case-insensitive for the same reason as findByConnectorTypeAndNameIgnoreCase()
    // above; config is left case-sensitive since it can be a filesystem path (case-sensitive on Linux).
    // A LIST: the caller's own row and somebody's shared one can both match - routinely, once one
    // user has registered past the duplicate warning - and the Optional this was threw on that.
    @Query("SELECT r FROM RegisteredConnector r WHERE r.connectorType = :type AND LOWER(r.name) = LOWER(:name) "
            + "AND ((:config IS NULL AND r.config IS NULL) OR r.config = :config) "
            + "AND (r.shared = true OR r.userId = :requesterId) ORDER BY r.createdAt")
    List<RegisteredConnector> findSharedOrOwnByConnectorTypeAndConfigAndName(
            @Param("type") ConnectorType type, @Param("config") String config, @Param("name") String name,
            @Param("requesterId") String requesterId);
}
