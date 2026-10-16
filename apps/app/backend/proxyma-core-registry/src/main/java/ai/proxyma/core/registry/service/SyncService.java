package ai.proxyma.core.registry.service;

import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.common.diagnostics.ErrorCategory;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.files.MissingUploadFolder;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.registry.event.ConnectorSyncedEvent;
import ai.proxyma.core.registry.event.DocumentIndexedEvent;
import ai.proxyma.core.registry.event.DocumentPurgedEvent;
import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.files.upload.service.StorageService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.quota.UserQuotaPort;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.dto.StorageSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.springframework.beans.factory.annotation.Autowired;
import ai.proxyma.core.system.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final TrackedDocumentRepository repository;
    private final RegisteredConnectorRepository connectorRepository;
    private final DocumentFetcher documentConverter;
    private final EmbeddingService embeddingService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppPaths appPaths;
    private final SettingsService settingsService;
    private final UserQuotaPort quotaPort;
    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;
    private final FileMetadataRepository fileMetadataRepository;
    private final StorageService storageService;
    // INDEXING telemetry. Recorded here rather than from a listener on ConnectorSyncedEvent
    // because that event carries only a connectorId - the counts and the incremental/complete
    // flags that make a sync worth measuring exist only inside reconcile()'s own scope.
    private final TelemetryShipper telemetryShipper;

    // Same property/default as LocalDiskStorageService/StorageStatsService - see
    // purgedMarkdownSnapshotPath() below.
    @org.springframework.beans.factory.annotation.Value("${proxyma.storage.local.root:/data}")
    private String storageRoot;

    public SyncService(TrackedDocumentRepository repository,
                       RegisteredConnectorRepository connectorRepository,
                       DocumentFetcher documentFetcher,
                       EmbeddingService embeddingService,
                       KnowledgeGraphService knowledgeGraphService,
                       ApplicationEventPublisher eventPublisher,
                       AppPaths appPaths,
                       SettingsService settingsService,
                       @Autowired(required = false) UserQuotaPort quotaPort,
                       VectorStoreService vectorStoreService,
                       BM25IndexService bm25IndexService,
                       FileMetadataRepository fileMetadataRepository,
                       StorageService storageService,
                       TelemetryShipper telemetryShipper) {
        this.repository = repository;
        this.connectorRepository = connectorRepository;
        this.documentConverter = documentFetcher;
        this.embeddingService = embeddingService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.eventPublisher = eventPublisher;
        this.appPaths = appPaths;
        this.settingsService = settingsService;
        this.quotaPort = quotaPort;
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
        this.fileMetadataRepository = fileMetadataRepository;
        this.storageService = storageService;
        this.telemetryShipper = telemetryShipper;
    }

    // ---- Stopping with the application ---------------------------------------------------------
    //
    // SET BEFORE THE DATABASES AND THE VECTOR STORE GO (SyncOrchestratorService, on the context's
    // close), since the 0.36.0 feedback. Passes and drains ran on into the shutdown: H2 closed under
    // them, ChromaDB was stopped mid-upsert, the event executor refused their notifications, and
    // every item in flight was recorded as a FAILED document - with an ERROR and a stack trace each,
    // a retry spent, and the source put in ERROR for a pass that had simply been cut short. Now a
    // pass stops between items and records nothing about itself, and an item cut short goes back to
    // the queue as if the process had stopped (see stoppedByShutdown).
    private volatile boolean stopping;

    /** From now on, passes and items stop at their next safe point and record no failure. */
    public void stopForShutdown() {
        stopping = true;
    }

    public boolean isStopping() {
        return stopping;
    }

    private static final int BATCH_SIZE = 100;
    // How long one pull from a source may go on once it has produced something, and how often the
    // source's counters are brought up to date while it is being listed. See pull().
    private static final long PULL_BUDGET_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long COUNT_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();
    // How many individual reconcileItem failures get their own ERROR line before the pass
    // switches to counting them. A fault that rejects one item rejects all of them, and the
    // first few carry the whole diagnosis; the rest are 26 MB of duplicate stack traces.
    private static final int MAX_LOGGED_SKIPS = 20;

    // Below this fraction of the connector's previously recorded itemCount, a "complete" sync is
    // treated as suspicious and purge is skipped even though the iterator reported a clean finish.
    // Guards against fetch-layer bugs that could report success despite badly truncated results
    // (defense in depth on top of the errored-vs-exhausted signal below, not a replacement for it).
    private static final double PURGE_SANITY_FLOOR = 0.5;

    // Even once incremental sync is active for a connector, force a full enumeration at least
    // this often - an incremental (modified-since) result can never detect a genuine remote
    // deletion (an untouched item and a deleted one look identical in that result set), so
    // deletions would otherwise never be reflected again after the first full sync.
    private static final java.time.Duration MAX_INCREMENTAL_SYNC_AGE = java.time.Duration.ofDays(1);

    /**
     * Whether this pass lists only what changed (ListRequest.Mode.CHANGES): the source has completed
     * a full pass, has synced before, its type lists incrementally, its periodic full pass is not due
     * ({@code MAX_INCREMENTAL_SYNC_AGE}) and nobody forced a full one. The one rule - the scheduler,
     * "Sync now" and "Sync now (changes)" all reach it with only {@code forceFull} differing.
     */
    static boolean incrementalPass(RegisteredConnector connector, ConnectorDescriptor descriptor, boolean forceFull,
                                   Instant now) {
        boolean dueForFullSync = connector.getLastFullSyncAt() == null
                || connector.getLastFullSyncAt().isBefore(now.minus(MAX_INCREMENTAL_SYNC_AGE));
        return connector.isAllItemsFetched()
                && connector.getLastSyncedAt() != null
                && descriptor.capabilities().incremental()
                && !dueForFullSync
                && !forceFull;
    }

    /**
     * Discovers what a connector's remote source currently looks like and reconciles it against
     * the DB: creates/updates TrackedDocument rows (QUEUED/DETECTED), purges rows for items no
     * longer present upstream, and advances lastSyncedAt/lastFullSyncAt. Does NOT embed anything -
     * that is a fully independent activity now, driven by SyncOrchestratorService's drain loop
     * pulling QUEUED/DETECTED/resumable-EMBEDDING rows straight from the DB on its own fast
     * schedule (see drainQueuedConnectors()/drainConnector()), not from whatever this particular
     * reconcile pass happened to discover. This split exists so a connector whose last reconcile
     * happened to be `incremental` (nothing changed upstream) never blocks its own backlog from
     * draining until the source's next sync-interval tick - previously reconcile() and
     * embedding ran back-to-back in one gated pass, so a connector sitting on a large backlog from
     * an earlier interrupted/incremental sync could go a full interval (up to 1h) with zero active
     * processing despite thousands of items sitting QUEUED.
     *
     * NOT @Transactional: each item commits independently so counts are visible to the UI during
     * long-running syncs.
     *
     * <p>{@code forceFull=true} bypasses the incremental-sync eligibility check below and always runs
     * the source's full enumeration (a FULL listing), regardless of how recently this connector last
     * completed one. Used by the manual per-connector "Sync now" action - an incremental pass is
     * structurally incapable of surfacing the things a user presses that button for: it only ever
     * returns items whose remote content changed after lastSyncedAt, so it can never observe items
     * a newly-shipped/fixed source has only just become able to enumerate (e.g. Bitbucket commit
     * history, newly-discoverable crawled pages), and it is never eligible to drive purgeDeleted()
     * (an unmodified item and a deleted one are indistinguishable in a modified-since result), so
     * an upstream deletion could otherwise not be reflected on demand at all.
     * <p>
     * Deliberately a transient parameter rather than persisting {@code allItemsFetched=false} (the
     * lever ConnectorService.retryFailed()/reindexConnector() use for the same effect): a forced sync
     * that then fails would leave the connector permanently flagged as never-fully-fetched off a
     * single button press. Scoped to one connector - syncAll()/syncConnector() deliberately keep the
     * normal incremental behavior, since forcing a full enumeration across every connector at once
     * would stampede every remote API simultaneously.
     * <p>
     * Cost is remote enumeration only, not re-embedding: reconcileItem() short-circuits every
     * already-COMPLETED, up-to-date item without so much as a DB write.
     */
    public PassResult reconcile(ConnectorSession session, ConnectorDescriptor descriptor, RegisteredConnector connector,
                                boolean forceFull) {
        UUID connectorId = connector.getId();
        // While a model-changed reindex is in flight for this connector, every ingest write
        // (vector upsert AND BM25 add - both go through the single collectionId parameter
        // chunkAndEmbed() takes) targets the disposable shadow collection
        // instead of the live one, so the live collection stays untouched and searchable under
        // the old model for the whole reindex. `connector` is loaded fresh from the DB by the
        // caller on every sync pass (SyncOrchestratorService never caches this entity across
        // ticks/restarts), so this always reflects the persisted value - a crash/restart mid-
        // reindex resumes writing into the same shadow collection rather than losing track of it.
        String shadowCollectionId = connector.getReindexShadowCollectionId();
        String collectionId = shadowCollectionId != null ? shadowCollectionId : connectorId.toString().replace("-", "");
        ConnectorType type = descriptor.type();
        long reconcileStart = System.nanoTime();
        Instant passStartedAt = Instant.now();
        Throwable fetchError = null;
        // Excludes DELETED rows, unlike RegisteredConnector.itemCount (which counts every row ever
        // created and only ever grows) - see countByConnectorIdAndStatusNot() for why the purge
        // sanity floor below needs a live count, not a historical one. And excludes uploads whose
        // bytes were purged after indexing: they are never listed again (purgeDeleted skips them for
        // the same reason), so counting them made every fully indexed upload source a short listing.
        long previousItemCount = repository.countByConnectorIdAndStatusNotAndContentPurgedFalse(
                connectorId, TrackedDocument.Status.DELETED);

        // Once a connector has completed one full enumeration, subsequent syncs use the
        // source's incremental (modified-since) query when available instead of always
        // re-walking the entire remote item list - see ListRequest.Mode.CHANGES. An
        // incremental result is inherently partial (only items touched since lastSyncedAt), so
        // it is NEVER eligible to drive purgeDeleted() - an item simply not modified since then
        // would be indistinguishable from a deleted one in that result set. A full sync is forced
        // periodically (MAX_INCREMENTAL_SYNC_AGE) regardless, so real deletions are still caught.
        boolean incremental = incrementalPass(connector, descriptor, forceFull, Instant.now());
        log.info("[Sync] Starting {} sync for connector={} source={}{}",
                incremental ? "incremental" : "full", connectorId, type,
                forceFull ? " (forced full)" : "");

        Set<String> seenIds = new HashSet<>();
        boolean complete = false;
        String fetchErrorMessage = null;
        // Per-item reconcile failures. Counted, not just logged: skipping a poisoned item is
        // deliberate (see the catch below), but a pass where EVERY item was skipped is a total
        // failure that used to report itself as a clean sync - see the summary block further down.
        int skippedItems = 0;
        String firstSkipMessage = null;

        // ── Reconcile: diff against source ───────────────────────────────────
        // source.sync()/syncSince() itself (not just iterating the result) used to run OUTSIDE
        // this try block - a source throwing before ever handing back an iterator (e.g.
        // WebCrawlConnector.crawl() runs its whole bounded BFS eagerly before sync() even returns,
        // so any exception inside that walk threw here) propagated all the way out of reconcile(),
        // skipping the ERROR-status/lastSyncError bookkeeping below entirely and leaving the
        // connector looking exactly like a healthy sync that legitimately found zero items - no
        // error surfaced anywhere in the UI. Now inside the try, so it's caught and reported the
        // same as a mid-iteration failure.
        Iterator<ItemMetadata> items = null;
        Listing listing = null;
        try {
            // The stored cursor goes with a CHANGES pass only: a full pass starts from nothing, so
            // a cursor a connector has outgrown can never stop it seeing everything.
            listing = session.list(incremental
                    ? ListRequest.changes(connector.getLastSyncedAt(), connector.getSyncCursor())
                    : ListRequest.full());
            items = listing;
            if (Listings.delegateOf(listing) instanceof MissingUploadFolder missing) {
                items = Listings.of(resolveMissingUploadFolder(missing, connectorId));
            }
            List<ItemMetadata> pending = new ArrayList<>();
            long countsUpdatedAt = System.nanoTime();
            int countedAt = 0;

            boolean sourceExhausted = pull(items, pending, BATCH_SIZE, System::nanoTime);

            while (!pending.isEmpty()) {
                if (isConnectorDeleting(connectorId)) {
                    log.info("[Sync] Aborting sync for connector={} - deletion in progress", connectorId);
                    return null;
                }
                if (stopping) return stoppedPass(connectorId, seenIds.size());

                ItemMetadata meta = pending.remove(0);
                seenIds.add(meta.getId());
                // An id longer than its column cannot be recorded - a crawled address with a long
                // query string. Left out with a line in the log, not counted as an item that
                // failed: counted, it put the whole source in ERROR on every pass for one address.
                boolean recordable = meta.getId() == null || meta.getId().length() <= TrackedDocument.SOURCE_ID_MAX_CHARS;
                if (!recordable) {
                    log.warn("[Sync] Leaving out an item of connector={} whose id is {} characters long",
                            connectorId, meta.getId().length());
                }
                // reconcileItem()'s persisted row (QUEUED/DETECTED) is all that matters here - the
                // returned TrackedDocument itself is no longer collected into a toProcess list;
                // embedding is now the drain loop's job, sourced fresh from the DB, not from this
                // particular reconcile pass's discoveries.
                //
                // Isolated per-item: a single poisoned item (e.g. a DB write rejected for a reason
                // specific to that item's content) must not abort the whole pass - the outer catch
                // below previously turned one bad item into a total reconcile failure, silently
                // dropping every other still-pending item and leaving the connector stuck retrying
                // the same poisoned item on every future sync (it sorts first in pagination, so it
                // is always the first thing hit again). Log and skip instead; seenIds already has
                // this id, so it will not be purged as deleted even though it was never persisted.
                try {
                    if (recordable) reconcileItem(meta, connectorId, type);
                } catch (Exception e) {
                    skippedItems++;
                    if (firstSkipMessage == null) firstSkipMessage = SyncErrorAware.summarize(e);
                    // Only the first few at ERROR. A schema-level fault rejects every row, and the
                    // one that actually happened wrote 12474 identical stack-bearing lines that
                    // buried every other event in the log; the count in the summary below is what
                    // makes the scale legible.
                    if (skippedItems <= MAX_LOGGED_SKIPS) {
                        log.error("[Sync] Skipping item id={} for connector={} - reconcileItem failed: {}",
                                meta.getId(), connectorId, e.getMessage());
                    } else if (skippedItems == MAX_LOGGED_SKIPS + 1) {
                        log.error("[Sync] Further reconcileItem failures for connector={} will be "
                                + "counted but not logged individually", connectorId);
                    }
                }

                // The counters are what the source's row shows ("Discovering items..." until the
                // first one is counted) and what lets the drain loop start embedding. Every hundred
                // items, AND every couple of seconds while anything new has been listed: a source
                // that finds a page every second or two never reached a hundred before it was done,
                // so a whole crawl showed zero items until it ended. BEFORE the refill below, which
                // can block for as long as the source takes to find its next item.
                long now = System.nanoTime();
                if (seenIds.size() % BATCH_SIZE == 0
                        || (seenIds.size() > countedAt && now - countsUpdatedAt >= COUNT_INTERVAL_NANOS)) {
                    updateConnectorCounts(connectorId);
                    countsUpdatedAt = now;
                    countedAt = seenIds.size();
                }

                // Refilled when everything pulled so far has been reconciled - not earlier. It
                // used to refill with twenty still pending, which bought nothing (the pull runs on
                // this same thread) and, once a pull could stop on the clock, cost a great deal:
                // every item reconciled was followed by another two-second pull, so what was
                // recorded fell further behind what had been found with every page.
                if (!sourceExhausted && pending.isEmpty()) {
                    sourceExhausted = pull(items, pending, BATCH_SIZE, System::nanoTime);
                    log.debug("[Sync] Pulled {} more item(s) for connector={}", pending.size(), connectorId);
                }
            }
            complete = sourceExhausted;
        } catch (Exception e) {
            // A database or a listing closed under the pass by the application stopping.
            if (stopping) return stoppedPass(connectorId, seenIds.size());
            log.error("[Sync] Reconcile interrupted for connector={} after {} items: {}",
                    connectorId, seenIds.size(), e.getMessage());
            fetchErrorMessage = SyncErrorAware.summarize(e);
            fetchError = e;
        }

        // A source iterator that swallows a fetch exception internally (rather than
        // propagating it) looks identical to a genuinely exhausted iterator from here -
        // items.hasNext() just returns false either way. SyncErrorAware lets such sources
        // (Jira, Confluence) report "I stopped early because of an error", so a partial fetch is
        // never mistaken for a complete one and used to justify purging items the sync never
        // actually reached.
        if (complete && items instanceof SyncErrorAware errorAware && errorAware.hasErrored()) {
            log.warn("[Sync] Treating sync as incomplete for connector={} - source reported a fetch error mid-pagination", connectorId);
            complete = false;
            fetchErrorMessage = errorAware.getErrorMessage();
        }

        // Partial is a SUCCESS that did not see everything - a crawl that stopped at its page cap,
        // a listing that skipped what it could not read. It must suppress purge-by-absence for the
        // same reason an error does (an item never reached is indistinguishable from one deleted
        // upstream), but unlike an error it must NOT put the source in ERROR or hold back the
        // lastSyncedAt checkpoint: hitting a configured cap every run is the connector working as
        // configured. Conflating the two either deleted every page past the cap or left a healthy
        // source permanently red.
        boolean partial = complete && items instanceof PartialSyncAware partialAware && partialAware.isPartial();
        String partialReason = partial ? ((PartialSyncAware) items).getPartialReason() : null;

        // Surface a persistent fetch failure at the connector level - previously nothing ever
        // moved RegisteredConnector.status away from ACTIVE, so a connector whose source had
        // returning 401 forever) still showed ACTIVE with its last-good item counts, completely
        // indistinguishable from a healthy connector in the UI - see ../proxyma-docs/LESSONS_LEARNED.md
        // "Connector status never reflected persistent sync failures". Reset back to ACTIVE the
        // next time a sync actually completes clean.
        // A pass in which nothing could be persisted is a FAILED pass, whatever the source did.
        // Previously `complete` came from sourceExhausted alone, so a database that rejected every
        // single insert still produced "complete=true", left the connector ACTIVE with no
        // lastSyncError, and set allItemsFetched - indistinguishable in the UI from a healthy
        // connector holding zero items. That is how a broken schema stayed invisible through 12474
        // consecutive failures across seven connectors.
        //
        // Not complete also means the deleted-item purge below is skipped, which is the right way
        // round: a pass that could not write must not be trusted to decide what to delete.
        if (skippedItems > 0) {
            log.warn("[Sync] connector={} skipped {} of {} item(s) during reconcile - first failure: {}",
                    connectorId, skippedItems, seenIds.size(), firstSkipMessage);
        }
        boolean nothingPersisted = skippedItems > 0 && skippedItems >= seenIds.size();
        if (nothingPersisted) {
            complete = false;
            if (fetchErrorMessage == null) {
                fetchErrorMessage = "No items could be recorded (" + skippedItems + " failed): " + firstSkipMessage;
            }
        } else if (skippedItems > 0 && fetchErrorMessage == null) {
            // Partial: the pass still counts as complete (one poisoned item must not block the
            // purge), but the connector says so rather than showing a clean ACTIVE.
            fetchErrorMessage = skippedItems + " item(s) could not be recorded: " + firstSkipMessage;
        }

        // A COMPLETE FULL LISTING FAR SHORTER THAN WHAT IS INDEXED is not trusted to purge (the
        // sanity floor below), and since 2026-10-14 it is not reported as a healthy pass either.
        // A folder that was moved, unmounted or on an offline drive lists nothing, and the source
        // finished "complete" with 220 documents still searchable and nothing on its card to say
        // its folder was gone (telemetry dff32853). It says so now, and the pass does not count as
        // a full enumeration. A listing of NOTHING is an error, retried on the failing-source
        // backoff; a listing of some is a note beside ACTIVE - files may really have been deleted.
        boolean suspiciouslyFew = previousItemCount > 0 && seenIds.size() < previousItemCount * PURGE_SANITY_FLOOR;
        final boolean doubtful = !incremental && !partial && complete && skippedItems == 0 && suspiciouslyFew;
        final boolean listedNothing = doubtful && seenIds.isEmpty();
        final String doubtNote = doubtful
                ? shortListingNote(type, seenIds.size(), previousItemCount) : null;

        final boolean syncFailed = !complete || skippedItems > 0 || listedNothing;
        final String capturedErrorMessage = listedNothing ? doubtNote : fetchErrorMessage;
        // Every write to the source's row below is a targeted update of the columns sync owns,
        // never a save() of the row read here - see RegisteredConnectorRepository.updateCounts.
        connectorRepository.findById(connectorId).ifPresent(r -> {
            if (syncFailed) {
                connectorRepository.updateSyncState(connectorId, RegisteredConnector.Status.ERROR,
                        RegisteredConnector.boundedSyncError(capturedErrorMessage));
            } else {
                // A healthy pass that did not see everything - a folder past its tier's file cap, a
                // crawl stopped at its page limit - says WHY on the source, as a note beside an
                // ACTIVE status rather than as an error. It used to be logged and nowhere else, so
                // a Community folder of 500 files read as fully indexed at 100.
                String note = RegisteredConnector.boundedSyncError(partial ? partialReason : doubtNote);
                if (r.getStatus() == RegisteredConnector.Status.ERROR
                        || !java.util.Objects.equals(r.getLastSyncError(), note)) {
                    connectorRepository.updateSyncState(connectorId, RegisteredConnector.Status.ACTIVE, note);
                }
            }
        });

        updateConnectorCounts(connectorId);
        // allItemsFetched tracks whether a full enumeration has ever completed - an incremental
        // sync never touches it (it stays true, since it was already true to be eligible for
        // incremental sync in the first place); only a full sync's outcome can set or clear it. A
        // doubtful listing is not one.
        if (!incremental) {
            connectorRepository.updateAllItemsFetched(connectorId, complete && !doubtful);
        }
        log.info("[Sync] Reconcile done for connector={} seen={} skipped={} complete={}",
                connectorId, seenIds.size(), skippedItems, complete);

        // An incremental sync only ever returns items whose upstream content changed - any
        // QUEUED/DETECTED row left over from an earlier full sync whose processing was
        // interrupted (app restart mid-embed) is unmodified upstream, so it's invisible to this
        // and every future incremental sync forever. Detect that here and force the *next* sync
        // to go full (which enumerates every item via source.sync(), giving these orphans a
        // live ItemMetadata again) instead of leaving them stranded for up to
        // MAX_INCREMENTAL_SYNC_AGE.
        if (incremental) {
            List<String> pendingSourceIds = repository.findSourceIdsByConnectorIdAndStatusIn(connectorId,
                    List.of(TrackedDocument.Status.QUEUED, TrackedDocument.Status.DETECTED));
            boolean hasOrphans = pendingSourceIds.stream().anyMatch(id -> !seenIds.contains(id));
            if (hasOrphans) {
                log.warn("[Sync] connector={} has QUEUED/DETECTED items an incremental sync didn't touch - forcing next sync to be full", connectorId);
                connectorRepository.clearLastFullSyncAt(connectorId);
            }
        }

        // ── Trailing bookkeeping: purge, lastSyncedAt/lastFullSyncAt advance ────────────────
        // Moved here from the old process() (which no longer exists as a sync-gated phase) -
        // none of this depends on whether any item has actually been embedded yet, only on what
        // this reconcile pass's own enumeration found. NOTE: does NOT include the reindex-swap /
        // indexedWithEmbeddingModel advance - that genuinely does depend on embedding having
        // caught up, so it lives in onConnectorDrained() instead, called by the drain loop only
        // once a connector's backlog is actually observed empty (see SyncOrchestratorService).
        int removed = 0;
        if (incremental) {
            log.debug("[Sync] Skipping purge for connector={} - incremental sync only saw a partial (modified-since) result", connectorId);
        } else if (partial) {
            log.info("[Sync] Skipping purge for connector={} - source reported a partial listing: {}",
                    connectorId, partialReason != null ? partialReason : "reason not given");
            removed = purgeGone(connectorId, collectionId, seenIds, ((PartialSyncAware) items).getGoneItemIds());
        } else if (complete && suspiciouslyFew) {
            log.warn("[Sync] Skipping purge for connector={} - seen={} is suspiciously low vs previous itemCount={}",
                    connectorId, seenIds.size(), previousItemCount);
        } else if (complete) {
            removed = purgeDeleted(connectorId, collectionId, seenIds);
        } else {
            log.warn("[Sync] Skipping purge for connector={} - iteration was incomplete", connectorId);
        }
        // A purge cut short: the checkpoint is not moved, so the next pass lists and purges again.
        if (stopping) return stoppedPass(connectorId, seenIds.size());
        updateConnectorCounts(connectorId);
        boolean wasFullSync = !incremental && complete && !doubtful;
        // lastSyncedAt is the "since" cutoff the next incremental sync passes to
        // source.syncSince() - only advance it when this sync actually completed
        // error-free. If an incremental fetch errored partway (complete=false, same signal
        // purge uses above), advancing the checkpoint anyway would silently skip re-fetching
        // the window that was never actually retrieved; leaving it unadvanced means the next
        // sync (incremental or the periodic forced full one) re-covers that window instead.
        //
        // The checkpoint is when the pass STARTED, not when it ended. The next incremental pass asks
        // for what changed since it (each connector adds a small overlap for clock skew), and an
        // item edited after the listing had passed it but before a long pass ended - an hour-long
        // commit walk, a large delta - was in neither pass when the checkpoint was the end.
        if (complete && !listedNothing) {
            // The cursor the listing handed back, with the checkpoint - only for a connector that
            // keeps one, so a connector that does not never has one written over.
            String cursor = descriptor.capabilities().cursor() && listing != null
                    ? listing.nextCursor() : connector.getSyncCursor();
            if (wasFullSync) connectorRepository.markFullySynced(connectorId, passStartedAt, cursor);
            else connectorRepository.markSynced(connectorId, passStartedAt, cursor);
        } else {
            log.warn("[Sync] Not advancing lastSyncedAt for connector={} - sync did not complete error-free", connectorId);
        }
        // Fires even when seenItems=0 (nothing changed) - ConnectorUpdateEvent only fires per
        // reconciled document, so a no-op sync would otherwise leave the frontend's displayed
        // lastSyncedAt stale indefinitely despite the backend value being correct.
        notifyPage(new ConnectorSyncedEvent(this, connectorId));
        log.info("[Sync] Completed {} reconcile for connector={} source={} seenItems={} skipped={} complete={}",
                incremental ? "incremental" : "full", connectorId, type, seenIds.size(), skippedItems, complete);
        // The same six facts the log line above states, minus the connectorId. `complete` is the
        // one that matters most: a sync that ended without covering everything it meant to is the
        // failure this telemetry exists to make visible across installations, and it is invisible
        // from the outside - the pass returns normally, the UI shows a finished sync, and only
        // the next run's re-covered window hints that anything went wrong.
        telemetryShipper.record(TelemetryEventType.INDEXING, Map.of(
                "connectorType", type.name(),
                "incremental", incremental,
                "complete", complete,
                "seenItems", seenIds.size(),
                "skippedItems", skippedItems));
        // The pass's duration and how it ended, by connector TYPE and an error CATEGORY - the
        // message (fetchErrorMessage) names hosts and projects and stays in the UI.
        String mode = incremental ? "incremental" : "full";
        String outcome = !complete ? "incomplete"
                : partial ? "partial"
                : skippedItems > 0 ? "complete_with_skips"
                : doubtful ? "short_listing"
                : "complete";
        String error = fetchError != null ? ErrorCategory.of(fetchError)
                : !complete ? "source_error"
                : skippedItems > 0 ? "item_error"
                : "none";
        Diagnostics.timeSince(DiagOp.SYNC_RECONCILE, reconcileStart, "connector", type.name(), "mode", mode);
        Diagnostics.count(DiagCounter.SYNC_OUTCOME, "connector", type.name(), "mode", mode,
                "outcome", outcome, "error", error);
        return new PassResult(seenIds.size(), skippedItems, removed, incremental, complete && !syncFailed,
                syncFailed ? capturedErrorMessage : partial ? partialReason : doubtNote);
    }

    // A pass the application's shutdown cut short. Null, like a pass that found its source being
    // deleted: nothing is recorded about it - no ERROR on the source, no failure backoff, no audit
    // entry, no checkpoint - so the next start lists the source again as if this pass never ran.
    private PassResult stoppedPass(UUID connectorId, int seen) {
        log.info("[Sync] Pass for connector={} stopped after {} item(s) - the application is shutting down",
                connectorId, seen);
        return null;
    }

    // What a source says when its listing came back far shorter than what is indexed - in words,
    // with both numbers, and saying that nothing was removed, which is what makes it safe to read.
    /**
     * Records items a source's owner PUSHED, one by one as a listing would - see
     * SyncOrchestratorService.recordPushed, the only caller, which holds the source's pass flag
     * around this. One item that cannot be recorded does not stop the rest; the next pass lists it
     * again.
     */
    void recordItems(RegisteredConnector connector, List<ItemMetadata> items) {
        for (ItemMetadata meta : items) {
            try {
                reconcileItem(meta, connector.getId(), connector.getConnectorType());
            } catch (Exception e) {
                log.error("[Sync] Could not record pushed item id={} for connector={}: {}",
                        meta.getId(), connector.getId(), e.getMessage());
            }
        }
        updateConnectorCounts(connector.getId());
    }

    static String shortListingNote(ConnectorType type, int seen, long before) {
        boolean files = type != null && type.isFileBacked();
        if (seen == 0) {
            return files
                    ? "The folder listed no files (" + before + " before). Nothing was removed from the index"
                            + " - check that the folder is still there."
                    : "The source listed no items (" + before + " before). Nothing was removed from the index"
                            + " - check that the source is still reachable.";
        }
        return "This pass listed " + seen + " of the " + before + " " + (files ? "files" : "items")
                + " indexed before, so nothing was removed from the index - check that the "
                + (files ? "folder" : "source") + " is still complete.";
    }

    /**
     * How one reconcile pass ended, for the caller that knows who asked for it - the audit log's
     * entry is written there, since only the orchestrator knows a scheduled pass from "Sync now".
     *
     * @param found    items the listing returned
     * @param skipped  items it returned that could not be recorded
     * @param removed  tracked items purged as gone upstream
     * @param ok       false when the pass is the source's ERROR - incomplete, or anything skipped
     * @param message  the error the source now shows when not ok, else the partial-listing note
     */
    public record PassResult(int found, int skipped, int removed, boolean incremental, boolean ok,
                             String message) {}

    // An upload source whose folder is not on the server: an EMPTY listing when nothing tracked
    // there still expects bytes, the connector's failure otherwise.
    //
    // Enterprise Server deletes an upload's bytes once it is indexed (contentPurged, keeping the
    // text), and until 2026-10-10 removing a source's last file removed its folder as well. So a
    // fully indexed source has no folder on every installation that ran such a build, and each of
    // its syncs failed with "no longer on the server" over documents that were all indexed and
    // searchable. The connector cannot tell that apart from files that really went missing - it
    // cannot see the documents - so it reports the absence by type and this decides.
    //
    // Empty is safe here because purge() never removes a content-purged document, which is what
    // every live document is on this branch: the listing deletes nothing. Any document that is NOT
    // purged - queued, failed, or indexed but still holding its bytes - needs files in that folder,
    // and their absence is the real error it always was. The folder is put back, empty, so the
    // next upload into the source has somewhere to land.
    private Iterator<ItemMetadata> resolveMissingUploadFolder(MissingUploadFolder missing, UUID connectorId) {
        long expectingBytes = repository.findByConnectorId(connectorId).stream()
                .filter(d -> d.getStatus() != TrackedDocument.Status.DELETED)
                .filter(d -> !d.isContentPurged())
                .count();
        if (expectingBytes > 0) {
            log.warn("[Sync] connector={} upload folder is missing and {} document(s) still need their files",
                    connectorId, expectingBytes);
            return missing;
        }
        boolean recreated = missing.recreateEmpty();
        log.info("[Sync] connector={} upload folder was missing; every document is indexed and purged, "
                + "so it is an empty listing{}", connectorId, recreated ? " (folder recreated)" : "");
        return Collections.emptyIterator();
    }

    // Called by SyncOrchestratorService's drain loop the moment a connector's QUEUED/DETECTED/
    // resumable-EMBEDDING backlog is observed empty - i.e. once processing has actually caught up,
    // not just once reconcile() has run. Covers two cases via applyReindexSwapIfDue(): a connector
    // indexed for the first time gets indexedWithEmbeddingModel stamped; a connector mid
    // model-changed-reindex gets its shadow collection swapped live (or left in place for retry if
    // any item failed). Safe to call repeatedly. Writes only the columns it decides, by targeted
    // update - the row it reads here is never saved back (see RegisteredConnectorRepository).
    public void onConnectorDrained(UUID connectorId) {
        connectorRepository.findById(connectorId).ifPresent(this::applyReindexSwapIfDue);
    }

    // Completion detection + swap trigger for a model-changed reindex. Called only from
    // onConnectorDrained(), itself only called once the drain loop observes a connector's
    // QUEUED/DETECTED/resumable-EMBEDDING backlog is empty - so every item is guaranteed to have
    // reached a terminal TrackedDocument.Status (COMPLETED or FAILED) by the time this runs. Reads
    // reindexShadowCollectionId fresh off `res` (loaded moments ago by the caller), so this is
    // correct whether this is a normal drain (shadow null - just advances
    // indexedWithEmbeddingModel as before), a fresh model-changed reindex, or a resumed one after
    // an app restart mid-reindex.
    private void applyReindexSwapIfDue(RegisteredConnector res) {
        String shadowCollectionId = res.getReindexShadowCollectionId();
        UUID connectorId = res.getId();
        if (shadowCollectionId == null) {
            if (res.isReindexInProgress()) {
                // Mixed-dimension-capable store (Postgres) - no shadow was ever created, documents
                // were replaced in place as each one completed (see ConnectorService.reindexConnector()
                // / TrackedDocument.reindexReplace). Same completion gate as the shadow path below:
                // only stamp the new model/provider and clear the in-flight flag once every item has
                // reached a terminal status with none failed - a failed item may have deleted its old
                // vector before its new one failed to insert, so leaving reindexInProgress set (and
                // the old model/provider stamped) keeps SearchService's old-model search pass running
                // for this connector until a retry+re-drain completes it cleanly.
                if (res.getFailedCount() > 0) {
                    log.warn("[Sync] Reindex (in-place, no shadow) for connector={} finished with {} failed item(s) - " +
                            "leaving reindexInProgress set for inspection/retry, indexedWithEmbeddingModel not advanced",
                            connectorId, res.getFailedCount());
                    return;
                }
                connectorRepository.finishReindex(connectorId,
                        settingsService.getEmbeddingModel(), settingsService.getEmbeddingProvider());
                log.info("[Sync] Reindex complete for connector={} (in-place replace, mixed-dimension store) - indexedWithEmbeddingModel={}",
                        connectorId, settingsService.getEmbeddingModel());
                return;
            }
            // STAMPED ONCE, the first time the source is indexed, and otherwise only by a reindex.
            //
            // This used to restamp the CURRENT model at every drain. Re-indexing after a model
            // change is manual, per source, so a source whose vectors were all the old model's
            // read as the new model's as soon as one item was added or retried - and its manual
            // Reindex then saw no model change, skipped the shadow collection, and on ChromaDB
            // deleted each document's old vectors before the new ones failed on the dimension,
            // leaving every document FAILED and unsearchable.
            connectorRepository.stampFirstEmbeddingModel(connectorId,
                    settingsService.getEmbeddingModel(), settingsService.getEmbeddingProvider());
            return;
        }
        if (res.getFailedCount() > 0) {
            // Safe default: leave the shadow collection and reindexShadowCollectionId in place,
            // and deliberately do NOT advance indexedWithEmbeddingModel - the live collection
            // still holds the old model's vectors, so it must keep reporting the old model as
            // "what it's indexed with". Retrying the failed items and re-running reindex is what
            // lets this pass through again and swap on a later, fully-clean completion.
            log.warn("[Sync] Reindex for connector={} finished with {} failed item(s) - leaving shadow " +
                    "collection={} in place for inspection/retry, live collection left untouched, " +
                    "indexedWithEmbeddingModel not advanced", connectorId, res.getFailedCount(), shadowCollectionId);
            return;
        }
        String liveCollectionId = connectorId.toString().replace("-", "");
        try {
            vectorStoreService.swapCollection(liveCollectionId, shadowCollectionId);
            bm25IndexService.swapCollection(liveCollectionId, shadowCollectionId);
            connectorRepository.finishReindex(connectorId,
                    settingsService.getEmbeddingModel(), settingsService.getEmbeddingProvider());
            log.info("[Sync] Reindex complete for connector={} - swapped shadow collection={} live, indexedWithEmbeddingModel={}",
                    connectorId, shadowCollectionId, settingsService.getEmbeddingModel());
        } catch (Exception e) {
            // Small blast radius (bounded to the swap step itself, not the whole reindex), not
            // worth a full two-phase commit across three heterogeneous stores - leave
            // reindexShadowCollectionId set and indexedWithEmbeddingModel unchanged so this is
            // retried (or inspected manually) rather than silently marking the connector as
            // indexed with the new model when the swap itself didn't actually complete.
            log.error("[Sync] Swap failed for connector={} (shadow={} -> live={}): {} - connector flagged for manual attention, shadow left in place",
                    connectorId, shadowCollectionId, liveCollectionId, e.getMessage(), e);
        }
    }

    /**
     * Moves up to {@code count} items from {@code source} to {@code target}, and returns whether the
     * source has nothing more.
     *
     * <p>BOUNDED BY TIME AS WELL AS BY COUNT, since 2026-10-02. It used to take a full hundred
     * before handing any of them on. From a source that answers a hundred at a time - one REST
     * page of a Confluence space - that is one round trip. From a web crawl, which finds one page
     * per fetch, it is a hundred fetches: and a hundred is the default page limit, so the WHOLE
     * crawl ran before the first page was recorded, counted or embedded, with "Discovering
     * items..." on the source the entire time (maintainer's report; WebCrawlConnector's own note
     * and the registration dialog both said the first page appears at once). So once the pull has
     * something and has been going for {@code PULL_BUDGET_NANOS}, it stops and what it has is
     * reconciled.
     *
     * <p>Never returns empty-handed unless the source is exhausted: the budget is only looked at
     * after an item, so the caller's loop cannot run dry with the source still going.
     *
     * <p>"Exhausted" is known only when the source SAID so. Stopped by the count or the clock, it
     * is not asked - asking is {@code hasNext()}, which for a crawl is the next page's fetch, the
     * very wait this exists to avoid.
     */
    static boolean pull(Iterator<ItemMetadata> source, List<ItemMetadata> target, int count,
                        java.util.function.LongSupplier nanoClock) {
        long started = nanoClock.getAsLong();
        for (int fetched = 0; fetched < count; fetched++) {
            if (!source.hasNext()) return true;
            target.add(source.next());
            if (nanoClock.getAsLong() - started >= PULL_BUDGET_NANOS) return false;
        }
        return false;
    }

    /**
     * Reconcile-only: compare source metadata against DB, create or update TrackedDocument.
     * Returns the document if it needs processing (new or changed), null if up-to-date.
     */
    private TrackedDocument reconcileItem(ItemMetadata meta, UUID connectorId, ConnectorType type) {
        Optional<TrackedDocument> existing = repository.findBySourceIdAndConnectorId(meta.getId(), connectorId);

        // Postgres timestamp columns only carry microsecond precision, but local filesystem
        // mtimes (and the Instants Files.readAttributes()/Instant.now() produce from them) carry
        // nanosecond precision. Persisting an un-truncated Instant gets it silently rounded on
        // write; re-reading the *same, unchanged* file's mtime on the next full sync and comparing
        // it against that rounded-down DB value makes it look permanently "newer" than what's
        // stored, so the file is endlessly requeued and re-embedded despite never changing (see
        // ../proxyma-docs/LESSONS_LEARNED.md - "Files connectors reprocess forever due to timestamp precision
        // loss"). Truncating up front keeps the compared and persisted values bit-for-bit stable.
        //
        // ItemMetadata.getLastModified() is not actually guaranteed non-null by the type system -
        // every current session sets a real value, but this call is
        // per-item isolated (see the try/catch around reconcileItem() above) specifically so one
        // bad item can't take down an entire sync, and a null here used to do exactly that
        // silently: every item from a source that forgot to set it got dropped one-by-one with
        // no visible error beyond a per-item log line, reading as "sync completed, zero items"
        // (confirmed live, 2026-07-28, for a web-crawl source bug -
        // now fixed at the source - see WebCrawlConnector). Falling back to now() here is a
        // second line of defense for the same class of mistake in any other/future source.
        Instant observed = meta.getLastModified();
        if (observed == null) {
            log.warn("[Sync] item id={} for connector={} has no lastModified - source={} should "
                    + "always set one; falling back to observation time", meta.getId(), connectorId, type);
            observed = Instant.now();
        }
        Instant lastModified = observed.truncatedTo(ChronoUnit.MICROS);

        if (existing.isPresent()) {
            TrackedDocument doc = existing.get();
            // The same words as what was indexed is up to date whatever the timestamp says - see
            // ItemMetadata.CONTENT_HASH. Only sources that send one are affected; for the rest this
            // is false and the timestamp decides, as it always has.
            String contentHash = meta.getExtra(ItemMetadata.CONTENT_HASH);
            boolean sameContent = contentHash != null && contentHash.equals(doc.getContentHash());
            boolean upToDate = (sameContent || (doc.getSourceLastModified() != null
                    && !doc.getSourceLastModified().isBefore(lastModified)))
                    && doc.getStatus() == TrackedDocument.Status.COMPLETED;
            if (upToDate && doc.getTotalChunks() > 0) {
                // One-time backfill: sourceSizeBytes didn't exist before this field was added, so
                // every document indexed before then has it null and would otherwise never pick
                // up a real value (this fast path is the only one that runs for already-COMPLETED
                // documents on every sync, by design, to avoid a DB write per unchanged item -
                // see ../proxyma-docs/LESSONS_LEARNED.md "File size shown as markdown char count"). Only
                // writes once per document, since the condition is false on every sync after.
                boolean backfilled = false;
                if (doc.getSourceSizeBytes() == null && meta.getSizeBytes() != null) {
                    doc.setSourceSizeBytes(meta.getSizeBytes());
                    backfilled = true;
                }
                // Same one-time backfill treatment as sourceSizeBytes above, for rows that
                // predate the contentType column.
                if (doc.getContentType() == null && meta.getContentType() != null) {
                    doc.setContentType(meta.getContentType());
                    backfilled = true;
                }
                // One-time backfill for documents indexed before sourceUrl existed (or before the
                // owning source started computing one) - lets already-COMPLETED Jira/Confluence
                // documents pick up a real citation URL on their next scheduled sync, without a
                // full reindex.
                if (doc.getSourceUrl() == null && meta.getExtra("url") != null) {
                    doc.setSourceUrl(meta.getExtra("url"));
                    backfilled = true;
                }
                // The same for who last changed it, which the listing states and the row did not
                // keep before - and the graph's metadata is due again once it is known.
                boolean authorLearned = false;
                if (doc.getSourceAuthor() == null && meta.getExtra(ItemMetadata.AUTHOR) != null) {
                    doc.setSourceAuthor(meta.getExtra(ItemMetadata.AUTHOR));
                    backfilled = true;
                    authorLearned = true;
                }
                if (backfilled) repository.save(doc);
                if (authorLearned) {
                    eventPublisher.publishEvent(new ai.proxyma.core.registry.event.DocumentMetadataChangedEvent(
                            doc.getConnectorId(), doc.getId()));
                }
                log.debug("[Sync] Skipping up-to-date item id={}", meta.getId());
                return null;
            }
            if (upToDate) {
                // upToDate && totalChunks == 0: a "ghost indexed" row (empty content that
                // slipped past processItem()'s guard before it existed - see
                // ../proxyma-docs/LESSONS_LEARNED.md "Silent conversion failures produce ghost Indexed
                // documents"). Failing it here (instead of re-queuing back to QUEUED) stops it
                // from being silently bounced through "processing" forever on every full sync
                // for a source that never actually changed - matches what processItem() itself
                // already does for a freshly-produced zero-chunk result.
                log.warn("[Sync] Failing COMPLETED item with 0 chunks (ghost-indexed): id={}", meta.getId());
                doc.setStatus(TrackedDocument.Status.FAILED);
                doc.setFailureMessage("No extractable content (0 chunks)");
                doc.setSourceLastModified(lastModified);
                doc.touch(lastModified);
                doc.setSourceSizeBytes(meta.getSizeBytes());
                doc.setContentType(meta.getContentType());
                doc.setTitle(meta.getTitle());
                doc.setSourceUrl(meta.getExtra("url"));
                repository.save(doc);
                publishUpdate(doc);
                return null;
            }
            // Did the source itself change since we last looked? Null means we have never
            // recorded one, which counts as changed - there is no evidence it is the same bytes.
            boolean sourceChanged = doc.getSourceLastModified() == null
                    || doc.getSourceLastModified().isBefore(lastModified);

            // A FAILED item that has used up its retry budget and has NOT changed upstream is left
            // exactly as it is. Requeuing it (which is what the "anything else" branch below does)
            // means re-downloading, re-converting and re-embedding a file that has failed
            // MAX_AUTO_RETRIES times already, on every full sync, forever - paid embedding calls
            // for a document that is not going to succeed this time either. The source changing is
            // what makes it worth another attempt, and that case falls through to the reset below.
            if (doc.getStatus() == TrackedDocument.Status.FAILED
                    && doc.getRetryCount() >= TrackedDocument.MAX_AUTO_RETRIES
                    && !sourceChanged) {
                log.debug("[Sync] Leaving exhausted FAILED item alone: id={} retryCount={}",
                        meta.getId(), doc.getRetryCount());
                return null;
            }

            // Preserve EMBEDDING status for crash-resume: processItem will detect
            // the cached markdown and resume from currentChunks offset
            if (doc.getStatus() == TrackedDocument.Status.EMBEDDING && doc.getCurrentChunks() > 0) {
                log.info("[Sync] Preserving EMBEDDING status for resume: id={} chunks={}/{}",
                        meta.getId(), doc.getCurrentChunks(), doc.getTotalChunks());
            } else {
                doc.setStatus(TrackedDocument.Status.QUEUED);
            }
            // New content earns a fresh retry budget. Without this reset the counter only ever
            // climbed: an item that failed its way to MAX_AUTO_RETRIES stayed exhausted for the
            // life of the row, so a document that was fixed at the source was never given a real
            // attempt again - it was requeued, failed the guard above, and sat there.
            if (sourceChanged && (doc.getRetryCount() != 0 || doc.getNextRetryAt() != null)) {
                doc.setRetryCount(0);
                doc.setNextRetryAt(null);
            }
            doc.setSourceLastModified(lastModified);
            if (contentHash != null) doc.setContentHash(contentHash);
            // The source moving is one of the two things lastTouchedAt tracks (the other is an
            // indexing run finishing - see finishRun). touch() takes the later of the two, so a
            // file modified last year that is being requeued now does not push the row back down
            // the list below items that have not changed since.
            doc.touch(lastModified);
            doc.setSourceSizeBytes(meta.getSizeBytes());
            doc.setContentType(meta.getContentType());
            doc.setTitle(meta.getTitle());
            doc.setSourceUrl(meta.getExtra("url"));
            doc.setSourceAuthor(meta.getExtra(ItemMetadata.AUTHOR));
            repository.save(doc);
            publishUpdate(doc);
            return doc;
        } else {
            TrackedDocument doc = new TrackedDocument();
            doc.setSourceId(meta.getId());
            doc.setTitle(meta.getTitle());
            doc.setConnectorType(type);
            doc.setConnectorId(connectorId);
            doc.setSourceLastModified(lastModified);
            doc.touch(lastModified);
            doc.setSourceSizeBytes(meta.getSizeBytes());
            doc.setContentType(meta.getContentType());
            doc.setSourceUrl(meta.getExtra("url"));
            doc.setContentHash(meta.getExtra(ItemMetadata.CONTENT_HASH));
            doc.setSourceAuthor(meta.getExtra(ItemMetadata.AUTHOR));
            doc.setStatus(TrackedDocument.Status.QUEUED);
            repository.save(doc);
            publishUpdate(doc);
            return doc;
        }
    }

    // Reconstructs a minimal ItemMetadata from a TrackedDocument row, for the drain loop to pass
    // to processItem() independent of any live source enumeration pass - extraProperties is
    // left empty; every source's getContent() must resolve everything else it needs from
    // id/contentType/source-instance auth state alone (see the per-source fallback fixes in
    // ConfluenceConnector/BitbucketConnector/SharePointConnector).
    ItemMetadata toMinimalItemMetadata(TrackedDocument doc) {
        ItemMetadata meta = new ItemMetadata();
        meta.setId(doc.getSourceId());
        meta.setTitle(doc.getTitle());
        meta.setSourceType(doc.getConnectorType());
        meta.setLastModified(doc.getSourceLastModified());
        meta.setContentType(resolveContentType(doc));
        meta.setSizeBytes(doc.getSourceSizeBytes());
        return meta;
    }

    // TrackedDocument rows created before the contentType column existed reconstruct with
    // contentType==null. Default to "text/markdown" for the five sources that unconditionally
    // first drain post-upgrade without waiting for a connector's next full reconcile() to re-touch
    // them. No safe default exists for FILES/SVN/SHAREPOINT - those get a correct contentType the
    // next time reconcile() happens to touch them (independent of whether they've drained yet).
    private String resolveContentType(TrackedDocument doc) {
        if (doc.getContentType() != null && !doc.getContentType().isBlank()) {
            return doc.getContentType();
        }
        // A type built on the connector framework renders every item it does not fetch as a file.
        ConnectorType type = doc.getConnectorType();
        if (type == ConnectorType.JIRA || type == ConnectorType.CONFLUENCE || type == ConnectorType.BITBUCKET) {
            return "text/markdown";
        }
        return null;
    }

    // Package-private (was private) - now called by SyncOrchestratorService's drain loop, which
    // supplies contentSupplier itself so it controls exactly how much of the fetch is wrapped in
    // the source's synchronized(source) lock (auth+fetch only, not the whole embed pipeline
    // below - see SyncOrchestratorService.drainConnector()).
    /**
     * INGEST IS BILLED TO THE CONNECTOR'S OWNER, not to whoever happened to press Sync and not to
     * the platform. Someone registering a hundred sources is precisely the cost worth tracing to a
     * person, and the owner is the only identity that survives the ways a sync can start - a
     * scheduled sweep and an auto-retry have no clicker at all. A connector with no owner (desktop,
     * where there are no accounts) falls to SYSTEM.
     *
     * <p>Set here rather than deeper because this is the widest scope that belongs to ONE
     * connector: everything below it - conversion, OCR, embedding - is spent on this document and
     * inherits the subject without any of them being told about it. Graph extraction is no longer
     * below it; GraphExtractionOrchestrator charges the same owner on its own schedule.
     */
    void processItem(ConnectorType sourceType, TrackedDocument doc,
                      String collectionId, ItemMetadata meta, Supplier<InputStream> contentSupplier) {
        String owner = connectorRepository.findById(doc.getConnectorId())
                .map(RegisteredConnector::getUserId).orElse(null);
        SpendContext.run(SpendSubject.owner(owner),
                () -> processItemOwned(sourceType, doc, collectionId, meta, contentSupplier));
    }

    private void processItemOwned(ConnectorType sourceType, TrackedDocument doc,
                      String collectionId, ItemMetadata meta, Supplier<InputStream> contentSupplier) {
        // Opened here, before the first thing that can fail, and closed by finishRun() on every
        // exit below - COMPLETED through the happy path, FAILED through markFailed(). indexMs is
        // cleared rather than left holding the previous run's number, so a row reading
        // "started 40 minutes ago, took 900ms" is impossible: while a run is in flight there is no
        // duration, which is what tells a reader it is still going.
        doc.setIndexStartedAt(Instant.now());
        doc.setIndexMs(null);
        ItemDiagnostics diag = new ItemDiagnostics(sourceType, meta, doc);
        try {
            String targetCollection = collectionId != null ? collectionId : doc.getSourceId();
            Path cacheFile = markdownCachePath(doc.getId());
            String ownerUserId = connectorRepository.findById(doc.getConnectorId())
                    .map(RegisteredConnector::getUserId).orElse(null);
            // Only UPLOADED_FILES documents are eligible for the post-COMPLETED purge below, so
            // only those need a permanent per-user snapshot path. That purge DELETES the stored
            // bytes once the markdown exists, which is safe only because those bytes are a copy
            // Proxyma made and owns - it must never be reachable for a LOCAL_FOLDER document,
            // whose sourceId is a file on the user's own disk. Named explicitly rather than via
            // isFileBacked() for exactly that reason.
            Path purgedSnapshotPath = (sourceType == ConnectorType.UPLOADED_FILES && ownerUserId != null)
                    ? purgedMarkdownSnapshotPath(ownerUserId, doc.getId())
                    : null;
            String markdown;
            int resumeOffset = 0;
            // Captured before contentSize is possibly overwritten below, so the quota delta only
            // ever reflects the actual change in this document's footprint (new doc: 0 -> full
            // size; modified doc: old size -> new size; crash-resume: unchanged, delta 0 - a
            // resumed embed was never charged on its interrupted attempt and won't be charged an
            // extra time here either, which under-counts that rare case rather than risking a
            // double charge).
            long previousContentSize = doc.getContentSize();
            long quotaDeltaBytes = 0;

            // Resume path: if the app crashed during EMBEDDING, the cached markdown
            // is still on disk. Skip download+convert and resume embedding from the
            // last committed chunk offset.
            boolean resumingCrash = cacheFile != null && Files.isRegularFile(cacheFile)
                    && doc.getStatus() == TrackedDocument.Status.EMBEDDING
                    && doc.getCurrentChunks() > 0;
            // Reindex path for a previously-purged file: its raw bytes are gone (deliberately -
            // see the purge step below), so the only way to re-embed it (a model change, a manual
            // reindex-all, ...) is from the permanently-retained markdown snapshot instead of
            // re-fetching/re-converting the original.
            boolean readingPurgedSnapshot = !resumingCrash && doc.isContentPurged()
                    && purgedSnapshotPath != null && Files.isRegularFile(purgedSnapshotPath);
            if (resumingCrash) {
                markdown = Files.readString(cacheFile, StandardCharsets.UTF_8);
                resumeOffset = doc.getCurrentChunks();
                log.info("[Sync] Resuming embedding for id={} from chunk {}", doc.getSourceId(), resumeOffset);
            } else if (readingPurgedSnapshot) {
                markdown = Files.readString(purgedSnapshotPath, StandardCharsets.UTF_8);
                log.info("[Sync] Re-embedding id={} from retained markdown snapshot (original bytes were purged)",
                        doc.getSourceId());
            } else {
                setStatusAndPublish(doc, TrackedDocument.Status.DOWNLOADING);
                long downloadStart = System.nanoTime();
                InputStream rawContent = contentSupplier.get();
                diag.stage(DiagOp.INGEST_DOWNLOAD, downloadStart);

                setStatusAndPublish(doc, TrackedDocument.Status.CONVERTING);
                long convertStart = System.nanoTime();
                markdown = documentConverter.convertToMarkdown(rawContent, meta.getContentType());
                diag.stage(DiagOp.INGEST_CONVERT, convertStart);

                if (markdown == null || markdown.isBlank()) {
                    log.warn("[Sync] Empty content after conversion for id={} contentType={}",
                            doc.getSourceId(), meta.getContentType());
                    diag.error = "empty_content";
                    // Says what the likely causes are, because "empty after conversion" alone left
                    // the user nothing to check. The commonest by far is a scanned PDF - pages that
                    // are pictures of text - and that one has a fix in Settings.
                    String type = meta.getContentType();
                    markFailed(doc, "This file converted to no text"
                            + (type != null && !type.isBlank() ? " (" + type + ")" : "")
                            + ". It may be a scanned image, password-protected, or empty."
                            + " Scanned documents are only read with OCR - see Settings > Conversion.");
                    return;
                }

                doc.setContentSize(markdown.length());
                // Populates cross-user duplicate detection (see TrackedDocument.contentHash) -
                // only meaningful for the file-backed sources, where sourceId is a real filesystem
                // path; other sources (Jira, Confluence, ...) are deduplicated by their
                // connector's (connectorType, config, name) tuple instead, not content hash.
                // Uploads only: the one reader of this hash is the upload dialog's duplicate check
                // (FileService), which exists only where uploads do. Every local folder's file was
                // read a second time for a hash nothing ever looked at.
                if (sourceType == ConnectorType.UPLOADED_FILES) {
                    doc.setContentHash(FileTypeSupport.computeFileHash(Path.of(doc.getSourceId())));
                }
                repository.save(doc);

                quotaDeltaBytes = Math.round((markdown.length() - previousContentSize)
                        * settingsService.getSettings(StorageSettings.PREFIX, StorageSettings.class).getOverheadMultiplier());
                if (quotaPort != null && quotaDeltaBytes > 0) {
                    if (ownerUserId != null && !quotaPort.hasCapacityFor(ownerUserId, quotaDeltaBytes)) {
                        log.warn("[Sync] Storage quota exceeded for user={} id={}", ownerUserId, doc.getSourceId());
                        diag.error = "quota";
                        markFailed(doc, "Storage quota exceeded - connector sync paused");
                        return;
                    }
                }

                // Cache the markdown so embedding can resume after crash
                if (cacheFile != null) {
                    Files.createDirectories(cacheFile.getParent());
                    Files.writeString(cacheFile, markdown, StandardCharsets.UTF_8);
                }
            }

            setStatusAndPublish(doc, TrackedDocument.Status.EMBEDDING);
            EmbeddingService.ChunkProgressCallback callback = (processed, total) -> {
                doc.setCurrentChunks(processed);
                doc.setTotalChunks(total);
                repository.save(doc);
                publishUpdateWithChunks(doc);
                // Saved first: the count just recorded is what a resumed write starts from.
                if (stopping) throw new EmbeddingService.Stopped("the application is shutting down");
            };
            // wireName(), never name(): this string is written into the vector store and BM25
            // metadata of every chunk, where it outlives any single release. See
            // ConnectorType.wireName() for why the file-backed types must all report "files"
            // here - writing the split through to the index would have made a full re-embed the
            // only way back to a consistent one.
            String connectorType = doc.getConnectorType() != null ? doc.getConnectorType().wireName() : null;
            long embedStart = System.nanoTime();
            // Every write replaces the document's previous chunks from its first chunk on, and a
            // resumed one keeps what the interrupted run wrote (EmbeddingService.chunkAndEmbed), so
            // a reindex's replace flag has nothing left to decide.
            embeddingService.chunkAndEmbed(targetCollection, doc.getSourceId(), markdown, meta.getContentType(),
                    resumeOffset, doc.getSourceUrl(), connectorType, callback);
            doc.setReindexReplace(false);
            diag.stage(DiagOp.INGEST_EMBED, embedStart);

            // NO knowledge-graph extraction here, deliberately. It ran at this point until
            // 2026-09-15, which made the graph a stage of indexing: a source already indexed never
            // got a graph without being re-embedded, a slow graph model held this drain worker, and
            // a graph refused for budget failed the DOCUMENT. GraphExtractionOrchestrator now reads
            // the chunks this call just wrote to the keyword index, on its own schedule, and
            // nothing it does can change this document's status. Do not put it back.

            if (doc.getTotalChunks() == 0) {
                log.warn("[Sync] Zero chunks produced for id={} (markdown length={})",
                        doc.getSourceId(), markdown.length());
                diag.error = "no_chunks";
                markFailed(doc, "No chunks produced from content (" + markdown.length() + " chars)");
                deleteMarkdownCache(cacheFile);
                return;
            }

            // Its owner deleted it while this run indexed it: what the run just wrote goes too,
            // rather than the row coming back COMPLETED and searchable.
            if (deletedWhileIndexing.remove(doc.getId())) {
                log.info("[Sync] id={} was deleted while it was indexed - removing what this run wrote",
                        doc.getSourceId());
                removeDocument(doc, targetCollection);
                updateConnectorCounts(doc.getConnectorId());
                deleteMarkdownCache(cacheFile);
                return;
            }

            doc.setStatus(TrackedDocument.Status.COMPLETED);
            doc.setLastIndexedAt(Instant.now());
            finishRun(doc);
            diag.ok = true;

            // Purge the original uploaded bytes now that they're durably embedded - server-mode
            // uploads only (purgedSnapshotPath is null otherwise), and only the first time this
            // content completes (a purged doc re-embedding from its own snapshot has nothing left
            // to purge). Snapshot is written and doc.contentPurged committed BEFORE the raw file is
            // deleted, so a crash between the two just leaves both copies around harmlessly instead
            // of losing the content entirely.
            FileMetadata purgeCandidate = null;
            if (!doc.isContentPurged() && purgedSnapshotPath != null) {
                purgeCandidate = fileMetadataRepository.findByStoragePath(doc.getSourceId()).orElse(null);
                if (purgeCandidate != null) {
                    try {
                        Files.createDirectories(purgedSnapshotPath.getParent());
                        Files.writeString(purgedSnapshotPath, markdown, StandardCharsets.UTF_8);
                        doc.setContentPurged(true);
                    } catch (IOException e) {
                        log.warn("[Sync] Failed to persist markdown snapshot for id={}, skipping purge: {}",
                                doc.getSourceId(), e.getMessage());
                        purgeCandidate = null;
                    }
                }
            }
            repository.save(doc);
            publishUpdate(doc);
            updateConnectorCounts(doc.getConnectorId());
            deleteMarkdownCache(cacheFile);
            if (purgeCandidate != null) {
                try {
                    // With the folder it was stored in, which the delete keeps: this is very often
                    // the source's last file, and the source still lists that folder.
                    storageService.delete(purgeCandidate.getStoragePath(),
                            purgeCandidate.getUploadedByUserId(), purgeCandidate.getFolder());
                } catch (Exception e) {
                    log.warn("[Sync] Failed to delete purged source bytes for id={}: {}",
                            doc.getSourceId(), e.getMessage());
                }
                purgeCandidate.setBytesPurged(true);
                // sizeBytes means "bytes currently on disk for this row" - zero it out here so
                // the Admin Users table's bulk aggregates (sumAllSizeBytes/
                // sumSizeBytesGroupedByUserId) stay accurate without an extra filesystem walk.
                // Without this, purged rows kept their original size and the bulk total drifted
                // upward forever versus the real (folder-walk-based) per-user detail figure.
                purgeCandidate.setSizeBytes(0);
                fileMetadataRepository.save(purgeCandidate);
                log.info("[Sync] Purged source bytes for id={} (markdown snapshot retained)", doc.getSourceId());
            }
            // Reconcile from real measured bytes now that embedding (and any purge above) has
            // actually happened, rather than keep the pre-embed character-length estimate used
            // only for the capacity pre-check above - the estimate is a rough proxy, this is real.
            if (quotaPort != null && ownerUserId != null) {
                quotaPort.reconcile(ownerUserId);
            }
            log.info("[Sync] Completed item id={} source={} chunks={}",
                    doc.getSourceId(), sourceType, doc.getTotalChunks());
            // Published after everything that could still fail this item has run, so a listener
            // never hears "indexed" about a document that then failed its purge or quota step.
            eventPublisher.publishEvent(new DocumentIndexedEvent(this, doc.getConnectorId(), doc.getId(),
                    doc.getSourceId(), doc.getTitle(), collectionId));

        } catch (ai.proxyma.core.connector.ItemGoneException gone) {
            // Gone at the source: removed now, as a complete listing would remove it - not FAILED and
            // fetched again three times while it stays searchable for up to a day.
            log.info("[Sync] id={} connectorId={} is gone at the source - removing it: {}",
                    doc.getSourceId(), doc.getConnectorId(), gone.getMessage());
            deletedWhileIndexing.remove(doc.getId());
            RegisteredConnector connector = connectorRepository.findById(doc.getConnectorId()).orElse(null);
            removeDocument(doc, collectionId != null ? collectionId : writeCollectionId(connector, doc.getConnectorId()));
            updateConnectorCounts(doc.getConnectorId());
            diag.ok = false;
            diag.error = "gone";
        } catch (Exception e) {
            if (stopping) {
                diag.stopped = true;
                stoppedByShutdown(doc, e);
                return;
            }
            // Full stack trace (not just e.getMessage()) - temporary, added to diagnose the
            // 2026-07-13 NoSuchFileException investigation, where the message alone (just a file
            // path for NoSuchFileException) gives no clue which step actually threw it. Also logs
            // connectorId explicitly since source.getType() alone doesn't disambiguate between
            // two connectors of the same source type (e.g. two JIRA projects).
            // Failures.describe, not getMessage: the message this records is the one shown on the
            // failed item in the UI, and the layers between here and the real problem each rewrap
            // it with the name of the OPERATION rather than the CAUSE. An embedding call refused
            // for billing arrived here as "ChromaDB upsert failed for collection <uuid>" - true,
            // useless, and it sent the reader to look at ChromaDB. The cause said "Insufficient
            // credits ... purchase more at <url>", which is the sentence the user can act on.
            log.error("[Sync] Failed item id={} connectorId={} source={}: {}",
                    doc.getSourceId(), doc.getConnectorId(), sourceType, Failures.describe(e), e);
            diag.ok = false;
            diag.error = ErrorCategory.of(e);
            markFailed(doc, Failures.describe(e));
        } finally {
            diag.finish();
        }
    }

    /**
     * One item's stage timings and outcome, for the daily diagnostics. Labeled by connector TYPE,
     * file CLASS (office/pdf/image/...) and a size RANGE - derived here from the item's metadata,
     * which carries the file name and path, so that neither goes further than this constructor.
     */
    private static final class ItemDiagnostics {
        private final long start = System.nanoTime();
        private final String connector;
        private final String fileClass;
        private final String size;
        boolean ok;
        // Cut short by the application stopping: neither a success nor a failure, so not counted.
        boolean stopped;
        String error = "unknown";

        ItemDiagnostics(ConnectorType sourceType, ItemMetadata meta, TrackedDocument doc) {
            this.connector = sourceType == null ? "none" : sourceType.name();
            String typeHint = meta != null && meta.getContentType() != null && !meta.getContentType().isBlank()
                    ? meta.getContentType() : doc.getSourceId();
            this.fileClass = DiagLabels.fileClass(typeHint);
            this.size = DiagLabels.sizeBucket(meta != null && meta.getSizeBytes() != null ? meta.getSizeBytes() : -1);
        }

        void stage(DiagOp op, long startNanos) {
            Diagnostics.timeSince(op, startNanos, "connector", connector, "fileClass", fileClass, "size", size);
        }

        void finish() {
            if (stopped) return;
            String outcome = ok ? "ok" : "failed";
            Diagnostics.timeSince(DiagOp.INGEST_ITEM, start,
                    "connector", connector, "fileClass", fileClass, "size", size, "outcome", outcome);
            Diagnostics.count(DiagCounter.INGEST_OUTCOME,
                    "connector", connector, "fileClass", fileClass, "outcome", outcome, "error", ok ? "none" : error);
        }
    }

    private Path markdownCachePath(java.util.UUID docId) {
        if (docId == null) return null;
        return appPaths.tempDir().resolve("markdown-cache").resolve(docId.toString() + ".md");
    }

    // Same {storageRoot}/users/{userId}/ root LocalDiskStorageService writes uploads under and
    // StorageStatsService walks for quota purposes - unlike markdownCachePath() above (a global,
    // transient crash-resume cache for any source type), this is permanent and per-user so a
    // purged file's retained content counts toward that user's own folder/quota, not a shared
    // system-wide temp directory nobody's usage accounts for.
    private Path purgedMarkdownSnapshotPath(String userId, java.util.UUID docId) {
        return ai.proxyma.core.connector.files.UploadSandbox.retainedTextFor(storageRoot, userId, docId);
    }

    private void deleteMarkdownCache(Path cacheFile) {
        if (cacheFile == null) return;
        try { Files.deleteIfExists(cacheFile); } catch (Exception ignored) {}
    }

    /**
     * An item the application's shutdown cut short. NOT a failure: no FAILED row, no failure message,
     * no retry spent, nothing for the auto-retry sweep to wait on - the item is where a process that
     * stopped would leave it, and the next start takes it up (SyncOrchestratorService.recoverOnStartup).
     *
     * <p>An embedding stopped between batches ({@link EmbeddingService.Stopped}) keeps its row
     * EMBEDDING at the chunk count it recorded, with its cached text, so it RESUMES there. Anything
     * else goes back to QUEUED from the first chunk: a write that failed has already taken back what
     * it wrote, and resuming after it would leave the document's first chunks missing. The row is
     * written if the database is still there to take it; if it is not, the startup sweep finds it in
     * the stage it reached.
     */
    private void stoppedByShutdown(TrackedDocument doc, Exception cause) {
        // Finished before the stop reached it - only what came after (a notice) was refused.
        if (doc.getStatus() == TrackedDocument.Status.COMPLETED) return;
        boolean resumable = cause instanceof EmbeddingService.Stopped
                && doc.getStatus() == TrackedDocument.Status.EMBEDDING && doc.getCurrentChunks() > 0;
        log.info("[Sync] Stopped item id={} connectorId={} - the application is shutting down; it {} at the next start",
                doc.getSourceId(), doc.getConnectorId(),
                resumable ? "resumes from chunk " + doc.getCurrentChunks() : "is indexed again");
        if (resumable) return;
        try {
            doc.setStatus(TrackedDocument.Status.QUEUED);
            doc.setCurrentChunks(0);
            doc.setIndexStartedAt(null);
            repository.save(doc);
        } catch (RuntimeException e) {
            log.debug("[Sync] Could not requeue id={} on the way down: {}", doc.getSourceId(), e.getMessage());
        }
    }

    private void setStatusAndPublish(TrackedDocument doc, TrackedDocument.Status status) {
        doc.setStatus(status);
        repository.save(doc);
        publishUpdate(doc);
    }

    // Every processItem() failure site routes through here so the auto-retry window (see
    // TrackedDocument.MAX_AUTO_RETRIES/AUTO_RETRY_DELAY) is applied uniformly regardless of
    // cause. SyncOrchestratorService.autoRetryFailedItems() is the scheduled sweep that requeues
    // these once retryCount < MAX_AUTO_RETRIES, nextRetryAt has passed, and the owning connector
    // has no other backlog left.
    private void markFailed(TrackedDocument doc, String message) {
        if (deletedWhileIndexing.remove(doc.getId())) {
            // Deleted by its owner during this run - see deleteTrackedDocumentBySourceId().
            RegisteredConnector connector = connectorRepository.findById(doc.getConnectorId()).orElse(null);
            removeDocument(doc, writeCollectionId(connector, doc.getConnectorId()));
            updateConnectorCounts(doc.getConnectorId());
            return;
        }
        doc.setStatus(TrackedDocument.Status.FAILED);
        // Not truncated here any more - TrackedDocument.setFailureMessage() does it, so every
        // writer of that field gets it rather than only this one.
        doc.setFailureMessage(message);
        finishRun(doc);
        doc.setNextRetryAt(doc.getRetryCount() < TrackedDocument.MAX_AUTO_RETRIES
                ? Instant.now().plus(TrackedDocument.AUTO_RETRY_DELAY) : null);
        repository.save(doc);
        publishUpdate(doc);
        updateConnectorCounts(doc.getConnectorId());
    }

    // Closes the timing window processItemOwned() opened, on both endings - a run that failed after
    // four minutes of conversion is exactly as worth knowing about as one that succeeded, and the
    // list ordering (failed first, then last touched) needs the failure to move the row too.
    // indexStartedAt is null only for a row FAILED outside a run (reconcile's ghost-indexed repair,
    // the startup sweep), which has no duration to record.
    private void finishRun(TrackedDocument doc) {
        Instant now = Instant.now();
        if (doc.getIndexStartedAt() != null) {
            doc.setIndexMs(Duration.between(doc.getIndexStartedAt(), now).toMillis());
        }
        doc.touch(now);
    }

    private void publishUpdate(TrackedDocument doc) {
        notifyPage(new ConnectorUpdateEvent(
                this, doc.getConnectorId(), doc.getId(),
                doc.getTitle(), doc.getStatus().name()));
    }

    private void publishUpdateWithChunks(TrackedDocument doc) {
        notifyPage(new ConnectorUpdateEvent(
                this, doc.getConnectorId(), doc.getId(),
                doc.getTitle(), doc.getStatus().name(),
                doc.getCurrentChunks(), doc.getTotalChunks()));
    }

    // A progress notice for the page, delivered by an @Async listener. Refused once the context is
    // closing - Spring's executors stop taking tasks on the close event, before anything else stops
    // - and that refusal reached the item as an exception: RH-17200 was recorded FAILED for a
    // notification nobody would have seen. Losing a notice never fails the work it describes.
    private void notifyPage(org.springframework.context.ApplicationEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.debug("[Sync] Progress notice not delivered - the event executor is stopping: {}", e.getMessage());
        }
    }

    // One targeted update of the three counters, never a save of the whole row: this runs after
    // every item, and a row read before a person's edit and saved after it put the edit back.
    private void updateConnectorCounts(UUID connectorId) {
        if (connectorId == null) return;
        // countByConnectorId() counts every TrackedDocument row ever created for this
        // connector, including ones purgeDeleted() has since marked DELETED - itemCount only
        // ever grew, never shrank, for any connector whose upstream source has items
        // added/removed/re-added over its lifetime (a long-lived Jira project, a Confluence
        // space that gets restructured, a folder that had files removed and re-uploaded).
        // reconcile()'s own previousItemCount already uses the correct live-count query
        // (countByConnectorIdAndStatusNot) for exactly this reason - this is the one place
        // that never got the same fix, even though itemCount is the number actually shown to
        // users ("N items" on the connector card) and summed into admin/MCP stats.
        int items = (int) repository.countByConnectorIdAndStatusNot(connectorId, TrackedDocument.Status.DELETED);
        int processed = (int) repository.countByConnectorIdAndStatus(connectorId, TrackedDocument.Status.COMPLETED);
        // Only counts items that have exhausted their auto-retry budget - a FAILED item
        // still within its retry window (see markFailed()) stays excluded from both this and
        // processedCount above, so the connector keeps reading as "still processing" (frontend
        // isProcessing/hasFailed logic in ConnectorCard.tsx) until retries are done.
        int failed = (int) repository.countByConnectorIdAndStatusAndRetryCountGreaterThanEqual(
                connectorId, TrackedDocument.Status.FAILED, TrackedDocument.MAX_AUTO_RETRIES);
        connectorRepository.updateCounts(connectorId, items, processed, failed);
    }

    // Package-private (was private) - also called by SyncOrchestratorService's drain loop, which
    // checks it per-item between batches to bail out of a connector mid-drain the moment deletion
    // starts.
    boolean isConnectorDeleting(UUID connectorId) {
        return connectorRepository.findById(connectorId)
                .map(RegisteredConnector::isDeleting)
                .orElse(true);
    }

    // Explicit, immediate counterpart to purgeDeleted()'s disk-absence-driven purge below - called
    // when the user deletes an uploaded file directly (FileService.delete()) so it doesn't have to
    // wait up to an hour for the owning connector's next scheduled reconcile to notice the file is
    // gone and infer a deletion from that. A no-op if no TrackedDocument matches (e.g. it hasn't
    // been picked up by a reconcile pass yet) or it's already DELETED.
    //
    // EVERY document of that path, not one: a file is tracked by two sources when the sandbox
    // root and a folder inside it are both registered, and the Optional lookup this used threw on
    // the second - after the bytes and the FileMetadata row were already gone, leaving both
    // documents searchable for good (they are content-purged, which purge-by-absence never takes).
    //
    // A document a drain is indexing right now is removed now AND remembered: the run still holds
    // its own copy of the row and would otherwise save it back as COMPLETED, with fresh vectors,
    // when it finishes. processItemOwned() and markFailed() look here before their last write.
    public void deleteTrackedDocumentBySourceId(String sourceId) {
        for (TrackedDocument doc : repository.findAllBySourceId(sourceId)) {
            removeDeletedByOwner(doc);
        }
    }

    /**
     * The same immediate removal for ONE source's item - a document deleted from a custom source
     * through the ingestion API. By source as well as id, unlike the file case above: two custom
     * sources may each hold a document their senders both called "a". A no-op when the item was
     * never recorded or is already gone.
     */
    public void deleteTrackedDocument(UUID connectorId, String sourceId) {
        repository.findBySourceIdAndConnectorId(sourceId, connectorId).ifPresent(this::removeDeletedByOwner);
    }

    private void removeDeletedByOwner(TrackedDocument doc) {
        if (doc.getStatus() == TrackedDocument.Status.DELETED) return;
        if (IN_PROGRESS_STATUSES.contains(doc.getStatus())) deletedWhileIndexing.add(doc.getId());
        RegisteredConnector connector = connectorRepository.findById(doc.getConnectorId()).orElse(null);
        removeDocument(doc, writeCollectionId(connector, doc.getConnectorId()));
        updateConnectorCounts(doc.getConnectorId());
        if (doc.isContentPurged() && connector != null) {
            deleteSnapshot(connector.getUserId(), doc.getId());
        }
    }

    /**
     * A file uploaded again over one whose bytes were purged after indexing: its documents read the
     * new bytes next time, not the text kept from the old ones.
     *
     * <p>Enterprise Server deletes an upload's bytes once it is indexed and keeps the converted text
     * (contentPurged). Uploading the same file again wrote new bytes to the same path, but nothing
     * cleared that flag - so the next sync re-embedded the OLD text from the retained copy, the new
     * bytes were never read, never purged, and never counted against anything.
     */
    public void resetPurgedContent(String sourceId) {
        for (TrackedDocument doc : repository.findAllBySourceId(sourceId)) {
            if (!doc.isContentPurged() || doc.getStatus() == TrackedDocument.Status.DELETED) continue;
            doc.setContentPurged(false);
            repository.save(doc);
            connectorRepository.findById(doc.getConnectorId())
                    .ifPresent(connector -> deleteSnapshot(connector.getUserId(), doc.getId()));
        }
    }

    private void deleteSnapshot(String ownerUserId, UUID documentId) {
        Path snapshot = purgedMarkdownSnapshotPath(ownerUserId, documentId);
        if (snapshot == null) return;
        try { Files.deleteIfExists(snapshot); } catch (Exception ignored) { }
    }

    // Documents deleted by their owner while a drain was indexing them - see
    // deleteTrackedDocumentBySourceId(). Ids only, removed by whichever end of the run sees them.
    private final Set<UUID> deletedWhileIndexing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // The collection a source's writes go to right now: the shadow one while a model-changed
    // reindex builds it, else the live one.
    private static String writeCollectionId(RegisteredConnector connector, UUID connectorId) {
        return (connector != null && connector.getReindexShadowCollectionId() != null)
                ? connector.getReindexShadowCollectionId()
                : connectorId.toString().replace("-", "");
    }

    // One document's removal, for both ways a document goes - absent from a complete listing
    // (purge) and deleted by its owner. Its entities and edges go with it, and anything left
    // mentioned by nothing is purged: a graph that keeps answering from a deleted document is the
    // "degrades invisibly" failure knowledge-graph.md warns about.
    private void removeDocument(TrackedDocument doc, String collectionId) {
        doc.setStatus(TrackedDocument.Status.DELETED);
        repository.save(doc);
        embeddingService.deleteBySourceId(collectionId, doc.getSourceId());
        knowledgeGraphService.forgetDocument(doc.getId());
        eventPublisher.publishEvent(new DocumentPurgedEvent(this, doc.getConnectorId(), doc.getId()));
    }

    private static final List<TrackedDocument.Status> IN_PROGRESS_STATUSES =
            List.of(TrackedDocument.Status.DOWNLOADING, TrackedDocument.Status.CONVERTING,
                    TrackedDocument.Status.EMBEDDING);

    private int purgeDeleted(UUID connectorId, String collectionId, Set<String> seenIds) {
        return purge(connectorId, collectionId, doc -> !seenIds.contains(doc.getSourceId()));
    }

    // What a PARTIAL listing may still remove. It cannot purge by absence - an item it never
    // reached looks exactly like one deleted upstream - so until 2026-10-02 it removed nothing, and
    // a source whose listing is partial every time (a crawl at its page limit) kept every item that
    // had ever gone wrong: a manual the site stopped hosting stayed a failed document, a sign-in
    // page indexed by an earlier build stayed a document. Two absences it can vouch for:
    //
    //  - an id the listing itself says is gone (PartialSyncAware.getGoneItemIds), and
    //  - a FAILED item the listing did not come across again. Nothing of it is in the index, and
    //    the source no longer leads to it; if it comes back, the listing finds it as a new item.
    private int purgeGone(UUID connectorId, String collectionId, Set<String> seenIds, Set<String> goneIds) {
        return purge(connectorId, collectionId, doc -> !seenIds.contains(doc.getSourceId())
                && (goneIds.contains(doc.getSourceId()) || doc.getStatus() == TrackedDocument.Status.FAILED));
    }

    // Returns how many it purged.
    private int purge(UUID connectorId, String collectionId, java.util.function.Predicate<TrackedDocument> gone) {
        List<TrackedDocument> tracked = repository.findByConnectorId(connectorId);
        int purged = 0;
        for (TrackedDocument doc : tracked) {
            if (stopping) break;
            if (doc.getStatus() == TrackedDocument.Status.DELETED) continue;
            // Purge now runs fully decoupled from any specific drain pass (previously this method
            // only ran after process()'s own loop had already driven every item to a terminal
            // status, making this race structurally impossible). Skip an item the drain loop may
            // currently be mid-processItem() for - the next purge pass picks it up once it reaches
            // a terminal state, rather than risking a delete-then-resurrect race against an
            // in-flight embed that's about to write it back as COMPLETED.
            if (IN_PROGRESS_STATUSES.contains(doc.getStatus())) {
                log.debug("[Sync] Skipping purge check for id={} - currently {}", doc.getSourceId(), doc.getStatus());
                continue;
            }
            // A purged document's raw bytes were deliberately deleted after processing (see
            // processItem()'s post-COMPLETED purge step) - it will never appear in seenIds again
            // (the files source's sync() only reports files that still exist on disk), and that's
            // expected, not a user deletion. Without this, the very next reconcile would wipe out
            // its vectors under the mistaken belief the user removed it.
            if (doc.isContentPurged()) continue;
            if (gone.test(doc)) {
                log.info("[Sync] Purging deleted item id={} connector={}", doc.getSourceId(), connectorId);
                removeDocument(doc, collectionId);
                purged++;
            }
        }
        return purged;
    }
}
