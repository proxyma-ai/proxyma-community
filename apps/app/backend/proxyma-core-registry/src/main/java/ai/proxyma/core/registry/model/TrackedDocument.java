package ai.proxyma.core.registry.model;

import ai.proxyma.core.connector.ConnectorType;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Index choices follow the actual query patterns in TrackedDocumentRepository/SyncOrchestratorService:
// - idx_doc_source_id: sourceId lookups (findBySourceId, existsBySourceId, ...)
// - idx_doc_connector_status: (connectorId, status) - also serves connectorId-only queries via the
//   leftmost prefix (findByConnectorId, countByConnectorId, ...); backs drainQueuedConnectors (2s poll)
// - idx_doc_status_retry: (status, nextRetryAt) - also serves status-only queries via the leftmost
//   prefix; backs autoRetryFailedItems' FAILED/nextRetryAt sweep (60s poll)
@Entity
@Table(name = "tracked_documents", indexes = {
    @Index(name = "idx_doc_source_id", columnList = "sourceId"),
    @Index(name = "idx_doc_connector_status", columnList = "connectorId, status"),
    @Index(name = "idx_doc_status_retry", columnList = "status, nextRetryAt")
})
public class TrackedDocument {

    // Auto-retry policy for FAILED items - see SyncService.markFailed() (sets nextRetryAt) and
    // SyncOrchestratorService.autoRetryFailedItems() (the scheduled sweep that requeues them).
    // A FAILED item with retryCount < MAX_AUTO_RETRIES is excluded from the connector's displayed
    // failedCount (SyncService.updateConnectorCounts()) until retries are exhausted.
    public static final int MAX_AUTO_RETRIES = 3;
    public static final Duration AUTO_RETRY_DELAY = Duration.ofMinutes(5);

    public static final int TITLE_MAX_CHARS = 1024;

    // The width of sourceId and sourceUrl. An item whose id is longer cannot be recorded at all -
    // SyncService skips it when it is listed rather than failing its insert on every pass.
    public static final int SOURCE_ID_MAX_CHARS = 2048;

    // The failureMessage column's width, and the budget setFailureMessage() truncates to. A
    // provider error is not length-bounded - an upstream that answers a 500 with an HTML page puts
    // the whole page here - and an over-long value fails the SAVE, which means the whole drain
    // transaction fails while it is only trying to record that one item failed.
    public static final int FAILURE_MESSAGE_MAX_CHARS = 2000;

    // Said, not implied: a reader seeing a message stop mid-sentence should know the rest was cut
    // here rather than go looking for an upstream that truncates its own errors.
    static final String TRUNCATION_MARKER = " ... [truncated]";

    public enum Status {
        DETECTED,
        QUEUED,
        DOWNLOADING,
        CONVERTING,
        EMBEDDING,
        COMPLETED,
        FAILED,
        DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false, length = 2048)
    private String sourceId;

    // Explicit length: without one this defaults to varchar(255), and a title is whatever the
    // remote system calls the item - a Confluence page heading, a rendered "[KEY-123] Summary", a
    // deep file name. 255 is a limit nobody chose, and overflowing it fails the INSERT rather than
    // the item.
    @Column(nullable = false, length = TITLE_MAX_CHARS)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectorType connectorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant sourceLastModified;

    // When this item was last MOVED, by anything: a change at the source, or a finished indexing
    // run, or an explicit re-index request. The item list sorts on it (failed first, then this,
    // newest first), which is why it exists beside lastIndexedAt - lastIndexedAt is null for
    // everything that has never succeeded, so a list sorted on it puts every queued and failed
    // item in one undifferentiated block at the end.
    //
    // Only ever moves forward - see touch(). All three of the columns below are nullable, and must
    // stay that way: ddl-auto=update cannot add a NOT NULL column to a populated table (PostgreSQL
    // refuses, Hibernate logs it at WARN and carries on, and the app then runs with the column
    // missing), and this table is the populated one on every existing install.
    @Column
    private Instant lastTouchedAt;

    @Column
    private Instant lastIndexedAt;

    // Start of the current or last indexing run, and how long the last FINISHED one took - set
    // whether it ended COMPLETED or FAILED, so a source whose items each take four minutes to
    // convert can be told apart from one that is stuck.
    @Column
    private Instant indexStartedAt;

    @Column
    private Long indexMs;

    @Column
    private String contentHash;

    @Column
    private int totalChunks;

    @Column
    private int currentChunks;

    @Column
    private long contentSize;

    // Raw byte size of the source item on disk (Files connector: actual file size). Null for
    // connectors with no real file (Jira, Confluence, ...) - distinct from contentSize above,
    // which is the converted markdown's character count and is meaningless to show as "file size".
    @Column
    private Long sourceSizeBytes;

    // ItemMetadata.contentType as last seen during reconcile() - lets a later, independent
    // processing pass reconstruct a minimal ItemMetadata (see SyncService.toMinimalItemMetadata())
    // without a live connector enumeration.
    @Column
    private String contentType;

    // Browsable web URL for this document (e.g. Jira issue browse link, Confluence page URL) -
    // computed by the connector from live ItemMetadata.extraProperties["url"] during
    // reconcileItem() and persisted here so processItem() (invoked later by
    // SyncOrchestratorService's drain loop with only a minimal reconstructed ItemMetadata - see
    // toMinimalItemMetadata()) can still thread it into the search index. Null for connectors
    // with no browsable URL concept (Files, ...).
    @Column(length = 2048)
    private String sourceUrl;

    // Who last changed the item, where the source's listing says so (SVN's last committer). Read by
    // the knowledge graph's metadata pass; null where the source states nobody.
    @Column(length = 256)
    private String sourceAuthor;

    @Column(length = FAILURE_MESSAGE_MAX_CHARS)
    private String failureMessage;

    @Column
    private boolean reindexReplace;

    // True once the FILES connector's raw source bytes have been deliberately deleted after this
    // document finished embedding (server-mode uploads only - see SyncService's post-COMPLETED
    // purge step). purgeDeleted() must never treat a purged document's absence from a disk walk as
    // user deletion; a permanently-retained markdown snapshot (see SyncService.markdownCachePath())
    // lets processItem() re-embed from cache instead of the (now-gone) original file on reindex.
    @Column(nullable = false)
    private boolean contentPurged;

    @Column
    private int retryCount;

    // When a FAILED item with retryCount < MAX_AUTO_RETRIES becomes eligible to be requeued for
    // another attempt - null once retries are exhausted or while the item isn't FAILED.
    @Column
    private Instant nextRetryAt;

    @Column(nullable = false)
    private Instant createdAt;

    public TrackedDocument() {
        this.createdAt = Instant.now();
        this.status = Status.DETECTED;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getTitle() { return title; }
    // Bounded here, like the failure message: a remote title or address is not length-bounded,
    // and one over its column failed the item's insert on every pass, leaving its source in ERROR.
    public void setTitle(String title) {
        this.title = title != null && title.length() > TITLE_MAX_CHARS ? title.substring(0, TITLE_MAX_CHARS) : title;
    }

    public ConnectorType getConnectorType() { return connectorType; }
    public void setConnectorType(ConnectorType connectorType) { this.connectorType = connectorType; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getSourceLastModified() { return sourceLastModified; }
    public void setSourceLastModified(Instant sourceLastModified) { this.sourceLastModified = sourceLastModified; }

    public Instant getLastTouchedAt() { return lastTouchedAt; }
    public void setLastTouchedAt(Instant lastTouchedAt) { this.lastTouchedAt = lastTouchedAt; }

    /**
     * Records that something happened to this item at {@code when} - but only if that is later
     * than what is already recorded.
     *
     * <p>The item list is ordered by this column, so an older value overwriting a newer one would
     * move a row back down the list after work was done on it. The two writers can legitimately
     * arrive out of order: a reconcile pass touches with the SOURCE's modification time, which for
     * a file that has sat unchanged for a year is a year old, while the indexing run that follows
     * touches with now.
     */
    public void touch(Instant when) {
        if (when == null) return;
        if (lastTouchedAt == null || when.isAfter(lastTouchedAt)) {
            this.lastTouchedAt = when;
        }
    }

    public Instant getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(Instant lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }

    public Instant getIndexStartedAt() { return indexStartedAt; }
    public void setIndexStartedAt(Instant indexStartedAt) { this.indexStartedAt = indexStartedAt; }

    public Long getIndexMs() { return indexMs; }
    public void setIndexMs(Long indexMs) { this.indexMs = indexMs; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getCurrentChunks() { return currentChunks; }
    public void setCurrentChunks(int currentChunks) { this.currentChunks = currentChunks; }

    public long getContentSize() { return contentSize; }
    public void setContentSize(long contentSize) { this.contentSize = contentSize; }

    public Long getSourceSizeBytes() { return sourceSizeBytes; }
    public void setSourceSizeBytes(Long sourceSizeBytes) { this.sourceSizeBytes = sourceSizeBytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getSourceUrl() { return sourceUrl; }
    public String getSourceAuthor() { return sourceAuthor; }
    public void setSourceAuthor(String sourceAuthor) {
        this.sourceAuthor = sourceAuthor != null && sourceAuthor.length() > 256 ? sourceAuthor.substring(0, 256) : sourceAuthor;
    }

    // An address cut short is a wrong link, so one too long for the column is left out instead.
    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl != null && sourceUrl.length() > SOURCE_ID_MAX_CHARS ? null : sourceUrl;
    }

    public String getFailureMessage() { return failureMessage; }

    /**
     * Truncates here, in the setter, rather than at the one call site that used to remember to.
     *
     * <p>The value is whatever an upstream said went wrong, and nothing bounds that: a proxy
     * answering a 500 with an HTML error page put the entire page in this field. Over the column
     * width it is the SAVE that throws, so the sync run recording one failed item fails whole -
     * which is how a single bad item took a whole drain batch with it. Truncating at the boundary
     * means no future writer can reintroduce that by not knowing about it.
     */
    public void setFailureMessage(String failureMessage) {
        this.failureMessage = truncateFailureMessage(failureMessage);
    }

    static String truncateFailureMessage(String message) {
        if (message == null || message.length() <= FAILURE_MESSAGE_MAX_CHARS) return message;
        return message.substring(0, FAILURE_MESSAGE_MAX_CHARS - TRUNCATION_MARKER.length())
                + TRUNCATION_MARKER;
    }

    public boolean isReindexReplace() { return reindexReplace; }
    public void setReindexReplace(boolean reindexReplace) { this.reindexReplace = reindexReplace; }

    public boolean isContentPurged() { return contentPurged; }
    public void setContentPurged(boolean contentPurged) { this.contentPurged = contentPurged; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
