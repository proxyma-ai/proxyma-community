package ai.proxyma.core.search.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.DocumentKey;
import ai.proxyma.core.registry.service.SourceCollections;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hybrid search's fusion keys a chunk by the collection it was found in as well as its source and
 * text. An item's id is unique only within its source - a Zendesk and a Freshdesk source both hold
 * {@code ticket:1} - so keyed by source and text alone, the same templated reply in both fused into
 * ONE candidate, and one of the two tickets was never returned.
 */
class SourceIdentityFusionTest {

    private static final UUID ZENDESK = UUID.randomUUID();
    private static final UUID FRESHDESK = UUID.randomUUID();
    private static final String REPLY = "Thanks for reaching out, we are looking into it.";

    private VectorStoreService vectors;
    private BM25IndexService keywords;
    private SearchService search;

    @BeforeEach
    void setUp() {
        vectors = mock(VectorStoreService.class);
        keywords = mock(BM25IndexService.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class)).thenReturn(new QueryExpansionSettings());
        when(settings.getSettings(RerankingSettings.PREFIX, RerankingSettings.class)).thenReturn(new RerankingSettings());
        when(settings.getSettings(GraphSettings.PREFIX, GraphSettings.class)).thenReturn(new GraphSettings());
        search = new SearchService(vectors, keywords, mock(QueryExpansionService.class), mock(RerankingService.class),
                settings, mock(ConnectorService.class), mock(LlmGateway.class),
                mock(ApplicationEventPublisher.class), mock(TelemetryShipper.class));
    }

    private static Document dense(String id, String collection, double score) {
        return Document.builder().id(id).text(REPLY)
                .metadata(Map.of("source", "ticket:1", ChunkMetadata.COLLECTION, collection)).score(score).build();
    }

    private static BM25IndexService.BM25Result keyword(String collection) {
        return new BM25IndexService.BM25Result("ticket:1", REPLY, 4.2f, null, "zendesk", collection);
    }

    private static Set<UUID> sourcesOf(List<Document> results) {
        return results.stream().map(DocumentKey::of).map(DocumentKey::connectorId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("one item id and one text in two sources stay two candidates after fusion")
    void twoSourcesStayTwo() {
        String z = SourceCollections.of(ZENDESK);
        String f = SourceCollections.of(FRESHDESK);
        when(vectors.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(keyword(z), keyword(f)));

        List<Document> results = search.search(List.of(z, f), "looking into it", 5, 0.3);

        assertEquals(2, results.size(), results.toString());
        assertEquals(Set.of(ZENDESK, FRESHDESK), sourcesOf(results));
    }

    @Test
    @DisplayName("each source's dense hit fuses with its own keyword hit, not both with the first source's")
    void eachFusesWithItsOwn() {
        String z = SourceCollections.of(ZENDESK);
        String f = SourceCollections.of(FRESHDESK);
        when(vectors.search(any(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(dense("z-1", z, 0.9), dense("f-1", f, 0.8)));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(keyword(f), keyword(z)));

        List<Document> results = search.search(List.of(z, f), "looking into it", 5, 0.3);

        assertEquals(Set.of("z-1", "f-1"), results.stream().map(Document::getId).collect(Collectors.toSet()));
        // First and second in one list, second and first in the other: the same fused score. Keyed
        // by source and text alone, both keyword hits went to z-1 and f-1 kept its dense rank only.
        Map<String, Object> scores = results.stream()
                .collect(Collectors.toMap(Document::getId, d -> d.getMetadata().get("rrfScore")));
        assertEquals(scores.get("z-1"), scores.get("f-1"), scores.toString());
    }

    @Test
    @DisplayName("a hit from a reindex shadow is its base source's, and fuses with the live collection's")
    void aShadowHitIsItsBaseSources() {
        String live = SourceCollections.of(ZENDESK);
        when(vectors.search(any(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(dense("shadow-1", SourceCollections.shadowOf(live), 0.9)));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(keyword(live)));

        List<Document> results = search.search(List.of(live), "looking into it", 5, 0.3);

        assertEquals(1, results.size(), results.toString());
        assertEquals(new DocumentKey(ZENDESK, "ticket:1"), DocumentKey.of(results.get(0)));
    }

    @Test
    @DisplayName("a keyword-only hit carries the collection it was found in")
    void aKeywordHitIsStamped() {
        String f = SourceCollections.of(FRESHDESK);
        when(vectors.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(keyword(f)));

        List<Document> results = search.search(List.of(f), "looking into it", 5, 0.3);

        assertEquals(new DocumentKey(FRESHDESK, "ticket:1"), DocumentKey.of(results.get(0)));
    }
}
