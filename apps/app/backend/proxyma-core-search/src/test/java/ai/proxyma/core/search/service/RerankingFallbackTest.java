package ai.proxyma.core.search.service;

import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What reranking does when the model behind it cannot do the job.
 *
 * <p>A reranker checkpoint that returned no embeddings sent every search to the prompt tier: one
 * chat request per candidate, forty of them in turn, to a model that answers a prompt with nothing
 * (0.23.0 feedback log, 2026-10-02). Neither failure may cost a search more than a few calls, and
 * both leave the candidates in the order retrieval gave them.
 */
class RerankingFallbackTest {

    private LlmGateway gateway;
    private RerankingService reranking;
    private List<Document> candidates;

    @BeforeEach
    void setUp() {
        gateway = mock(LlmGateway.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(RerankingSettings.PREFIX), eq(RerankingSettings.class)))
                .thenReturn(new RerankingSettings());
        when(gateway.binding(LlmRole.RERANK)).thenReturn(Optional.empty());
        reranking = new RerankingService(gateway, settings, mock(SearchLlmSpendPublisher.class));

        candidates = new ArrayList<>();
        // Retrieval order: the first is the best match, each next one a little worse.
        for (int i = 0; i < 12; i++) {
            candidates.add(Document.builder().id("doc-" + i).text("chunk " + i).score(0.9 - i * 0.05).build());
        }
    }

    private static LlmGateway.LlmResult reply(String text) {
        ChatResponse response = text == null
                ? new ChatResponse(List.of())
                : new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new LlmGateway.LlmResult(response, "p", "m", true);
    }

    private List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    @DisplayName("a reranker that fails is not asked to score by prompt - retrieval order stands")
    void aFailingRerankerIsNotPrompted() {
        when(gateway.rerank(any(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("The reranking model did not return embeddings"));

        List<Document> top = reranking.rerank("q", candidates, 3);

        assertEquals(List.of("doc-0", "doc-1", "doc-2"), ids(top));
        verify(gateway, never()).chat(any(), anyList());
    }

    @Test
    @DisplayName("a model that answers nothing is given up on after three candidates, and retrieval order stands")
    void aModelThatAnswersNothing() {
        when(gateway.rerank(any(), anyString(), anyList())).thenReturn(Optional.empty());
        when(gateway.chat(any(), anyList())).thenReturn(reply(null));

        List<Document> top = reranking.rerank("q", candidates, 3);

        verify(gateway, times(3)).chat(any(), anyList());
        assertEquals(List.of("doc-0", "doc-1", "doc-2"), ids(top));
    }

    @Test
    @DisplayName("a model that does answer scores every candidate, and its scores decide")
    void aModelThatAnswers() {
        when(gateway.rerank(any(), anyString(), anyList())).thenReturn(Optional.empty());
        // Everything is a 2, except the last candidate, which the model thinks is the answer.
        when(gateway.chat(any(), anyList())).thenAnswer(inv -> {
            String prompt = String.valueOf(inv.getArgument(1, List.class).get(1));
            return reply(prompt.contains("chunk 11") ? "10" : "2");
        });

        List<Document> top = reranking.rerank("q", candidates, 1);

        verify(gateway, times(12)).chat(any(), anyList());
        assertEquals(List.of("doc-11"), ids(top));
    }

    // ingest-08: candidates as fusion hands them over - dense hits scored 0-1 by cosine, keyword-only
    // hits by an unbounded Lucene score - with the fused order NOT the order of those raw scores.
    private static List<Document> mixedScales() {
        List<Document> fused = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean keywordOnly = i % 2 == 1;
            fused.add(Document.builder().id("fused-" + i).text("chunk " + i)
                    .score(keywordOnly ? 12.0 + i : 0.8 - i * 0.05).build());
        }
        return fused;
    }

    @Test
    @DisplayName("ingest-08: a reranker that times out leaves the fused order, not the raw retriever scores")
    void aFallbackKeepsTheFusedOrder() {
        when(gateway.rerank(any(), anyString(), anyList())).thenThrow(new IllegalStateException("timed out"));

        List<Document> top = reranking.rerank("q", mixedScales(), 6);

        assertEquals(List.of("fused-0", "fused-1", "fused-2", "fused-3", "fused-4", "fused-5"), ids(top));
    }

    @Test
    @DisplayName("ingest-08: what a native reranker left out of its top_n ranks below what it returned, in fused order")
    void aBackfillRanksBelowTheReranked() {
        // The reranker returns two of six, the fourth and the sixth, as its best.
        when(gateway.rerank(any(), anyString(), anyList())).thenReturn(Optional.of(new LlmGateway.Ranking(
                List.of(new LlmGateway.Ranked(5, 0.9), new LlmGateway.Ranked(3, 0.4)), "native")));

        List<Document> top = reranking.rerank("q", mixedScales(), 6);

        assertEquals(List.of("fused-5", "fused-3", "fused-0", "fused-1", "fused-2", "fused-4"), ids(top));
    }
}
