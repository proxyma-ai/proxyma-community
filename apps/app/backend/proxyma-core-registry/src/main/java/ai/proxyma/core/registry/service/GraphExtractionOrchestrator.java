package ai.proxyma.core.registry.service;

import ai.proxyma.core.registry.event.ConnectorGraphEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.graph.service.DocumentGraphState;
import ai.proxyma.core.graph.service.GraphModelUnreachableException;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the knowledge graph from what is ALREADY indexed, on its own schedule.
 *
 * <p><b>The graph is a plug-in to indexing, not a stage of it.</b> Extraction used to run inside
 * SyncService.processItem(), between embedding and COMPLETED, and that made the graph a passenger:
 * switching it on for a source that was already indexed did nothing until every document was
 * re-embedded, a slow graph model held up the drain worker, and a graph refused for budget marked
 * the DOCUMENT failed - whose retry then downloaded, converted and embedded it again to have
 * another go at the graph. Now ingest knows nothing about the graph, and this worker reads what
 * ingest produced:
 *
 * <ul>
 *   <li>It picks COMPLETED documents of sources with the graph switched on that the graph has not
 *       read, or has not read since they were last indexed ({@link DocumentGraphState#need}).</li>
 *   <li>It reads their chunks out of the KEYWORD INDEX, where every indexed document's chunk text
 *       already is, in both editions. Nothing is fetched, converted or embedded.</li>
 *   <li>It never writes a TrackedDocument. Whatever happens here - a failure, a timeout, the
 *       deployment out of budget - the document stays exactly as indexed and exactly as
 *       searchable.</li>
 * </ul>
 *
 * <p>Switching the graph OFF stays in ConnectorService.setGraphEnabled, which deletes the graph's
 * rows and nothing else. This worker notices the switch before each document, and cleans up after
 * one that was already running when the switch went.
 */
@Service
public class GraphExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionOrchestrator.class);

    /**
     * Documents extracted at once, across every source. Each is a sequence of LLM calls, and the
     * provider behind the graph role - often a small local model - is the real ceiling, so this is
     * kept low. Extraction used to inherit the drain loop's sixteen, which was never a number
     * anyone chose for the graph.
     */
    static final int MAX_CONCURRENT_DOCUMENTS = 4;

    /** Documents taken from one source per pass, so one large source cannot starve the others. */
    static final int BATCH_PER_CONNECTOR = 8;

    /** How long the worker stands down after the deployment's spend limit refuses a call. */
    static final Duration QUOTA_BACKOFF = Duration.ofMinutes(15);

    /**
     * How long it stands down the FIRST time the graph model cannot be reached at all.
     *
     * <p>Shorter than the quota wait, because this one usually clears by itself - a local model
     * that was not running gets started, a laptop rejoins the network. The corpus is picked up
     * again without anybody touching the toggle, which is the whole point of retrying rather than
     * recording the documents as read.
     *
     * <p>Each stand-down in a row that again reached no model doubles the next - 5, 10, 20, 40, then
     * 60 minutes at most - and the first document a model answers resets it (see
     * {@link #unreachableBackoff}). It was five minutes for ever: a remote model that did not answer
     * for two days was asked every five minutes for two days, and the only trace was a WARN line in
     * a log nobody reads, so {@link #modelOutage} now says so where the source's graph progress is
     * shown.
     */
    static final Duration UNREACHABLE_BACKOFF = Duration.ofMinutes(5);

    /** The longest stand-down for a model that keeps not answering. */
    static final Duration UNREACHABLE_BACKOFF_MAX = Duration.ofMinutes(60);

    /** The stand-down after {@code standDowns} in a row reached no model: 5, 10, 20, 40, 60, 60... */
    static Duration unreachableBackoff(int standDowns) {
        long minutes = UNREACHABLE_BACKOFF.toMinutes() << Math.min(Math.max(0, standDowns - 1), 10);
        return Duration.ofMinutes(Math.min(minutes, UNREACHABLE_BACKOFF_MAX.toMinutes()));
    }

    /**
     * The knowledge-graph model has stopped answering, as a screen can say it.
     *
     * @param since        when the first stand-down of this outage began
     * @param retryAt      when the worker asks again
     * @param standDowns   how many stand-downs in a row reached no model
     * @param host         the host the model is reached at, when the provider names one
     * @param reason       why the last call failed, in words
     * @param throughProxy whether calls to that host go through the configured proxy - which, for a
     *                     model on the local network, is usually why it cannot be reached
     */
    public record ModelOutage(Instant since, Instant retryAt, int standDowns, String host, String reason,
                              boolean throughProxy) {}

    private final RegisteredConnectorRepository connectorRepository;
    private final TrackedDocumentRepository documentRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final BM25IndexService bm25IndexService;
    private final SyncOrchestratorService syncOrchestratorService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    // The documents being READ right now - a subset of inFlight, which also holds the ones queued
    // behind the semaphore. What a screen means by "the graph is extracting this", and the only
    // honest answer to it: a progress row left EXTRACTING by a process that stopped mid-document
    // says so until the worker reaches it again, which with sync paused or the model down can be
    // never. See ConnectorService.graphProgress.
    private final Set<UUID> extracting = ConcurrentHashMap.newKeySet();

    // Setter-injected and optional, so the tests that build this by hand need not supply one.
    private ApplicationEventPublisher events;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_DOCUMENTS);

    // A source's fingerprint as of the last scan that found NOTHING to do. While it still matches,
    // the source is skipped without loading its documents - which is what keeps a scan every few
    // seconds cheap on a deployment holding a hundred thousand of them. See Fingerprint.
    private final Map<UUID, Fingerprint> settled = new ConcurrentHashMap<>();

    private volatile Instant standDownUntil;

    // Guarded by `this`: how many stand-downs in a row reached no model, and the outage they make.
    private int unreachableStandDowns;
    private volatile ModelOutage outage;

    // Owners whose own budget refused a call, until it resets - only their sources wait. The worker
    // stood down for EVERYBODY when one owner ran out, and called it the model being unreachable.
    private final Map<String, Instant> ownersStandingDown = new ConcurrentHashMap<>();

    // Package-private so a test can move time on.
    Clock clock = Clock.systemUTC();

    // Optional, for naming the model's host and whether a proxy stands in the way (modelOutage).
    private ai.proxyma.core.settings.service.SettingsService settingsService;
    private ai.proxyma.core.settings.provider.ProviderInstanceService providers;
    private org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.webcontent.WebProxyProvider> proxy;

    @Autowired(required = false)
    void setModelContext(ai.proxyma.core.settings.service.SettingsService settingsService,
                         ai.proxyma.core.settings.provider.ProviderInstanceService providers,
                         org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.webcontent.WebProxyProvider> proxy) {
        this.settingsService = settingsService;
        this.providers = providers;
        this.proxy = proxy;
    }

    /** The knowledge-graph model's outage, while the worker is standing down for one. */
    public Optional<ModelOutage> modelOutage() {
        return Optional.ofNullable(outage);
    }

    // Whether this process has re-queued the rows an outage recorded as read. Once is enough: every
    // row written DONE from here on carries its answer count, so no new ones can appear.
    private final AtomicBoolean unreadRowsRequeued = new AtomicBoolean(false);

    public GraphExtractionOrchestrator(RegisteredConnectorRepository connectorRepository,
                                       TrackedDocumentRepository documentRepository,
                                       KnowledgeGraphService knowledgeGraphService,
                                       BM25IndexService bm25IndexService,
                                       @Lazy SyncOrchestratorService syncOrchestratorService) {
        this.connectorRepository = connectorRepository;
        this.documentRepository = documentRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.bm25IndexService = bm25IndexService;
        this.syncOrchestratorService = syncOrchestratorService;
    }

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Whether this document is being read by the graph at this moment. */
    public boolean isExtracting(UUID documentId) {
        return documentId != null && extracting.contains(documentId);
    }

    private void announce(UUID connectorId) {
        if (events == null) return;
        try {
            events.publishEvent(new ConnectorGraphEvent(this, connectorId));
        } catch (RuntimeException e) {
            // A screen refresh is never worth a document's extraction.
            log.debug("[GraphWorker] could not announce progress for {}: {}", connectorId, e.getMessage());
        }
    }

    /**
     * Starts a run when none is going. A run keeps taking passes until one finds nothing to do, so
     * a backfill does not wait a tick between batches.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("graph-extraction").start(() -> {
            try {
                while (runPass() > 0) {
                    // keep going while there is work
                }
            } catch (Exception e) {
                log.warn("[GraphWorker] extraction run stopped: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * One pass: find work, extract it, wait for it. Returns how many documents it actually
     * attempted, so the caller stops as soon as a pass is idle.
     */
    int runPass() {
        if (syncOrchestratorService.isPaused()) return 0;
        Instant until = standDownUntil;
        if (until != null && clock.instant().isBefore(until)) return 0;
        // Metadata first, and whether or not a model is configured: it needs none.
        int metadata = metadataPass();
        if (!knowledgeGraphService.isExtractionAvailable()) return metadata;

        requeueUnreadRowsOnce();
        List<Work> work = collectWork();
        if (work.isEmpty()) return metadata;

        AtomicInteger attempted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(work.size());
        for (int i = 0; i < work.size(); i++) {
            Work w = work.get(i);
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Never started, so never released from inFlight by their own finally.
                for (Work unstarted : work.subList(i, work.size())) inFlight.remove(unstarted.documentId());
                break;
            }
            threads.add(Thread.ofVirtual().name("graph-extract-" + w.documentId()).start(() -> {
                try {
                    if (extractOne(w)) attempted.incrementAndGet();
                } catch (Exception e) {
                    log.warn("[GraphWorker] document {} could not be extracted: {}", w.documentId(), e.getMessage());
                } finally {
                    inFlight.remove(w.documentId());
                    slots.release();
                }
            }));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return attempted.get() + metadata;
    }

    /** Documents whose metadata one pass brings up to date. Each is a keyword-index read and a write. */
    static final int METADATA_PER_PASS = 100;

    /** Entity names one pass embeds. */
    static final int VECTORS_PER_PASS = 200;

    private EntityVectorService entityVectors;

    // Optional, so the worker can be built by hand in a test.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEntityVectors(EntityVectorService entityVectors) {
        this.entityVectors = entityVectors;
    }

    /** Entities whose derived name keys one pass fills in. */
    static final int KEYS_PER_PASS = 500;

    /** Sources whose metadata is up to date, by the fingerprint they had then - see {@link #settled}. */
    private final Map<UUID, Fingerprint> metadataSettled = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * THE METADATA PASS (since 2026-10-13): every graph-enabled source's documents get the exact
     * edges their own metadata states - assignee, parent, links, authors, page tree - with no model
     * call. It runs on every pass, so a source with no extraction model configured still has those
     * edges; a document indexed before the reader existed, or written by an older version of it
     * (SourceFactsReader.VERSION), is brought up to date without being read by a model again; and
     * entities written before their derived name keys existed get them.
     *
     * <p>Sequential, on the pass's own thread: each document is one keyword-index read and one short
     * write, and running it before the model's threads start keeps the two from writing one
     * document's rows at once.
     *
     * @return how many documents and entities it brought up to date, so a run keeps going while it
     *         has work
     */
    int metadataPass() {
        int keys = 0;
        try {
            keys = knowledgeGraphService.backfillDerivedKeys(KEYS_PER_PASS);
        } catch (RuntimeException e) {
            log.debug("[GraphWorker] could not fill in derived name keys: {}", e.getMessage());
        }
        // Entity name vectors, a batch per pass - including every entity again after the embedding
        // model changes (EntityVectorService). Not counted towards "keep going": a model that keeps
        // failing must not hold the run in a loop, and the next tick picks up where this left off.
        if (entityVectors != null) entityVectors.embedPending(VECTORS_PER_PASS);
        int done = 0;
        List<RegisteredConnector> enabled = connectorRepository.findAll().stream()
                .filter(c -> c.isGraphEnabled() && !c.isDeleting())
                .filter(c -> hasMetadata(c.getConnectorType()))
                .toList();
        metadataSettled.keySet().retainAll(enabled.stream().map(RegisteredConnector::getId).toList());
        for (RegisteredConnector connector : enabled) {
            if (done >= METADATA_PER_PASS) break;
            Fingerprint fingerprint = fingerprint(connector.getId());
            if (fingerprint.equals(metadataSettled.get(connector.getId()))) continue;
            Map<UUID, DocumentGraphState> states = knowledgeGraphService.documentStates(connector.getId());
            boolean left = false;
            for (Object[] row : documentRepository.findIdAndLastIndexedAtByConnectorIdAndStatus(
                    connector.getId(), TrackedDocument.Status.COMPLETED)) {
                UUID documentId = (UUID) row[0];
                Instant indexedAt = (Instant) row[1];
                if (inFlight.contains(documentId)) continue;
                if (!DocumentGraphState.needsMetadata(states.get(documentId), indexedAt,
                        ai.proxyma.core.connector.facts.SourceFactsReader.VERSION)) continue;
                if (done >= METADATA_PER_PASS) { left = true; break; }
                if (refreshMetadataOf(connector, documentId, indexedAt)) done++;
            }
            if (!left) metadataSettled.put(connector.getId(), fingerprint(connector.getId()));
        }
        if (done > 0) log.debug("[GraphWorker] metadata pass brought {} item(s) up to date", done);
        return done + keys;
    }

    private boolean refreshMetadataOf(RegisteredConnector connector, UUID documentId, Instant indexedAt) {
        TrackedDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || doc.getStatus() != TrackedDocument.Status.COMPLETED) return false;
        try {
            List<String> chunks = bm25IndexService.getChunkTexts(collectionOf(connector), doc.getSourceId());
            knowledgeGraphService.refreshMetadata(connector.getId(), documentId, indexedAt,
                    sourceFacts(doc, connector, chunks), ai.proxyma.core.connector.facts.SourceFactsReader.VERSION);
            return true;
        } catch (RuntimeException e) {
            log.debug("[GraphWorker] metadata of document {} not written: {}", documentId, e.getMessage());
            return false;
        }
    }

    // The collection the document's chunks were actually written to. During a model-changed
    // reindex that is the shadow, exactly as SyncService.deleteTrackedDocumentBySourceId resolves it.
    private static String collectionOf(RegisteredConnector connector) {
        return connector.getReindexShadowCollectionId() != null
                ? connector.getReindexShadowCollectionId()
                : connector.getId().toString().replace("-", "");
    }

    /**
     * Once per process, before the first scan: DONE rows that were never really read go back to
     * PENDING, so the scan below picks them up like any other unread document.
     *
     * <p>HANDOFF carried this as a known gap - "a graph row written DONE during a model outage is
     * not repaired automatically; toggle that source's graph off and on" - and toggling is a remedy
     * only for someone who knows the graph is wrong, which an empty graph never tells anyone. Here,
     * rather than at a startup event, because it only matters once extraction can run, and at the
     * start of a pass none of this worker's threads - the only thing that writes DONE - is running.
     *
     * <p>A failure is logged and tried again on the next pass; the scan goes ahead regardless,
     * since everything else it would do is unaffected.
     */
    private void requeueUnreadRowsOnce() {
        if (unreadRowsRequeued.get()) return;
        try {
            knowledgeGraphService.requeueUnreadDocuments();
            unreadRowsRequeued.set(true);
        } catch (RuntimeException e) {
            log.warn("[GraphWorker] could not re-queue documents recorded as read with no answer: {}",
                    e.getMessage());
        }
    }

    /** Up to {@link #BATCH_PER_CONNECTOR} documents from each source that has any, interleaved. */
    List<Work> collectWork() {
        Instant now = Instant.now();
        List<RegisteredConnector> enabled = connectorRepository.findAll().stream()
                .filter(c -> c.isGraphEnabled() && !c.isDeleting())
                .toList();
        settled.keySet().retainAll(enabled.stream().map(RegisteredConnector::getId).toList());

        List<List<Work>> perConnector = new ArrayList<>();
        for (RegisteredConnector connector : enabled) {
            UUID connectorId = connector.getId();
            if (ownerStandingDown(connector.getUserId())) continue;
            Fingerprint fingerprint = fingerprint(connectorId);
            if (fingerprint.equals(settled.get(connectorId))) continue;

            Map<UUID, DocumentGraphState> states = knowledgeGraphService.documentStates(connectorId);
            List<Work> batch = new ArrayList<>();
            boolean waitingOnRetry = false;
            for (Object[] row : documentRepository.findIdAndLastIndexedAtByConnectorIdAndStatus(
                    connectorId, TrackedDocument.Status.COMPLETED)) {
                UUID documentId = (UUID) row[0];
                Instant indexedAt = (Instant) row[1];
                if (inFlight.contains(documentId)) continue;
                DocumentGraphState.Need need = DocumentGraphState.need(states.get(documentId), indexedAt, now);
                if (need == DocumentGraphState.Need.LATER) waitingOnRetry = true;
                if (need != DocumentGraphState.Need.NOW) continue;
                if (batch.size() < BATCH_PER_CONNECTOR) {
                    batch.add(new Work(connectorId, connector.getUserId(), documentId, indexedAt));
                }
            }

            if (batch.isEmpty()) {
                // Only a source with nothing pending AND nothing waiting to retry is settled - a
                // retry that comes due changes no fingerprint, so settling on one would strand it.
                if (!waitingOnRetry) settled.put(connectorId, fingerprint);
                continue;
            }
            settled.remove(connectorId);
            perConnector.add(batch);
        }

        List<Work> interleaved = new ArrayList<>();
        for (int i = 0; i < BATCH_PER_CONNECTOR; i++) {
            for (List<Work> batch : perConnector) {
                if (i < batch.size()) interleaved.add(batch.get(i));
            }
        }
        for (Work w : interleaved) inFlight.add(w.documentId());
        return interleaved;
    }

    /**
     * One document, charged to the source's owner exactly as its indexing was.
     *
     * @return whether an extraction was actually attempted - false when the document or its source
     *         changed under the pass, or the deployment is out of budget
     */
    boolean extractOne(Work w) {
        AtomicBoolean attempted = new AtomicBoolean(false);
        SpendContext.run(SpendSubject.owner(w.ownerUserId()), () -> attempted.set(extractOwned(w)));
        return attempted.get();
    }

    /**
     * What the item's own metadata states, as graph facts - read from the header its connector
     * rendered into these very chunks (SourceFactsReader), so it costs no call and needs no re-sync.
     * Empty for a source type that renders none; never a reason to fail the document.
     */
    static ai.proxyma.core.graph.service.ExtractedGraph sourceFacts(TrackedDocument doc, RegisteredConnector connector,
                                                                    List<String> chunks) {
        try {
            ai.proxyma.core.connector.facts.SourceFacts facts = doc.getConnectorType() == ai.proxyma.core.connector.ConnectorType.SVN
                    ? svnFacts(doc)
                    : ai.proxyma.core.connector.facts.SourceFactsReader.read(
                            doc.getConnectorType(), doc.getSourceId(), doc.getTitle(), connector.getName(), chunks);
            if (facts.isEmpty()) return null;
            return new ai.proxyma.core.graph.service.ExtractedGraph(
                    facts.things().stream().map(t -> new ai.proxyma.core.graph.service.ExtractedGraph.Entity(
                            t.name(), t.type(), t.description(), null)).toList(),
                    facts.links().stream().map(l -> new ai.proxyma.core.graph.service.ExtractedGraph.Relation(
                            l.subject(), l.predicate(), l.object(), null, l.validFrom(), l.validTo(), l.occurredAt())).toList());
        } catch (RuntimeException e) {
            log.debug("[GraphWorker] could not read the metadata of document {}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * An SVN file's metadata. SVN renders no header - a file is indexed as it is - so its one fact
     * comes from the row: who last committed it (TrackedDocument.sourceAuthor, from the listing) and
     * since when.
     */
    static ai.proxyma.core.connector.facts.SourceFacts svnFacts(TrackedDocument doc) {
        String path = doc.getTitle() != null && !doc.getTitle().isBlank() ? doc.getTitle() : doc.getSourceId();
        String author = doc.getSourceAuthor();
        if (path == null || author == null || author.isBlank()) return ai.proxyma.core.connector.facts.SourceFacts.empty();
        return new ai.proxyma.core.connector.facts.SourceFacts(
                List.of(new ai.proxyma.core.connector.facts.SourceFacts.Thing(path, "file", null),
                        new ai.proxyma.core.connector.facts.SourceFacts.Thing(author.trim(), "person", null)),
                List.of(new ai.proxyma.core.connector.facts.SourceFacts.Link(path, "last changed by", author.trim(),
                        doc.getSourceLastModified(), null, null)));
    }

    /** The source types whose items carry metadata the graph reads - see {@link #metadataPass}. */
    static boolean hasMetadata(ai.proxyma.core.connector.ConnectorType type) {
        return type == ai.proxyma.core.connector.ConnectorType.SVN
                || ai.proxyma.core.connector.facts.SourceFactsReader.reads(type);
    }

    /**
     * A sync learned metadata it did not have for an item already indexed (SVN's last committer):
     * that item's metadata edges are due again, and its source is no longer settled.
     */
    @org.springframework.context.event.EventListener
    public void onMetadataChanged(ai.proxyma.core.registry.event.DocumentMetadataChangedEvent event) {
        try {
            knowledgeGraphService.invalidateMetadata(event.documentId());
        } catch (RuntimeException e) {
            log.debug("[GraphWorker] could not mark the metadata of {} as due: {}", event.documentId(), e.getMessage());
        }
        metadataSettled.remove(event.connectorId());
    }

    private boolean extractOwned(Work w) {
        TrackedDocument doc = documentRepository.findById(w.documentId()).orElse(null);
        if (doc == null || doc.getStatus() != TrackedDocument.Status.COMPLETED) return false;
        // Re-indexed since the scan: this pass would extract the old chunks' timestamp against the
        // new chunks. The next scan offers it again with the right one.
        if (!DocumentGraphState.sameInstant(doc.getLastIndexedAt(), w.indexedAt())) return false;

        RegisteredConnector connector = connectorRepository.findById(w.connectorId()).orElse(null);
        if (connector == null || !connector.isGraphEnabled() || connector.isDeleting()) return false;

        // The collection the document's chunks were actually written to. During a model-changed
        // reindex that is the shadow, exactly as SyncService.deleteTrackedDocumentBySourceId
        // resolves it.
        String collectionId = connector.getReindexShadowCollectionId() != null
                ? connector.getReindexShadowCollectionId()
                : w.connectorId().toString().replace("-", "");
        List<String> chunks = bm25IndexService.getChunkTexts(collectionId, doc.getSourceId());

        // Marked and announced only once the document is REALLY being read - after its progress
        // row says EXTRACTING. A document that turns out unchanged is neither, so a re-index
        // backfill of a thousand unchanged items sends no events at all.
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            KnowledgeGraphService.RefreshOutcome outcome = knowledgeGraphService.refreshDocument(
                    w.connectorId(), w.documentId(), w.indexedAt(), chunks,
                    sourceFacts(doc, connector, chunks), ai.proxyma.core.connector.facts.SourceFactsReader.VERSION, () -> {
                started.set(true);
                extracting.add(w.documentId());
                announce(w.connectorId());
            });
            if (outcome == KnowledgeGraphService.RefreshOutcome.EXTRACTED
                    || outcome == KnowledgeGraphService.RefreshOutcome.PARTIAL) {
                modelAnswered();
            }
        } catch (GlobalQuotaExceededException e) {
            standDownUntil = clock.instant().plus(QUOTA_BACKOFF);
            log.warn("[GraphWorker] standing down for {} minutes - {} The documents themselves are "
                    + "indexed and searchable; their graph resumes when this clears.",
                    QUOTA_BACKOFF.toMinutes(), e.getMessage());
            return false;
        } catch (UserBudgetExceededException e) {
            // This owner's budget, not the model and not the deployment: their sources wait until it
            // resets, and everybody else's carry on.
            Instant resets = e.resetsAt() != null && e.resetsAt().isAfter(clock.instant())
                    ? e.resetsAt() : clock.instant().plus(QUOTA_BACKOFF);
            if (w.ownerUserId() != null) ownersStandingDown.put(w.ownerUserId(), resets);
            log.info("[GraphWorker] the knowledge graph of one owner's sources waits until {} - their "
                    + "budget is spent. Their documents are indexed and searchable.", resets);
            return false;
        } catch (GraphModelUnreachableException e) {
            // Stand down, rather than walk the rest of the corpus failing identically - longer each
            // time, and said where the source's graph progress is shown (modelOutage). The document
            // stays PENDING, so when the model answers again everything it could not read is still
            // waiting - no re-index, and nobody has to notice and toggle anything.
            standDownUnreachable(e);
            return false;
        } finally {
            extracting.remove(w.documentId());
            cleanUpIfUnplugged(w);
            if (started.get()) announce(w.connectorId());
        }
        return true;
    }

    private boolean ownerStandingDown(String owner) {
        if (owner == null) return false;
        Instant until = ownersStandingDown.get(owner);
        if (until == null) return false;
        if (clock.instant().isBefore(until)) return true;
        ownersStandingDown.remove(owner, until);
        return false;
    }

    // One stand-down per pass however many of its documents met the outage: the documents of a pass
    // run side by side, and four failing together are one outage, not four in a row.
    private synchronized void standDownUnreachable(GraphModelUnreachableException e) {
        Instant now = clock.instant();
        String reason = e.lastCallFailure() != null ? e.lastCallFailure() : e.getMessage();
        ModelOutage current = outage;
        Instant until = standDownUntil;
        if (current != null && until != null && now.isBefore(until)) {
            outage = new ModelOutage(current.since(), current.retryAt(), current.standDowns(), current.host(),
                    reason, current.throughProxy());
            return;
        }
        unreachableStandDowns++;
        Duration backoff = unreachableBackoff(unreachableStandDowns);
        Instant retryAt = now.plus(backoff);
        standDownUntil = retryAt;
        String host = modelHost();
        boolean viaProxy = goesThroughProxy(host);
        outage = new ModelOutage(current != null ? current.since() : now, retryAt, unreachableStandDowns,
                host, reason, viaProxy);
        log.warn("[GraphWorker] the knowledge-graph model{} is not answering ({} stand-down(s) in a row), "
                        + "standing down for {} minutes: {}{}", host != null ? " at " + host : "",
                unreachableStandDowns, backoff.toMinutes(), reason,
                viaProxy ? " - the request went through the proxy; add the host to No proxy if it is on your network" : "");
    }

    // A model answered: the outage is over, and the next one starts again from the first stand-down.
    private synchronized void modelAnswered() {
        if (unreachableStandDowns == 0 && outage == null) return;
        if (outage != null) log.info("[GraphWorker] the knowledge-graph model is answering again");
        unreachableStandDowns = 0;
        outage = null;
    }

    // The host the knowledge-graph role's provider is reached at, when its settings name one.
    private String modelHost() {
        if (settingsService == null || providers == null) return null;
        try {
            String id = settingsService.getSettings(ai.proxyma.core.settings.dto.GraphSettings.PREFIX,
                    ai.proxyma.core.settings.dto.GraphSettings.class).getProvider();
            if (id == null || id.isBlank()) return null;
            return providers.resolve(id).map(p -> p.baseUrl()).filter(u -> u != null && !u.isBlank())
                    .map(u -> java.net.URI.create(u.trim()).getHost()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Whether a call to that host goes through the configured proxy, by the rule every client follows.
    private boolean goesThroughProxy(String host) {
        if (host == null || proxy == null) return false;
        try {
            ai.proxyma.core.webcontent.WebProxyProvider p = proxy.getIfAvailable();
            if (p == null) return false;
            java.net.Proxy configured = p.getJavaProxy();
            return configured != null && configured != java.net.Proxy.NO_PROXY && !p.bypassesProxy(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The graph switched off, the source deleted or the document removed WHILE its extraction ran.
     * The delete for that already happened, before this document's rows were written, so they would
     * survive it - a graph answering from a source the operator has just unplugged.
     */
    private void cleanUpIfUnplugged(Work w) {
        boolean sourceGone = connectorRepository.findById(w.connectorId())
                .map(c -> !c.isGraphEnabled() || c.isDeleting())
                .orElse(true);
        boolean documentGone = documentRepository.findById(w.documentId())
                .map(d -> d.getStatus() == TrackedDocument.Status.DELETED)
                .orElse(true);
        if (sourceGone || documentGone) {
            knowledgeGraphService.forgetDocument(w.documentId());
        }
    }

    private Fingerprint fingerprint(UUID connectorId) {
        return new Fingerprint(
                documentRepository.countByConnectorIdAndStatus(connectorId, TrackedDocument.Status.COMPLETED),
                documentRepository.findMaxLastIndexedAtByConnectorIdAndStatus(connectorId, TrackedDocument.Status.COMPLETED),
                knowledgeGraphService.documentStateCount(connectorId));
    }

    /**
     * What has to change for a settled source to need looking at again. A document indexed or
     * removed moves the count; a document re-indexed moves the latest timestamp; the graph being
     * switched off and on again deletes the progress rows, which moves the last. Three cheap
     * aggregates on indexed columns instead of loading every document of every source.
     */
    record Fingerprint(long completedDocuments, Instant latestIndexedAt, long graphStates) {}

    record Work(UUID connectorId, String ownerUserId, UUID documentId, Instant indexedAt) {}
}
