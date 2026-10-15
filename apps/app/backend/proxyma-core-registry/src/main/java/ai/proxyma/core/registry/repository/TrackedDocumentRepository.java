package ai.proxyma.core.registry.repository;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.model.TrackedDocument.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TrackedDocumentRepository extends JpaRepository<TrackedDocument, UUID> {

    // One-shot rewrite of rows written before ConnectorType.FILES was split - see
    // LegacyFilesTypeMigrator, which owns the reasoning and is the only caller. This table is the
    // large one (a row per indexed document, not per connector), which is why it is a bulk UPDATE
    // rather than a find-mutate-save loop on the startup path.
    @Modifying
    @Query("update TrackedDocument d set d.connectorType = :target where d.connectorType = :legacy")
    int rewriteConnectorType(@Param("legacy") ConnectorType legacy, @Param("target") ConnectorType target);

    // ── Bulk requeues: one statement for a whole source, not a save per row ──────────────────

    // A reindex queues every LIVE document again. DELETED rows are kept for good and were
    // requeued too: the drain fetched items gone upstream (each failing three times), and the
    // inflated live count then tripped the purge sanity floor on every later full sync, so real
    // deletions were never purged again.
    @Modifying
    @Transactional
    @Query("update TrackedDocument d set d.status = :queued, d.totalChunks = 0, d.currentChunks = 0, "
            + "d.lastIndexedAt = null, d.failureMessage = null, d.reindexReplace = :replace "
            + "where d.connectorId = :connectorId and d.status <> :deleted")
    int requeueLiveForReindex(@Param("connectorId") UUID connectorId, @Param("replace") boolean replace,
                              @Param("queued") Status queued, @Param("deleted") Status deleted);

    @Query("select d.id from TrackedDocument d where d.connectorId = :connectorId and d.status <> :deleted")
    List<UUID> findIdsByConnectorIdAndStatusNot(@Param("connectorId") UUID connectorId,
                                                @Param("deleted") Status deleted);

    // "Retry failed": every FAILED document of the source, with a fresh retry budget.
    @Modifying
    @Transactional
    @Query("update TrackedDocument d set d.status = :queued, d.totalChunks = 0, d.currentChunks = 0, "
            + "d.failureMessage = null, d.retryCount = 0, d.nextRetryAt = null "
            + "where d.connectorId = :connectorId and d.status = :failed")
    int requeueFailed(@Param("connectorId") UUID connectorId, @Param("queued") Status queued,
                      @Param("failed") Status failed);

    // The auto-retry sweep's requeue of one source's due failures, all at once - see
    // SyncOrchestratorService.autoRetryFailedItems.
    @Modifying
    @Transactional
    @Query("update TrackedDocument d set d.status = :queued, d.retryCount = d.retryCount + 1, "
            + "d.nextRetryAt = null, d.failureMessage = null "
            + "where d.id in :ids and d.status = :failed")
    int requeueForRetry(@Param("ids") Collection<UUID> ids, @Param("queued") Status queued,
                        @Param("failed") Status failed);

    // A list, never an Optional: a sourceId is unique only within one source. An uploaded file is
    // tracked twice when the sandbox root and a folder inside it are both registered, and an
    // Optional finder then threw - after the file's bytes and row were already deleted.
    List<TrackedDocument> findAllBySourceId(String sourceId);

    List<TrackedDocument> findByConnectorType(ConnectorType connectorType);

    List<TrackedDocument> findByStatus(Status status);

    // COMPLETED-with-no-chunks is always a "ghost indexed" row (empty content that slipped past
    // the guard in SyncService.processItem() before it was added - see LESSONS_LEARNED "Silent
    // conversion failures produce ghost Indexed documents") - used by
    // SyncOrchestratorService.recoverOnStartup() to repair legacy rows left over from before that
    // guard existed.
    List<TrackedDocument> findByStatusAndTotalChunks(Status status, int totalChunks);

    List<TrackedDocument> findByConnectorTypeAndStatus(ConnectorType connectorType, Status status);

    @Query("SELECT d.sourceId FROM TrackedDocument d WHERE d.connectorType = :type AND d.status <> 'DELETED'")
    List<String> findActiveSourceIdsByConnectorType(@Param("type") ConnectorType connectorType);

    long countByConnectorTypeAndStatus(ConnectorType connectorType, Status status);

    boolean existsBySourceId(String sourceId);

    Optional<TrackedDocument> findBySourceIdAndConnectorId(String sourceId, UUID connectorId);

    // (connectorId, sourceId, title) for a batch of search hits, scoped to connectors the caller
    // can see. Three columns and not the entities: this runs on every search response, and a
    // search returns up to topK chunks that may all belong to different documents - loading whole
    // rows to read one string off each would pull content hashes and failure messages through the
    // pool for nothing. The connector comes back because a sourceId is unique only within its
    // source: the caller keeps the rows whose PAIR it asked for (ConnectorService.resolveDocumentTitles).
    //
    // DELETED rows are excluded. A document deleted at the source can still have chunks in the
    // index until the next sync sweeps them, and naming it would be worse than leaving the
    // title unset.
    @Query("select d.connectorId, d.sourceId, d.title from TrackedDocument d"
         + " where d.connectorId in :connectorIds and d.sourceId in :sourceIds"
         + " and d.status <> 'DELETED'")
    List<Object[]> findTitlesBySourceIds(@Param("connectorIds") Collection<UUID> connectorIds,
                                        @Param("sourceIds") Collection<String> sourceIds);

    // The documents behind a page of search results, within what the caller can see - so the graph
    // tool can tie each result back to the graph's passages (SearchKnowledgeGraphTool). Deleted
    // rows excluded, for the reason given above.
    @Query("select d from TrackedDocument d"
         + " where d.connectorId in :connectorIds and d.sourceId in :sourceIds"
         + " and d.status <> 'DELETED'")
    List<TrackedDocument> findLiveBySourceIds(@Param("connectorIds") Collection<UUID> connectorIds,
                                              @Param("sourceIds") Collection<String> sourceIds);

    // The documents of one source a reader could mean by a NAME: its title, or the file name a
    // search result shows for it (SearchTool reduces a file's absolute path to its last segment).
    // Lower-cased on both sides, so a name matches whatever its case. The two suffix patterns are
    // the last segment after a forward or a back slash - a network share's sourceId is a UNC path -
    // and arrive LIKE-escaped with '!', because a file name is full of '_', which LIKE reads as
    // "any character". It is a candidate list: ConnectorService.findDocumentsNamed compares the
    // last segment exactly, so a name containing a slash cannot match a longer path by accident.
    @Query("select d from TrackedDocument d where d.connectorId = :connectorId and d.status <> 'DELETED'"
         + " and (lower(d.title) = :name or lower(d.sourceId) = :name"
         + " or lower(d.sourceId) like :slashSuffix escape '!' or lower(d.sourceId) like :backslashSuffix escape '!')")
    List<TrackedDocument> findNameCandidates(@Param("connectorId") UUID connectorId,
                                             @Param("name") String name,
                                             @Param("slashSuffix") String slashSuffix,
                                             @Param("backslashSuffix") String backslashSuffix);

    List<TrackedDocument> findByConnectorId(UUID connectorId);

    long countByConnectorId(UUID connectorId);

    long countByConnectorIdAndStatus(UUID connectorId, Status status);

    // "Final" failed count for display - excludes items still within their auto-retry window
    // (see TrackedDocument.MAX_AUTO_RETRIES) so a connector's shown failedCount only ever reflects
    // items that have exhausted retries. Used by SyncService.updateConnectorCounts().
    long countByConnectorIdAndStatusAndRetryCountGreaterThanEqual(UUID connectorId, Status status, int retryCount);

    // Candidates for SyncOrchestratorService's auto-retry sweep: FAILED, still within budget, and
    // due (nextRetryAt in the past).
    List<TrackedDocument> findByStatusAndRetryCountLessThanAndNextRetryAtBefore(
            Status status, int retryCount, Instant instant);

    // Count variant of findByConnectorIdAndStatusIn - the auto-retry sweep only needs to know
    // whether a connector has any other work left (DRAINABLE_STATUSES), not load the rows.
    long countByConnectorIdAndStatusIn(UUID connectorId, List<Status> statuses);

    // Excludes DELETED - unlike countByConnectorId(), which counts every row ever created for
    // the connector (DELETED rows are never removed, only status-flipped by purgeDeleted()) and
    // therefore only ever grows. Used as the "how many items does this connector actually have
    // right now" baseline for SyncService's purge sanity floor - comparing a live count against
    // an ever-inflating historical one would eventually make the floor trip permanently for any
    // connector that has accumulated enough legitimate deletions over its lifetime.
    long countByConnectorIdAndStatusNot(UUID connectorId, Status status);

    // What a full listing is expected to show again: live documents whose original is still on
    // disk. Enterprise Server deletes an upload's bytes once it is indexed (contentPurged), so such
    // a document is never listed again - counted here, every fully indexed upload source read as a
    // folder that had emptied, and the purge sanity floor called each of its passes an error.
    long countByConnectorIdAndStatusNotAndContentPurgedFalse(UUID connectorId, Status status);

    // Items actively being worked on right now - naturally bounded by the embedding batch size,
    // so this is never paginated; it backs the modal's small "pinned" active-items section.
    List<TrackedDocument> findByConnectorIdAndStatusIn(UUID connectorId, List<Status> statuses);

    // Pageable sibling of the above - used by SyncOrchestratorService's drain loop to pull one
    // bounded batch of processable (DETECTED/QUEUED/resumable-EMBEDDING) rows at a time per
    // connector, independent of whatever reconcile() last discovered.
    List<TrackedDocument> findByConnectorIdAndStatusInOrderByIdAsc(
            UUID connectorId, List<Status> statuses, org.springframework.data.domain.Pageable pageable);

    // sourceId-only projection used by SyncService to detect items an incremental sync's
    // connector.syncSince() call never mentioned (because their upstream content is unchanged),
    // yet are still sitting QUEUED/DETECTED from an earlier full sync whose Phase 2 (processing)
    // was interrupted - e.g. app restart mid-embed. Deliberately loaded into memory rather than
    // expressed as a SQL NOT IN against this sync's seenIds, since seenIds can be very large for
    // a full sync and JPQL/SQL parameter lists don't scale to that.
    @Query("SELECT d.sourceId FROM TrackedDocument d WHERE d.connectorId = :connectorId AND d.status IN :statuses")
    List<String> findSourceIdsByConnectorIdAndStatusIn(@Param("connectorId") UUID connectorId,
                                                      @Param("statuses") List<Status> statuses);

    // The knowledge-graph worker's scan: which documents of a connector are indexed, and when each
    // was last indexed. Pairs rather than rows - a TrackedDocument carries a 2048-character source
    // id and URL the scan has no use for, and a large source has tens of thousands of them.
    @Query("SELECT d.id, d.lastIndexedAt FROM TrackedDocument d WHERE d.connectorId = :connectorId AND d.status = :status")
    List<Object[]> findIdAndLastIndexedAtByConnectorIdAndStatus(@Param("connectorId") UUID connectorId,
                                                                @Param("status") Status status);

    // The other half of that worker's cheap "has anything changed" test, beside
    // countByConnectorIdAndStatus: a re-indexed document moves this even when the count stands still.
    @Query("SELECT max(d.lastIndexedAt) FROM TrackedDocument d WHERE d.connectorId = :connectorId AND d.status = :status")
    Instant findMaxLastIndexedAtByConnectorIdAndStatus(@Param("connectorId") UUID connectorId,
                                                       @Param("status") Status status);

    // Server-wide (desktop mode / no auth) count of actively-processing items, for the Connectors
    // page header's "N items indexing" stat - a real snapshot query, not an SSE-accumulated
    // client-side count (which under-reports until every active item happens to emit its next
    // status event after the page mounts).
    long countByStatusIn(List<Status> statuses);

    // Scoped variant for multi-user server mode: only items belonging to connectors the caller
    // owns or that are shared. TrackedDocument.connectorId is a raw UUID column (no @ManyToOne),
    // so this is a subquery against RegisteredConnector rather than a JPQL join.
    @Query("SELECT COUNT(d) FROM TrackedDocument d WHERE d.status IN :statuses "
            + "AND d.connectorId IN (SELECT r.id FROM RegisteredConnector r WHERE r.userId = :userId OR r.shared = true)")
    long countByStatusInForUser(@Param("statuses") List<Status> statuses, @Param("userId") String userId);

    // Everything else (queued/completed/failed/...), keyset-paginated instead of offset - offset
    // pagination breaks under concurrent writes: an item transitioning into or out of the excluded
    // "active" statuses between two page fetches shifts every subsequent row's offset, causing
    // items to be skipped or duplicated across pages. Keyset pagination ("give me rows after this
    // one") is unaffected by rows changing outside the current page window. All three cursor
    // parameters are null for the first page.
    //
    // The ORDER is failed first, then most recently touched, then id - not alphabetical by title,
    // which is what this sorted by until 2026-09-18. A list of a thousand documents sorted by name
    // puts the three that failed wherever the alphabet happens to put them, so the one thing a
    // reader opens this list to find is the one thing they have to scroll for.
    //
    // COALESCE(lastTouchedAt, createdAt), not lastTouchedAt alone: rows written before that column
    // existed have it null, and null sorts as a block at one end - every legacy row would be
    // either above everything or below everything. createdAt is never null and is the closest
    // thing those rows carry to the same fact.
    @Query("SELECT d FROM TrackedDocument d WHERE d.connectorId = :connectorId AND d.status NOT IN :statuses "
            + "AND (:cursorFailed IS NULL"
            + " OR (CASE WHEN d.status = :failedStatus THEN 0 ELSE 1 END) > :cursorFailed"
            + " OR ((CASE WHEN d.status = :failedStatus THEN 0 ELSE 1 END) = :cursorFailed"
            + "     AND (COALESCE(d.lastTouchedAt, d.createdAt) < :cursorTouched"
            + "          OR (COALESCE(d.lastTouchedAt, d.createdAt) = :cursorTouched AND d.id > :cursorId)))) "
            + "ORDER BY (CASE WHEN d.status = :failedStatus THEN 0 ELSE 1 END) ASC, "
            + "COALESCE(d.lastTouchedAt, d.createdAt) DESC, d.id ASC")
    List<TrackedDocument> findByConnectorIdAndStatusNotInAfterCursor(
            @Param("connectorId") UUID connectorId,
            @Param("statuses") List<Status> statuses,
            @Param("failedStatus") Status failedStatus,
            @Param("cursorFailed") Integer cursorFailed,
            @Param("cursorTouched") Instant cursorTouched,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    // Per-user quota accounting - sums the char-length "contentSize" of every COMPLETED document
    // owned (via RegisteredConnector.userId) by each user in the page. Same theta-join shape as
    // RegisteredConnectorRepository's grouped queries (TrackedDocument.connectorId is a raw UUID
    // column, no @ManyToOne). Bulk/grouped variant avoids one query per user for the admin list.
    @Query("SELECT r.userId, COALESCE(SUM(d.contentSize), 0) FROM TrackedDocument d, RegisteredConnector r "
            + "WHERE d.connectorId = r.id AND r.userId IN :userIds AND d.status = 'COMPLETED' GROUP BY r.userId")
    List<Object[]> sumContentSizeGroupedByUserId(@Param("userIds") List<String> userIds);

    @Query("SELECT COALESCE(SUM(d.contentSize), 0) FROM TrackedDocument d, RegisteredConnector r "
            + "WHERE d.connectorId = r.id AND r.userId = :userId AND d.status = 'COMPLETED'")
    long sumContentSizeByUserId(@Param("userId") String userId);

    // Cross-user duplicate detection for file uploads (see FileController.checkSharedDuplicates).
    // Candidates are limited to connectors the requester owns (any sharing state) or that ANYONE
    // has marked shared - never another user's private connector, which would leak its existence.
    // contentHash is a FILE hash for the Files connector (SyncService.processItem()) and a hash of
    // the extracted text for a crawled web page (ItemMetadata.CONTENT_HASH). A web page has no
    // sourceSizeBytes, so the size condition keeps it out of this match; Jira, Confluence and the
    // rest carry no hash at all.
    @Query("SELECT d, r FROM TrackedDocument d, RegisteredConnector r "
            + "WHERE d.connectorId = r.id AND d.contentHash = :contentHash AND d.sourceSizeBytes = :sizeBytes "
            + "AND d.status <> 'DELETED' AND (r.shared = true OR r.userId = :requesterId)")
    List<Object[]> findSharedOrOwnByContentHashAndSize(@Param("contentHash") String contentHash,
                                                        @Param("sizeBytes") long sizeBytes,
                                                        @Param("requesterId") String requesterId);
}
