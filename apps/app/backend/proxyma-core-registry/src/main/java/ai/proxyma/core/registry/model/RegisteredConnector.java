package ai.proxyma.core.registry.model;

import ai.proxyma.core.connector.ConnectorType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registered_connectors", indexes = {
    @Index(name = "idx_res_connector", columnList = "connectorType"),
    @Index(name = "idx_res_name", columnList = "name"),
    @Index(name = "idx_res_user", columnList = "userId")
})
public class RegisteredConnector {

    public enum Status { ACTIVE, ERROR, PAUSED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectorType connectorType;

    @Column(nullable = false)
    private String name;

    @Column
    private String userId;

    @Column(length = 4096)
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    // Short, sanitized summary of why status==ERROR (see SyncErrorAware.summarize()), or the note
    // a healthy but partial pass leaves beside ACTIVE. Reset to null the next time a sync completes
    // clean. Never longer than the column - see setLastSyncError.
    public static final int LAST_SYNC_ERROR_MAX = 500;

    @Column(length = LAST_SYNC_ERROR_MAX)
    private String lastSyncError;

    @Column(nullable = false)
    private int itemCount;

    @Column(nullable = false)
    private int processedCount;

    @Column(nullable = false)
    private int failedCount;

    @Column(nullable = false)
    private boolean shared;

    @Column(nullable = false)
    private boolean allItemsFetched;

    @Column(nullable = false)
    private boolean deleting;

    // At most ONE connector holds priority at a time (enforced by
    // ConnectorService.setPriority(), which clears every other holder in the same
    // transaction). While set and the connector still has unprocessed items, the drain
    // scheduler gives this connector all embedding throughput: its drain bypasses the
    // concurrency limiter and every other connector's drain loop yields between items -
    // see SyncOrchestratorService.
    @Column(nullable = false)
    private boolean priority;

    // Whether sync should walk subdirectories (true, default) or list only the configured
    // folder's immediate contents (false) - see LocalFolderSession/UploadedFilesSession.list().
    // Also honored by SVN and network share sources (Capabilities.recursiveScope); meaningless
    // for every other connector type, always true there.
    @Column(nullable = false)
    private boolean recursive = true;

    // FILES-only: comma-separated, lowercase, dot-less extensions this folder should index
    // ("pdf,docx,xlsx"). null/blank means "every type Proxyma knows how to convert" - the
    // behavior every folder registered before this column existed had, and still the behavior for
    // any other connector type. Parsed and applied in the files connectors' sync() via
    // isSupportedFileType(file, enabledExts); the extension set is stored per-connector rather than
    // globally so two folders can be scoped differently. Since 2026-09-27 the SVN, network share and
    // SharePoint connectors honor it too (Capabilities.fileTypeFilter); for SVN a null here
    // leaves the svn indexed-extensions setting in charge, as before.
    @Column(length = 2048)
    private String fileTypes;

    /**
     * Whether this connector's documents are extracted into the knowledge graph.
     *
     * <p>OFF by default, and ANDed with the deployment-wide graph.enabled switch - see
     * GraphSettings, which owns the other half. Two switches rather than one because they answer
     * different questions: the global one is "may this cost money at all on this deployment", the
     * per-connector one is "is THIS corpus worth extracting". A team wiki is; a 40,000-file
     * archive of build logs is not, and paying an LLM call per chunk of it is how an operator
     * discovers that on an invoice.
     *
     * <p>Switching it OFF removes what this connector already contributed (see
     * ConnectorService) - leaving the rows would keep answering from a corpus the operator has
     * just said to stop using, which is worse than either state they asked for.
     *
     * <p><b>columnDefinition carries the DEFAULT, and it is load-bearing.</b> Without it Hibernate
     * emits {@code ADD COLUMN graph_enabled boolean not null}, which PostgreSQL REFUSES on a table
     * that already has rows - there is no value it could put in the existing ones. ddl-auto=update
     * logs that failure and carries on, so the column never appears while the entity goes on
     * selecting it, and every read of registered_connectors fails during bean construction. That
     * took the production demo down on 2026-08-19.
     *
     * <p>A default makes the same ALTER legal, and it is applied during EntityManagerFactory init -
     * before any repository can read - rather than depending on SchemaMigration's @PostConstruct,
     * which is NOT ordered before the beans that query connectors while being constructed.
     *
     * <p>An EMPTY table accepts the no-default form. That is why the dev deployment went green (no
     * connectors there) and why no local H2 boot could have caught it (H2 created the table whole,
     * with the column already in it). Neither environment could reproduce production.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean graphEnabled = false;

    /**
     * The connection this source is read through - one of its OWNER's connections, which holds the
     * server and the credential (ConnectorConnection). Null for a source type that needs none: a
     * folder, uploaded files, a website, a network share.
     *
     * <p>Read it through {@code ConnectorEndpointResolver} and nowhere else. Sync, the chat tools and
     * the MCP tools all have to agree about which server a source lives on and which credential
     * reaches it, and one reader is how they keep agreeing.
     *
     * <p>Nullable for the schema reason on {@link #graphEnabled}: a NOT NULL column with no default
     * is refused by PostgreSQL on a table that already has rows.
     */
    @Column(length = 36)
    private java.util.UUID connectionId;

    @Column
    private String indexedWithEmbeddingModel;

    // Provider the live collection is currently keyed to, alongside indexedWithEmbeddingModel -
    // without this, a provider-only change (e.g. ollama -> openai) with a same-named model would
    // not even be detected as a model change by ConnectorService.reindexConnector().
    @Column
    private String indexedWithEmbeddingProvider;

    // True from the moment a model-changed reindex starts until its backlog fully drains with no
    // failures. On backing stores that support mixed-dimension collections (see
    // VectorStoreService.supportsMixedDimensionCollection(), true for Postgres) this is the only
    // signal that a reindex is in flight - reindexShadowCollectionId stays null for that path since
    // documents are replaced in place instead. Surfaced to the UI (connector card/modal) so users
    // know old search results may still reflect the previous model until this clears.
    @Column(nullable = false)
    private boolean reindexInProgress;

    // Non-null only while a model-changed reindex is building a "shadow" collection
    // (id = collectionId + "_reindex") alongside the still-live/searchable base collection - see
    // ConnectorService.reindexConnector() and SyncService.reconcile()/process(). Only used on backing
    // stores that do NOT support mixed-dimension collections (ChromaDB/desktop) - a collection's
    // dimension is fixed at creation there, so old and new embeddings cannot coexist under one
    // collection id. Ingest threads this value (read fresh from this entity on every sync pass, not
    // cached in memory) so a crash/restart mid-reindex simply resumes writing into the same shadow
    // collection. Cleared back to null once the swap into the live collection succeeds; left set
    // (with the shadow collection intact) if any item failed, so the old data stays live/searchable
    // indefinitely rather than risk swapping in a partially-reindexed collection.
    @Column
    private String reindexShadowCollectionId;

    @Column
    private Instant lastSyncedAt;

    // Timestamp of the last sync that performed a full remote enumeration (as opposed to an
    // incremental/modified-since fetch). Only a full enumeration can safely purge (mark deleted)
    // tracked items absent from it, so SyncOrchestratorService periodically forces a full sync
    // even once incremental sync is active, to catch deletions an incremental sync structurally
    // cannot see (an item untouched since the last sync is indistinguishable from a deleted one
    // in an incremental result).
    @Column
    private Instant lastFullSyncAt;

    /**
     * Where the next pass resumes - what the last COMPLETE pass's listing handed back
     * (Listing.nextCursor): a change token, a tree hash, a timestamp. Opaque to everything but the
     * source's own connector, null until one has, and written together with lastSyncedAt so a pass
     * that failed half way is repeated from where the last good one ended. TEXT, nullable: a delta
     * link can be long, and a NOT NULL column with no default is refused on a populated table.
     */
    @Column(columnDefinition = "TEXT")
    private String syncCursor;

    @Column(nullable = false)
    private Instant createdAt;

    public RegisteredConnector() {
        this.status = Status.ACTIVE;
        this.shared = false;
        this.allItemsFetched = false;
        this.deleting = false;
        this.priority = false;
        this.reindexInProgress = false;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ConnectorType getConnectorType() { return connectorType; }
    public void setConnectorType(ConnectorType connectorType) { this.connectorType = connectorType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getLastSyncError() { return lastSyncError; }
    // CUT TO THE COLUMN HERE, the one door every writer uses. A crawl's note names the pages that
    // would not load, a wiki's addresses run to two hundred characters each, and a note past 500
    // was refused by the database - at the very end of the pass, after every page had been
    // fetched. The pass was recorded as failed, lastSyncedAt never moved, the source was due again
    // a minute later, and it crawled the whole site again, for ever, showing no items (found
    // 2026-10-02 by crawling a public wiki; the installed 0.22.0 does it).
    public void setLastSyncError(String lastSyncError) {
        this.lastSyncError = boundedSyncError(lastSyncError);
    }

    /** A sync error cut to fit its column - for the setter and for the targeted update alike. */
    public static String boundedSyncError(String lastSyncError) {
        return lastSyncError != null && lastSyncError.length() > LAST_SYNC_ERROR_MAX
                ? lastSyncError.substring(0, LAST_SYNC_ERROR_MAX - 3) + "..."
                : lastSyncError;
    }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public int getProcessedCount() { return processedCount; }
    public void setProcessedCount(int processedCount) { this.processedCount = processedCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }

    public boolean isAllItemsFetched() { return allItemsFetched; }
    public void setAllItemsFetched(boolean allItemsFetched) { this.allItemsFetched = allItemsFetched; }

    public boolean isDeleting() { return deleting; }
    public void setDeleting(boolean deleting) { this.deleting = deleting; }

    public boolean isPriority() { return priority; }
    public void setPriority(boolean priority) { this.priority = priority; }

    public boolean isRecursive() { return recursive; }
    public void setRecursive(boolean recursive) { this.recursive = recursive; }

    public boolean isGraphEnabled() { return graphEnabled; }
    public void setGraphEnabled(boolean graphEnabled) { this.graphEnabled = graphEnabled; }

    public String getFileTypes() { return fileTypes; }
    public void setFileTypes(String fileTypes) { this.fileTypes = fileTypes; }

    public java.util.UUID getConnectionId() { return connectionId; }
    public void setConnectionId(java.util.UUID connectionId) { this.connectionId = connectionId; }

    // Returns the value AS STORED, which is the ENC( envelope - see the field. Callers other than
    // ConnectorEndpointResolver want the resolver, not this.

    public String getIndexedWithEmbeddingModel() { return indexedWithEmbeddingModel; }
    public void setIndexedWithEmbeddingModel(String indexedWithEmbeddingModel) { this.indexedWithEmbeddingModel = indexedWithEmbeddingModel; }

    public String getIndexedWithEmbeddingProvider() { return indexedWithEmbeddingProvider; }
    public void setIndexedWithEmbeddingProvider(String indexedWithEmbeddingProvider) { this.indexedWithEmbeddingProvider = indexedWithEmbeddingProvider; }

    public boolean isReindexInProgress() { return reindexInProgress; }
    public void setReindexInProgress(boolean reindexInProgress) { this.reindexInProgress = reindexInProgress; }

    public String getReindexShadowCollectionId() { return reindexShadowCollectionId; }
    public void setReindexShadowCollectionId(String reindexShadowCollectionId) { this.reindexShadowCollectionId = reindexShadowCollectionId; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getSyncCursor() { return syncCursor; }
    public void setSyncCursor(String syncCursor) { this.syncCursor = syncCursor; }

    public Instant getLastFullSyncAt() { return lastFullSyncAt; }
    public void setLastFullSyncAt(Instant lastFullSyncAt) { this.lastFullSyncAt = lastFullSyncAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
