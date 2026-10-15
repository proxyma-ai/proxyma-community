package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgDocumentState;
import ai.proxyma.core.graph.model.KgEntity;
import ai.proxyma.core.graph.model.KgRelation;
import ai.proxyma.core.graph.repository.KgAliasRepository;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import ai.proxyma.core.system.util.Failures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The knowledge graph, as everything outside this module sees it.
 *
 * <p>One entry point for the two things anyone wants: put a document's contents into the graph,
 * and walk out from a name. Callers hand over UUIDs and text and get back plain records - this
 * module reaches back into nobody, which is the rule its pom states and the reason it can be
 * depended on by registry, search and tools at once without a cycle.
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    /** Seed entities per query term. A term matching more than this is too generic to be a useful
     *  starting point, and expanding all of them would return most of the graph. */
    private static final int SEEDS_PER_TERM = 5;

    /** Total entities in one answer. Past this the result stops being a neighbourhood and becomes a
     *  dump the model has to read in full - which is the cost this feature is supposed to reduce. */
    private static final int MAX_ENTITIES = 60;

    /** Total edges in one answer, those nearest the names asked about and most corroborated first. */
    private static final int MAX_EDGES = 120;

    /** Edge rows read per hop before grouping. Several documents stating one triple are several
     *  rows, so this is larger than MAX_EDGES; it is applied AFTER the source filter (SEC-51). */
    private static final int EDGE_ROWS_PER_HOP = 600;

    /** Paths reported between the names asked about, and the longest walked (edges). */
    private static final int MAX_PATHS = 10;
    private static final int MAX_PATH_LENGTH = 4;

    /** Steps one pair of names may spend looking for paths - a bound on a dense neighbourhood. */
    private static final int PATH_STEPS_PER_PAIR = 20_000;

    /**
     * Documents named per entity, and per edge, in one answer. The total is still said - the
     * entity's documentCount, the edge's weight - but the list is the few that matter most: a person
     * metadata ties to two thousand issues was two thousand titles in one tool result.
     */
    static final int DOCUMENTS_PER_ENTITY = 5;
    static final int DOCUMENTS_PER_EDGE = 5;

    public static final int MAX_HOPS = 2;

    /**
     * How many opening chunks may fail to reach a model before the document is abandoned.
     *
     * <p>Three rather than one, because a single failed call is ordinary - a provider hiccup, one
     * pathological chunk - and abandoning a whole document on it would make the graph fragile in
     * exactly the way the per-chunk design avoids. Three in a row with nothing extracted is not a
     * hiccup, and the alternative is measured in hours per document.
     */
    static final int UNREACHABLE_STREAK = 3;

    private final KgEntityRepository entities;
    private final KgAliasRepository aliases;
    private final KgMentionRepository mentions;
    private final KgRelationRepository relations;
    private final GraphExtractionService extraction;
    private final KnowledgeGraphWriter writer;
    private final KgDocumentStateRepository documentStates;

    public KnowledgeGraphService(KgEntityRepository entities, KgAliasRepository aliases,
                                 KgMentionRepository mentions, KgRelationRepository relations,
                                 GraphExtractionService extraction, KnowledgeGraphWriter writer,
                                 KgDocumentStateRepository documentStates) {
        this.entities = entities;
        this.aliases = aliases;
        this.mentions = mentions;
        this.relations = relations;
        this.extraction = extraction;
        this.writer = writer;
        this.documentStates = documentStates;
    }

    /**
     * Whether the graph holds anything at all.
     *
     * <p>Asked once per ReAct iteration to decide whether the traversal tool is worth advertising,
     * so it is a bare COUNT on an indexed table and nothing more. Not cached: the honest answer
     * changes the moment a first document finishes extracting, and a stale "no" would hide the
     * tool for the rest of a conversation that could have used it.
     */
    @Transactional(readOnly = true)
    public boolean hasAnyData() {
        try {
            return entities.count() > 0;
        } catch (Exception e) {
            // An unreadable graph is an absent graph, never a broken chat turn.
            log.debug("[Graph] could not count entities: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Whether the graph holds anything from these sources - the ones one person can read.
     *
     * <p>What decides whether that person is offered the traversal tool. {@link #hasAnyData} asked
     * the whole table, so on Enterprise Server a user who could read no graph data at all was still
     * offered the tool, and spent a call on every conversation to be told there was nothing.
     */
    @Transactional(readOnly = true)
    public boolean hasDataIn(Collection<UUID> connectorIds) {
        if (connectorIds == null || connectorIds.isEmpty()) return false;
        try {
            return mentions.existsByConnectorIdIn(connectorIds);
        } catch (Exception e) {
            log.debug("[Graph] could not ask whether these sources have graph data: {}", e.getMessage());
            return false;
        }
    }

    /** Whether extraction can run: the deployment switch is on and a graph model resolved. */
    public boolean isExtractionAvailable() {
        return extraction.isAvailable();
    }

    // ---------------------------------------------------------------- construction

    /**
     * Extracts one document into the graph, replacing whatever it contributed before.
     *
     * <p>NOT transactional, on purpose, and this is the load-bearing decision in the class: it
     * makes one LLM call per chunk, so a transaction around it would be held open across minutes of
     * network calls. The chunks' graphs are gathered in memory and written in ONE short transaction
     * at the end ({@link KnowledgeGraphWriter#replaceDocument}), so the document's previous graph
     * stays readable for as long as the re-read takes, and an interrupted re-read changes nothing.
     *
     * @throws ai.proxyma.core.settings.usage.GlobalQuotaExceededException when the deployment is out
     *         of budget. Deliberately allowed to escape: {@link #refreshDocument} puts the document
     *         back to PENDING so it is extracted again once the quota resets, rather than recording
     *         a graph with most of it missing as finished.
     */
    public int extractDocument(UUID connectorId, UUID documentId, List<String> chunks) {
        return extractDocument(connectorId, documentId, chunks, null);
    }

    /**
     * The same, reporting how far it has got after every chunk.
     *
     * <p>{@code onProgress} is called with the number of chunks attempted so far. A document of any
     * size is one row that says DONE at the end, and on a real corpus - 581 chunks at about a
     * minute each - that end is hours away, so a caller with nothing to show would show nothing
     * for hours. The callback is what lets the row say "37 of 581" while it happens.
     *
     * @return how many chunks a model actually answered, "nothing here" included - the evidence
     *         {@link #refreshDocument} records that this document was read (see
     *         KgDocumentState.chunksAnswered). 0 when nothing was attempted.
     */
    public int extractDocument(UUID connectorId, UUID documentId, List<String> chunks,
                               java.util.function.IntConsumer onProgress) {
        if (connectorId == null || documentId == null || chunks == null || chunks.isEmpty()) return 0;
        if (!extraction.isAvailable()) return 0;
        Read read = read(documentId, chunks, null, onProgress);
        replaceRetryingOnceOnEntityRace(connectorId, documentId, read.graphs());
        return read.answered();
    }

    /**
     * What one read of a document produced.
     *
     * @param graphs   what each chunk that named something yielded, by its index
     * @param answered how many chunks a model answered, "nothing here" included
     * @param missing  the indexes of chunks no model answered even when asked a second time
     */
    record Read(List<KnowledgeGraphWriter.ChunkGraph> graphs, int answered, List<Integer> missing) {}

    /**
     * Reads the chunks at {@code only} - every chunk when null - one model call each.
     *
     * <p><b>A chunk no model answered is asked again</b>, once, at the end of the read (since
     * 2026-10-13). A timeout here and there is ordinary on a slow or busy model, and each one used
     * to leave a hole in a document recorded as finished - DONE is never offered again, so the hole
     * stayed until the document itself changed. What a second asking still cannot read is returned
     * as {@code missing}, and {@link #refreshDocument} records it so the next offer reads those
     * chunks and only those.
     *
     * @throws GraphModelUnreachableException when the read was abandoned (a run of chunks no model
     *         answered) or no chunk at all was answered - see the comments at the throw
     */
    Read read(UUID documentId, List<String> chunks, List<Integer> only, java.util.function.IntConsumer onProgress) {
        long start = System.currentTimeMillis();
        List<Integer> indexes = new ArrayList<>();
        if (only == null) {
            for (int i = 0; i < chunks.size(); i++) indexes.add(i);
        } else {
            only.stream().filter(i -> i >= 0 && i < chunks.size()).forEach(indexes::add);
        }

        Map<Integer, ExtractedGraph> found = new java.util.TreeMap<>();
        List<Integer> unreached = new ArrayList<>();
        int chunksAttempted = 0;
        int answered = 0;
        // CONSECUTIVE, and reset by any real answer: a model that replies to one chunk in three is
        // slow or erratic, not absent, and that document should be read to the end.
        int unreachableStreak = 0;
        // Whether the loop ended before reading the document, which decides below whether an empty
        // result means "there is nothing in this document" or "nobody read this document".
        boolean stoppedEarly = false;
        for (int i : indexes) {
            String chunk = chunks.get(i);
            if (chunk == null || chunk.isBlank()) continue;
            chunksAttempted++;
            GraphExtractionService.Attempt attempt = extraction.extractChunk(chunk);
            if (attempt.outcome() == GraphExtractionService.Outcome.UNREACHABLE) {
                unreached.add(i);
                unreachableStreak++;
                // GIVE UP ONCE NOTHING IS ANSWERING, whatever came before. Found on a real install
                // while watching it: the graph model was timing out at 120s a chunk and the
                // document had 278 of them, so reading them all costs NINE HOURS to learn what
                // three calls already said. A document given up on is NOT READ, however much of
                // it was - see below.
                if (unreachableStreak >= UNREACHABLE_STREAK) {
                    log.warn("[Graph] document {}: giving up after {} consecutive call(s) that reached "
                            + "no model ({} of {} chunk(s) answered). Raise graph.call-timeout-seconds, or "
                            + "point the knowledge-graph role at a faster model.",
                            documentId, unreachableStreak, answered, indexes.size());
                    stoppedEarly = true;
                    break;
                }
                reportProgress(onProgress, chunksAttempted);
                continue;
            }
            unreachableStreak = 0;
            answered++;
            reportProgress(onProgress, chunksAttempted);
            if (!attempt.graph().isEmpty()) found.put(i, attempt.graph());
        }

        // The second asking. Not after an abandoned read - that is a model that is not there, and
        // asking it again is the nine hours the streak guard exists to save.
        List<Integer> missing = new ArrayList<>();
        if (!stoppedEarly && !unreached.isEmpty() && answered > 0) {
            int streak = 0;
            for (int i : unreached) {
                if (streak >= UNREACHABLE_STREAK) { missing.add(i); continue; }
                GraphExtractionService.Attempt attempt = extraction.extractChunk(chunks.get(i));
                if (attempt.outcome() == GraphExtractionService.Outcome.UNREACHABLE) {
                    streak++;
                    missing.add(i);
                    continue;
                }
                streak = 0;
                answered++;
                if (!attempt.graph().isEmpty()) found.put(i, attempt.graph());
            }
        } else {
            missing.addAll(unreached);
        }

        long elapsed = System.currentTimeMillis() - start;
        // Per document, with how it ended: read through, abandoned because no model answered, or
        // read with nothing found. A slow graph model is the commonest complaint about this feature.
        ai.proxyma.core.common.diagnostics.Diagnostics.time(ai.proxyma.core.common.diagnostics.DiagOp.GRAPH_EXTRACT,
                elapsed, "outcome", stoppedEarly ? "stopped_early"
                        : chunksAttempted > 0 && answered == 0 ? "unreachable"
                        : !missing.isEmpty() ? "partial"
                        : found.isEmpty() ? "empty" : "ok");
        // NOTHING GOT THROUGH. Not a document with nothing in it - a document nobody read. Ollama
        // stopped, a base URL is wrong, every call timed out. Thrown rather than logged, because
        // the alternative is what happened on 2026-09-16: twenty documents recorded as extracted
        // in half a second, an empty graph, and no reason on the screen to think otherwise.
        // A DOCUMENT ABANDONED PART WAY IS NOT READ EITHER (since 2026-10-13). It used to be
        // recorded DONE with whatever the first chunks gave, and DONE with a matching hash is never
        // offered again - so the rest of it stayed out of the graph until the document itself
        // changed. Nothing is written for it now: its previous graph stands, and it is read again
        // once a model answers.
        if (chunksAttempted > 0 && (stoppedEarly || answered == 0)) {
            String lastFailure = extraction.lastFailure();
            throw new GraphModelUnreachableException(
                    "the extraction of document " + documentId + " stopped before the end ("
                    + chunksAttempted + " of " + indexes.size() + " chunk(s) attempted, "
                    + answered + " answered, " + elapsed + "ms). The "
                    + "knowledge-graph model in Settings > Search is unreachable, or too slow "
                    + "for the extraction timeout" + (lastFailure != null ? " - last: " + lastFailure : "") + ".",
                    lastFailure);
        }
        if (found.isEmpty()) {
            // WARN, because an empty document is either a model that cannot follow the format or
            // one that ran out of time, and both are fixable in Settings. ATTEMPTED, not
            // chunks.size(): "none of 235 chunk(s) produced graph content" for a document abandoned
            // after three invites exactly the wrong conclusion.
            log.warn("[Graph] document {}: none of the {} chunk(s) attempted produced graph content "
                    + "({}ms). Check the knowledge-graph model in Settings, and the extraction "
                    + "timeout if the model is slow or emits reasoning tokens.",
                    documentId, chunksAttempted, elapsed);
        } else {
            log.info("[Graph] document {}: {} of {} chunk(s) produced graph content in {}ms{}",
                    documentId, found.size(), indexes.size(), elapsed,
                    missing.isEmpty() ? "" : " - " + missing.size() + " could not be read and wait for a later pass");
        }
        List<KnowledgeGraphWriter.ChunkGraph> graphs = new ArrayList<>();
        // The chunk index, matching the order the same chunks were embedded in. Free-form on the
        // column by design (see KgMention.chunkRef), so this stays a string.
        found.forEach((i, g) -> graphs.add(new KnowledgeGraphWriter.ChunkGraph(String.valueOf(i), g)));
        return new Read(graphs, answered, List.copyOf(missing));
    }

    /**
     * The document's write, retried once if it lost a race for an entity name.
     *
     * <p><b>Two connectors sync in parallel</b> - one drain thread each - and both meet the same
     * name for the first time in the same instant. {@code kg_entities} has a unique index on
     * (normalizedName, type), which is what makes the graph converge on one node instead of a
     * fragment per document, so one of the two writes has to lose. It loses at COMMIT, after
     * {@link EntityResolver} has done everything it can about it.
     *
     * <p>The cost of that was out of all proportion to the cause. The exception propagated to
     * SyncService, which abandoned the document, so a whole 200-page specification lost every
     * entity and every relationship it had because one of its names collided with one word from a
     * file being indexed at the same moment. Three documents in one overnight run.
     *
     * <p>Retried ONCE and no more. The race is decided by then in the only way it can be: the
     * winner has committed, so this attempt's {@code lookup} finds the row that beat it and uses it.
     * A second failure is not the same race twice, and looping on it would turn one bad document
     * into an indefinite one. Also retried: an entity the orphan purge of a parallel write removed
     * between this one's lookup and its commit. The transaction that failed left nothing behind.
     */
    private void replaceRetryingOnceOnEntityRace(UUID connectorId, UUID documentId,
                                                 List<KnowledgeGraphWriter.ChunkGraph> graphs) {
        try {
            writer.replaceDocument(connectorId, documentId, graphs);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            log.debug("[Graph] document {} lost an entity race - retrying once", documentId);
            writer.replaceDocument(connectorId, documentId, graphs);
        }
    }

    /** The same, for chunks ADDED to a document's graph - the ones a partial read left missing. */
    private void addRetryingOnceOnEntityRace(UUID connectorId, UUID documentId,
                                             List<KnowledgeGraphWriter.ChunkGraph> graphs) {
        try {
            writer.addChunks(connectorId, documentId, graphs);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            log.debug("[Graph] document {} lost an entity race - retrying once", documentId);
            writer.addChunks(connectorId, documentId, graphs);
        }
    }

    /**
     * One document brought up to date in the graph, from chunks the caller already has.
     *
     * <p><b>This is what makes the graph independent of vectorization.</b> The chunks come from
     * wherever the document is already stored - the extraction worker reads them out of the
     * keyword index - so nothing is downloaded, converted or embedded to build a graph, and
     * nothing here can change a document's indexing status. A failure is recorded against the
     * graph's own progress row and retried by the graph's own worker.
     *
     * <p>Chunks identical to the ones last extracted cost nothing: the hash matches and the row is
     * re-stamped. That is the case for every document after a re-index that changed only the
     * embedding model.
     *
     * <p><b>A document read with some chunks missing</b> is recorded FAILED - so the worker offers it
     * again after the retry delay, within the retry budget - with its graph written and the missing
     * chunks' indexes on the row. The next offer of the same chunks reads those and only those, and
     * adds them to the graph already there.
     *
     * @param sourceIndexedAt the document's lastIndexedAt, recorded so a later re-index is noticed
     * @throws GraphModelUnreachableException when no call reached a model. The row goes back to
     *         PENDING rather than DONE - the document has not been read, and recording it as read
     *         is how an outage becomes a permanently empty graph nobody is told about
     * @throws GlobalQuotaExceededException when the deployment is out of budget. The row goes back
     *         to PENDING rather than FAILED - a budget refusal is not the document's fault and must
     *         not use up its retries - and the caller is expected to stop, not move to the next one.
     */
    public RefreshOutcome refreshDocument(UUID connectorId, UUID documentId, Instant sourceIndexedAt,
                                          List<String> chunks) {
        return refreshDocument(connectorId, documentId, sourceIndexedAt, chunks, null);
    }

    /**
     * The same, telling {@code onStarted} the moment the document is really being read - after its
     * row says EXTRACTING, and never for a document that turns out UNCHANGED or cannot be read. The
     * worker announces "the graph is reading this item" from there (since 2026-09-24), so a screen
     * refreshing on that announcement finds the row it is about.
     */
    public RefreshOutcome refreshDocument(UUID connectorId, UUID documentId, Instant sourceIndexedAt,
                                          List<String> chunks, Runnable onStarted) {
        return refreshDocument(connectorId, documentId, sourceIndexedAt, chunks, null, onStarted);
    }

    /** The chunk reference of what an item's own metadata states - not a passage, so never quoted. */
    public static final String METADATA_REF = "meta";

    /**
     * The same, with what the item's source METADATA states about it (since 2026-10-13): who it is
     * assigned to, what it is a child of, which issue it links - exact, and read from the item's
     * rendered header rather than by a model (SourceFactsReader in the connector module). Written in
     * the same transaction as the model's extraction, under {@link #METADATA_REF}, so a re-read
     * replaces both together and neither can be left behind by the other.
     */
    public RefreshOutcome refreshDocument(UUID connectorId, UUID documentId, Instant sourceIndexedAt,
                                          List<String> chunks, ExtractedGraph facts, Runnable onStarted) {
        return refreshDocument(connectorId, documentId, sourceIndexedAt, chunks, facts, 0, onStarted);
    }

    /** The same, recording which version of the metadata reader produced {@code facts}. */
    public RefreshOutcome refreshDocument(UUID connectorId, UUID documentId, Instant sourceIndexedAt,
                                          List<String> chunks, ExtractedGraph facts, int metadataVersion,
                                          Runnable onStarted) {
        if (connectorId == null || documentId == null) return RefreshOutcome.SKIPPED;
        // Checked before anything is written: a switch turned off mid-scan must not leave rows
        // recording a failure that was really "not configured".
        if (!extraction.isAvailable()) return RefreshOutcome.SKIPPED;

        KgDocumentState state = documentStates.findById(documentId)
                .orElseGet(() -> new KgDocumentState(documentId, connectorId));
        if (!DocumentGraphState.sameInstant(state.getSourceIndexedAt(), sourceIndexedAt)) {
            state.setAttempts(0);
            state.setNextAttemptAt(null);
        }
        state.setSourceIndexedAt(sourceIndexedAt);

        List<String> usable = chunks == null ? List.of()
                : chunks.stream().filter(c -> c != null && !c.isBlank()).toList();
        if (usable.isEmpty()) {
            // A COMPLETED document always has chunks, so an empty read is the keyword index being
            // busy or damaged. Counted as an attempt, so a document that can never be read stops
            // being offered instead of being retried forever.
            recordFailure(state, "No chunk text could be read for this document from the keyword index");
            return RefreshOutcome.FAILED;
        }

        String hash = chunkHash(usable);
        if (state.getStatus() == KgDocumentState.Status.DONE && hash.equals(state.getChunkHash())) {
            save(state);
            return RefreshOutcome.UNCHANGED;
        }
        // The same chunks as a read that left some missing: read only those, and add them.
        List<Integer> resume = hash.equals(state.getChunkHash()) ? missingOf(state) : List.of();
        boolean resuming = !resume.isEmpty();
        int answeredBefore = resuming && state.getChunksAnswered() != null ? state.getChunksAnswered() : 0;

        state.setStatus(KgDocumentState.Status.EXTRACTING);
        // The total is known now, and the row carries it from the start so a reader can see
        // "0 of 581" rather than nothing at all.
        state.setChunkCount(resuming ? resume.size() : usable.size());
        state.setChunksDone(0);
        save(state);
        if (onStarted != null) onStarted.run();
        Read read;
        try {
            read = read(documentId, usable, resuming ? resume : null, done -> {
                state.setChunksDone(done);
                save(state);
            });
            if (resuming) {
                // The metadata was written with the first read; only the missing passages are new.
                addRetryingOnceOnEntityRace(connectorId, documentId, read.graphs());
            } else {
                List<KnowledgeGraphWriter.ChunkGraph> graphs = new ArrayList<>(read.graphs());
                if (facts != null && !facts.isEmpty()) {
                    graphs.add(new KnowledgeGraphWriter.ChunkGraph(METADATA_REF, facts));
                }
                replaceRetryingOnceOnEntityRace(connectorId, documentId, graphs);
            }
        } catch (GlobalQuotaExceededException | UserBudgetExceededException | GraphModelUnreachableException e) {
            // ALL go back to PENDING without spending a retry, and all propagate. None is this
            // document's fault - a deployment out of budget, an owner out of budget, a model that is
            // not answering - and each will be true of that next document too, so the caller stops
            // rather than marching through the corpus recording failures it caused itself. A
            // resumed read keeps its missing list, so the next offer resumes again.
            state.setStatus(resuming ? KgDocumentState.Status.FAILED : KgDocumentState.Status.PENDING);
            state.setChunkCount(usable.size());
            save(state);
            throw e;
        } catch (RuntimeException e) {
            // The whole cause chain, not the outer message: a provider SDK wraps the model's own
            // refusal ("404 ... model is no longer available") in "Failed to generate content",
            // and the outer message was all a failed document ever showed. The fallback is for an
            // exception that carries no message at all, which used to be stored as null.
            recordFailure(state, Failures.describe(e, "Graph extraction failed with no reason given"));
            log.warn("[Graph] extraction failed for document {} (attempt {} of {}) - its indexing is "
                    + "unaffected: {}", documentId, state.getAttempts(), DocumentGraphState.MAX_ATTEMPTS,
                    e.getMessage());
            return RefreshOutcome.FAILED;
        }

        state.setChunkHash(hash);
        state.setChunkCount(usable.size());
        state.setChunksDone(usable.size() - read.missing().size());
        if (!resuming) {
            // The metadata was written with this read, from these chunks.
            state.setMetadataVersion(metadataVersion);
            state.setMetadataIndexedAt(sourceIndexedAt);
        }
        state.setChunksAnswered(answeredBefore + read.answered());
        state.setExtractedAt(Instant.now());
        // The graph this document feeds just changed; a view paging from a held ranking would slice
        // a picture that no longer exists. See ranked().
        rankings.clear();
        if (!read.missing().isEmpty()) {
            String missing = joinMissing(read.missing());
            state.setStatus(KgDocumentState.Status.FAILED);
            state.setAttempts(state.getAttempts() + 1);
            state.setNextAttemptAt(Instant.now().plus(DocumentGraphState.RETRY_DELAY));
            // Too many to list is a document mostly unread: no list, and no hash, so the next
            // offer reads it whole.
            state.setMissingChunks(missing);
            if (missing == null) state.setChunkHash(null);
            state.setFailureMessage(read.missing().size() + " of " + usable.size()
                    + " passage(s) could not be read in time. They are tried again later.");
            save(state);
            return RefreshOutcome.PARTIAL;
        }
        state.setStatus(KgDocumentState.Status.DONE);
        state.setMissingChunks(null);
        state.setAttempts(0);
        state.setNextAttemptAt(null);
        state.setFailureMessage(null);
        save(state);
        return RefreshOutcome.EXTRACTED;
    }

    /**
     * A document's METADATA edges brought up to date, with no model call - see
     * DocumentGraphState.needsMetadata. Only the metadata rows are replaced; what a model read stays.
     * A document the graph has never seen gets a PENDING row, so the model still reads it once one
     * is configured - the row says nothing about the model's read except that it is due.
     *
     * @param facts           what the item's metadata states; null or empty clears its metadata rows
     * @param metadataVersion the version of the reader that produced {@code facts}
     */
    public void refreshMetadata(UUID connectorId, UUID documentId, Instant indexedAt, ExtractedGraph facts,
                                int metadataVersion) {
        if (connectorId == null || documentId == null) return;
        KgDocumentState state = documentStates.findById(documentId)
                .orElseGet(() -> new KgDocumentState(documentId, connectorId));
        try {
            writer.replaceMetadata(connectorId, documentId, facts);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            log.debug("[Graph] document {} lost an entity race writing its metadata - retrying once", documentId);
            writer.replaceMetadata(connectorId, documentId, facts);
        }
        state.setMetadataVersion(metadataVersion);
        state.setMetadataIndexedAt(indexedAt);
        save(state);
        rankings.clear();
    }

    /**
     * Entities whose name vector is missing or was made with another embedding model ({@code key}),
     * as their vectors are made: the normalized name and the type. Global, like the entities - a
     * vector lends nobody anything: a query reaches an entity through it only after the visibility
     * filter (see seedsFor).
     */
    @Transactional(readOnly = true)
    public List<EntityText> entitiesNeedingVectors(String key, int batch) {
        return entities.findNeedingVectors(key, PageRequest.of(0, Math.max(1, batch))).stream()
                .map(e -> new EntityText(e.getId(), e.getNormalizedName()
                        + (e.typeOrNull() == null ? "" : " (" + e.typeOrNull() + ")")))
                .toList();
    }

    @Transactional
    public void markVectorized(List<UUID> ids, String key) {
        if (ids == null || ids.isEmpty()) return;
        entities.markVectorized(ids, key);
    }

    /** The embedding models some entity's vector was still made with, besides {@code key}. */
    @Transactional(readOnly = true)
    public List<String> otherVectorKeys(String key) {
        return entities.findOtherVectorKeys(key);
    }

    /** Marks a document's metadata edges as due again - the next metadata pass rewrites them. */
    @Transactional
    public void invalidateMetadata(UUID documentId) {
        if (documentId == null) return;
        documentStates.findById(documentId).ifPresent(state -> {
            state.setMetadataVersion(null);
            save(state);
        });
    }

    /**
     * Fills in the derived name keys (GraphNames.fold, squash, initials) of entities written before
     * they existed - so the spelling, acronym and typo matches reach an existing graph too, without
     * re-reading it. A batch at a time; returns how many were filled.
     */
    @Transactional
    public int backfillDerivedKeys(int batch) {
        List<KgEntity> missing = entities.findWithoutDerivedKeys(PageRequest.of(0, Math.max(1, batch)));
        for (KgEntity e : missing) {
            e.setDerivedKeys(e.getNormalizedName());
            if (e.getSquashedName() == null) e.setSquashedName("");
            entities.save(e);
        }
        return missing.size();
    }

    /** The missing-chunk list a row can hold. Past this the document is read whole next time. */
    static final int MAX_MISSING_LIST = 2000;

    static String joinMissing(List<Integer> missing) {
        String joined = String.join(",", missing.stream().map(String::valueOf).toList());
        return joined.length() <= MAX_MISSING_LIST ? joined : null;
    }

    static List<Integer> missingOf(KgDocumentState state) {
        String raw = state.getMissingChunks();
        if (raw == null || raw.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // A damaged list reads as "nothing to resume", which reads the document whole.
                return List.of();
            }
        }
        return out;
    }

    /** PARTIAL: read and written, with some chunks no model answered - tried again later. */
    public enum RefreshOutcome { EXTRACTED, PARTIAL, UNCHANGED, FAILED, SKIPPED }

    /**
     * Re-queues every document recorded as read that provably was not: DONE, nothing in the graph
     * from it, and no model answer on record. Returns how many.
     *
     * <p><b>The repair for rows an outage already wrote.</b> Before 2026-09-16 a document no call
     * reached was recorded DONE with an empty graph, and DONE is never offered again - so those
     * documents stayed empty until somebody toggled the source's graph off and on, which nothing on
     * the screen told them to do. GraphModelUnreachableException stops new ones; this finds the
     * old ones. The rule, and what it costs, is on
     * KgDocumentStateRepository.requeueDoneWithoutAnswers.
     *
     * <p>Touches the progress rows and nothing else. The documents are not re-indexed - the worker
     * reads them from the keyword index again, as it does any PENDING document.
     */
    @Transactional
    public int requeueUnreadDocuments() {
        int requeued = documentStates.requeueDoneWithoutAnswers(
                KgDocumentState.Status.DONE, KgDocumentState.Status.PENDING, Instant.now());
        if (requeued > 0) {
            log.info("[Graph] {} document(s) were recorded as read with nothing in the graph and no "
                    + "model answer on record - queued to be read again", requeued);
        }
        return requeued;
    }

    private void recordFailure(KgDocumentState state, String message) {
        state.setStatus(KgDocumentState.Status.FAILED);
        state.setAttempts(state.getAttempts() + 1);
        state.setNextAttemptAt(Instant.now().plus(DocumentGraphState.RETRY_DELAY));
        state.setFailureMessage(truncate(message, 2000));
        save(state);
    }

    private void save(KgDocumentState state) {
        state.setUpdatedAt(Instant.now());
        documentStates.save(state);
    }

    private static void reportProgress(java.util.function.IntConsumer onProgress, int done) {
        if (onProgress == null) return;
        try {
            onProgress.accept(done);
        } catch (Exception e) {
            // A progress row that will not save is not a reason to stop reading the document.
            log.debug("[Graph] could not record extraction progress: {}", e.getMessage());
        }
    }

    /** SHA-256 over the chunk texts, in order. A separator no chunk contains keeps ["ab","c"] and
     *  ["a","bc"] from hashing alike. */
    static String chunkHash(List<String> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String chunk : chunks) {
                digest.update(chunk.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    /** Every progress row for one connector, keyed by document - what the worker scans against. */
    @Transactional(readOnly = true)
    public Map<UUID, DocumentGraphState> documentStates(UUID connectorId) {
        Map<UUID, DocumentGraphState> out = new HashMap<>();
        if (connectorId == null) return out;
        for (KgDocumentState s : documentStates.findByConnectorId(connectorId)) {
            out.put(s.getDocumentId(), DocumentGraphState.of(s));
        }
        return out;
    }

    /** How many progress rows a connector has. Part of the worker's cheap "anything changed" test. */
    @Transactional(readOnly = true)
    public long documentStateCount(UUID connectorId) {
        return connectorId == null ? 0 : documentStates.countByConnectorId(connectorId);
    }

    /** How many of a connector's documents the graph has read, and how many it has given up on. */
    @Transactional(readOnly = true)
    public DocumentProgress progressFor(UUID connectorId) {
        if (connectorId == null) return new DocumentProgress(0, 0, 0, 0, List.of());
        // What is in flight, so a screen can say "reading item 3: 37 of 581 passages" instead of a
        // number that will not move for hours.
        long chunksDone = 0;
        long chunkTotal = 0;
        // NAMED, NOT ONLY COUNTED. A summed count answers "is this source being read" and cannot
        // answer "which item", which is the question somebody looking at a list of twenty of them
        // actually has. The worker holds at most a handful of documents open at once, so this list
        // is short by construction rather than by a limit.
        List<Reading> reading = new ArrayList<>();
        for (KgDocumentState s : documentStates.findByConnectorIdAndStatus(
                connectorId, KgDocumentState.Status.EXTRACTING)) {
            chunksDone += s.getChunksDone();
            chunkTotal += s.getChunkCount();
            reading.add(new Reading(s.getDocumentId(), s.getChunksDone(), s.getChunkCount()));
        }
        return new DocumentProgress(
                documentStates.countByConnectorIdAndStatus(connectorId, KgDocumentState.Status.DONE),
                documentStates.countByConnectorIdAndStatusAndAttemptsGreaterThanEqual(
                        connectorId, KgDocumentState.Status.FAILED, DocumentGraphState.MAX_ATTEMPTS),
                chunksDone, chunkTotal, List.copyOf(reading));
    }

    /**
     * @param extracted   documents read to the end
     * @param failed      documents that have used up their retries
     * @param chunksDone  passages read so far in whatever is being read right now
     * @param chunkTotal  passages those documents hold in total
     * @param reading     the documents open RIGHT NOW, one entry each, so a screen can mark the
     *                    row rather than print a total nobody can match to an item
     */
    public record DocumentProgress(long extracted, long failed, long chunksDone, long chunkTotal,
                                   List<Reading> reading) {}

    /** One document the extractor has open, and how far into it that is. */
    public record Reading(UUID documentId, int chunksDone, int chunkCount) {}

    /** Everything this document contributed, and its progress row, gone. Called when a document is
     *  deleted - and by the extraction worker when a source was unplugged under a running extraction. */
    public void forgetDocument(UUID documentId) {
        rankings.clear();   // see refreshDocument - the same reason
        if (documentId == null) return;
        writer.forgetDocument(documentId);
    }

    /** Everything this connector contributed, gone. Called when a connector is deleted, or when its
     *  graph opt-in is switched off - leaving the rows behind would keep answering from a corpus the
     *  operator has said to stop using. Touches the graph and nothing else: the connector's vectors
     *  and keyword index are not this module's, and unplugging the graph must not disturb them. */
    public void forgetConnector(UUID connectorId) {
        rankings.clear();   // a view held open must not page on through a graph just deleted
        if (connectorId == null) return;
        writer.deleteConnector(connectorId);
    }

    // ---------------------------------------------------------------- reads

    /** What one connector has contributed, for the operator judging whether extraction is any good. */
    @Transactional(readOnly = true)
    public ConnectorGraphCounts countsFor(UUID connectorId) {
        if (connectorId == null) return new ConnectorGraphCounts(0, 0, 0);
        return new ConnectorGraphCounts(
                mentions.countDistinctEntitiesByConnectorId(connectorId),
                mentions.countByConnectorId(connectorId),
                relations.countByConnectorId(connectorId));
    }

    public record ConnectorGraphCounts(long entities, long mentions, long relations) {}

    // ---------------------------------------------------------------- the graph as a picture

    /** Nodes in one view. Past this a force layout is a hairball rather than a diagram, and the
     *  browser is laying out more circles than a person can look at. */
    private static final int MAX_VIEW_NODES = 1500;

    /** Edges in one view. Deliberately not 2x the nodes: a dense graph hits this first, and it is
     *  the number that decides whether the picture is readable. */
    private static final int MAX_VIEW_EDGES = 3000;

    /**
     * How a view is asked for: how much of it at most, and which slice of that to send now.
     *
     * <p>THE CAP AND THE PAGE ARE DIFFERENT NUMBERS, and conflating them would break the one
     * property that makes paging safe here. {@code cap} decides which nodes are in the picture at
     * all - the top N by connectedness - and every page is drawn from that same fixed set, so a
     * node's reported degree counts the edges it will actually be drawn with and does not creep
     * upward as later pages arrive. {@code offset} and {@code limit} only decide how that fixed
     * set is delivered.
     *
     * @param cap    the most nodes this view may ever hold, clamped to {@link #MAX_VIEW_NODES}.
     * @param offset how far into the ranked node list this page starts.
     * @param limit  how many nodes this page carries.
     */
    public record Window(int cap, int offset, int limit) {

        /** Everything, in one page - what a caller with no opinion gets. */
        public static Window all() {
            return new Window(MAX_VIEW_NODES, 0, MAX_VIEW_NODES);
        }

        public Window {
            cap = Math.max(1, Math.min(MAX_VIEW_NODES, cap));
            offset = Math.max(0, offset);
            limit = Math.max(1, Math.min(MAX_VIEW_NODES, limit));
        }
    }

    /**
     * Everything ONE DOCUMENT put into the graph.
     *
     * <p>Its own entities and its own edges, and nothing a sibling asserted. "What did this file
     * contribute" is a question with an exact answer, and padding it with context from elsewhere
     * would make a file look like it said things it did not.
     */
    @Transactional(readOnly = true)
    public GraphView viewForDocument(UUID connectorId, UUID documentId, Window window) {
        if (connectorId == null || documentId == null) return GraphView.empty();
        return pageOf(ranked("d:" + connectorId + ":" + documentId, window, () -> rank(
                counts(mentions.mentionCountsByDocument(documentId)),
                viewEdges(relations.viewEdgesOfDocument(documentId, Instant.now(), PageRequest.of(0, MAX_VIEW_EDGES))),
                relations.countByDocumentId(documentId),
                connectorId)), window);
    }

    /**
     * Everything a WHOLE SOURCE put into the graph - every item in it, in one picture.
     *
     * <p>This is the view that answers the thing a folder of related files is actually for. The
     * cross-file joins need no special construction step: entity resolution is global by
     * normalised name, so a component that both `design.md` and `api.md` mention is ONE node here,
     * with each file's edges hanging off it. What limits how well that works is not the scope of
     * extraction but the reach of resolution - see EntityResolver, where an unmatched surface form
     * stays a separate node until something names the two together.
     *
     * <p>DELIVERED A PAGE AT A TIME, most connected first. A source with a thousand entities took
     * one long request that drew nothing at all until every last leaf node had arrived, and the
     * nodes that arrived last were the ones that matter least - a reader was waiting on the dust
     * to see the hubs. A page is a slice of the same ranked list, with the edges that slice
     * completes, so the first one is already the picture and each one after it fills it in.
     */
    @Transactional(readOnly = true)
    public GraphView viewForConnector(UUID connectorId) {
        return viewForConnector(connectorId, Window.all());
    }

    @Transactional(readOnly = true)
    public GraphView viewForConnector(UUID connectorId, Window window) {
        if (connectorId == null) return GraphView.empty();
        return pageOf(ranked("c:" + connectorId, window, () -> rank(
                counts(mentions.mentionCountsByConnector(connectorId)),
                viewEdges(relations.viewEdgesOfConnector(connectorId, Instant.now(), PageRequest.of(0, MAX_VIEW_EDGES))),
                relations.countByConnectorId(connectorId),
                connectorId)), window);
    }

    /** One drawable edge: a triple, how many documents state it, and one of them to open. */
    record ViewEdge(UUID subjectId, UUID objectId, String predicate, String description, int weight,
                    UUID documentId) {}

    // Entity id -> count, from a grouped query's rows.
    private static Map<UUID, Integer> counts(List<Object[]> rows) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Object[] row : rows) out.put((UUID) row[0], ((Number) row[1]).intValue());
        return out;
    }

    // The grouped view rows: subject, object, predicate, distinct documents, a description, a document.
    private static List<ViewEdge> viewEdges(List<Object[]> rows) {
        List<ViewEdge> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID document = null;
            try {
                if (row[5] != null) document = UUID.fromString(row[5].toString());
            } catch (IllegalArgumentException ignored) {
                // Not a UUID - the edge is still drawn, with nothing to open.
            }
            out.add(new ViewEdge((UUID) row[0], (UUID) row[1], (String) row[2], (String) row[4],
                    ((Number) row[3]).intValue(), document));
        }
        return out;
    }

    /** How long a view's ranking is reused by the pages after its first. */
    static final Duration RANKING_TTL = Duration.ofMinutes(2);

    /** Views whose ranking is held at once - one per open graph, and a handful is plenty. */
    private static final int MAX_RANKINGS = 32;

    /**
     * The ranked scope a view's pages are sliced from, remembered BETWEEN PAGES.
     *
     * <p>Every page used to redo the whole ranking - all the scope's entity ids, up to 3,000 edges,
     * a mention count over every one of those ids - to hand back 250 nodes of it, so a 1,500-star
     * view paid for the full computation six times over and each page arrived as slowly as the
     * first. The first page (offset 0) always ranks afresh, so opening a view never shows an old
     * graph; the pages after it reuse that ranking, which also means they are slices of ONE
     * consistent snapshot even if extraction writes something between two of them.
     */
    private final Map<String, Ranked> rankings = new ConcurrentHashMap<>();

    private Ranked ranked(String key, Window window, Supplier<Ranked> compute) {
        Instant now = Instant.now();
        if (window.offset() > 0) {
            Ranked held = rankings.get(key);
            if (held != null && held.at().plus(RANKING_TTL).isAfter(now)) return held;
        }
        Ranked fresh = compute.get();
        rankings.values().removeIf(r -> !r.at().plus(RANKING_TTL).isAfter(now));
        if (rankings.size() >= MAX_RANKINGS) rankings.clear();
        if (fresh != null) rankings.put(key, fresh);
        return fresh;
    }

    /** A scope ranked for drawing: everything a page needs, and nothing that depends on the window. */
    private record Ranked(Instant at, UUID connectorId, int scopeSize, List<UUID> ranked,
                          Map<UUID, Integer> degree, Map<UUID, Integer> mentionCounts,
                          List<ViewEdge> usable, long totalEdges, boolean edgesCapped) {}

    /**
     * Turns a scope's entities - with how often each is mentioned - and its grouped edges into
     * something drawable.
     *
     * <p>ORDER MATTERS HERE. Edges are fetched first and capped, most-stated first, then the node set
     * is narrowed to what those edges actually touch plus the most-mentioned isolated entities. Doing
     * it the other way - cap the nodes, then keep edges between survivors - drops edges whose far
     * end fell off the list and draws a graph that looks sparser than it is, which is the one
     * distortion a reader cannot detect from the picture.
     */
    private Ranked rank(Map<UUID, Integer> mentionCounts, List<ViewEdge> scopeEdges,
                        long totalEdges, UUID connectorId) {
        if (mentionCounts.isEmpty()) return null;

        // An edge is only drawable when BOTH ends are in scope. A relation extracted from this
        // document whose object resolved to an entity this document does not itself mention would
        // otherwise draw a line to nothing.
        List<ViewEdge> usable = scopeEdges.stream()
                .filter(r -> mentionCounts.containsKey(r.subjectId()) && mentionCounts.containsKey(r.objectId()))
                .filter(r -> !r.subjectId().equals(r.objectId()))
                .toList();

        // One per drawn edge: a triple twenty documents state is one edge of weight twenty, not
        // twenty parallel edges and a degree of twenty.
        Map<UUID, Integer> degree = new HashMap<>();
        for (ViewEdge r : usable) {
            degree.merge(r.subjectId(), 1, Integer::sum);
            degree.merge(r.objectId(), 1, Integer::sum);
        }

        // Connected nodes first, then the loudest isolated ones. An entity with no edge is still
        // worth showing - "this source names Kubernetes forty times and connects it to nothing"
        // is a real finding about the extraction, not an empty row.
        List<UUID> ranked = new ArrayList<>(mentionCounts.keySet());
        // THE ID IS THE LAST TIE-BREAK, and it is what makes paging safe rather than tidy. Two
        // entities with the same degree and the same mention count would otherwise keep whatever
        // order the mention query happened to return, and a page boundary falling between them
        // could send one entity twice and another never across two requests.
        ranked.sort(Comparator
                .comparingInt((UUID id) -> degree.getOrDefault(id, 0)).reversed()
                .thenComparing(Comparator.comparingInt((UUID id) -> mentionCounts.getOrDefault(id, 0)).reversed())
                .thenComparing(UUID::toString));

        return new Ranked(Instant.now(), connectorId, mentionCounts.size(), List.copyOf(ranked), degree,
                mentionCounts, usable, totalEdges, scopeEdges.size() >= MAX_VIEW_EDGES);
    }

    /** One window of a ranked scope. */
    private GraphView pageOf(Ranked scope, Window window) {
        if (scope == null) return GraphView.empty();
        List<UUID> ranked = scope.ranked();
        Map<UUID, Integer> degree = scope.degree();
        Map<UUID, Integer> mentionCounts = scope.mentionCounts();
        List<ViewEdge> usable = scope.usable();
        long totalEdges = scope.totalEdges();

        // The whole picture, not this page: every page is a slice of THIS set, so a node's degree
        // counts the edges it will be drawn with rather than growing as later pages land.
        int capped = Math.min(window.cap(), ranked.size());
        List<UUID> keptIds = ranked.subList(0, capped);
        Set<UUID> kept = new LinkedHashSet<>(keptIds);

        boolean truncated = ranked.size() > capped || scope.edgesCapped();

        int from = Math.min(window.offset(), capped);
        int to = Math.min(from + window.limit(), capped);
        List<UUID> pageIds = keptIds.subList(from, to);
        Set<UUID> page = new LinkedHashSet<>(pageIds);

        // Named as THIS source writes them - never the entity's canonical name, which another
        // source, possibly one the viewer cannot read, may have coined. See displayOf.
        Map<UUID, Display> display = displayOf(page, List.of(scope.connectorId()));
        List<GraphView.Node> nodes = new ArrayList<>(page.size());
        for (KgEntity e : entities.findAllById(page)) {
            Display d = display.get(e.getId());
            nodes.add(new GraphView.Node(e.getId(), d == null ? e.getCanonicalName() : d.name(),
                    d == null ? e.typeOrNull() : d.type(),
                    mentionCounts.getOrDefault(e.getId(), 0), degree.getOrDefault(e.getId(), 0)));
        }

        // AN EDGE BELONGS TO THE PAGE THAT COMPLETES IT - the page holding the LATER of its two
        // endpoints. Not "either endpoint is on this page", which reads as the same rule and is
        // not: an edge from a page-one hub to a page-three leaf would then be sent with page one,
        // where its far end does not exist yet, and a client that drops an edge to an unknown node
        // would drop it for good. Ranking both ends and sending it with the second is the only
        // assignment that is both complete and free of duplicates.
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < keptIds.size(); i++) rank.put(keptIds.get(i), i);
        int pageFrom = from;
        int pageTo = to;
        List<GraphView.Edge> edges = usable.stream()
                .filter(r -> kept.contains(r.subjectId()) && kept.contains(r.objectId()))
                .filter(r -> {
                    int last = Math.max(rank.get(r.subjectId()), rank.get(r.objectId()));
                    return last >= pageFrom && last < pageTo;
                })
                .map(r -> new GraphView.Edge(r.subjectId(), r.objectId(), r.predicate(),
                        r.description(), r.weight(), r.documentId()))
                .toList();

        return new GraphView(nodes, edges, truncated, scope.scopeSize(), totalEdges, to < capped);
    }

    /**
     * Walks out from the names in a query.
     *
     * <p>Scoped to what the asking user can see, three ways. An entity is reached only if a source
     * they can read mentions it. An EDGE is followed only if a source they can read stated it
     * (SEC-51 - it used to be enough that both ends were visible somewhere, which handed over a
     * private document's predicate, quoted sentence and title). And every name, type and description
     * shown is one a readable document wrote (see {@link #displayOf}).
     *
     * @param connectorIds the connectors this user may read. EMPTY MEANS NOTHING IS VISIBLE and the
     *                     result is empty - never "unfiltered". A visibility filter that opens up
     *                     when its input is empty is the shape of a data leak.
     */
    @Transactional(readOnly = true)
    public GraphNeighbourhood neighbourhood(List<String> terms, int hops, List<UUID> connectorIds) {
        return neighbourhood(terms, hops, connectorIds, List.of());
    }

    /**
     * The same, ranked by MEANING as well as by structure (since 2026-10-13).
     *
     * <p>{@code relevant} are the passages a search found most relevant to the question being asked.
     * The entities those passages mention are what the question is about: they rank the names a
     * term matched, the neighbours of each ring, and the edges returned - an edge drawn from one of
     * those very passages first. And when no name matches at all, they are where the walk starts -
     * the question is linked to the graph by what it means rather than by how it spells a name.
     */
    @Transactional(readOnly = true)
    public GraphNeighbourhood neighbourhood(List<String> terms, int hops, List<UUID> connectorIds,
                                            List<RelevantPassage> relevant) {
        return neighbourhood(terms, hops, connectorIds, relevant, Map.of());
    }

    /**
     * The same, with the entities whose NAMES mean something close to each term
     * ({@code similarByTerm}, from the entity name vectors - see {@link SimilarEntity}): the last
     * tier for a term nothing else matched, and - when very close - synonyms of what did match.
     */
    @Transactional(readOnly = true)
    public GraphNeighbourhood neighbourhood(List<String> terms, int hops, List<UUID> connectorIds,
                                            List<RelevantPassage> relevant,
                                            Map<String, List<SimilarEntity>> similarByTerm) {
        Map<String, List<SimilarEntity>> similar = similarByTerm == null ? Map.of() : similarByTerm;
        if (connectorIds == null || connectorIds.isEmpty()) return GraphNeighbourhood.empty();
        List<String> named = terms == null ? List.of() : terms;
        List<RelevantPassage> passages = relevant == null ? List.of() : relevant;
        if (named.isEmpty() && passages.isEmpty()) return GraphNeighbourhood.empty();
        int depth = Math.max(1, Math.min(hops, MAX_HOPS));
        boolean truncated = false;

        Relevance relevance = relevanceOf(passages, connectorIds);

        // One group per thing asked about: the names a term matched, with their spelling variants.
        // Paths are looked for BETWEEN groups, so two spellings of one name are not "a connection".
        List<List<KgEntity>> groups = new ArrayList<>();
        for (String term : named) {
            List<KgEntity> matched = withSynonyms(seedsFor(term, connectorIds, relevance,
                    similar.getOrDefault(term, List.of())), connectorIds);
            if (!matched.isEmpty()) groups.add(matched);
        }
        if (groups.isEmpty() && !relevance.byEntity().isEmpty()) {
            // Nothing named matched: start from what the relevant passages are about.
            List<UUID> top = relevance.byEntity().entrySet().stream()
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                    .limit(SEEDS_FROM_PASSAGES).map(Map.Entry::getKey).toList();
            List<KgEntity> fromPassages = new ArrayList<>();
            Map<UUID, KgEntity> byId = new HashMap<>();
            entities.findAllById(top).forEach(e -> byId.put(e.getId(), e));
            top.forEach(id -> { if (byId.containsKey(id)) fromPassages.add(byId.get(id)); });
            if (!fromPassages.isEmpty()) groups.add(fromPassages);
        }
        if (groups.isEmpty()) return GraphNeighbourhood.empty();

        Map<UUID, KgEntity> found = new LinkedHashMap<>();
        Map<UUID, Integer> hopOf = new HashMap<>();
        for (List<KgEntity> group : groups) {
            for (KgEntity e : group) {
                found.putIfAbsent(e.getId(), e);
                hopOf.putIfAbsent(e.getId(), 0);
            }
        }

        Map<UUID, KgRelation> collected = new LinkedHashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>(found.keySet());

        Instant now = Instant.now();
        for (int hop = 1; hop <= depth && !frontier.isEmpty(); hop++) {
            // Current edges only, filtered in the query rather than after its page - see
            // KgRelationRepository.findCurrentTouchingAnyIn.
            List<KgRelation> edges = relations.findCurrentTouchingAnyIn(
                    frontier, connectorIds, now, PageRequest.of(0, EDGE_ROWS_PER_HOP));
            if (edges.size() >= EDGE_ROWS_PER_HOP) truncated = true;
            edges.forEach(r -> collected.putIfAbsent(r.getId(), r));

            // The next ring, what the question is about first, then the most connected: an entity
            // several documents link to the frontier before one a single passage mentions in
            // passing. A hub's neighbours used to be kept in whatever order the rows came back, so
            // the cap kept an arbitrary few.
            Map<UUID, Set<UUID>> docsLinking = new LinkedHashMap<>();
            for (KgRelation r : edges) {
                for (UUID id : List.of(r.getSubjectId(), r.getObjectId())) {
                    if (found.containsKey(id)) continue;
                    docsLinking.computeIfAbsent(id, k -> new HashSet<>()).add(r.getDocumentId());
                }
            }
            if (docsLinking.isEmpty()) break;
            List<UUID> next = new ArrayList<>(docsLinking.keySet());
            next.sort(Comparator.comparingDouble((UUID id) -> relevance.of(id)).reversed()
                    .thenComparing(Comparator.comparingInt((UUID id) -> docsLinking.get(id).size()).reversed())
                    .thenComparing(UUID::toString));

            // Visibility again, as a second wall: an edge from a readable source names two entities
            // that source mentions, so this should change nothing - and if it ever does, the filter
            // is here rather than nowhere.
            Set<UUID> visible = new HashSet<>(mentions.visibleEntityIds(next, connectorIds));
            Map<UUID, KgEntity> byId = new HashMap<>();
            for (KgEntity e : entities.findAllById(next.stream().filter(visible::contains).toList())) {
                byId.put(e.getId(), e);
            }
            Set<UUID> added = new LinkedHashSet<>();
            for (UUID id : next) {
                KgEntity e = byId.get(id);
                if (e == null) continue;
                if (found.size() >= MAX_ENTITIES) { truncated = true; break; }
                found.put(id, e);
                hopOf.put(id, hop);
                added.add(id);
            }
            frontier = added;
        }

        return assemble(found, hopOf, groups, new ArrayList<>(collected.values()), connectorIds, relevance,
                truncated);
    }

    /** How close in meaning a name must be to match a term no other tier matched. */
    static final double SIMILAR_MATCH = 0.75;

    /** How close in meaning a name must be to count as a synonym of a match. */
    static final double SIMILAR_SYNONYM = 0.9;

    private List<KgEntity> similarAbove(List<SimilarEntity> similar, double threshold) {
        List<UUID> ids = similar.stream().filter(s -> s.score() >= threshold)
                .sorted(Comparator.comparingDouble(SimilarEntity::score).reversed())
                .map(SimilarEntity::id).limit(SEEDS_PER_TERM).toList();
        if (ids.isEmpty()) return List.of();
        Map<UUID, KgEntity> byId = new HashMap<>();
        entities.findAllById(ids).forEach(e -> byId.put(e.getId(), e));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Seeds taken from the relevant passages when no name matched. */
    private static final int SEEDS_FROM_PASSAGES = 6;

    /** Candidates read for a typo-tolerant match before distances are computed. */
    private static final int FUZZY_CANDIDATES = 300;

    /**
     * How relevant each entity and each passage is to the question.
     *
     * @param byEntity    the summed relevance of the passages mentioning each entity
     * @param byPassage   each passage's own relevance, keyed by document and chunk
     * @param documentsOf for each entity, the relevant documents mentioning it, most relevant first
     */
    record Relevance(Map<UUID, Double> byEntity, Map<String, Double> byPassage, Map<UUID, List<UUID>> documentsOf) {
        static Relevance none() { return new Relevance(Map.of(), Map.of(), Map.of()); }
        double of(UUID entity) { return byEntity.getOrDefault(entity, 0.0); }
        double ofPassage(UUID document, String chunkRef) { return byPassage.getOrDefault(document + "#" + chunkRef, 0.0); }
        List<UUID> documentsAbout(UUID entity) { return documentsOf.getOrDefault(entity, List.of()); }
    }

    private Relevance relevanceOf(List<RelevantPassage> passages, List<UUID> connectorIds) {
        if (passages.isEmpty()) return Relevance.none();
        Map<String, Double> byPassage = new HashMap<>();
        for (RelevantPassage p : passages) {
            if (p.documentId() == null || p.chunkRef() == null) continue;
            byPassage.merge(p.documentId() + "#" + p.chunkRef(), p.score(), Math::max);
        }
        Set<UUID> documents = new HashSet<>();
        passages.forEach(p -> { if (p.documentId() != null) documents.add(p.documentId()); });
        Map<UUID, Double> byEntity = new HashMap<>();
        Map<UUID, Map<UUID, Double>> byEntityDocument = new HashMap<>();
        if (!documents.isEmpty()) {
            for (Object[] row : mentions.mentionsInDocuments(documents, connectorIds)) {
                Double score = byPassage.get(row[1] + "#" + row[2]);
                if (score == null) continue;
                byEntity.merge((UUID) row[0], score, Double::sum);
                byEntityDocument.computeIfAbsent((UUID) row[0], k -> new HashMap<>()).merge((UUID) row[1], score, Math::max);
            }
        }
        Map<UUID, List<UUID>> documentsOf = new HashMap<>();
        byEntityDocument.forEach((entity, scores) -> documentsOf.put(entity, scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed().thenComparing(e -> e.getKey().toString()))
                .map(Map.Entry::getKey).toList()));
        return new Relevance(byEntity, byPassage, documentsOf);
    }

    /**
     * The entities one term names, best match first. Tier by tier, and the first tier that finds
     * something VISIBLE answers - checked for visibility before it is accepted, so an exact match the
     * asker cannot see neither answers nor hides the looser ones they can:
     *
     * <ol>
     *   <li>the exact name, or an alias some readable source gave it;</li>
     *   <li>the name typed without diacritics ("da nang");</li>
     *   <li>the same letters with spaces and punctuation ignored ("authservice");</li>
     *   <li>an acronym's expansion ("SSO" for "Single Sign-On");</li>
     *   <li>a name containing the term;</li>
     *   <li>a name a typo or two away, or with the same words in another order;</li>
     *   <li>a name that MEANS something close ({@code similar}, from the name vectors).</li>
     * </ol>
     *
     * And a name very close in meaning comes along with any tier's matches, as a synonym - entity
     * resolution step 2, decided here and stored nowhere, never a merge. Within a tier, what the
     * question is about comes first (see {@link Relevance}).
     */
    private List<KgEntity> seedsFor(String term, List<UUID> connectorIds, Relevance relevance,
                                    List<SimilarEntity> similar) {
        if (!GraphNames.isUsable(term)) return List.of();
        String normalized = GraphNames.normalize(term);
        String folded = GraphNames.fold(term);
        String squashed = GraphNames.squash(term);
        PageRequest page = PageRequest.of(0, SEEDS_PER_TERM);

        List<KgEntity> exact = new ArrayList<>(entities.findByNormalizedName(normalized));
        List<UUID> byAlias = aliases.entityIdsForAlias(normalized, connectorIds);
        if (!byAlias.isEmpty()) exact.addAll(entities.findAllById(byAlias));
        // Each tier is asked for VISIBLE matches (see KgEntityRepository.VISIBLE), so its page holds
        // what this reader can see rather than five rows of anybody's.
        List<KgEntity> hit = visibleOf(exact, connectorIds);
        if (hit.isEmpty()) hit = entities.findByFoldedName(folded, connectorIds, page);
        if (hit.isEmpty() && !squashed.isEmpty()) hit = entities.findBySquashedName(squashed, connectorIds, page);
        if (hit.isEmpty() && looksLikeAcronym(term)) {
            hit = entities.findByInitials(folded, connectorIds, page);
        }
        if (hit.isEmpty()) hit = entities.searchByNameContaining(likePattern(normalized),
                likePattern(folded), connectorIds, page);
        if (hit.isEmpty()) hit = fuzzy(term, connectorIds);
        if (hit.isEmpty()) hit = visibleOf(similarAbove(similar, SIMILAR_MATCH), connectorIds);
        else {
            List<KgEntity> merged = new ArrayList<>(hit);
            merged.addAll(visibleOf(similarAbove(similar, SIMILAR_SYNONYM), connectorIds));
            hit = merged.stream().distinct().toList();
        }
        return hit.stream()
                .sorted(Comparator.comparingDouble((KgEntity e) -> relevance.of(e.getId())).reversed())
                .limit(SEEDS_PER_TERM).toList();
    }

    // One word of two to eight letters or digits, typed as an acronym usually is.
    private static boolean looksLikeAcronym(String term) {
        String t = term.trim();
        return t.matches("[\\p{L}\\p{N}]{2,8}") && !t.equals(t.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Names a typo or two from the term, or with the same words in another order - the last tier,
     * for a term nothing else matched. Distances on the squashed form, so case, accents, spacing and
     * punctuation never count as a typo: one edit for a name of five to eight characters, two up to
     * fourteen, three beyond. Shorter names must match exactly - a typo there is another word.
     */
    List<KgEntity> fuzzy(String term, List<UUID> connectorIds) {
        String squashed = GraphNames.squash(term);
        if (squashed.length() < 5) return List.of();
        int allowed = squashed.length() <= 8 ? 1 : squashed.length() <= 14 ? 2 : 3;
        Map<UUID, KgEntity> candidates = new LinkedHashMap<>();
        // Visible candidates, closest in length first - a typo is a character or two either way.
        entities.findBySquashedNamePrefix(likePattern(squashed.substring(0, 2)), squashed.length(), connectorIds,
                        PageRequest.of(0, FUZZY_CANDIDATES))
                .forEach(e -> candidates.putIfAbsent(e.getId(), e));
        // Reordered words: candidates containing the longest word of the term.
        List<String> words = words(term);
        String longest = words.stream().max(Comparator.comparingInt(String::length)).orElse("");
        if (words.size() >= 2 && longest.length() >= 4) {
            entities.searchByNameContaining(likePattern(longest), likePattern(longest), connectorIds,
                            PageRequest.of(0, FUZZY_CANDIDATES))
                    .forEach(e -> candidates.putIfAbsent(e.getId(), e));
        }
        record Scored(KgEntity entity, int distance) {}
        List<Scored> scored = new ArrayList<>();
        Set<String> termWords = new HashSet<>(words);
        for (KgEntity e : candidates.values()) {
            String key = e.getSquashedName() != null ? e.getSquashedName() : GraphNames.squash(e.getNormalizedName());
            if (key.isEmpty()) continue;
            if (words.size() >= 2 && new HashSet<>(words(e.getNormalizedName())).equals(termWords)) {
                scored.add(new Scored(e, 0));
                continue;
            }
            if (Math.abs(key.length() - squashed.length()) > allowed) continue;
            int d = editDistance(squashed, key, allowed);
            if (d <= allowed) scored.add(new Scored(e, d));
        }
        return scored.stream().sorted(Comparator.comparingInt(Scored::distance)
                        .thenComparing(sc -> sc.entity().getNormalizedName()))
                .map(Scored::entity).limit(SEEDS_PER_TERM).toList();
    }

    private static List<String> words(String raw) {
        List<String> out = new ArrayList<>();
        for (String w : GraphNames.fold(raw).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /**
     * Optimal string alignment distance - insertions, deletions, substitutions and swaps of two
     * neighbouring characters - giving up once every alignment is past {@code max}.
     */
    static int editDistance(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[n][m];
    }

    /**
     * Entity-resolution step 2, as a SYNONYM rather than a merge (the review's HippoRAG-style
     * choice): each matched entity brings the visible entities spelled the same once spacing,
     * punctuation, case and accents are ignored - "Auth Service", "auth-service", "AuthService".
     * Decided at query time and stored nowhere, so it is reversed by changing this method; a wrong
     * merge could only be reversed by finding it first.
     */
    private List<KgEntity> withSynonyms(List<KgEntity> matched, List<UUID> connectorIds) {
        if (matched.isEmpty()) return matched;
        Map<UUID, KgEntity> out = new LinkedHashMap<>();
        matched.forEach(e -> out.putIfAbsent(e.getId(), e));
        List<KgEntity> variants = new ArrayList<>();
        for (KgEntity e : matched) {
            String key = e.getSquashedName();
            if (key == null || key.isEmpty()) continue;
            variants.addAll(entities.findBySquashedName(key, connectorIds, PageRequest.of(0, SEEDS_PER_TERM)));
        }
        variants.forEach(e -> out.putIfAbsent(e.getId(), e));
        return List.copyOf(out.values());
    }

    private List<KgEntity> visibleOf(List<KgEntity> candidates, List<UUID> connectorIds) {
        if (candidates.isEmpty()) return List.of();
        Map<UUID, KgEntity> byId = new LinkedHashMap<>();
        candidates.forEach(e -> byId.putIfAbsent(e.getId(), e));
        Set<UUID> visible = new HashSet<>(mentions.visibleEntityIds(new ArrayList<>(byId.keySet()), connectorIds));
        return byId.values().stream().filter(e -> visible.contains(e.getId())).toList();
    }

    /** Seeds of the search channel, and the entities whose passages it scores. */
    private static final int CHANNEL_SEEDS = 10;
    private static final int CHANNEL_ENTITIES = 30;

    /**
     * THE GRAPH CHANNEL OF HYBRID SEARCH (the HippoRAG 2 pattern, the one graph method the
     * evaluations found does not hurt plain fact lookup): passages ranked by how central the
     * entities they mention are to the query, for SearchService to fuse beside the dense and keyword
     * lists. Off unless the deployment switches it on (GraphSettings.searchChannel).
     *
     * <ol>
     *   <li>Seeds: entities NAMED by the query - every run of one to three of its words matched
     *       exactly, without diacritics, or with spacing ignored - and entities whose names mean
     *       something very close ({@code similar}). No contains or typo matching: a search channel
     *       that guesses adds noise to every query.</li>
     *   <li>Personalized PageRank over the readable sources' current edges two steps around the
     *       seeds, restarting at the seeds.</li>
     *   <li>Each passage scored by the rank of the entities it mentions.</li>
     * </ol>
     *
     * Empty when nothing in the query names anything - the channel then simply contributes nothing.
     */
    @Transactional(readOnly = true)
    public List<RelevantPassage> graphPassages(String query, List<SimilarEntity> similar, List<UUID> connectorIds,
                                               int limit) {
        if (query == null || query.isBlank() || connectorIds == null || connectorIds.isEmpty()) return List.of();
        List<String> words = words(query);
        Map<UUID, KgEntity> candidates = new LinkedHashMap<>();
        PageRequest page = PageRequest.of(0, SEEDS_PER_TERM);
        for (int n = Math.min(3, words.size()); n >= 1; n--) {
            for (int i = 0; i + n <= words.size(); i++) {
                String phrase = String.join(" ", words.subList(i, i + n));
                if (!GraphNames.isUsable(phrase)) continue;
                entities.findByNormalizedName(GraphNames.normalize(phrase)).forEach(e -> candidates.putIfAbsent(e.getId(), e));
                entities.findByFoldedName(GraphNames.fold(phrase), connectorIds, page).forEach(e -> candidates.putIfAbsent(e.getId(), e));
                String squashed = GraphNames.squash(phrase);
                if (!squashed.isEmpty()) entities.findBySquashedName(squashed, connectorIds, page).forEach(e -> candidates.putIfAbsent(e.getId(), e));
            }
        }
        similarAbove(similar == null ? List.of() : similar, 0.8).forEach(e -> candidates.putIfAbsent(e.getId(), e));
        List<UUID> seeds = visibleOf(new ArrayList<>(candidates.values()), connectorIds).stream()
                .map(KgEntity::getId).limit(CHANNEL_SEEDS).toList();
        if (seeds.isEmpty()) return List.of();

        // Two steps of current, readable edges around the seeds.
        Instant now = Instant.now();
        Map<UUID, Map<UUID, Integer>> adjacent = new HashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>(seeds);
        Set<UUID> reached = new LinkedHashSet<>(seeds);
        for (int hop = 0; hop < 2 && !frontier.isEmpty(); hop++) {
            Set<UUID> next = new LinkedHashSet<>();
            for (KgRelation r : relations.findCurrentTouchingAnyIn(frontier, connectorIds, now,
                    PageRequest.of(0, EDGE_ROWS_PER_HOP))) {
                if (r.getSubjectId().equals(r.getObjectId())) continue;
                adjacent.computeIfAbsent(r.getSubjectId(), k -> new HashMap<>()).merge(r.getObjectId(), 1, Integer::sum);
                adjacent.computeIfAbsent(r.getObjectId(), k -> new HashMap<>()).merge(r.getSubjectId(), 1, Integer::sum);
                for (UUID id : List.of(r.getSubjectId(), r.getObjectId())) if (reached.add(id)) next.add(id);
            }
            frontier = next;
        }
        Map<UUID, Double> rank = personalizedPageRank(adjacent, seeds);
        List<UUID> central = rank.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed().thenComparing(e -> e.getKey().toString()))
                .map(Map.Entry::getKey).limit(CHANNEL_ENTITIES).toList();

        Map<String, Double> byPassage = new LinkedHashMap<>();
        Map<String, RelevantPassage> passages = new HashMap<>();
        for (Object[] row : mentions.passagesOf(central, connectorIds)) {
            String chunkRef = (String) row[2];
            if (!isPassage(chunkRef)) continue;
            String key = row[1] + "#" + chunkRef;
            byPassage.merge(key, rank.getOrDefault((UUID) row[0], 0.0), Double::sum);
            passages.putIfAbsent(key, new RelevantPassage((UUID) row[1], chunkRef, 0));
        }
        return byPassage.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(Math.max(1, limit))
                .map(e -> new RelevantPassage(passages.get(e.getKey()).documentId(), passages.get(e.getKey()).chunkRef(), e.getValue()))
                .toList();
    }

    /**
     * Personalized PageRank: the walk restarts at the seeds half the time, so what ranks high is
     * what the seeds are tightly connected to - not what is central to the corpus as a whole.
     */
    static Map<UUID, Double> personalizedPageRank(Map<UUID, Map<UUID, Integer>> adjacent, List<UUID> seeds) {
        Set<UUID> nodes = new LinkedHashSet<>(adjacent.keySet());
        nodes.addAll(seeds);
        Map<UUID, Double> restart = new HashMap<>();
        seeds.forEach(s -> restart.put(s, 1.0 / seeds.size()));
        Map<UUID, Double> rank = new HashMap<>(restart);
        double alpha = 0.5;
        for (int i = 0; i < 20; i++) {
            Map<UUID, Double> next = new HashMap<>();
            for (UUID node : nodes) next.put(node, alpha * restart.getOrDefault(node, 0.0));
            for (Map.Entry<UUID, Map<UUID, Integer>> e : adjacent.entrySet()) {
                double mass = rank.getOrDefault(e.getKey(), 0.0);
                if (mass == 0) continue;
                int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                for (Map.Entry<UUID, Integer> n : e.getValue().entrySet()) {
                    next.merge(n.getKey(), (1 - alpha) * mass * n.getValue() / total, Double::sum);
                }
            }
            rank = next;
        }
        return rank;
    }

    /** Edges read to group a corpus into themes, newest first. */
    private static final int THEME_EDGES = 3000;

    /**
     * What a corpus is about: the groups of entities its relationships tie together, largest - or,
     * given the passages relevant to a question, most relevant - first.
     *
     * <p>The LazyGraphRAG choice rather than GraphRAG's: grouped at QUERY time, from the readable
     * sources' current edges (label propagation, a few rounds), and nothing summarised in advance -
     * the agent reads the groups and says what they are about. Precomputed community reports would
     * cost a model call per community on every change, and would have to be computed per reader to
     * respect what each can see.
     */
    @Transactional(readOnly = true)
    public List<GraphTheme> themes(List<UUID> connectorIds, List<RelevantPassage> relevant, int max) {
        if (connectorIds == null || connectorIds.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<KgRelation> edges = relations.findCurrentInConnectors(connectorIds, now, PageRequest.of(0, THEME_EDGES)).stream()
                .filter(r -> !r.getSubjectId().equals(r.getObjectId())).toList();
        if (edges.isEmpty()) return List.of();

        Map<UUID, Map<UUID, Integer>> adjacent = new HashMap<>();
        for (KgRelation r : edges) {
            adjacent.computeIfAbsent(r.getSubjectId(), k -> new HashMap<>()).merge(r.getObjectId(), 1, Integer::sum);
            adjacent.computeIfAbsent(r.getObjectId(), k -> new HashMap<>()).merge(r.getSubjectId(), 1, Integer::sum);
        }
        // Label propagation: each entity takes the label most of its neighbours carry, weighted by
        // how many edges join them; ties to the smaller label, in a fixed order, so the same graph
        // always groups the same way.
        List<UUID> order = new ArrayList<>(adjacent.keySet());
        order.sort(Comparator.comparing(UUID::toString));
        Map<UUID, UUID> label = new HashMap<>();
        order.forEach(id -> label.put(id, id));
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            for (UUID id : order) {
                Map<UUID, Integer> votes = new HashMap<>();
                adjacent.get(id).forEach((n, w) -> votes.merge(label.get(n), w, Integer::sum));
                UUID best = votes.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()
                                .thenComparing(e -> e.getKey().toString()))
                        .map(Map.Entry::getKey).findFirst().orElse(label.get(id));
                if (!best.equals(label.get(id))) { label.put(id, best); changed = true; }
            }
            if (!changed) break;
        }
        Map<UUID, List<UUID>> groups = new HashMap<>();
        label.forEach((id, l) -> groups.computeIfAbsent(l, k -> new ArrayList<>()).add(id));

        Relevance relevance = relevanceOf(relevant == null ? List.of() : relevant, connectorIds);
        List<List<UUID>> ranked = groups.values().stream().filter(g -> g.size() >= 3)
                .sorted(Comparator.comparingDouble((List<UUID> g) -> g.stream().mapToDouble(relevance::of).sum()).reversed()
                        .thenComparing(Comparator.comparingInt((List<UUID> g) -> g.size()).reversed())
                        .thenComparing(g -> g.stream().map(UUID::toString).sorted().findFirst().orElse("")))
                .limit(Math.max(1, max)).toList();
        if (ranked.isEmpty()) return List.of();

        Set<UUID> shown = new HashSet<>();
        ranked.forEach(shown::addAll);
        Map<UUID, Display> display = displayOf(shown, connectorIds);
        List<GraphTheme> out = new ArrayList<>();
        for (List<UUID> group : ranked) {
            Set<UUID> members = new HashSet<>(group);
            List<UUID> central = group.stream()
                    .sorted(Comparator.comparingInt((UUID id) -> adjacent.get(id).values().stream().mapToInt(Integer::intValue).sum()).reversed()
                            .thenComparing(UUID::toString))
                    .filter(display::containsKey).limit(8).toList();
            Map<String, Set<UUID>> byStatement = new LinkedHashMap<>();
            Map<UUID, Integer> documentUse = new HashMap<>();
            for (KgRelation r : edges) {
                if (!members.contains(r.getSubjectId()) || !members.contains(r.getObjectId())) continue;
                if (!display.containsKey(r.getSubjectId()) || !display.containsKey(r.getObjectId())) continue;
                byStatement.computeIfAbsent(display.get(r.getSubjectId()).name() + " " + r.getPredicate() + " "
                        + display.get(r.getObjectId()).name(), k -> new HashSet<>()).add(r.getDocumentId());
                documentUse.merge(r.getDocumentId(), 1, Integer::sum);
            }
            List<String> statements = byStatement.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, Set<UUID>> e) -> e.getValue().size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).limit(6).toList();
            List<UUID> documents = documentUse.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().toString()))
                    .map(Map.Entry::getKey).limit(5).toList();
            out.add(new GraphTheme(central.stream().map(id -> display.get(id).name()).toList(), statements,
                    group.size(), documents));
        }
        return out;
    }

    /**
     * Whether a relation holds NOW and is a state, not an event: not ended (validTo in the past) and
     * not a moment (occurredAt). A traversal answers "how do these connect", which a former assignee
     * or a status change three months ago does not; the timeline answers "what happened".
     */
    static boolean current(KgRelation r, Instant now) {
        return r.getOccurredAt() == null && (r.getValidTo() == null || r.getValidTo().isAfter(now));
    }

    /**
     * What happened to the things these terms name, oldest first, as the readable sources' METADATA
     * dates it: a state starting and ending (assigned to somebody, then somebody else), an event (a
     * status change), and everything a source stamped with a time. What a model read from text has
     * no date and is not here.
     */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(List<String> terms, List<UUID> connectorIds) {
        if (terms == null || terms.isEmpty() || connectorIds == null || connectorIds.isEmpty()) return List.of();
        Relevance none = Relevance.none();
        Map<UUID, KgEntity> seeds = new LinkedHashMap<>();
        for (String term : terms) {
            withSynonyms(seedsFor(term, connectorIds, none, List.of()), connectorIds).forEach(e -> seeds.putIfAbsent(e.getId(), e));
        }
        if (seeds.isEmpty()) return List.of();
        // Dated rows only, filtered in the query rather than after its page (graph-09).
        List<KgRelation> rows = relations.findDatedTouchingAnyIn(seeds.keySet(), connectorIds,
                PageRequest.of(0, EDGE_ROWS_PER_HOP));
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { ids.add(r.getSubjectId()); ids.add(r.getObjectId()); });
        Map<UUID, Display> display = displayOf(ids, connectorIds);
        Map<UUID, KgEntity> named = new HashMap<>();
        entities.findAllById(ids).forEach(e -> named.put(e.getId(), e));
        java.util.function.Function<UUID, String> nameOf = id -> display.containsKey(id) ? display.get(id).name()
                : named.containsKey(id) ? named.get(id).getCanonicalName() : null;

        List<TimelineEntry> out = new ArrayList<>();
        for (KgRelation r : rows) {
            String subject = nameOf.apply(r.getSubjectId());
            String object = nameOf.apply(r.getObjectId());
            if (subject == null || object == null) continue;
            if (r.getOccurredAt() != null) {
                out.add(new TimelineEntry(r.getOccurredAt(), subject, r.getPredicate(), object,
                        TimelineEntry.Change.HAPPENED, r.getDocumentId()));
                continue;
            }
            if (r.getValidFrom() != null) {
                out.add(new TimelineEntry(r.getValidFrom(), subject, r.getPredicate(), object,
                        TimelineEntry.Change.STARTED, r.getDocumentId()));
            }
            if (r.getValidTo() != null) {
                out.add(new TimelineEntry(r.getValidTo(), subject, r.getPredicate(), object,
                        TimelineEntry.Change.ENDED, r.getDocumentId()));
            }
        }
        // At one instant, what ended before what started - a spell handed from one person to the
        // next reads in that order - and then by name, so the same rows always read the same way.
        out.sort(Comparator.comparing(TimelineEntry::at).thenComparing(TimelineEntry::predicate)
                .thenComparingInt(e -> e.change() == TimelineEntry.Change.ENDED ? 0 : e.change() == TimelineEntry.Change.STARTED ? 1 : 2)
                .thenComparing(TimelineEntry::subject).thenComparing(TimelineEntry::object));
        return out.stream().distinct().toList();
    }

    /** Whether a chunk reference names a passage of text - a chunk index - rather than the metadata. */
    static boolean isPassage(String chunkRef) {
        return chunkRef != null && !chunkRef.isEmpty() && chunkRef.chars().allMatch(Character::isDigit);
    }

    /** A LIKE pattern for a user-supplied term, escaped with '!' (see KgEntityRepository). */
    static String likePattern(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** What an entity is called, of what type, and what it is, as readable documents say. */
    record Display(String name, String type, String description) {}

    /**
     * What these entities are called, of what type and what they are - as the given sources wrote
     * them.
     *
     * <p>The most written surface form and the most written type, from mentions in those sources
     * only, and the newest description one of them gave. Not the entity's canonicalName or type:
     * those are whatever the first document ANYWHERE said, and on Enterprise Server that can be a
     * document the reader cannot open - a name or classification coined there would otherwise be
     * shown to everybody who reaches the same entity through their own documents.
     */
    Map<UUID, Display> displayOf(Collection<UUID> ids, Collection<UUID> connectorIds) {
        if (ids.isEmpty() || connectorIds.isEmpty()) return Map.of();
        Map<UUID, Map<String, Long>> names = new HashMap<>();
        Map<UUID, Map<String, Long>> types = new HashMap<>();
        for (Object[] row : mentions.namesIn(ids, connectorIds)) {
            UUID id = (UUID) row[0];
            long n = ((Number) row[3]).longValue();
            if (row[1] != null) names.computeIfAbsent(id, k -> new HashMap<>()).merge((String) row[1], n, Long::sum);
            if (row[2] != null && !((String) row[2]).isEmpty()) {
                types.computeIfAbsent(id, k -> new HashMap<>()).merge((String) row[2], n, Long::sum);
            }
        }
        Map<UUID, String> descriptions = new HashMap<>();
        for (Object[] row : mentions.latestDescriptionsIn(ids, connectorIds)) {
            descriptions.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        Map<UUID, Display> out = new HashMap<>();
        for (UUID id : ids) {
            String name = mostWritten(names.get(id));
            if (name == null) continue;
            out.put(id, new Display(name, mostWritten(types.get(id)), descriptions.get(id)));
        }
        return out;
    }

    // Most frequent; a tie goes to the shorter string, then the alphabetically first, so the same
    // data always reads the same.
    private static String mostWritten(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Long> e) -> e.getValue()).reversed()
                        .thenComparingInt(e -> e.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private GraphNeighbourhood assemble(Map<UUID, KgEntity> found, Map<UUID, Integer> hopOf,
                                        List<List<KgEntity>> groups, List<KgRelation> collected,
                                        List<UUID> connectorIds, Relevance relevance, boolean truncated) {
        List<UUID> ids = new ArrayList<>(found.keySet());
        Map<UUID, Display> display = displayOf(ids, connectorIds);
        java.util.function.Function<UUID, String> nameOf = id -> {
            Display d = display.get(id);
            return d != null ? d.name() : found.get(id).getCanonicalName();
        };

        Map<UUID, Long> documentCounts = new HashMap<>();
        Map<UUID, List<UUID>> docsByEntity = documentsOf(ids, connectorIds, relevance, documentCounts);

        List<GraphNeighbourhood.Entity> outEntities = found.values().stream()
                .map(e -> {
                    Display d = display.get(e.getId());
                    return new GraphNeighbourhood.Entity(e.getId(), nameOf.apply(e.getId()),
                            d != null ? d.type() : e.typeOrNull(), d != null ? d.description() : null,
                            hopOf.getOrDefault(e.getId(), 0), docsByEntity.getOrDefault(e.getId(), List.of()),
                            documentCounts.getOrDefault(e.getId(), 0L));
                })
                .sorted(Comparator.comparingInt(GraphNeighbourhood.Entity::hop)
                        .thenComparing(Comparator.comparingDouble((GraphNeighbourhood.Entity e) -> relevance.of(e.id())).reversed()))
                .toList();

        // ONE EDGE PER TRIPLE, its weight the number of distinct documents stating it. Rows are per
        // document (KgRelation.weight); the first row with a passage is the one cited. Only edges
        // whose BOTH ends survived - a half-visible edge would read "X depends on <a name the user
        // cannot see>".
        Map<String, List<KgRelation>> byTriple = new LinkedHashMap<>();
        for (KgRelation r : collected) {
            if (!hopOf.containsKey(r.getSubjectId()) || !hopOf.containsKey(r.getObjectId())) continue;
            byTriple.computeIfAbsent(r.getSubjectId() + "|" + r.getPredicate() + "|" + r.getObjectId(),
                    k -> new ArrayList<>()).add(r);
        }
        record Grouped(KgRelation first, List<UUID> docs, String description, KgRelation cited, int nearest,
                       double relevance, boolean fromMetadata) {}
        List<Grouped> grouped = new ArrayList<>();
        for (List<KgRelation> rows : byTriple.values()) {
            List<UUID> docs = rows.stream().map(KgRelation::getDocumentId).distinct().toList();
            String description = rows.stream().map(KgRelation::getDescription)
                    .filter(d -> d != null && !d.isBlank()).findFirst().orElse(null);
            // The passage to quote: the one most relevant to the question, else the first a model
            // read. A metadata edge has no passage.
            KgRelation cited = rows.stream().filter(r -> isPassage(r.getChunkRef()))
                    .max(Comparator.comparingDouble(r -> relevance.ofPassage(r.getDocumentId(), r.getChunkRef())))
                    .orElse(rows.get(0));
            KgRelation first = rows.get(0);
            int nearest = Math.min(hopOf.get(first.getSubjectId()), hopOf.get(first.getObjectId()));
            // An edge drawn from a passage relevant to the question first, then edges between
            // entities the question is about.
            double score = rows.stream().mapToDouble(r -> relevance.ofPassage(r.getDocumentId(), r.getChunkRef()))
                    .max().orElse(0) + 0.25 * (relevance.of(first.getSubjectId()) + relevance.of(first.getObjectId()));
            boolean fromMetadata = rows.stream().allMatch(r -> METADATA_REF.equals(r.getChunkRef()));
            grouped.add(new Grouped(first, docs, description, cited, nearest, score, fromMetadata));
        }
        grouped.sort(Comparator.comparingInt(Grouped::nearest)
                .thenComparing(Comparator.comparingDouble(Grouped::relevance).reversed())
                .thenComparing(Comparator.comparingInt((Grouped g) -> g.docs().size()).reversed())
                .thenComparing(g -> nameOf.apply(g.first().getSubjectId()))
                .thenComparing(g -> g.first().getPredicate()));
        if (grouped.size() > MAX_EDGES) truncated = true;

        // The weight counts every document; the list names a few, the cited one first.
        List<GraphNeighbourhood.Edge> outEdges = grouped.stream().limit(MAX_EDGES)
                .map(g -> new GraphNeighbourhood.Edge(
                        nameOf.apply(g.first().getSubjectId()), g.first().getPredicate(),
                        nameOf.apply(g.first().getObjectId()), g.description(), g.docs().size(),
                        citedFirst(g.cited().getDocumentId(), g.docs()),
                        g.cited().getDocumentId(), isPassage(g.cited().getChunkRef()) ? g.cited().getChunkRef() : null,
                        g.fromMetadata()))
                .toList();

        List<GraphNeighbourhood.Path> paths = paths(groups, grouped.stream().limit(MAX_EDGES)
                .map(Grouped::first).toList(), nameOf);
        return new GraphNeighbourhood(outEntities, outEdges, paths, truncated);
    }

    /**
     * Each entity's documents - at most {@link #DOCUMENTS_PER_ENTITY}: the ones the question is about
     * first, then those mentioning it most - with how many there are in all put in {@code counts}.
     *
     * <p>An entity few documents mention has them all read in one query. A hub - and metadata makes
     * every assignee, component and label one - is asked for its top few alone, so neither the
     * answer nor the query grows with the corpus: every document id of a hub went into one list,
     * then into the tool's title lookup as one bind parameter each.
     */
    private Map<UUID, List<UUID>> documentsOf(List<UUID> ids, List<UUID> connectorIds, Relevance relevance,
                                              Map<UUID, Long> counts) {
        if (ids.isEmpty()) return Map.of();
        for (Object[] row : mentions.documentCountsFor(ids, connectorIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        List<UUID> few = ids.stream()
                .filter(id -> counts.containsKey(id) && counts.get(id) <= DOCUMENTS_PER_ENTITY).toList();
        Map<UUID, List<UUID>> all = new HashMap<>();
        if (!few.isEmpty()) {
            for (Object[] row : mentions.documentIdsFor(few, connectorIds)) {
                all.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((UUID) row[1]);
            }
        }
        Map<UUID, List<UUID>> out = new HashMap<>();
        for (UUID id : ids) {
            Long n = counts.get(id);
            if (n == null) continue;
            LinkedHashSet<UUID> listed = new LinkedHashSet<>(relevance.documentsAbout(id));
            if (n <= DOCUMENTS_PER_ENTITY) {
                listed.addAll(all.getOrDefault(id, List.of()));
            } else {
                listed.addAll(mentions.topDocumentsFor(id, connectorIds, PageRequest.of(0, DOCUMENTS_PER_ENTITY)));
            }
            out.put(id, listed.stream().limit(DOCUMENTS_PER_ENTITY).toList());
        }
        return out;
    }

    // An edge's documents with the one its passage is cited from first, at most DOCUMENTS_PER_EDGE.
    private static List<UUID> citedFirst(UUID cited, List<UUID> documents) {
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
        if (cited != null) ordered.add(cited);
        ordered.addAll(documents);
        return ordered.stream().limit(DOCUMENTS_PER_EDGE).toList();
    }

    /**
     * How the names asked about connect to EACH OTHER - "how is X related to Y", the question a
     * graph answers that search cannot. Simple paths of at most {@link #MAX_PATH_LENGTH} edges
     * between two seeds, through the edges already found, shortest first. An edge may be walked
     * against its direction - "A owns B" connects B to A - and each step keeps the direction it was
     * stated in.
     *
     * <p>EACH PAIR IS SEARCHED SHORTEST FIRST, with a cap of its own. One depth-first walk shared one
     * cap across every pair: the 2- and 3-step paths it met first could fill the cap before the
     * direct edge listed after them was ever looked at, and with three names the first pair could
     * use the whole cap so the others reported no path at all.
     */
    private List<GraphNeighbourhood.Path> paths(List<List<KgEntity>> groups, List<KgRelation> edges,
                                                java.util.function.Function<UUID, String> nameOf) {
        if (groups.size() < 2 || edges.isEmpty()) return List.of();
        Map<UUID, List<KgRelation>> adjacent = new HashMap<>();
        for (KgRelation r : edges) {
            adjacent.computeIfAbsent(r.getSubjectId(), k -> new ArrayList<>()).add(r);
            adjacent.computeIfAbsent(r.getObjectId(), k -> new ArrayList<>()).add(r);
        }
        List<List<KgRelation>> found = new ArrayList<>();
        List<List<KgEntity>> ends = groups.stream().limit(6).toList();
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                for (KgEntity from : ends.get(i)) {
                    for (KgEntity to : ends.get(j)) {
                        if (from.getId().equals(to.getId())) continue;
                        found.addAll(pathsBetween(from.getId(), to.getId(), adjacent));
                    }
                }
            }
        }
        return found.stream()
                .sorted(Comparator.comparingInt(List::size))
                .limit(MAX_PATHS)
                .map(path -> new GraphNeighbourhood.Path(path.stream()
                        .map(r -> new GraphNeighbourhood.Step(nameOf.apply(r.getSubjectId()), r.getPredicate(),
                                nameOf.apply(r.getObjectId())))
                        .toList()))
                .toList();
    }

    // One pair's paths, one length at a time from a single edge up, until MAX_PATHS are found.
    static List<List<KgRelation>> pathsBetween(UUID from, UUID to, Map<UUID, List<KgRelation>> adjacent) {
        List<List<KgRelation>> out = new ArrayList<>();
        int[] budget = {PATH_STEPS_PER_PAIR};
        for (int length = 1; length <= MAX_PATH_LENGTH && out.size() < MAX_PATHS && budget[0] > 0; length++) {
            walk(from, to, length, adjacent, new ArrayDeque<>(), new LinkedHashSet<>(List.of(from)), out, budget);
        }
        return out;
    }

    // Every simple path of exactly `length` edges from `at` to `target`, while the pair's cap and
    // step budget last.
    private static void walk(UUID at, UUID target, int length, Map<UUID, List<KgRelation>> adjacent,
                             Deque<KgRelation> path, Set<UUID> visited, List<List<KgRelation>> out, int[] budget) {
        for (KgRelation r : adjacent.getOrDefault(at, List.of())) {
            if (out.size() >= MAX_PATHS || --budget[0] <= 0) return;
            UUID next = r.getSubjectId().equals(at) ? r.getObjectId() : r.getSubjectId();
            if (visited.contains(next)) continue;
            path.addLast(r);
            if (path.size() == length) {
                if (next.equals(target)) out.add(List.copyOf(path));
            } else if (!next.equals(target)) {
                visited.add(next);
                walk(next, target, length, adjacent, path, visited, out, budget);
                visited.remove(next);
            }
            path.removeLast();
        }
    }
}
