package ai.proxyma.core.search.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.search.domain.SearchSpend;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);

    // Reranking prompts are short (a query plus an 800-char snippet) - 30s is generous for a
    // healthy provider and bounds how long one hung/slow candidate can block the whole rerank,
    // which previously had no timeout at all and no way for a Stop request to interrupt it. Also
    // used to bound the embedding path's (batched) calls below.
    private static final int SCORE_TIMEOUT_SECONDS = 30;
    // How many candidates in a row may go unscored before the prompt tier gives up on the rest.
    private static final int UNANSWERED_LIMIT = 3;

    private static final String SYSTEM_PROMPT =
            "You are a relevance scoring assistant. Given a search query and a document chunk, " +
            "rate how relevant the chunk is to the query on a scale of 0 to 10, where:\n" +
            "  10 = perfectly answers the query\n" +
            "   5 = partially relevant\n" +
            "   0 = completely irrelevant\n\n" +
            "Respond with ONLY a single integer between 0 and 10. No explanation.";

    record ScoredDoc(Document doc, double score) {}

    // One prompt-scoring call's outcome: the score AND what the call cost, so scoreByLlm() can sum
    // the usage across its loop. The two have to travel together - a score with its usage dropped
    // somewhere along the way is exactly the silent under-reporting this tier had.
    private record LlmScore(double score, TokenUsage usage, boolean answered) {}

    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final SearchLlmSpendPublisher spendPublisher;

    public RerankingService(LlmGateway llmGateway, SettingsService settingsService,
                            SearchLlmSpendPublisher spendPublisher) {
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.spendPublisher = spendPublisher;
    }

    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;

        RerankingSettings settings = settingsService.getSettings(RerankingSettings.PREFIX, RerankingSettings.class);

        // ASK FOR A RANKING, DO NOT PICK A STRATEGY. Which capability answers - a native
        // /v1/rerank endpoint or embedding cosine - is a question about the configured provider,
        // and this service used to answer it itself: it read `nativeRerank` off the instance,
        // checked `protocol().maySupportNativeRerank()`, and built an embedding model straight off
        // the adapter when both said no. That is a search service holding an opinion about vLLM.
        //
        // An EMPTY result means the provider can rank in no way at all, which is when the tier
        // below takes over. That one stays here on purpose: it is a prompt, not a capability.
        String tier;
        List<ScoredDoc> scored;
        ProviderRanking provider = rankViaProvider(query, candidates);
        if (provider != null) {
            scored = provider.scored();
            tier = provider.tier();
        } else {
            // LLM-prompt "rate 0-10" scoring. Still the only tier whose spend is recorded: it goes
            // through a chat model, which reports token usage and has a pricing entry. The other
            // two report neither, so their cost stays unaccounted - unchanged by this refactor and
            // now fixable in one place rather than three.
            scored = scoreByLlm(query, candidates);
            tier = "llm";
        }
        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());

        List<Document> result = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            ScoredDoc sd = scored.get(i);
            sd.doc().getMetadata().put("rerankScore", sd.score());
            result.add(sd.doc());
        }
        log.debug("[Reranking] ({}) query='{}' candidates={} -> top {}", tier, query, candidates.size(), result.size());
        return result;
    }

    /** What a provider-side ranking produced, plus which capability produced it. */
    private record ProviderRanking(List<ScoredDoc> scored, String tier) {}

    /**
     * Ask the gateway to rank, and map its answer back onto the candidate documents.
     *
     * <p>Returns null when the provider can rank in no way at all, which is the signal to fall
     * back to prompt scoring. Everything provider-shaped that used to be decided here - native
     * endpoint versus embedding cosine - now happens behind LlmGateway.rerank().
     *
     * <p>The abort check and the timeout stay on this side, deliberately: SearchContext is a
     * search concept, and SCORE_TIMEOUT_SECONDS is tuned to how long a search may stall rather
     * than to what a provider considers slow.
     */
    private ProviderRanking rankViaProvider(String query, List<Document> candidates) {
        // Resolved here, on the search's thread - the call itself runs on a pool thread.
        LlmCaller caller = LlmCaller.of(LlmRole.RERANK, SearchSpend.subject());
        if (SearchContext.isAborted()) {
            log.info("[Reranking] abort detected, skipping provider ranking for {} candidate(s)", candidates.size());
            return new ProviderRanking(fallbackScores(candidates), "aborted");
        }
        List<String> texts = candidates.stream().map(Document::getText).toList();
        LlmGateway.Ranking ranking;
        try {
            ranking = CompletableFuture
                    .supplyAsync(() -> llmGateway.rerank(caller, query, texts), BlockingWork.executor())
                    .get(SCORE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[Reranking] provider ranking failed, falling back to original ranking: {}", e.getMessage());
            return new ProviderRanking(fallbackScores(candidates), "fallback");
        }
        if (ranking == null) return null;

        List<ScoredDoc> scored = new ArrayList<>(candidates.size());
        for (LlmGateway.Ranked r : ranking.ranked()) {
            if (r.index() < 0 || r.index() >= candidates.size()) continue;
            scored.add(new ScoredDoc(candidates.get(r.index()), r.score()));
        }
        // A native endpoint can return fewer results than candidates (a server-side top_n) -
        // backfill anything missing rather than silently dropping it before topK truncation. BELOW
        // everything the endpoint returned, since leaving them out is its verdict on them, and in
        // retrieval order among themselves (the sort is stable). They were backfilled with their
        // raw retriever score, which on a 0-1 reranker put every keyword-only chunk - an unbounded
        // Lucene score - above every chunk the reranker had chosen.
        if (scored.size() < candidates.size()) {
            double floor = Math.min(0.0, scored.stream().mapToDouble(ScoredDoc::score).min().orElse(0.0));
            Set<Document> covered = scored.stream().map(ScoredDoc::doc).collect(Collectors.toSet());
            for (Document doc : candidates) {
                if (!covered.contains(doc)) scored.add(new ScoredDoc(doc, floor));
            }
        }
        return new ProviderRanking(scored, ranking.strategy());
    }
    private List<ScoredDoc> scoreByLlm(String query, List<Document> candidates) {
        List<ScoredDoc> scored = new ArrayList<>(candidates.size());
        // Summed across the whole loop and published once at the end rather than per candidate:
        // this tier makes one LLM call PER CANDIDATE, so a 40-candidate rerank would otherwise fire
        // 40 spend events and 40 conversation-row updates to account for one search. It is also the
        // reason this tier needs accounting at all - it can easily cost more than answering with
        // the results does.
        TokenUsage totalUsage = TokenUsage.EMPTY;
        // Checked once per candidate rather than only at the top of rerank() - a Stop request can
        // arrive at any point during this loop (each scoreDocument() call is itself bounded by
        // SCORE_TIMEOUT_SECONDS, but without this check the loop would still march through every
        // remaining candidate one timeout at a time before ever noticing the abort).
        boolean aborted = false;
        // A model that has answered nothing to its first few prompts will answer nothing to the
        // rest: one that cannot chat, or one whose provider is down. The loop is sequential, so
        // without this a search waited out every remaining candidate to learn the same thing.
        int unanswered = 0;
        boolean anyAnswered = false;
        for (int i = 0; i < candidates.size(); i++) {
            Document doc = candidates.get(i);
            double unscored = LLM_SCALE * rankScore(i, candidates.size());
            if (!aborted && SearchContext.isAborted()) {
                aborted = true;
                log.info("[Reranking] abort detected, skipping LLM scoring for {} remaining candidate(s)",
                        candidates.size() - scored.size());
            }
            if (!aborted && unanswered >= UNANSWERED_LIMIT) {
                aborted = true;
                log.warn("[Reranking] the reranking model gave no score for {} candidates in a row - "
                        + "leaving the other {} in retrieval order", unanswered, candidates.size() - scored.size());
            }
            if (aborted) {
                scored.add(new ScoredDoc(doc, unscored));
                continue;
            }
            LlmScore result = scoreDocument(query, doc, unscored);
            unanswered = result.answered() ? 0 : unanswered + 1;
            anyAnswered |= result.answered();
            totalUsage = totalUsage.plus(result.usage());
            scored.add(new ScoredDoc(doc, result.score()));
        }
        // Scores from a model that never answered are not a ranking. Retrieval order is the honest
        // result.
        if (!anyAnswered && unanswered >= UNANSWERED_LIMIT) scored = fallbackScores(candidates);
        // Published even when the loop aborted part-way - the candidates it did score were billed.
        // One publish for the whole loop, so the binding is asked for once rather than per candidate.
        final TokenUsage loopUsage = totalUsage;
        llmGateway.binding(LlmRole.RERANK).ifPresent(b ->
                spendPublisher.publish("reranking", b.provider(), b.model(), b.dedicated(), loopUsage));
        return scored;
    }

    /** The prompt tier's scale: the model is asked for 0 to 10. */
    private static final double LLM_SCALE = 10.0;

    /**
     * Where a candidate stands in what retrieval handed over - the fused (RRF) order - on a 0-1
     * scale, 1 for the first. What a candidate no model scored is ranked by, so a fallback keeps
     * retrieval's order. It used to be the candidate's RAW retriever score: a cosine similarity for
     * a dense hit and an unbounded Lucene score for a keyword-only one, so when the reranker timed
     * out every keyword-only chunk outranked every dense chunk, whatever fusion had said.
     */
    static double rankScore(int index, int size) {
        return size <= 0 ? 0.0 : (size - index) / (double) size;
    }

    // Retrieval order, for when no model scored anything: Stop, a provider that failed or timed out.
    static List<ScoredDoc> fallbackScores(List<Document> candidates) {
        List<ScoredDoc> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new ScoredDoc(candidates.get(i), rankScore(i, candidates.size())));
        }
        return scored;
    }

    // `unscored` is what the candidate is worth on this tier's scale when no usable score comes back.
    private LlmScore scoreDocument(String query, Document doc, double unscored) {
        LlmCaller caller = LlmCaller.of(LlmRole.RERANK, SearchSpend.subject());
        try {
            String text = doc.getText();
            if (text == null || text.isBlank()) return new LlmScore(0.0, TokenUsage.EMPTY, true);
            String snippet = text.length() > 800 ? text.substring(0, 800) + "..." : text;
            String userMsg = "Query: " + query + "\n\nDocument chunk:\n" + snippet + "\n\nRelevance score (0-10):";
            List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userMsg));
            // chatModel.call() has no built-in timeout - wrapping it in a bounded future stops
            // THIS loop from waiting on a hung/slow provider indefinitely (it doesn't cancel the
            // underlying HTTP call itself, but that's fine here: nothing downstream ever reads
            // its result once we've moved on).
            LlmGateway.LlmResult result = CompletableFuture
                    .supplyAsync(() -> llmGateway.chat(caller, messages), BlockingWork.executor())
                    .get(SCORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String reply = result.text() == null ? "" : result.text().trim();
            return new LlmScore(parseScore(reply, unscored), result.usage(), !reply.isEmpty());
        } catch (Exception e) {
            log.debug("[Reranking] score failed: {}", e.getMessage());
            // A call that threw AFTER the provider answered (a malformed reply, a timeout on our
            // side of an answered request) was still billed, but there is no response here to read
            // usage from - unavoidable, and the same shape of gap a failed main-flow call has.
            return new LlmScore(unscored, TokenUsage.EMPTY, false);
        }
    }

    private double parseScore(String raw, double unscored) {
        String cleaned = raw.replaceAll("[^0-9]", " ").trim();
        if (cleaned.isEmpty()) return unscored;
        try {
            int score = Integer.parseInt(cleaned.split("\\s+")[0]);
            return Math.max(0, Math.min(10, score));
        } catch (NumberFormatException e) {
            return unscored;
        }
    }
}
