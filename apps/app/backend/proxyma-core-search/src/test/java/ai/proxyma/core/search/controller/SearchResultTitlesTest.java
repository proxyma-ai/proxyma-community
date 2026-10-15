package ai.proxyma.core.search.controller;

import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.DocumentKey;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.dto.SearchRequest;
import ai.proxyma.core.search.dto.SearchResponse;
import ai.proxyma.core.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search API's titles, asked of the registry by DOCUMENT - source and sourceId - and put back
 * on each result by the same pair. A Freshdesk result for {@code ticket:1} came back under the
 * Zendesk ticket's title: both sources hold a {@code ticket:1}, and the title was looked up by that
 * id alone.
 */
class SearchResultTitlesTest {

    private static final UUID ZENDESK = UUID.randomUUID();
    private static final UUID FRESHDESK = UUID.randomUUID();

    private static Document hit(String collection, String text) {
        return Document.builder().id(UUID.randomUUID().toString()).text(text)
                .metadata(Map.of("source", "ticket:1", ChunkMetadata.COLLECTION, collection, "normalizedScore", 1.0))
                .build();
    }

    @Test
    @DisplayName("two sources holding one item id: each result gets its own title, a shadow's its base source's")
    @SuppressWarnings("unchecked")
    void eachResultItsOwnTitle() {
        SearchService search = mock(SearchService.class);
        ConnectorService connectors = mock(ConnectorService.class);
        String z = SourceCollections.of(ZENDESK);
        String f = SourceCollections.of(FRESHDESK);
        when(connectors.visibleCollectionIds(null)).thenReturn(List.of(z, f));
        when(search.search(eq(List.of(z, f)), anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                hit(z, "Customer asks for a refund."),
                hit(SourceCollections.shadowOf(f), "The printer jams on every page.")));
        // The registry answers only for the pairs it is asked about, as resolveDocumentTitles does.
        Map<DocumentKey, String> registry = Map.of(
                new DocumentKey(ZENDESK, "ticket:1"), "Refund request",
                new DocumentKey(FRESHDESK, "ticket:1"), "Printer jam");
        when(connectors.resolveDocumentTitles(isNull(), any())).thenAnswer(inv -> {
            Map<DocumentKey, String> out = new HashMap<>();
            for (DocumentKey k : (Collection<DocumentKey>) inv.getArgument(1)) {
                if (registry.containsKey(k)) out.put(k, registry.get(k));
            }
            return out;
        });
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());
        SearchRequest request = new SearchRequest();
        request.setQuery("ticket");

        SearchResponse response = new SearchController(search, connectors).search(request, exchange).block().getBody();

        assertEquals("Refund request", response.getResults().get(0).getTitle());
        assertEquals("Printer jam", response.getResults().get(1).getTitle());
        verify(connectors).resolveDocumentTitles(isNull(),
                eq(new java.util.LinkedHashSet<>(List.of(new DocumentKey(ZENDESK, "ticket:1"),
                        new DocumentKey(FRESHDESK, "ticket:1")))));
    }

    @Test
    @DisplayName("a hit that names no source's document is left without a title")
    void noSourceNoTitle() {
        SearchService search = mock(SearchService.class);
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.visibleCollectionIds(null)).thenReturn(List.of("c"));
        when(search.search(any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of(hit("guardrailpolicy", "x")));
        when(connectors.resolveDocumentTitles(isNull(), any())).thenReturn(Map.of());
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());
        SearchRequest request = new SearchRequest();
        request.setQuery("x");

        SearchResponse response = new SearchController(search, connectors).search(request, exchange).block().getBody();

        assertNull(response.getResults().get(0).getTitle());
        verify(connectors).resolveDocumentTitles(isNull(), eq(Set.of()));
    }
}
