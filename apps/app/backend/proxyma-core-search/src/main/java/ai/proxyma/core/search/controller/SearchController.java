package ai.proxyma.core.search.controller;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.DocumentKey;
import ai.proxyma.core.search.dto.SearchRequest;
import ai.proxyma.core.search.dto.SearchResponse;
import ai.proxyma.core.search.dto.SearchResponse.SearchResultItem;
import ai.proxyma.core.search.service.ResultSources;
import ai.proxyma.core.search.service.SearchService;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final ConnectorService connectorService;

    public SearchController(SearchService searchService, ConnectorService connectorService) {
        this.searchService = searchService;
        this.connectorService = connectorService;
    }

    @PostMapping
    public Mono<ResponseEntity<SearchResponse>> search(@Valid @RequestBody SearchRequest request,
                                                        ServerWebExchange exchange) {
        return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
            String uid = userId.isEmpty() ? null : userId;
            // WHOSE SPEND THIS IS. Query expansion, reranking and the query embedding all bill a
            // model from inside search(), and until 2026-09-16 this path set no SpendSubject at
            // all - so every one of those landed under SYSTEM in the ledger while the user's quota
            // was charged correctly elsewhere. The two screens disagreed about who spent it.
            // Set here, on the thread the work actually runs on, rather than in JwtAuthFilter:
            // that runs on a Netty thread this callable never touches.
            return SpendContext.with(SpendSubject.user(uid), () -> searchOwned(request, uid));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private ResponseEntity<SearchResponse> searchOwned(SearchRequest request, String uid) {
        {
            List<String> collectionIds = connectorService.visibleCollectionIds(uid);


            if (collectionIds.isEmpty()) {
                SearchResponse empty = new SearchResponse();
                empty.setQuery(request.getQuery());
                empty.setTotalResults(0);
                empty.setResults(List.of());
                return ResponseEntity.ok(empty);
            }

            List<Document> docs = searchService.search(
                    collectionIds,
                    request.getQuery(),
                    request.getTopK(),
                    request.getMinScore()
            );

            SearchResponse response = new SearchResponse();
            response.setQuery(request.getQuery());
            response.setTotalResults(docs.size());

            // WHAT THE DOCUMENT IS CALLED. A chunk's metadata carries `source`, `url` and
            // `connectorType` and no title, so this field was null on every result the API has
            // ever returned - the published examples show "title": null. The title lives on the
            // registry's TrackedDocument, resolved here in ONE query for the whole page rather
            // than per result: topK chunks routinely come from topK different documents, and a
            // lookup inside the loop would be that many round trips on the response path. By
            // DOCUMENT (source and sourceId), never by sourceId alone: two sources both hold a
            // `ticket:1`, and each result is named by its own (DocumentKey).
            Map<DocumentKey, String> titles = connectorService.resolveDocumentTitles(uid,
                    documentsOf(docs));

            List<SearchResultItem> items = new ArrayList<>();
            for (Document doc : docs) {
                SearchResultItem item = new SearchResultItem();
                item.setId(doc.getId());
                DocumentKey document = DocumentKey.of(doc);
                // Left null when the document has no registry row - deleted at the source since
                // indexing, most often. Better an absent title than a stale one.
                item.setTitle(document == null ? null : titles.get(document));
                item.setSnippet(doc.getText() != null && doc.getText().length() > 300
                        ? doc.getText().substring(0, 300) + "..." : doc.getText());
                // The link when the chunk has one, as the published API documents - it returned the
                // source id instead, which for Jira or Confluence is an issue key or a page id.
                item.setSourceUrl(ResultSources.of(doc));
                Object connType = doc.getMetadata().get("connectorType");
                // Normalized on the way out: chunks indexed by older builds carry the raw enum
                // name, so without this a result set spanning old and new chunks labels the
                // same kind of source two different ways.
                item.setConnectorType(connType != null
                        ? ai.proxyma.core.connector.ConnectorType.normalizeWireName(connType.toString()) : null);
                // Fused, normalized 0-1 score - raw retriever scores mix incomparable scales
                // (cosine similarity vs unbounded Lucene BM25).
                Object normalized = doc.getMetadata().get("normalizedScore");
                item.setScore(normalized instanceof Number n ? n.doubleValue()
                        : doc.getScore() != null ? doc.getScore() : 0.0);
                items.add(item);
            }
            response.setResults(items);
            return ResponseEntity.ok(response);
        }
    }

    // Deduplicated and order-preserving: several chunks of one document is the normal shape of a
    // result page, and the IN list should carry each document once.
    private static Set<DocumentKey> documentsOf(List<Document> docs) {
        Set<DocumentKey> ids = new LinkedHashSet<>();
        for (Document doc : docs) {
            DocumentKey document = DocumentKey.of(doc);
            if (document != null) ids.add(document);
        }
        return ids;
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("");
    }
}
