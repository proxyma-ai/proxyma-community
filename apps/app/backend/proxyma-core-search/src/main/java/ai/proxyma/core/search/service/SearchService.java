package ai.proxyma.core.search.service;

import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.domain.SearchSpend;
import ai.proxyma.core.search.event.SearchStageLatencyRecordedEvent;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int RRF_K = 60;
    private static final double MMR_LAMBDA = 0.7;
    private static final int RETRIEVAL_EXPANSION_MULTIPLIER = 10;
    private static final int RERANK_CANDIDATE_MULTIPLIER = 4;
    private static final int MIN_TOKEN_LENGTH = 2;

    /**
     * The most results one search returns, whoever asks - the REST API, the agent's search tool,
     * memory recall. Every stage scales with it: ten candidates per result from each retriever, four
     * per result through the reranker (one model call each on the prompt tier), and MMR selection,
     * which compares every candidate with every pick. A model asking for 1,000 results cost billions
     * of comparisons, and on pgvector anything past 50 failed outright (PgVectorStoreService).
     */
    public static final int MAX_TOP_K = 50;

    private final VectorStoreService vectorStoreService;
    private final BM25IndexService bm25IndexService;
    private final QueryExpansionService queryExpansionService;
    private final RerankingService rerankingService;
    private final SettingsService settingsService;
    private final ConnectorService connectorService;
    private final LlmGateway llmGateway;
    private final ApplicationEventPublisher eventPublisher;
    // Injected rather than reached through another event: SearchStageLatencyRecordedEvent is a
    // local measurement (System > Performance), and what leaves the building is decided here, in
    // one place, with only counts and durations in it.
    private final TelemetryShipper telemetryShipper;

    private GraphSearchChannel graphChannel;

    // Optional, so SearchService can be built by hand in a test with no graph behind it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGraphChannel(GraphSearchChannel graphChannel) {
        this.graphChannel = graphChannel;
    }

    public SearchService(VectorStoreService vectorStoreService,
                         BM25IndexService bm25IndexService,
                         QueryExpansionService queryExpansionService,
                         RerankingService rerankingService,
                         SettingsService settingsService,
                         ConnectorService connectorService,
                         LlmGateway llmGateway,
                         ApplicationEventPublisher eventPublisher,
                         TelemetryShipper telemetryShipper) {
        this.vectorStoreService = vectorStoreService;
        this.bm25IndexService = bm25IndexService;
        this.queryExpansionService = queryExpansionService;
        this.rerankingService = rerankingService;
        this.settingsService = settingsService;
        this.connectorService = connectorService;
        this.llmGateway = llmGateway;
        this.eventPublisher = eventPublisher;
        this.telemetryShipper = telemetryShipper;
    }

    /**
     * Which of the two OPTIONAL, model-backed stages a search may skip.
     *
     * <p>Both cost real provider calls. Query expansion is one completion per search, and the
     * prompt-scoring reranker is one per candidate - at the default topK that is roughly twenty
     * calls. For an interactive search paid for once per user question, that is a good trade. For
     * a caller that runs on EVERY turn automatically, it is not: the guardrail's policy retrieval
     * would multiply the cost of the whole product by it, silently, on a path nobody asked for.
     *
     * <p>{@link #deploymentDefault()} keeps the historical behaviour - read both from settings -
     * so the existing call sites are unchanged by this record existing.
     */
    public record SearchOptions(Boolean queryExpansion, Boolean reranking, Boolean graphChannel) {

        public SearchOptions(Boolean queryExpansion, Boolean reranking) {
            this(queryExpansion, reranking, null);
        }

        /** Both stages follow the deployment's own Settings, as every search did before. */
        public static SearchOptions deploymentDefault() {
            return new SearchOptions(null, null);
        }

        /** Dense + BM25 + RRF only. No completions at all beyond the embedding of the query. */
        public static SearchOptions retrievalOnly() {
            return new SearchOptions(false, false, false);
        }

        boolean queryExpansionOr(boolean configured) {
            return queryExpansion == null ? configured : queryExpansion;
        }

        boolean rerankingOr(boolean configured) {
            return reranking == null ? configured : reranking;
        }

        boolean graphChannelOr(boolean configured) {
            return graphChannel == null ? configured : graphChannel;
        }
    }

    public List<Document> search(List<String> collectionIds, String query, int topK, double minDenseScore) {
        return search(collectionIds, query, topK, minDenseScore, SearchOptions.deploymentDefault());
    }

    /**
     * Runs with the search's spend subject in force (see {@link SearchSpend}), so every model call
     * inside - expansion, reranking, and the query embedding the vector store makes through the
     * metered bean - is billed to the same person, however the search arrived.
     */
    public List<Document> search(List<String> collectionIds, String query, int topK, double minDenseScore,
                                 SearchOptions options) {
        SpendSubject subject = SearchSpend.subject();
        return SpendContext.with(subject,
                () -> searchAs(subject, collectionIds, query, topK, minDenseScore, options));
    }

    private List<Document> searchAs(SpendSubject subject, List<String> collectionIds, String query, int topK,
                                    double minDenseScore, SearchOptions options) {
        Assert.notEmpty(collectionIds, "collectionIds must not be empty");
        Assert.hasText(query, "query must not be empty");
        Assert.isTrue(topK > 0, "topK must be larger than zero");
        Assert.isTrue(minDenseScore > 0, "minDenseScore must be larger than zero");
        topK = Math.min(topK, MAX_TOP_K);

        boolean qeEnabled = options.queryExpansionOr(
                settingsService.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class).isEnabled());
        boolean rerankEnabled = options.rerankingOr(
                settingsService.getSettings(RerankingSettings.PREFIX, RerankingSettings.class).isEnabled());
        boolean graphEnabled = graphChannel != null && options.graphChannelOr(settingsService.getSettings(
                ai.proxyma.core.settings.dto.GraphSettings.PREFIX, ai.proxyma.core.settings.dto.GraphSettings.class).isSearchChannel());
        log.info("[Search] queryExpansion={} reranking={} graph={} collections={}", qeEnabled, rerankEnabled, graphEnabled,
                collectionIds.size());

        long t0 = System.nanoTime();
        int expansionCandidates = topK * RETRIEVAL_EXPANSION_MULTIPLIER;

        // Per-stage latency accumulators for System > Performance (see
        // SearchStageLatencyRecordedEvent) - denseMs/bm25Ms sum across every query variant's own
        // dense/BM25 call below, since a multi-variant expansion pass runs a full round of each per
        // variant.
        long expansionStart = System.nanoTime();
        List<String> queryVariants = qeEnabled ? queryExpansionService.expand(query) : List.of(query);
        long expansionMs = (System.nanoTime() - expansionStart) / 1_000_000;
        long denseNanos = 0;
        long bm25Nanos = 0;
        // Whether the dense half of this search failed. It is not the search's failure: the keyword
        // index (and the graph channel) answer without it, which is what the agent's memory recall
        // already did by hand. On pgvector an embedding outage used to take down every search,
        // the guardrail's per-turn policy retrieval with it; on ChromaDB it was swallowed in the
        // store and nobody was told.
        boolean denseFailed = false;

        // Connectors still mid model-changed reindex need an extra dense pass per distinct
        // (provider, old model) they were indexed with, so not-yet-replaced documents (still under
        // their original embedding, possibly a different dimension than the current global model)
        // stay searchable until that connector's backlog fully drains - see
        // ConnectorService.getInFlightReindexInfo(). On a mixed-dimension-capable store (Postgres),
        // those same collection ids also still belong in the normal current-model pass below (their
        // already-replaced documents are already under the new model); on a store that can't mix
        // dimensions in one collection (ChromaDB), the whole collection is still old-format until
        // the shadow swap, so it's excluded from the current-model pass entirely.
        List<ConnectorService.InFlightReindex> inFlightReindexes = connectorService.getInFlightReindexInfo(collectionIds);
        boolean mixedDimensionCapable = vectorStoreService.supportsMixedDimensionCollection();
        Set<String> reindexingIds = inFlightReindexes.stream()
                .flatMap(g -> g.collectionIds().stream())
                .collect(Collectors.toSet());
        List<String> currentModelCollectionIds = mixedDimensionCapable || reindexingIds.isEmpty()
                ? collectionIds
                : collectionIds.stream().filter(id -> !reindexingIds.contains(id)).toList();

        List<List<String>> denseRankedLists = new ArrayList<>();
        List<List<String>> bm25RankedLists = new ArrayList<>();
        Map<String, Document> docById = new LinkedHashMap<>();
        // The same chunk retrieved densely and via BM25 must fuse into ONE RRF candidate, or the
        // reciprocal ranks never combine and fusion degenerates into interleaving two lists. Dense
        // hits carry store-generated UUIDs and BM25 hits carry no ID at all, so identity has to be
        // re-derived from content: collection + source + chunk text. The COLLECTION too, because a
        // source names an item only within its collection - two SaaS sources both hold a
        // `ticket:1`, and two of their chunks with the same text (a templated reply, an empty
        // description) fused into one candidate that then answered for only one of them.
        Map<String, String> idByContentKey = new HashMap<>();

        for (String qv : queryVariants) {
            // Stop can otherwise go unnoticed for the rest of a multi-variant expansion pass
            // (each variant runs a full dense+BM25 round) - break out with whatever's already
            // been fused instead of running every remaining variant regardless, matching the
            // SearchContext.isAborted() convention RerankingService already uses.
            if (SearchContext.isAborted()) break;

            // ONE RANKED LIST PER DENSE PASS, not one list for all of them. A source mid model-
            // changed reindex is searched with its old model in a pass of its own, and appended
            // after the current-model pass it started below every other source's last hit, so it
            // lost to everything for the whole reindex. Scores from two models are not comparable
            // anyway; reciprocal ranks are.
            List<List<Document>> densePasses = new ArrayList<>();
            long denseStart = System.nanoTime();
            if (!currentModelCollectionIds.isEmpty() && !denseFailed) {
                try {
                    densePasses.add(vectorStoreService.search(currentModelCollectionIds, qv, expansionCandidates, minDenseScore));
                } catch (Exception e) {
                    denseFailed = true;
                    log.warn("[Search] Semantic search is unavailable, answering from the keyword index: {}",
                            e.getMessage());
                }
            }
            for (ConnectorService.InFlightReindex group : inFlightReindexes) {
                try {
                    // group.provider() is the provider INSTANCE id the collection was embedded
                    // with. An instance since deleted throws here and is caught below, which is
                    // right: the old collection simply cannot be searched with its original model.
                    // Through the gateway, METERED: this used to be built straight off the adapter,
                    // so every search during a re-index embedded its query off the books.
                    EmbeddingModel oldModel = llmGateway.embeddingModel(
                            LlmCaller.of(LlmRole.EMBEDDING, subject), group.provider(), group.model());
                    if (oldModel == null) {
                        throw new IllegalStateException("No configured provider with id " + group.provider());
                    }
                    densePasses.add(vectorStoreService.search(group.collectionIds(), qv, expansionCandidates, minDenseScore, oldModel));
                } catch (Exception e) {
                    log.warn("[Search] Old-model search pass failed for provider={} model={} collections={}: {}",
                            group.provider(), group.model(), group.collectionIds().size(), e.getMessage());
                }
            }
            denseNanos += System.nanoTime() - denseStart;
            for (List<Document> pass : densePasses) {
                List<String> denseIds = new ArrayList<>();
                for (Document d : pass) {
                    docById.putIfAbsent(d.getId(), d);
                    idByContentKey.putIfAbsent(contentKey(ChunkMetadata.collectionOf(d), d.getMetadata().get("source"),
                            d.getText()), d.getId());
                    denseIds.add(d.getId());
                }
                denseRankedLists.add(denseIds);
            }

            long bm25Start = System.nanoTime();
            List<BM25IndexService.BM25Result> bm25Results = bm25IndexService.search(collectionIds, qv, expansionCandidates);
            bm25Nanos += System.nanoTime() - bm25Start;
            List<String> bm25Ids = new ArrayList<>();
            for (BM25IndexService.BM25Result br : bm25Results) {
                String key = contentKey(br.collectionId(), br.source(), br.text());
                String docId = idByContentKey.get(key);
                if (docId == null) {
                    docId = "bm25:" + SourceCollections.baseOf(br.collectionId()) + ":" + br.source().hashCode()
                            + ":" + br.text().hashCode();
                    idByContentKey.put(key, docId);
                    if (!docById.containsKey(docId)) {
                        Map<String, Object> bm25Meta = new LinkedHashMap<>();
                        bm25Meta.put("source", br.source());
                        if (br.collectionId() != null) bm25Meta.put(ChunkMetadata.COLLECTION, br.collectionId());
                        if (br.url() != null && !br.url().isBlank()) bm25Meta.put("url", br.url());
                        if (br.connectorType() != null && !br.connectorType().isBlank())
                            bm25Meta.put("connectorType", br.connectorType());
                        Document bm25Doc = Document.builder()
                                .id(docId)
                                .text(br.text())
                                .metadata(bm25Meta)
                                .score((double) br.score())
                                .build();
                        docById.put(docId, bm25Doc);
                    }
                }
                bm25Ids.add(docId);
            }
            bm25RankedLists.add(bm25Ids);
        }

        // THE GRAPH CHANNEL, once for the query rather than per variant: passages ranked by how
        // central the entities they mention are to it (GraphSearchChannel). A passage the dense or
        // keyword list already found fuses with that entry by the same content key; one only the
        // graph found joins as its own. Off unless GraphSettings.searchChannel is on.
        long graphNanos = 0;
        List<String> graphIds = new ArrayList<>();
        if (graphEnabled) {
            long graphStart = System.nanoTime();
            for (ai.proxyma.core.registry.service.GraphPassageResolver.Resolved p
                    : graphChannel.rank(collectionIds, query, expansionCandidates)) {
                String collection = SourceCollections.of(p.connectorId());
                String key = contentKey(collection, p.sourceId(), p.text());
                String docId = idByContentKey.get(key);
                if (docId == null) {
                    docId = "graph:" + p.documentId() + ":" + p.chunkRef();
                    idByContentKey.put(key, docId);
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("source", p.sourceId());
                    meta.put(ChunkMetadata.COLLECTION, collection);
                    if (p.url() != null && !p.url().isBlank()) meta.put("url", p.url());
                    if (p.connectorType() != null) meta.put("connectorType", p.connectorType());
                    docById.putIfAbsent(docId, Document.builder().id(docId).text(p.text()).metadata(meta)
                            .score(p.score()).build());
                }
                if (!graphIds.contains(docId)) graphIds.add(docId);
            }
            graphNanos = System.nanoTime() - graphStart;
        }

        List<List<String>> allLists = new ArrayList<>();
        allLists.addAll(denseRankedLists);
        allLists.addAll(bm25RankedLists);
        if (!graphIds.isEmpty()) allLists.add(graphIds);
        Map<String, Double> rrfScores = computeRrf(allLists);
        List<Document> fusedDocs = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(expansionCandidates)
                .map(e -> { Document d = docById.get(e.getKey()); if (d != null) d.getMetadata().put("rrfScore", e.getValue()); return d; })
                .filter(Objects::nonNull).collect(Collectors.toList());

        int rerankCandidates = topK * RERANK_CANDIDATE_MULTIPLIER;
        List<Document> rerankInput = fusedDocs.size() > rerankCandidates ? fusedDocs.subList(0, rerankCandidates) : fusedDocs;
        long rerankStart = System.nanoTime();
        List<Document> rerankedDocs = rerankEnabled ? rerankingService.rerank(query, rerankInput, rerankCandidates) : rerankInput;
        long rerankMs = (System.nanoTime() - rerankStart) / 1_000_000;
        List<Document> results = mmrSelect(rerankedDocs, topK);
        normalizeScores(results);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("[Search] results={} ({}ms)", results.size(), totalMs);
        publishStageLatency(query, queryVariants.size(), expansionMs,
                denseNanos / 1_000_000, bm25Nanos / 1_000_000, rerankMs, totalMs, results.size(),
                graphEnabled, graphNanos / 1_000_000);
        // The same stage timings, always, into the daily diagnostics - labeled only by which
        // optional stages ran, since "search is slow" means nothing without knowing that.
        String expansionLabel = DiagLabels.onOff(qeEnabled);
        String rerankLabel = DiagLabels.onOff(rerankEnabled);
        if (qeEnabled) Diagnostics.time(DiagOp.SEARCH_EXPANSION, expansionMs);
        Diagnostics.time(DiagOp.SEARCH_DENSE, denseNanos / 1_000_000, "outcome", denseFailed ? "failed" : "ok");
        Diagnostics.time(DiagOp.SEARCH_BM25, bm25Nanos / 1_000_000);
        if (rerankEnabled) Diagnostics.time(DiagOp.SEARCH_RERANK, rerankMs);
        if (graphEnabled) Diagnostics.time(DiagOp.SEARCH_GRAPH, graphNanos / 1_000_000,
                "found", DiagLabels.onOff(!graphIds.isEmpty()));
        Diagnostics.time(DiagOp.SEARCH_TOTAL, totalMs, "expansion", expansionLabel, "rerank", rerankLabel,
                "graph", DiagLabels.onOff(graphEnabled));
        // Counts and durations only. queryLength, never the query - the same line
        // SearchStageLatencyRecordedEvent draws, and for a stronger reason: this one leaves the
        // building. Both toggles are here because "search is slow" is unanswerable without
        // knowing whether expansion and reranking were even running.
        telemetryShipper.record(TelemetryEventType.SEARCH, Map.of(
                "queryLength", query.length(),
                "variantCount", queryVariants.size(),
                "resultCount", results.size(),
                "totalMs", totalMs,
                "queryExpansion", qeEnabled,
                "reranking", rerankEnabled));
        return results;
    }

    // Publishes SearchStageLatencyRecordedEvent for every call - both standalone /api/v1/search
    // requests and chat-driven searches, since both paths call this same search() method. ALWAYS,
    // since 2026-10-14: it still waited for the evaluation.enabled toggle after chat turn latency
    // stopped doing so, and nothing sets that toggle any more, so System > Performance counted
    // chat turns and never a single search.
    private void publishStageLatency(String query, int variantCount, long expansionMs,
                                     long denseMs, long bm25Ms, long rerankMs, long totalMs, int resultCount,
                                     boolean graphChannel, long graphMs) {
        eventPublisher.publishEvent(new SearchStageLatencyRecordedEvent(
                query.length(), variantCount, expansionMs, denseMs, bm25Ms, rerankMs, totalMs, resultCount,
                graphChannel, graphMs));
    }

    // Reports the same basis the results were ranked on (see relevanceOf), so the score a caller
    // sees explains the order it sees. This used to read rrfScore unconditionally, which meant that
    // with reranking enabled the number surfaced through /api/v1/search and the MCP search tool came
    // from a different scorer than the one that ordered the list - the two disagreed and neither
    // caller had any way to tell. Order can still deviate slightly from a pure descending sort,
    // since mmrSelect() trades some relevance for diversity; the score is the relevance component.
    private void normalizeScores(List<Document> docs) {
        if (docs.isEmpty()) return;
        double max = docs.stream().mapToDouble(this::relevanceOf).max().orElse(1.0);
        if (max <= 0) return;
        for (Document d : docs) {
            d.getMetadata().put("normalizedScore", Math.round((relevanceOf(d) / max) * 100.0) / 100.0);
        }
    }

    private Map<String, Double> computeRrf(List<List<String>> rankedLists) {
        Map<String, Double> scores = new HashMap<>();
        for (List<String> ranked : rankedLists) for (int i = 0; i < ranked.size(); i++) scores.merge(ranked.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        return scores;
    }

    private List<Document> mmrSelect(List<Document> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        List<Document> selected = new ArrayList<>(), remaining = new ArrayList<>(candidates);
        Map<Document, Set<String>> ws = new IdentityHashMap<>();
        for (Document d : candidates) ws.put(d, tokenise(d.getText()));
        Map<Document, Double> rel = normalizedRelevance(candidates);
        while (selected.size() < topK && !remaining.isEmpty()) {
            Document best = null; double bestMmr = Double.NEGATIVE_INFINITY;
            for (Document c : remaining) {
                double maxSim = selected.stream().mapToDouble(s -> jaccard(ws.get(c), ws.get(s))).max().orElse(0.0);
                double mmr = MMR_LAMBDA * rel.get(c) - (1 - MMR_LAMBDA) * maxSim;
                if (mmr > bestMmr) { bestMmr = mmr; best = c; }
            }
            if (best != null) { selected.add(best); remaining.remove(best); } else break;
        }
        return selected;
    }

    // Relevance rescaled to 0-1 across the candidate set, so MMR_LAMBDA actually balances the two
    // terms whatever produced the underlying numbers. Raw values were fed in before, and they are
    // not on comparable scales: an RRF score is a sum of 1/(RRF_K + rank) terms, so of order 0.03,
    // set against a Jaccard diversity penalty of up to 1.0 - the penalty outweighed relevance by
    // more than an order of magnitude and selection was driven almost entirely by novelty rather
    // than by how well a chunk matched the query. rerankScore has its own scales again (0-1 from a
    // native cross-encoder, 0-10 from the embedding and LLM tiers) and would have tilted it just as
    // hard the other way. Dividing by the maximum keeps each scorer's own sense of relative
    // confidence intact, rather than stretching small genuine differences across the full range.
    private Map<Document, Double> normalizedRelevance(List<Document> candidates) {
        Map<Document, Double> rel = new IdentityHashMap<>();
        double max = candidates.stream().mapToDouble(this::relevanceOf).max().orElse(0.0);
        for (Document d : candidates) rel.put(d, max > 0 ? relevanceOf(d) / max : 0.0);
        return rel;
    }

    // What both MMR selection and the reported score rank on: the reranker's own verdict when
    // reranking ran, otherwise the RRF fusion score. RerankingService stamps rerankScore on every
    // document it scores, but nothing read it before this - so with reranking enabled the pipeline
    // paid for a cross-encoder pass on topK * RERANK_CANDIDATE_MULTIPLIER candidates on every
    // search and then ordered by RRF regardless, discarding the result outright.
    private double relevanceOf(Document d) {
        Object r = d.getMetadata().get("rerankScore");
        if (r instanceof Number n) return n.doubleValue();
        return rrfScoreOf(d);
    }

    // A reindex shadow's chunk is its base source's (SourceCollections), so it keys as the base.
    private String contentKey(String collection, Object source, String text) {
        return SourceCollections.baseOf(collection) + "\u0000" + source + "\u0000" + (text == null ? "" : text);
    }

    private double rrfScoreOf(Document d) { Object r = d.getMetadata().get("rrfScore"); if (r instanceof Double v) return v; if (r instanceof Number n) return n.doubleValue(); return d.getScore() != null ? d.getScore() : 0.0; }
    private Set<String> tokenise(String t) { if (t == null || t.isBlank()) return Set.of(); return Arrays.stream(t.toLowerCase().split("[^a-z0-9]+")).filter(s -> s.length() > MIN_TOKEN_LENGTH).collect(Collectors.toSet()); }
    private double jaccard(Set<String> a, Set<String> b) { if (a.isEmpty() && b.isEmpty()) return 1.0; if (a.isEmpty() || b.isEmpty()) return 0.0; long i = a.stream().filter(b::contains).count(); long u = a.size() + b.size() - i; return u == 0 ? 0 : (double) i / u; }
}
