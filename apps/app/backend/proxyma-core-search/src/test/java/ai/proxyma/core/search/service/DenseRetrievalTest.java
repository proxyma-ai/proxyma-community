package ai.proxyma.core.search.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.search.event.SearchStageLatencyRecordedEvent;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dense half of hybrid search: what happens when it fails, how big it may be asked to be, and
 * how a source mid model-changed reindex ranks.
 */
class DenseRetrievalTest {

    private VectorStoreService vectors;
    private BM25IndexService keywords;
    private ConnectorService connectors;
    private LlmGateway gateway;
    private ApplicationEventPublisher events;
    private SearchService search;

    @BeforeEach
    void setUp() {
        vectors = mock(VectorStoreService.class);
        keywords = mock(BM25IndexService.class);
        connectors = mock(ConnectorService.class);
        gateway = mock(LlmGateway.class);
        events = mock(ApplicationEventPublisher.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class)).thenReturn(new QueryExpansionSettings());
        when(settings.getSettings(RerankingSettings.PREFIX, RerankingSettings.class)).thenReturn(new RerankingSettings());
        when(settings.getSettings(GraphSettings.PREFIX, GraphSettings.class)).thenReturn(new GraphSettings());
        search = new SearchService(vectors, keywords, mock(QueryExpansionService.class), mock(RerankingService.class),
                settings, connectors, gateway, events, mock(TelemetryShipper.class));
    }

    // Each passage a word of its own, so selection's diversity term treats every pair alike and
    // only rank decides.
    private static Document dense(String id, String source, double score) {
        return Document.builder().id(id).text(id.replace("-", "")).metadata(Map.of("source", source)).score(score).build();
    }

    @Test
    @DisplayName("ingest-20: an embedding outage leaves the keyword answers standing instead of failing the search")
    void aDenseFailureFallsBackToKeywords() {
        when(vectors.search(any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("embedding provider unreachable"));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(
                new BM25IndexService.BM25Result("runbook.md", "restart the ledger", 7.5f, null, "files", "c1")));

        List<Document> results = search.search(List.of("c1"), "ledger restart", 5, 0.3);

        assertEquals(1, results.size());
        assertEquals("restart the ledger", results.get(0).getText());
    }

    @Test
    @DisplayName("ingest-05/16: however many results are asked for, retrieval is asked for at most fifty's worth")
    void topKIsBounded() {
        when(vectors.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of());

        search.search(List.of("c1"), "anything", 1000, 0.3);

        verify(vectors).search(eq(List.of("c1")), eq("anything"), eq(SearchService.MAX_TOP_K * 10), anyDouble());
    }

    @Test
    @DisplayName("ingest-33: a source still on its old model ranks as a list of its own, not after every other source")
    void anOldModelPassIsItsOwnList() {
        when(connectors.getInFlightReindexInfo(anyList())).thenReturn(List.of(
                new ConnectorService.InFlightReindex("provider-1", "old-model", List.of("reindexing"))));
        when(gateway.embeddingModel(any(), eq("provider-1"), eq("old-model"))).thenReturn(mock(EmbeddingModel.class));
        List<Document> current = new ArrayList<>();
        for (int i = 0; i < 6; i++) current.add(dense("current-" + i, "current.md#" + i, 0.9 - i * 0.01));
        when(vectors.search(eq(List.of("live")), anyString(), anyInt(), anyDouble())).thenReturn(current);
        when(vectors.search(eq(List.of("reindexing")), anyString(), anyInt(), anyDouble(), any(EmbeddingModel.class)))
                .thenReturn(List.of(dense("old-0", "old.md", 0.4)));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of());

        List<Document> results = search.search(List.of("live", "reindexing"), "q", 3, 0.3);

        // The old model's best match ties the current model's best: both first of their list.
        assertTrue(results.stream().map(Document::getId).toList().contains("old-0"), results.toString());
    }

    @Test
    @DisplayName("ingest-06: stage latency is published for every search, with no toggle")
    void latencyIsAlwaysPublished() {
        when(vectors.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of(dense("d", "a.md", 0.8)));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of());

        search.search(List.of("c1"), "q", 3, 0.3);

        verify(events).publishEvent(any(SearchStageLatencyRecordedEvent.class));
    }
}
