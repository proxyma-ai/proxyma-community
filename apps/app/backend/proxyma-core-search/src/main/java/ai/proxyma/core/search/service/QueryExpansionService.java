package ai.proxyma.core.search.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.search.domain.SearchSpend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class QueryExpansionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExpansionService.class);
    private static final int DEFAULT_VARIANTS = 3;
    private static final int MAX_QUERY_WORDS = 15;
    // chatModel.call() has no built-in timeout - a single unbounded call here previously had no
    // way to notice a Stop request or ever give up on a hung provider. One call per search, so no
    // per-iteration abort check is needed (unlike RerankingService's loop) - this bound alone is
    // enough to keep a slow/hung provider from blocking the whole search indefinitely.
    private static final int EXPANSION_TIMEOUT_SECONDS = 30;

    private final LlmGateway llmGateway;
    private final SearchLlmSpendPublisher spendPublisher;

    public QueryExpansionService(LlmGateway llmGateway,
                                 SearchLlmSpendPublisher spendPublisher) {
        this.llmGateway = llmGateway;
        this.spendPublisher = spendPublisher;
    }

    public List<String> expand(String query) {
        if (query == null || query.isBlank()) return List.of();
        // Doubled backslash: "\s" alone is the escape for a space, so tabs and newlines did not
        // separate words and a pasted multi-line query was never counted as long.
        int wordCount = query.trim().split("\\s+").length;
        if (wordCount >= MAX_QUERY_WORDS) {
            log.debug("[QueryExpansion] skipped - query has {} words (max={})", wordCount, MAX_QUERY_WORDS);
            return List.of(query);
        }
        try {
            int n = DEFAULT_VARIANTS;
            String system = "You are a search query specialist. " +
                    "Given a search query, generate exactly " + n + " alternative queries that " +
                    "rephrase or approach the same information need using different technical terms, " +
                    "synonyms, or angles. Output ONLY the queries, one per line, with no numbering, " +
                    "labels, or explanations.";
            String user = "Original query: " + query + "\n\nGenerate " + n + " alternative queries:";

            List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
            // Resolved here, on the search's thread - the call itself runs on a pool thread.
            LlmCaller caller = LlmCaller.of(LlmRole.QUERY_EXPANSION, SearchSpend.subject());
            // The timeout stays here: chat() has none of its own, and how long a SEARCH may stall
            // is a search decision rather than a provider one.
            LlmGateway.LlmResult result = CompletableFuture
                    .supplyAsync(() -> llmGateway.chat(caller, messages), BlockingWork.executor())
                    .get(EXPANSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // Published before the parsing below, which can still throw on a malformed reply: the
            // call was billed either way, and expansion silently swallows its own failures (see the
            // catch), so recording after parsing would drop exactly the spend nobody noticed.
            spendPublisher.publish("query-expansion", result.provider(), result.model(),
                    result.dedicatedModel(), result.usage());
            String raw = result.text().trim();

            List<String> variants = new ArrayList<>();
            variants.add(query);
            for (String line : raw.split("\n")) {
                String trimmed = line.trim().replaceFirst("^[-*|\\d.]+\\s*", "");
                if (!trimmed.isBlank() && !trimmed.equalsIgnoreCase(query)) {
                    variants.add(trimmed);
                    if (variants.size() - 1 >= n) break;
                }
            }
            log.debug("[QueryExpansion] '{}' -> {} variants", query, variants.size() - 1);
            return variants;
        } catch (Exception e) {
            log.warn("[QueryExpansion] failed, falling back to original: {}", e.getMessage());
            return List.of(query);
        }
    }
}
