package ai.proxyma.core.search.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.GraphPassageResolver;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.settings.dto.EvaluationSettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The knowledge graph as a third list of hybrid search: off unless switched on, and when on, a
 * passage only the graph found joins the results while one the vectors also found is fused with
 * that entry rather than listed twice.
 */
class GraphChannelFusionTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final String COLLECTION = SourceCollections.of(SOURCE);

    private VectorStoreService vectors;
    private SettingsService settings;
    private GraphSearchChannel channel;
    private SearchService search;
    private final GraphSettings graph = new GraphSettings();

    @BeforeEach
    void setUp() {
        vectors = mock(VectorStoreService.class);
        settings = mock(SettingsService.class);
        channel = mock(GraphSearchChannel.class);
        when(settings.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class)).thenReturn(new QueryExpansionSettings());
        when(settings.getSettings(RerankingSettings.PREFIX, RerankingSettings.class)).thenReturn(new RerankingSettings());
        when(settings.getSettings(EvaluationSettings.PREFIX, EvaluationSettings.class)).thenReturn(new EvaluationSettings());
        when(settings.getSettings(GraphSettings.PREFIX, GraphSettings.class)).thenReturn(graph);
        when(vectors.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                Document.builder().id("dense-1").text("Ana leads the Payments team.")
                        .metadata(Map.of("source", "onboarding.md", ChunkMetadata.COLLECTION, COLLECTION))
                        .score(0.8).build()));
        UUID onboarding = UUID.randomUUID();
        UUID runbook = UUID.randomUUID();
        when(channel.rank(any(), anyString(), anyInt())).thenReturn(List.of(
                new GraphPassageResolver.Resolved(onboarding, "1", SOURCE, "onboarding.md", "Ana leads the Payments team.", null, "files", 0.9),
                new GraphPassageResolver.Resolved(runbook, "0", SOURCE, "runbook.md", "Payments owns the Ledger.", null, "files", 0.5)));
        search = new SearchService(vectors, mock(BM25IndexService.class), mock(QueryExpansionService.class),
                mock(RerankingService.class), settings, mock(ConnectorService.class), mock(LlmGateway.class),
                mock(ApplicationEventPublisher.class), mock(TelemetryShipper.class));
        search.setGraphChannel(channel);
    }

    @Test
    @DisplayName("off by default: the graph is not asked, and the results are the vectors' and keywords'")
    void offByDefault() {
        List<Document> results = search.search(List.of(COLLECTION), "who leads payments", 5, 0.3);

        verify(channel, never()).rank(any(), anyString(), anyInt());
        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("on: a passage only the graph found joins, and one the vectors found too is fused, not repeated")
    void onFusesTheGraphList() {
        graph.setSearchChannel(true);

        List<Document> results = search.search(List.of(COLLECTION), "who leads payments", 5, 0.3);

        assertEquals(2, results.size(), results.toString());
        assertTrue(results.stream().anyMatch(d -> d.getText().equals("Payments owns the Ledger.")));
        assertEquals(1, results.stream().filter(d -> d.getText().equals("Ana leads the Payments team.")).count());
        verify(channel).rank(eq(List.of(COLLECTION)), eq("who leads payments"), anyInt());
    }

    @Test
    @DisplayName("a caller that asked for retrieval only never pays for the walk")
    void retrievalOnlySkipsIt() {
        graph.setSearchChannel(true);

        search.search(List.of(COLLECTION), "who leads payments", 5, 0.3, SearchService.SearchOptions.retrievalOnly());

        verify(channel, never()).rank(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("collections are read back as the sources they belong to; others add nothing")
    void sourcesOfCollections() {
        UUID id = UUID.randomUUID();
        String hex = id.toString().replace("-", "");
        assertEquals(List.of(id), GraphSearchChannel.sourcesOf(List.of(hex, hex + "_reindex", "guardrailpolicy")));
        assertFalse(GraphSearchChannel.sourcesOf(List.of("agentmemory")).iterator().hasNext());
    }
}
