package ai.proxyma.core.registry.service;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.quota.UserQuotaPort;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.service.SettingsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class SyncOrchestratorService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestratorService.class);

    // ---- Stopping before what sync work stands on ---------------------------------------------
    //
    // SYNC WORK STOPS FIRST, since the 0.36.0 feedback. Passes and drains are threads of their own
    // that nothing told about a shutdown, so they ran on while Spring took the application apart
    // around them: the event executor refused their notices, ChromaDB was stopped mid-upsert, the
    // databases closed, and every item in flight became a FAILED document - and the restart that
    // asked for all this was halted after fifteen seconds with them still running.
    //
    // Two steps. On the close event - the first thing a closing context does, and handled here
    // before the executors stop taking tasks - nothing new starts and running work stops at its next
    // safe point (SyncService.stopForShutdown). Then, as a lifecycle bean stopped in the same phase
    // as the web server's graceful shutdown and so ALONGSIDE it rather than after it, the running
    // passes and drains are waited for, up to the phase's own timeout, before any bean they use is
    // destroyed. What is still running then stops on its own and records nothing as failed.
    private volatile boolean stopping;
    private volatile boolean lifecycleRunning;
    // Every pass and drain thread while it runs - what the stop waits for.
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    // The web server's graceful-shutdown phase (WebServerGracefulShutdownLifecycle), so the two waits
    // overlap. Its timeout (spring.lifecycle.timeout-per-shutdown-phase, 5s on Desktop) is the bound.
    static final int STOP_PHASE = Integer.MAX_VALUE - 1024;
    private static final long STOP_WAIT_MILLIS = 30_000;

    // Which plugin serves each type, and what its descriptor says - the cadence, the edition, the
    // service it reads through. Sessions are opened over contexts built from each source's own row.
    private final ConnectorCatalog catalog;
    private final SourceContextFactory contexts;
    private final SyncService syncService;
    private final RegisteredConnectorRepository connectorRepository;
    private final TrackedDocumentRepository documentRepository;
    private final SettingsService settingsService;
    private final ConnectorEndpointResolver endpointResolver;

    // Enterprise Server. Gates the Microsoft 365 types, which exist on that edition only.
    @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // Guards reconcile() only - see drainingFlags below for the independent per-connector
    // draining guard.
    private final Map<UUID, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private volatile boolean globalPaused = false;

    // A sync asked for while one is already running for the same connector is REMEMBERED, not
    // dropped. Dropping it lost work that was genuinely new: files uploaded while their folder was
    // mid-sync were on disk and counted against the user's quota, but the running pass had already
    // enumerated past them and the request that would have caught them was answered with a log
    // line. Five requests during one pass collapse into one follow-up, which is the point - this is
    // a set, not a queue. forceFull is tracked separately so a "sync everything" request arriving
    // during an incremental pass is not downgraded to an incremental follow-up.
    private final Set<UUID> resyncRequested = ConcurrentHashMap.newKeySet();
    private final Set<UUID> forceFullRequested = ConcurrentHashMap.newKeySet();

    // HOW MANY SOURCES LIST AT ONCE, since 2026-10-04 (maintainer's feedback on 0.26.0: "avoid
    // syncing all the sources at a time"). Every due source used to start its own pass on the same
    // tick - after a night with the app closed, all of them - and a crawl, several Confluence
    // spaces and the searches the agent ran beside them all competed for one machine. Passes the
    // SCHEDULER, "Sync all" or a follow-up start wait their turn here, in the order they asked;
    // a source somebody registered, reconfigured or pressed "Sync now" on starts at once and counts
    // toward the limit. proxyma.sync.max-concurrent-syncs; Desktop and Home Server set 2.
    private static final int DEFAULT_MAX_CONCURRENT_SYNCS = 8;
    private int maxConcurrentSyncs = DEFAULT_MAX_CONCURRENT_SYNCS;
    private final Object syncSlots = new Object();
    // Both guarded by syncSlots.
    private int activeSyncs;
    private final Set<UUID> waitingForSlot = new LinkedHashSet<>();
    private final Set<UUID> forceFullWaiting = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Value("${proxyma.sync.max-concurrent-syncs:8}")
    void setMaxConcurrentSyncs(int value) {
        this.maxConcurrentSyncs = Math.max(1, value);
    }

    // Every drain ends in embedding calls to the same shared embedding backend (Ollama/OpenAI/
    // etc, often a single GPU). This bounds how many connectors actively drain (download/convert/
    // embed) at once; connectors beyond this just wait for a permit to free up - the embedding
    // backend's own throughput, not this number, is the real ceiling. If it can't keep up with 8
    // concurrent connectors it will queue or error per-request (caught in
    // SyncService.processItem()'s try/catch, surfaced as a FAILED item and retried), not fall
    // over.
    //
    // Formerly MAX_CONCURRENT_CONNECTOR_SYNCS/syncConcurrencyLimiter, gating SyncService.process()
    // as part of the same sync pass as reconcile(). Renamed and moved to gate the independent
    // drain loop below (2026-07-13) - reconcile() (diff against source) and draining (embed
    // whatever is QUEUED in the DB) are now two fully decoupled activities; draining no longer
    // waits on a connector's sync interval to run at all. See drainQueuedConnectors().
    // Now that embedding itself is gated by its own dedicated, provider-capacity-sized semaphore
    // (PgVectorStoreService.embeddingConcurrencyLimiter, sized from EmbeddingSettings.
    // maxConcurrentRequests), this cap's only remaining job is bounding fetch/convert concurrency
    // (I/O-bound, further serialized per-source-type already) plus routine DB work - raised
    // from 8 so more connectors can have content fetched/converted and ready to embed at once,
    // keeping the embedding gate fed instead of idle behind a slow network fetch.
    //
    // A PROPERTY, since 2026-10-03 (proxyma.sync.max-concurrent-drains). Sixteen was a constant,
    // and with hundreds of owners holding a backlog each gets one slot and takes its turn in
    // slices; a host with the cores and the provider capacity for more had no way to say so.
    private static final int DEFAULT_MAX_CONCURRENT_DRAINS = 16;
    private int maxConcurrentDrains = DEFAULT_MAX_CONCURRENT_DRAINS;
    private Semaphore drainConcurrencyLimiter = new Semaphore(DEFAULT_MAX_CONCURRENT_DRAINS);

    // Set once, while the bean is being built and before the scheduler has run a tick.
    @org.springframework.beans.factory.annotation.Value("${proxyma.sync.max-concurrent-drains:16}")
    void setMaxConcurrentDrains(int value) {
        this.maxConcurrentDrains = Math.max(1, value);
        this.drainConcurrencyLimiter = new Semaphore(this.maxConcurrentDrains);
    }

    // ---- Per-user fair share ------------------------------------------------------------------
    //
    // Everything above is per-CONNECTOR, not per-USER: drainQueuedConnectors() used to start a drain
    // for every backlogged connector in connectorRepository.findAll() order, each holding its
    // drain permit until fully drained (potentially hours). One user with
    // 16+ backlogged connectors therefore held every permit and every other user's indexing sat at
    // zero throughput until that user finished. The three mechanisms below make the drain slots
    // fair between owners without changing single-user (desktop) behaviour at all:
    //
    //   1. perOwnerDrainCap() - a dynamic cap of totalSlots/ownersWithBacklog, so one owner can
    //      never occupy more than its share while others are waiting. One owner -> cap 16 -> the
    //      old behaviour exactly.
    //   2. fairOrder() - candidates are interleaved round-robin across owners, with the starting
    //      owner rotated each tick, so a freed slot doesn't systematically go to the same owner.
    //   3. DRAIN_SLICE_MILLIS - a drain releases its permit after a bounded slice instead of
    //      holding it until the connector is fully drained. Without this, (1) and (2) would only
    //      apply to slots that happen to be free: an owner that already grabbed 16 permits before
    //      anyone else registered anything would still hold them for hours. The next tick (<=2s)
    //      restarts the connector, so a slice boundary costs one extra findByConnectorIdAndStatusIn
    //      query, not any re-fetching or re-embedding.
    //
    // Owner key for a connector: its userId, or "" in desktop/single-user mode where userId is null.
    private static final String NO_OWNER = "";
    private static final long DRAIN_SLICE_MILLIS = 60_000;

    // In-flight drains per owner - incremented synchronously in drainConnectorAsync() (called only
    // from the single scheduler thread, so a tick sees its own earlier increments when applying the
    // cap) and decremented when the drain thread finishes.
    private final Map<String, AtomicInteger> activeDrainsByOwner = new ConcurrentHashMap<>();

    // Rotates which owner is served first each tick - see fairOrder().
    private int fairOrderCursor;

    // Per-owner priority holder: the connector that owner asked to process first AND that still has
    // a backlog. Replaced wholesale (immutable map) every drainQueuedConnectors tick (2s).
    //
    // Was a single global UUID, which meant one user's "Process first" click stopped indexing for
    // every OTHER user too - the starvation this whole section exists to prevent, in its most
    // acute form. Priority is now scoped to the owner: the holder's drain bypasses
    // drainConcurrencyLimiter and is exempt from slicing, and only that same owner's other
    // connectors yield between items. Other owners' drains are unaffected.
    //
    // Concurrency overshoot: each priority holder may run permit-less when no slot is free, so the
    // bound is one extra drain per owner with an active priority (was: one, globally). That is
    // naturally small - a priority flag is a manual per-connector action, and ConnectorService
    // .setPriority() keeps at most one holder per user - and the real load ceiling is the
    // embedding semaphore (PgVectorStoreService, embedding.max-concurrent-requests), not this.
    private volatile Map<String, UUID> activePriorityByOwner = Map.of();

    // Guards drainConnector() - separate from runningFlags (reconcile) so a connector can reconcile
    // and drain concurrently (they touch different things: reconcile diffs against the remote
    // source and writes rows, drain reads QUEUED rows and embeds them) without either blocking on
    // the other's AtomicBoolean.
    private final Map<UUID, AtomicBoolean> drainingFlags = new ConcurrentHashMap<>();

    private static final List<TrackedDocument.Status> IN_PROGRESS_STATUSES = List.of(
            TrackedDocument.Status.DOWNLOADING,
            TrackedDocument.Status.CONVERTING,
            TrackedDocument.Status.EMBEDDING);

    // What the drain loop pulls per batch: DETECTED/QUEUED (never yet embedded, or changed since
    // last embed) plus EMBEDDING (a crash-resume candidate - SyncService.processItem() detects the
    // cached markdown + currentChunks>0 and resumes rather than re-downloading/re-converting).
    private static final List<TrackedDocument.Status> DRAINABLE_STATUSES = List.of(
            TrackedDocument.Status.DETECTED, TrackedDocument.Status.QUEUED, TrackedDocument.Status.EMBEDDING);
    private static final int DRAIN_BATCH_SIZE = 25;

    private final UserQuotaPort quotaPort;
    private final FeatureGateService featureGateService;

    @Autowired
    public SyncOrchestratorService(ConnectorCatalog catalog,
                                   SourceContextFactory contexts,
                                   SyncService syncService,
                                   RegisteredConnectorRepository connectorRepository,
                                   TrackedDocumentRepository documentRepository,
                                   SettingsService settingsService,
                                   ConnectorEndpointResolver endpointResolver,
                                   FeatureGateService featureGateService,
                                   @Autowired(required = false) UserQuotaPort quotaPort) {
        this.catalog = catalog;
        this.contexts = contexts;
        this.syncService = syncService;
        this.connectorRepository = connectorRepository;
        this.documentRepository = documentRepository;
        this.settingsService = settingsService;
        this.endpointResolver = endpointResolver;
        this.featureGateService = featureGateService;
        this.quotaPort = quotaPort;
    }

    /**
     * Over exactly these plugins - what the tests that build the orchestrator by hand drive.
     */
    public SyncOrchestratorService(List<ConnectorPlugin> plugins,
                                   SyncService syncService,
                                   RegisteredConnectorRepository connectorRepository,
                                   TrackedDocumentRepository documentRepository,
                                   SettingsService settingsService,
                                   ConnectorEndpointResolver endpointResolver,
                                   FeatureGateService featureGateService,
                                   UserQuotaPort quotaPort) {
        this(ConnectorCatalog.of(plugins, List.of()), syncService, connectorRepository,
                documentRepository, settingsService, endpointResolver, featureGateService, quotaPort);
    }

    private SyncOrchestratorService(ConnectorCatalog catalog, SyncService syncService,
                                    RegisteredConnectorRepository connectorRepository,
                                    TrackedDocumentRepository documentRepository, SettingsService settingsService,
                                    ConnectorEndpointResolver endpointResolver, FeatureGateService featureGateService,
                                    UserQuotaPort quotaPort) {
        this(catalog, new SourceContextFactory(endpointResolver, catalog, ConnectorHttp.direct(), null), syncService,
                connectorRepository, documentRepository, settingsService, endpointResolver, featureGateService,
                quotaPort);
    }

    // Cheap (DB-row-only) per-user quota check shared by drainQueuedConnectors() (decides whether
    // to start a connector's drain at all) and drainConnector()'s per-item loop (decides whether an
    // already-running drain should stop early). null quotaPort (desktop mode) never blocks.
    private boolean isOwnerOverQuota(RegisteredConnector connector) {
        return quotaPort != null && connector.getUserId() != null && quotaPort.isOverQuota(connector.getUserId());
    }

    @PostConstruct
    public void recoverOnStartup() {
        int recovered = 0;
        for (TrackedDocument.Status status : IN_PROGRESS_STATUSES) {
            List<TrackedDocument> stuck = documentRepository.findByStatus(status);
            for (TrackedDocument doc : stuck) {
                if (status == TrackedDocument.Status.EMBEDDING && doc.getCurrentChunks() > 0) {
                    // Leave as EMBEDDING - SyncService.processItem will detect the
                    // cached markdown file and resume from currentChunks offset
                    log.info("[Orchestrator] Will resume embedding for doc={} at chunk {}/{}",
                            doc.getId(), doc.getCurrentChunks(), doc.getTotalChunks());
                } else {
                    doc.setStatus(TrackedDocument.Status.QUEUED);
                    doc.setCurrentChunks(0);
                    doc.setFailureMessage(null);
                    documentRepository.save(doc);
                }
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("[Orchestrator] Recovered {} in-progress items on startup", recovered);
        }
        repairGhostIndexedDocuments();
    }

    // COMPLETED-with-zero-chunks rows are legacy "ghost indexed" documents (empty content that
    // slipped past a guard added later - see ../proxyma-docs/LESSONS_LEARNED.md). Without this repair,
    // SyncService.reconcileItem() would otherwise re-queue them forever on every full sync (every
    // Files sync is always full, since the files sources never support incremental sync) - showing
    // as a completed document that mysteriously starts "processing" again. Flipping them to
    // FAILED here makes the true state visible immediately and lets the existing retryFailed()
    // flow pick them back up if the source content is ever fixed.
    private void repairGhostIndexedDocuments() {
        List<TrackedDocument> ghosts = documentRepository.findByStatusAndTotalChunks(
                TrackedDocument.Status.COMPLETED, 0);
        for (TrackedDocument doc : ghosts) {
            doc.setStatus(TrackedDocument.Status.FAILED);
            doc.setFailureMessage("No extractable content (0 chunks) - repaired on startup");
            documentRepository.save(doc);
        }
        if (!ghosts.isEmpty()) {
            log.info("[Orchestrator] Repaired {} ghost-indexed (COMPLETED, 0 chunks) documents to FAILED on startup",
                    ghosts.size());
        }
    }

    // Scheduler tick interval - deliberately much finer than any real per-type sync interval
    // (the shortest configurable is minutes) so isDueForSync() below, not this fixed rate, is
    // what actually paces each connector's sync cadence.
    @Scheduled(fixedRate = 60_000)
    public void scheduledSync() {
        if (stopping) return;
        if (globalPaused) {
            log.debug("[Orchestrator] Skipping scheduled sync: paused");
            return;
        }
        List<RegisteredConnector> connectors = connectorRepository.findAll();
        // Priority holder first - matters most for a just-registered prioritized connector whose
        // items only enter the drain queue once this enumeration has run.
        connectors.sort((a, b) -> Boolean.compare(!a.isPriority(), !b.isPriority()));
        int due = 0;
        for (RegisteredConnector connector : connectors) {
            if (!isDueForSync(connector)) continue;
            if (isBackingOff(connector.getId())) continue;
            // A source is "due" until its first pass ENDS, and a first pass can take many minutes.
            // Asking again here was remembered as a follow-up (see resyncRequested, which is for
            // requests that carry something new), so every first sync longer than a minute was
            // followed at once by a second complete one - for a website, the whole crawl again.
            // The pass already running is the one this tick wants.
            if (isReconciling(connector.getId())) continue;
            due++;
            syncConnectorInternal(connector, false, true, SyncTrigger.SCHEDULED);
        }
        if (due > 0) {
            log.info("[Orchestrator] Scheduled sync triggered for {}/{} connectors ({} waiting for a slot)",
                    due, connectors.size(), waitingCount());
        }
    }

    // A type nobody describes - a row of a type this build has no plugin or descriptor for - is
    // looked at hourly, so it is neither hammered nor forgotten.
    private static final int UNDESCRIBED_SYNC_INTERVAL_SECONDS = 3600;

    // A connector is due when it has never synced, or its type's interval has elapsed since
    // lastSyncedAt. The interval is the descriptor's default unless the setting it names (a
    // per-type "<wireName>.sync-interval-seconds", or an older type's own key) overrides it - a
    // switch per type until the connector framework v2, which a new type silently fell out of.
    private boolean isDueForSync(RegisteredConnector connector) {
        if (connector.getLastSyncedAt() == null) return true;
        int intervalSeconds = syncIntervalSeconds(connector);
        if (intervalSeconds <= 0) return false;
        return connector.getLastSyncedAt().isBefore(java.time.Instant.now().minusSeconds(intervalSeconds));
    }

    int syncIntervalSeconds(RegisteredConnector connector) {
        ConnectorDescriptor descriptor = catalog.descriptor(connector.getConnectorType());
        if (descriptor == null) return UNDESCRIBED_SYNC_INTERVAL_SECONDS;
        int fallback = (int) Math.max(0, descriptor.defaultSyncInterval().toSeconds());
        String setting = descriptor.syncIntervalSetting();
        int dot = setting.indexOf('.');
        if (dot <= 0 || settingsService == null) return fallback;
        Map<String, Object> fields = settingsService.getSettingsFields(setting.substring(0, dot));
        Object value = fields == null ? null : fields.get(setting.substring(dot + 1));
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- Backoff for a source whose passes fail ----------------------------------------------
    //
    // A source is due when lastSyncedAt is older than its interval, and only a COMPLETE pass moves
    // lastSyncedAt. So a source that started failing - a revoked token, a server down, a crawl cut
    // short - was due on every one-minute tick: a whole listing (for a website, a whole crawl) and an
    // audit row a minute, 1440 a day, for as long as it failed. Its scheduled passes now wait one
    // minute after the first failure, doubling, up to the source's own interval; a pass that
    // succeeds, or anyone pressing "Sync now", is unaffected. In memory: a restart retries at once,
    // which is what a restart is for.
    private record Backoff(int failures, Instant retryAt) {}

    private final Map<UUID, Backoff> failureBackoff = new ConcurrentHashMap<>();

    private boolean isBackingOff(UUID connectorId) {
        Backoff backoff = failureBackoff.get(connectorId);
        return backoff != null && Instant.now().isBefore(backoff.retryAt());
    }

    // After every pass: a failure lengthens the wait, a success clears it.
    void recordPassOutcome(RegisteredConnector connector, boolean ok) {
        if (ok) {
            failureBackoff.remove(connector.getId());
            return;
        }
        long capSeconds = Math.max(60, syncIntervalSeconds(connector));
        failureBackoff.compute(connector.getId(), (id, previous) -> {
            int failures = previous == null ? 1 : previous.failures() + 1;
            long waitSeconds = Math.min(capSeconds, 60L << Math.min(failures - 1, 20));
            return new Backoff(failures, Instant.now().plusSeconds(waitSeconds));
        });
    }

    // Independent, always-on drain of whatever is currently QUEUED/DETECTED/resumable-EMBEDDING in
    // the DB - fully decoupled from isDueForSync() above and from reconcile() ever having run
    // recently. Fixes the core bug this split was built for: a connector whose last reconcile
    // happened to be `incremental` (nothing changed upstream) previously sat with a large backlog
    // completely idle until the source's next sync-interval tick (up to 1h for Jira/Confluence)
    // forced a full re-walk. Tick interval is deliberately much finer than any real embedding job,
    // and it never queries the (potentially huge) tracked_documents table: the backlog check reads
    // the counters RegisteredConnector already maintains.
    //
    // IT READS ONLY THE SOURCES THIS TICK CAN DO SOMETHING FOR, since 2026-10-03: the ones with a
    // backlog, and the paused ones, which are re-examined for resume. It loaded every source every
    // two seconds - with tens of thousands of them, a constant stream of full-table entity loads
    // to find the handful with work. The query's conditions are the loop's own first three tests,
    // which stay below: a row can change between the read and the loop.
    @Scheduled(fixedDelay = 2_000)
    public void drainQueuedConnectors() {
        if (globalPaused || stopping) return;
        List<RegisteredConnector> connectors =
                connectorRepository.findWithDrainWork(RegisteredConnector.Status.PAUSED);

        // Pass 1: per-connector state maintenance (quota pause/resume) and candidate selection.
        // Runs over EVERY connector, not just schedulable ones - the PAUSED-resume transition below
        // must happen regardless of whether this owner has a free fair-share slot this tick.
        List<RegisteredConnector> candidates = new ArrayList<>();
        for (RegisteredConnector connector : connectors) {
            if (connector.isDeleting()) continue;

            // A PAUSED connector must be re-evaluated for resume every tick BEFORE the backlog
            // check below, not after it. Once every item in a connector (typically a small FILES
            // connector) exhausts its auto-retry budget and becomes permanently FAILED,
            // itemCount == processedCount + failedCount holds forever, so the "no backlog"
            // continue would otherwise skip straight past the resume logic and orphan the
            // connector in PAUSED even after the owner's quota usage drops back under the limit -
            // multi-item connectors (Jira/Confluence) rarely hit this because they almost always
            // still have other QUEUED/DETECTED items keeping the backlog check from short-
            // circuiting them first.
            if (connector.getStatus() == RegisteredConnector.Status.PAUSED) {
                if (isOwnerOverQuota(connector)) {
                    continue; // still over quota, nothing else to do this tick
                }
                // Targeted, like every background write to a source's row - the row this tick read
                // must not be saved back over an edit made since (RegisteredConnectorRepository).
                connector.setStatus(RegisteredConnector.Status.ACTIVE);
                connectorRepository.updateStatus(connector.getId(), RegisteredConnector.Status.ACTIVE);
                log.info("[Orchestrator] Resumed connector={} - owner back under storage quota", connector.getId());
            }

            if (connector.getItemCount() <= connector.getProcessedCount() + connector.getFailedCount()) {
                continue; // no backlog
            }
            // Circuit breaker: a user over their storage quota gets ALL their processing stopped
            // (across every connector they own), not just individual items failing one-by-one after
            // a full download+convert has already been wasted on each - see SyncService.processItem
            // for that (now secondary/defensive) per-item check. Re-evaluated every tick, so this
            // also IS the auto-resume mechanism (see the PAUSED check above) - no separate resume
            // scheduler needed once usage drops back under the limit.
            if (isOwnerOverQuota(connector)) {
                connector.setStatus(RegisteredConnector.Status.PAUSED);
                connectorRepository.updateStatus(connector.getId(), RegisteredConnector.Status.PAUSED);
                log.info("[Orchestrator] Paused connector={} - owner over storage quota", connector.getId());
                continue;
            }
            candidates.add(connector);
        }

        // Pass 2: refresh each owner's priority holder BEFORE starting/continuing any drains this
        // tick, so in-flight drain loops (which read the volatile between items) yield promptly and
        // the holders are the first drains (re)started below. Only candidates can hold priority -
        // a prioritized connector that is fully drained, deleting or quota-paused is not a holder,
        // so it neither bypasses the limiter nor makes its owner's other connectors yield.
        Map<String, UUID> priorities = new LinkedHashMap<>();
        for (RegisteredConnector r : candidates) {
            if (r.isPriority()) priorities.putIfAbsent(ownerKey(r), r.getId());
        }
        for (Map.Entry<String, UUID> e : priorities.entrySet()) {
            if (!e.getValue().equals(activePriorityByOwner.get(e.getKey()))) {
                log.info("[Orchestrator] Priority processing active for connector={} (owner={})",
                        e.getValue(), e.getKey().isEmpty() ? "-" : e.getKey());
            }
        }
        activePriorityByOwner = Map.copyOf(priorities);

        // Pass 3: fair-share scheduling. The cap is recomputed every tick from the owners that
        // actually have a backlog right now, so slots freed by a finished owner are immediately
        // redistributed among the rest rather than staying reserved.
        int cap = perOwnerDrainCap(candidates);
        for (RegisteredConnector connector : fairOrder(candidates, priorities)) {
            String owner = ownerKey(connector);
            boolean isPriorityHolder = connector.getId().equals(priorities.get(owner));
            if (!isPriorityHolder && activeDrains(owner) >= cap) {
                continue; // this owner is already using its full share - try again next tick
            }
            drainConnectorAsync(connector);
        }
    }

    private static String ownerKey(RegisteredConnector connector) {
        String userId = connector.getUserId();
        return userId == null ? NO_OWNER : userId;
    }

    private int activeDrains(String owner) {
        AtomicInteger counter = activeDrainsByOwner.get(owner);
        return counter == null ? 0 : counter.get();
    }

    // Even split of the drain slots across the owners that currently have a backlog, floor'd but
    // never below 1 (an owner with a backlog always gets to make progress, even with more owners
    // than slots - the slice mechanism is what keeps that fair over time). A single owner gets the
    // full number of slots, so desktop/single-user behaviour is unchanged.
    private int perOwnerDrainCap(List<RegisteredConnector> candidates) {
        long owners = candidates.stream().map(SyncOrchestratorService::ownerKey).distinct().count();
        if (owners <= 1) return maxConcurrentDrains;
        return Math.max(1, maxConcurrentDrains / (int) owners);
    }

    // Round-robin interleave across owners (owner A's 1st connector, owner B's 1st, owner A's 2nd,
    // ...), with each owner's priority holder first within its own group and the owner order
    // rotated by one per tick. Without the rotation, findAll() order would decide who gets a slot
    // that freed up since the last tick, which is exactly the systematic bias the cap is meant to
    // remove when several owners sit at the cap simultaneously.
    private List<RegisteredConnector> fairOrder(List<RegisteredConnector> candidates,
                                               Map<String, UUID> priorities) {
        Map<String, List<RegisteredConnector>> byOwner = new LinkedHashMap<>();
        for (RegisteredConnector r : candidates) {
            byOwner.computeIfAbsent(ownerKey(r), k -> new ArrayList<>()).add(r);
        }
        List<List<RegisteredConnector>> groups = new ArrayList<>();
        for (Map.Entry<String, List<RegisteredConnector>> e : byOwner.entrySet()) {
            UUID holder = priorities.get(e.getKey());
            if (holder != null) {
                e.getValue().sort((a, b) -> Boolean.compare(!a.getId().equals(holder), !b.getId().equals(holder)));
            }
            groups.add(e.getValue());
        }
        if (groups.size() > 1) {
            int shift = Math.floorMod(fairOrderCursor++, groups.size());
            java.util.Collections.rotate(groups, -shift);
        }
        int deepest = groups.stream().mapToInt(List::size).max().orElse(0);
        List<RegisteredConnector> ordered = new ArrayList<>(candidates.size());
        for (int i = 0; i < deepest; i++) {
            for (List<RegisteredConnector> group : groups) {
                if (i < group.size()) ordered.add(group.get(i));
            }
        }
        return ordered;
    }

    // Requeues FAILED items that are still within their auto-retry budget (see
    // TrackedDocument.MAX_AUTO_RETRIES/AUTO_RETRY_DELAY) and are due (nextRetryAt has passed) -
    // but only once their owning connector has no other backlog left (DRAINABLE_STATUSES, the
    // exact same set drainConnector() itself uses to decide a connector is fully drained), so
    // retries only kick in after everything else has been processed. Deliberately does NOT call
    // updateConnectorCounts() - a retryCount < MAX_AUTO_RETRIES item was already excluded from the
    // connector's stored failedCount (see SyncService.updateConnectorCounts()), so resetting it to
    // QUEUED doesn't change that counter; the normal drain flow recomputes it once this attempt
    // itself completes or fails.
    // Ids per requeue statement, well inside every database's bound on parameters.
    private static final int RETRY_REQUEUE_CHUNK = 500;

    @Scheduled(fixedRate = 60_000)
    public void autoRetryFailedItems() {
        if (globalPaused || stopping) return;
        List<TrackedDocument> candidates = documentRepository.findByStatusAndRetryCountLessThanAndNextRetryAtBefore(
                TrackedDocument.Status.FAILED, TrackedDocument.MAX_AUTO_RETRIES, Instant.now());
        // BY SOURCE: the backlog is looked at once per source, before any of its rows is requeued,
        // and all its due rows go back together. It was looked at per ROW, and the first row
        // requeued was itself a backlog to every later row of the same source - so a source whose
        // five hundred items failed in one provider outage got one of them retried a minute.
        Map<UUID, List<UUID>> dueBySource = new LinkedHashMap<>();
        for (TrackedDocument doc : candidates) {
            if (doc.getConnectorId() == null) continue;
            dueBySource.computeIfAbsent(doc.getConnectorId(), k -> new ArrayList<>()).add(doc.getId());
        }
        int requeued = 0;
        for (Map.Entry<UUID, List<UUID>> source : dueBySource.entrySet()) {
            if (documentRepository.countByConnectorIdAndStatusIn(source.getKey(), DRAINABLE_STATUSES) > 0) continue;
            List<UUID> ids = source.getValue();
            for (int from = 0; from < ids.size(); from += RETRY_REQUEUE_CHUNK) {
                requeued += documentRepository.requeueForRetry(
                        ids.subList(from, Math.min(ids.size(), from + RETRY_REQUEUE_CHUNK)),
                        TrackedDocument.Status.QUEUED, TrackedDocument.Status.FAILED);
            }
        }
        if (requeued > 0) {
            log.info("[Orchestrator] Auto-retry: requeued {} failed item(s) for retry", requeued);
        }
    }

    private void drainConnectorAsync(RegisteredConnector connector) {
        UUID connectorId = connector.getId();
        AtomicBoolean flag = drainingFlags.computeIfAbsent(connectorId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return; // already draining this connector
        }
        ConnectorPlugin plugin = catalog.plugin(connector.getConnectorType()).orElse(null);
        if (plugin == null) {
            log.warn("[Orchestrator] No source found for type={}", connector.getConnectorType());
            flag.set(false);
            return;
        }
        // Counted here, on the scheduler thread, rather than inside the virtual thread: the cap
        // check in drainQueuedConnectors() has to see the drains this same tick already started,
        // otherwise one tick could start every one of an owner's connectors before any of them had
        // a chance to register themselves.
        String owner = ownerKey(connector);
        activeDrainsByOwner.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
        startWorker("drain-" + connectorId, () -> {
            try {
                // The priority holder must never wait behind already-running drains for a permit.
                // It proceeds permit-less when none is free; its owner's other drains yield between
                // items as soon as they observe activePriorityByOwner, so the overshoot is brief
                // and bounded at one per owner holding a priority (see the field's comment).
                boolean permitAcquired;
                if (connectorId.equals(activePriorityByOwner.get(owner))) {
                    permitAcquired = drainConcurrencyLimiter.tryAcquire();
                } else {
                    drainConcurrencyLimiter.acquire();
                    permitAcquired = true;
                }
                try {
                    drainConnector(plugin, connectorId, owner);
                } finally {
                    if (permitAcquired) drainConcurrencyLimiter.release();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (stopping) {
                    log.debug("[Orchestrator] Drain of connector={} ended by the shutdown: {}", connectorId, e.getMessage());
                } else {
                    log.error("[Orchestrator] Drain failed for connector={}: {}", connectorId, e.getMessage(), e);
                }
            } finally {
                activeDrainsByOwner.get(owner).decrementAndGet();
                flag.set(false);
            }
        });
    }

    // A pass or drain thread, counted in `workers` from before it starts until it ends.
    private void startWorker(String name, Runnable body) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                body.run();
            } finally {
                workers.remove(Thread.currentThread());
            }
        });
        workers.add(thread);
        thread.start();
    }

    // Drains one connector's current backlog to empty, one item at a time (Lucene only allows one
    // IndexWriter open per collection at a time - see BM25IndexService.addDocuments(), which opens
    // a fresh writer per call - so two threads embedding into the SAME connector/collection
    // concurrently would throw LockObtainFailedException; the drainingFlags CAS above is what
    // guarantees only one thread ever drains a given connector at a time). Different connectors -
    // including same-source-type ones - drain fully concurrently, bounded only by
    // drainConcurrencyLimiter and the per-owner fair-share cap.
    //
    // Returns after at most DRAIN_SLICE_MILLIS of work (priority holders excepted) even if the
    // backlog is not empty, so the permit goes back into the pool and the next tick can hand it to
    // whichever owner is furthest below its share. Resuming costs one extra batch query - all
    // progress is already committed per item by processItem().
    private void drainConnector(ConnectorPlugin plugin, UUID connectorId, String owner) {
        long sliceDeadline = System.currentTimeMillis() + DRAIN_SLICE_MILLIS;
        while (true) {
            if (stopping) return;
            RegisteredConnector connector = connectorRepository.findById(connectorId).orElse(null);
            if (connector == null || connector.isDeleting()) return;
            // Its queue waits for a connection rather than failing item by item: every fetch would be
            // refused for want of a server, and each refusal was recorded as a FAILED document.
            if (lacksConnection(connector)) return;
            List<TrackedDocument> batch = documentRepository.findByConnectorIdAndStatusInOrderByIdAsc(
                    connectorId, DRAINABLE_STATUSES, PageRequest.of(0, DRAIN_BATCH_SIZE));
            if (batch.isEmpty()) {
                // Backlog just drained to empty - the correct moment to stamp
                // indexedWithEmbeddingModel / complete a model-changed reindex swap, now that
                // every item is guaranteed to have reached a terminal status. See
                // SyncService.onConnectorDrained() for why this can't live in reconcile() instead.
                syncService.onConnectorDrained(connectorId);
                return;
            }
            String collectionId = connector.getReindexShadowCollectionId() != null
                    ? connector.getReindexShadowCollectionId()
                    : connectorId.toString().replace("-", "");
            // ONE SESSION PER BATCH, opened over this source's own context and closed with the
            // batch - so its fetches see this source's server and credential and nobody else's.
            try (ConnectorSession session = plugin.open(contexts.build(connector))) {
                if (!drainBatch(session, connector, batch, collectionId, connectorId, owner, sliceDeadline)) return;
            }
        }
    }

    // One batch through one session. False when the drain should stop here - the source is being
    // deleted, its owner went over quota, a priority source of the same owner wants the slot, or
    // the fair-share slice ran out.
    private boolean drainBatch(ConnectorSession session, RegisteredConnector connector, List<TrackedDocument> batch,
                               String collectionId, UUID connectorId, String owner, long sliceDeadline) {
        {
            for (TrackedDocument doc : batch) {
                if (stopping) return false;
                if (syncService.isConnectorDeleting(connectorId)) return false;
                // An already-running drain must also stop within one item (not just newly-started
                // ones via drainQueuedConnectors()) the moment its owner crosses over quota mid-batch.
                if (isOwnerOverQuota(connector)) return false;
                // Yield to a priority connector OF THE SAME OWNER: stop before the next item (item
                // granularity, so within seconds rather than after the whole 25-item batch) and
                // release this drain's permit via the caller's finally. The scheduler tick restarts
                // this connector's drain once the holder is fully drained or de-prioritized. Another
                // owner's priority never stops this drain - see activePriorityByOwner.
                UUID prio = activePriorityByOwner.get(owner);
                boolean isPriorityHolder = connectorId.equals(prio);
                if (prio != null && !isPriorityHolder) {
                    log.debug("[Orchestrator] Drain of connector={} yielding to priority connector={} (same owner)",
                            connectorId, prio);
                    return false;
                }
                // Fair-share slice boundary: give the permit back so another owner's connector can
                // start, unless this is the priority holder (which is meant to run to completion).
                if (!isPriorityHolder && System.currentTimeMillis() >= sliceDeadline) {
                    log.debug("[Orchestrator] Drain of connector={} yielding at slice boundary", connectorId);
                    return false;
                }
                ItemMetadata meta = syncService.toMinimalItemMetadata(doc);
                // Only the fetch is the session's: download, convert and embed run outside it. A
                // session takes no lock - the per-type lock the old connector beans needed is gone
                // with them.
                Supplier<InputStream> contentSupplier = () -> session.fetch(meta);
                syncService.processItem(connector.getConnectorType(), doc, collectionId, meta, contentSupplier);
            }
        }
        return true;
    }

    public void syncAll() {
        log.info("[Orchestrator] Manual sync all triggered");
        List<RegisteredConnector> connectors = connectorRepository.findAll();
        for (RegisteredConnector connector : connectors) {
            syncConnectorInternal(connector, false, true, SyncTrigger.MANUAL);
        }
    }

    public void syncConnector(ConnectorType type) {
        log.info("[Orchestrator] Sync triggered for source type={}", type);
        List<RegisteredConnector> connectors = connectorRepository.findByConnectorType(type);
        for (RegisteredConnector connector : connectors) {
            syncConnectorInternal(connector, false, true, SyncTrigger.MANUAL);
        }
    }

    public void syncConnector(UUID connectorId, String userId) {
        syncConnectorReportStarted(connectorId, userId);
    }

    // Returns true if a sync was actually started, false if one was already running
    // for this connector (so callers - e.g. the manual "Sync now" button - can tell
    // a no-op apart from a real sync).
    public boolean syncConnectorReportStarted(UUID connectorId, String userId) {
        return syncConnectorReportStarted(connectorId, userId, false);
    }

    // forceFull=true makes this connector's sync bypass incremental eligibility and run the
    // source's full enumeration - see SyncService.reconcile(source, connector, forceFull) for
    // why the manual "Sync now" action wants that and the scheduler does not.
    public boolean syncConnectorReportStarted(UUID connectorId, String userId, boolean forceFull) {
        RegisteredConnector connector = connectorRepository.findById(connectorId).orElse(null);
        if (connector == null) {
            log.warn("[Orchestrator] Connector not found: id={}", connectorId);
            return false;
        }
        if (userId != null && !userId.equals(connector.getUserId())) {
            log.warn("[Orchestrator] Connector does not belong to caller: id={} userId={}", connectorId, userId);
            return false;
        }
        return syncConnectorInternal(connector, forceFull, SyncTrigger.requestedBy(userId));
    }

    /**
     * Records documents just PUSHED to a source (a custom source - the ingestion API, the agent's
     * saveToSource) as tracked items, exactly as a pass's listing would have: the same
     * SyncService.reconcileItem, so the drain picks them up within seconds and everything after that
     * - statuses, failures, retries, the graph - is what it is for any item.
     *
     * <p>WITHOUT A PASS, deliberately. A pass per push listed what arrived since the last one, and
     * an incremental pass that finds items still queued from an earlier push forces the next pass to
     * be full (SyncService.reconcile's orphan check) - so a bulk import of a hundred thousand
     * documents in batches of a hundred re-listed the whole source every other batch.
     *
     * <p>UNDER THE SOURCE'S PASS FLAG, so a push and a pass never record one new item side by side
     * (both would find no row and insert one each). While a pass runs, nothing is recorded here: the
     * documents are already stored, so the one follow-up pass owed to the source (resyncRequested)
     * lists them. False when that is what happened, or the source is gone.
     */
    public boolean recordPushed(UUID connectorId, List<ItemMetadata> items) {
        RegisteredConnector connector = connectorRepository.findById(connectorId).orElse(null);
        if (connector == null || connector.isDeleting()) return false;
        AtomicBoolean flag = runningFlags.computeIfAbsent(connectorId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            resyncRequested.add(connectorId);
            return false;
        }
        try {
            syncService.recordItems(connector, items);
        } finally {
            flag.set(false);
            // A pass somebody asked for while the flag was held here was remembered, not run.
            runQueuedFollowUp(connectorId);
        }
        return true;
    }

    public void pause() {
        globalPaused = true;
        log.info("[Orchestrator] Global sync paused");
    }

    public void resume() {
        globalPaused = false;
        log.info("[Orchestrator] Global sync resumed");
        Thread.ofVirtual().name("sync-resume").start(this::startWaiting);
    }

    public boolean isPaused() { return globalPaused; }

    /**
     * Whether anything is running for this connector right now - a reconcile pass, a drain, or
     * both.
     *
     * <p>Both flags, for the reason waitForSyncToStop() below gives: reconcile and draining are
     * decoupled, and a connector with nothing left to enumerate can still be embedding for minutes.
     * Reporting only the reconcile flag would show a source as idle for almost all of the time it
     * is actually working.
     *
     * <p>In-memory and therefore per-process, which is what it should be: it answers "is this
     * instance doing something", and there is one instance.
     */
    public boolean isSyncing(UUID connectorId) {
        AtomicBoolean drainFlag = drainingFlags.get(connectorId);
        return isReconciling(connectorId) || (drainFlag != null && drainFlag.get());
    }

    // The reconcile half alone: is a pass listing this source right now.
    private boolean isReconciling(UUID connectorId) {
        AtomicBoolean syncFlag = runningFlags.get(connectorId);
        return syncFlag != null && syncFlag.get();
    }

    // Waits for BOTH reconcile (runningFlags) and drain (drainingFlags) to stop for this connector.
    // Callers (ConnectorService.removeConnector()/reindexConnector()) delete TrackedDocument rows or
    // reset them to QUEUED right after this returns - draining is now decoupled and can run for
    // minutes (not the old short-lived process() call bundled into one sync pass), so a connector
    // can easily have runningFlags[id]==false while drainingFlags[id]==true. Polling only the
    // former would return immediately and let the caller mutate/delete rows out from under an
    // in-flight processItem() call.
    public void waitForSyncToStop(UUID connectorId) {
        AtomicBoolean syncFlag = runningFlags.get(connectorId);
        AtomicBoolean drainFlag = drainingFlags.get(connectorId);
        if (syncFlag == null && drainFlag == null) return;
        long deadline = System.currentTimeMillis() + 30_000;
        while ((syncFlag != null && syncFlag.get()) || (drainFlag != null && drainFlag.get())) {
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if ((syncFlag != null && syncFlag.get()) || (drainFlag != null && drainFlag.get())) {
            log.warn("[Orchestrator] Sync/drain for connector={} did not stop within timeout", connectorId);
        }
    }

    // A request somebody made for this one source: it starts now, whatever else is running.
    private boolean syncConnectorInternal(RegisteredConnector connector, boolean forceFull, SyncTrigger trigger) {
        return syncConnectorInternal(connector, forceFull, false, trigger);
    }

    // waitForSlot: wait behind maxConcurrentSyncs rather than start now - see waitingForSlot.
    private boolean syncConnectorInternal(RegisteredConnector connector, boolean forceFull, boolean waitForSlot,
                                          SyncTrigger trigger) {
        if (stopping) return false;
        if (connector.isDeleting()) {
            log.debug("[Orchestrator] Skipping sync for deleting connector={}", connector.getId());
            return false;
        }
        // An ENTERPRISE type (Microsoft 365) is Enterprise Server only. A source registered by an
        // older Desktop or Home Server build stays listed, so it can be removed, and is never synced
        // again.
        boolean enterpriseType = connector.getConnectorType() != null && catalog.requiresEnterprise(connector.getConnectorType());
        if (enterpriseType && !multiUser) {
            log.debug("[Orchestrator] Skipping Enterprise-only connector={} - Enterprise Server only", connector.getId());
            return false;
        }
        // And it needs the license: on an Enterprise Server whose trial or license has ended the
        // source stays listed and what it indexed stays searchable, but it is not synced again
        // until a license is in force. Keep working, stop growing.
        if (enterpriseType && !featureGateService.getFeatures().isConnectorAllowed(catalog.edition(connector.getConnectorType()))) {
            recordNotLicensed(connector);
            return false;
        }
        if (lacksConnection(connector)) {
            recordNoConnection(connector);
            return false;
        }
        AtomicBoolean flag = runningFlags.computeIfAbsent(connector.getId(), k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            // Remembered, not dropped - the running pass may already be past whatever this request
            // is about (see resyncRequested). One follow-up runs when it finishes, however many
            // requests arrive in the meantime.
            resyncRequested.add(connector.getId());
            if (forceFull) forceFullRequested.add(connector.getId());
            log.info("[Orchestrator] Sync already running for connector={} - queued one follow-up pass (forceFull={})",
                    connector.getId(), forceFull);
            return false;
        }
        if (!takeSyncSlot(connector.getId(), forceFull, waitForSlot)) {
            flag.set(false);
            log.debug("[Orchestrator] connector={} waits for a sync slot ({} running)", connector.getId(), maxConcurrentSyncs);
            return false;
        }
        ConnectorPlugin plugin = catalog.plugin(connector.getConnectorType()).orElse(null);
        if (plugin == null) {
            log.warn("[Orchestrator] No source found for type={}", connector.getConnectorType());
            flag.set(false);
            releaseSyncSlot();
            return false;
        }
        long startedAt = System.nanoTime();
        startWorker("sync-" + connector.getId(), () -> {
            try {
                // ONE SESSION PER PASS, over a context built from this source's own row, opened here
                // and closed when the pass ends. Sessions share nothing, so two sources of one type
                // list side by side - where synchronized(bean) once made them wait.
                SyncService.PassResult result;
                try (ConnectorSession session = plugin.open(contexts.build(connector))) {
                    result = syncService.reconcile(session, plugin.descriptor(), connector, forceFull);
                }
                if (result != null) recordPassOutcome(connector, result.ok());
                auditPass(connector, trigger, result, null, startedAt);
            } catch (Exception e) {
                if (stopping) {
                    // Cut short by the shutdown, not failed: no backoff, no audit entry.
                    log.debug("[Orchestrator] Sync of connector={} ended by the shutdown: {}", connector.getId(), e.getMessage());
                } else {
                    log.error("[Orchestrator] Sync failed for connector={}: {}", connector.getId(), e.getMessage(), e);
                    recordPassOutcome(connector, false);
                    auditPass(connector, trigger, null, e, startedAt);
                }
            } finally {
                flag.set(false);
                releaseSyncSlot();
                runQueuedFollowUp(connector.getId());
                startWaiting();
            }
        });
        return true;
    }

    /**
     * Who a sync pass is for, as the audit log files it. A person's request - "Sync now", a
     * registration's first pass, a re-sync after an edit - carries their id (null on Desktop, which
     * the writer files as the local user); the scheduler and the follow-ups it owes are SYSTEM.
     */
    record SyncTrigger(String name, boolean byUser, String userId) {
        static final SyncTrigger SCHEDULED = new SyncTrigger("scheduled", false, null);
        static final SyncTrigger MANUAL = new SyncTrigger("manual", false, null);
        static final SyncTrigger FOLLOW_UP = new SyncTrigger("follow-up", false, null);

        static SyncTrigger requestedBy(String userId) {
            return new SyncTrigger("manual", true, userId);
        }
    }

    // One entry per pass, when it ends. A pass that found the source being deleted returns no
    // result and is not recorded - the removal is. The counts after the pass are the source's own
    // (indexed and failed are the whole source's, not this pass's: embedding is the drain loop's).
    private void auditPass(RegisteredConnector connector, SyncTrigger trigger, SyncService.PassResult result,
                           Exception error, long startedAt) {
        if (result == null && error == null) return;
        boolean ok = error == null && result.ok();
        String message = error != null ? error.getMessage() : result.message();
        // A scheduled pass failing the way the last one did is not news: the source already shows
        // that error, and an entry per attempt filled the log with one line per backoff step.
        // `connector` is the row as the pass found it, so this compares with the state before it.
        if (!ok && !trigger.byUser() && connector.getStatus() == RegisteredConnector.Status.ERROR
                && java.util.Objects.equals(connector.getLastSyncError(), RegisteredConnector.boundedSyncError(message))) {
            return;
        }
        RegisteredConnector fresh;
        try {
            fresh = connectorRepository.findById(connector.getId()).orElse(connector);
        } catch (RuntimeException e) {
            fresh = connector; // auditing must never fail the pass it records
        }
        AuditEntry.Builder entry = AuditEntry.of(AuditCategory.SOURCES, ok ? "sync.finished" : "sync.failed")
                .outcome(ok ? AuditEntry.OK : AuditEntry.FAILED)
                .target(fresh.getName())
                .summary(ok ? "Synced " + fresh.getName() : "Sync of " + fresh.getName() + " failed")
                .duration((System.nanoTime() - startedAt) / 1_000_000)
                .detail("type", fresh.getConnectorType() == null ? null : fresh.getConnectorType().name())
                .detail("trigger", trigger.name())
                .detail(ok ? "note" : "error", message);
        if (result != null) {
            entry.detail("mode", result.incremental() ? "incremental" : "full")
                    .detail("found", result.found())
                    .detail("notRecorded", result.skipped() > 0 ? result.skipped() : null)
                    .detail("removed", result.removed());
        }
        entry.detail("items", fresh.getItemCount())
                .detail("indexed", fresh.getProcessedCount())
                .detail("failed", fresh.getFailedCount());
        if (trigger.byUser()) entry.user(trigger.userId());
        else entry.system();
        entry.record();
    }

    // Takes a slot, or - for a pass that waits - queues the source for one. A pass that does not
    // wait always takes one, so the count may run over the limit while somebody's own requests do.
    private boolean takeSyncSlot(UUID connectorId, boolean forceFull, boolean waitForSlot) {
        synchronized (syncSlots) {
            if (waitForSlot && activeSyncs >= maxConcurrentSyncs) {
                waitingForSlot.add(connectorId);
                if (forceFull) forceFullWaiting.add(connectorId);
                return false;
            }
            // Its place in the queue goes, and so does the full pass the queued request asked for:
            // left behind, it turned a later scheduled pass into a full one nobody asked for.
            waitingForSlot.remove(connectorId);
            forceFullWaiting.remove(connectorId);
            activeSyncs++;
            return true;
        }
    }

    private void releaseSyncSlot() {
        synchronized (syncSlots) {
            activeSyncs = Math.max(0, activeSyncs - 1);
        }
    }

    int waitingCount() {
        synchronized (syncSlots) {
            return waitingForSlot.size();
        }
    }

    /**
     * Starts the sources waiting for a slot, oldest first, while slots are free. Each is re-read:
     * it may have been deleted, or synced by somebody's own request, while it waited. Runs on the
     * finishing pass's thread, after its follow-up, so a source owed a second pass is not pushed
     * behind the queue. A pause holds the queue as it holds the scheduler.
     */
    private void startWaiting() {
        while (!globalPaused && !stopping) {
            UUID next;
            synchronized (syncSlots) {
                if (activeSyncs >= maxConcurrentSyncs || waitingForSlot.isEmpty()) return;
                Iterator<UUID> it = waitingForSlot.iterator();
                next = it.next();
                it.remove();
            }
            boolean forceFull = forceFullWaiting.remove(next);
            try {
                RegisteredConnector fresh = connectorRepository.findById(next).orElse(null);
                if (fresh == null || fresh.isDeleting()) continue;
                // Only the scheduler and "Sync all" queue a pass, so a waiting one is filed as scheduled.
                syncConnectorInternal(fresh, forceFull, true, SyncTrigger.SCHEDULED);
            } catch (Exception e) {
                log.error("[Orchestrator] Waiting sync failed to start for connector={}: {}", next, e.getMessage(), e);
            }
        }
    }

    /**
     * Starts the one follow-up pass owed to a connector whose sync requests arrived while it was
     * already running. Called after the running flag is cleared, so the follow-up can take it.
     *
     * <p>The connector is re-read from the database rather than reusing the object the finished
     * pass held: minutes may have passed, and it may since have been deleted, paused or pointed at
     * a different server. Any failure here is logged and swallowed - this runs in a finally block
     * on the sync thread, and a follow-up that cannot start must not turn into an exception that
     * looks like the original sync failing.
     */
    private void runQueuedFollowUp(UUID connectorId) {
        if (stopping) return;
        if (!resyncRequested.remove(connectorId)) return;
        boolean forceFull = forceFullRequested.remove(connectorId);
        try {
            RegisteredConnector fresh = connectorRepository.findById(connectorId).orElse(null);
            if (fresh == null || fresh.isDeleting()) {
                log.debug("[Orchestrator] Dropping queued follow-up sync for connector={} - gone or deleting", connectorId);
                return;
            }
            log.info("[Orchestrator] Running queued follow-up sync for connector={} (forceFull={})", connectorId, forceFull);
            syncConnectorInternal(fresh, forceFull, true, SyncTrigger.FOLLOW_UP);
        } catch (Exception e) {
            log.error("[Orchestrator] Queued follow-up sync failed for connector={}: {}", connectorId, e.getMessage(), e);
        }
    }

    /**
     * A source of a service that reads through a connection, with none it can use - registered
     * before connections existed, or its connection deleted. Its connector would only report "not
     * configured" and name a setting that no longer exists, so sync does not start at all.
     */
    boolean lacksConnection(RegisteredConnector connector) {
        return catalog.protocolFor(connector.getConnectorType()) != null
                && !endpointResolver.resolve(connector).exists();
    }

    // The source says what to do about it, in the error its card and dialog already show.
    private void recordNoConnection(RegisteredConnector connector) {
        String message = noConnectionMessage(connector.getConnectorType());
        connectorRepository.findById(connector.getId()).ifPresent(r -> {
            if (r.getStatus() == RegisteredConnector.Status.ERROR && message.equals(r.getLastSyncError())) return;
            connectorRepository.updateSyncState(r.getId(), RegisteredConnector.Status.ERROR,
                    RegisteredConnector.boundedSyncError(message));
        });
        log.info("[Orchestrator] Not syncing connector={} - it has no {} connection",
                connector.getId(), connector.getConnectorType());
    }

    static final String NOT_LICENSED_MESSAGE =
            "This source needs an Enterprise license. It is kept, and searchable, but not synced.";

    private void recordNotLicensed(RegisteredConnector connector) {
        connectorRepository.findById(connector.getId()).ifPresent(r -> {
            if (r.getStatus() == RegisteredConnector.Status.ERROR && NOT_LICENSED_MESSAGE.equals(r.getLastSyncError())) return;
            r.setStatus(RegisteredConnector.Status.ERROR);
            r.setLastSyncError(NOT_LICENSED_MESSAGE);
            connectorRepository.save(r);
        });
        log.info("[Orchestrator] Not syncing Enterprise-only connector={} - no Enterprise license in force", connector.getId());
    }

    String noConnectionMessage(ConnectorType type) {
        String service = catalog.displayName(catalog.protocolFor(type));
        return "This source has no " + service + " connection. Edit it and choose one, or add one in "
                + "Settings > Connections.";
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    /**
     * The first step of a stop: nothing new starts, and running work stops at its next safe point.
     * On the close event, ordered first, so it is in force before Spring's executors stop taking
     * tasks (they do on this same event) and long before any bean is destroyed.
     */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onContextClosed() {
        beginStopping();
    }

    void beginStopping() {
        if (stopping) return;
        stopping = true;
        syncService.stopForShutdown();
        log.info("[Orchestrator] Stopping sync work for shutdown ({} pass(es) and drain(s) running)", workers.size());
    }

    /** Whether any pass or drain thread is still running. */
    boolean hasRunningWork() {
        return !workers.isEmpty();
    }

    // Waits for the running passes and drains to end, up to `millis`. True when they all did.
    boolean awaitWorkers(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (!workers.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return workers.isEmpty();
    }

    @Override
    public void start() {
        lifecycleRunning = true;
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    @Override
    public int getPhase() {
        return STOP_PHASE;
    }

    @Override
    public void stop() {
        beginStopping();
        finishStopping();
    }

    // Asynchronous, so the web server's graceful shutdown in the same phase waits alongside it.
    @Override
    public void stop(Runnable callback) {
        beginStopping();
        Thread.ofPlatform().daemon().name("sync-stop").start(() -> {
            try {
                finishStopping();
            } finally {
                callback.run();
            }
        });
    }

    private void finishStopping() {
        long started = System.currentTimeMillis();
        boolean done = awaitWorkers(STOP_WAIT_MILLIS);
        lifecycleRunning = false;
        if (done) {
            log.info("[Orchestrator] Sync work stopped in {} ms", System.currentTimeMillis() - started);
        } else {
            log.info("[Orchestrator] {} sync thread(s) still finishing an item as the application stops",
                    workers.size());
        }
    }

    /**
     * Runs the connector's own access probe for a CANDIDATE source - a transient
     * {@link RegisteredConnector} that has not been saved and may never be.
     *
     * <p>This is the one place registration checks a credential, so every entry point gets it:
     * the REST endpoints and the agent tools alike. It used to live in the
     * REST controller, so registering through a tool or in a batch created a source with a wrong
     * token that sat at zero items, its only explanation a log line on another thread minutes
     * later.
     *
     * <p>A session over the candidate, closed at once; it shares nothing with any other source's. A
     * connector with no probe answers
     * {@link AccessCheck#unsupported()} and registration proceeds exactly as before.
     */
    public AccessCheck verifyAccess(RegisteredConnector candidate) {
        ConnectorPlugin plugin = catalog.plugin(candidate.getConnectorType()).orElse(null);
        if (plugin == null) return AccessCheck.unsupported();
        try (ConnectorSession session = plugin.open(contexts.build(candidate))) {
            return session.verifyAccess();
        } catch (Exception e) {
            // verifyAccess() must not throw, but opening a session reads settings and a
            // connector may still get this wrong. A probe that fails to RUN is not a refusal -
            // reporting it as one would block registration on an unrelated fault.
            log.warn("[Orchestrator] Access probe could not run for type={}: {}",
                    candidate.getConnectorType(), e.getMessage());
            return AccessCheck.unsupported();
        }
    }

}
