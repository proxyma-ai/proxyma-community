package ai.proxyma.core.tools;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.DocumentKey;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.SearchService;
import org.springframework.ai.document.Document;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SearchTool {

    private final SearchService searchService;
    private final ConnectorService connectorService;

    public SearchTool(SearchService searchService, ConnectorService connectorService) {
        this.searchService = searchService;
        this.connectorService = connectorService;
    }

    static final int MAX_TOP_K = 25;

    @Tool(description = "Hybrid keyword + semantic search over everything indexed: files, Jira, Confluence, "
            + "Bitbucket, SVN, network shares, web pages and, on Enterprise Server, OneDrive, SharePoint, Teams "
            + "and Outlook - any indexed type, listed or not. Does not reach live systems or the public web. Try this first; "
            + "follow up with synonyms and related terms.")
    @ToolResult({
            @ToolResultField(name = "results", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Ranked matches, each with content, source (the item: its link, else its "
                            + "file name or id), sourceName and sourceId (the source it was indexed from, as "
                            + "getConnectorStatus lists it), type and score. EMPTY IS A SUCCESS - it means "
                            + "nothing indexed matched, not that the search failed."),
            @ToolResultField(name = "totalFound", type = ToolFieldType.INTEGER, always = true,
                    description = "Length of 'results'. The number returned after the top-K cut, not the size "
                            + "of the corpus that matched.")
    })
    public Map<String, Object> searchIndexedSources(
            @ToolParam(required = true,  description = "Search query") String query,
            @ToolParam(required = false, description = "Results to return (default 5, at most 25)") Integer topK,
            @ToolParam(required = false, description = "Min semantic similarity, above 0 up to 1 (default 0.3). Filters the "
                    + "semantic half only; keyword matches are kept") Double similarityThreshold,
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the user @mentioned") List<String> sourceNames) {
        try {
            String userId = SearchContext.getUserId();
            // The sources searched, by id: each result says which one it came from (below).
            Map<UUID, RegisteredConnector> searched = new LinkedHashMap<>();
            if (sourceNames != null && !sourceNames.isEmpty()) {
                List<RegisteredConnector> matched = connectorService.resolveVisibleByNames(userId, sourceNames);
                if (matched.isEmpty()) {
                    return Map.of("success", false, "error", "No visible source matches: " + sourceNames);
                }
                matched.forEach(r -> searched.put(r.getId(), r));
            } else {
                connectorService.listVisibleTo(userId).forEach(r -> searched.put(r.getId(), r));
            }
            List<String> collectionIds = searched.keySet().stream().map(SourceCollections::of).toList();

            if (collectionIds.isEmpty()) {
                return Map.of("success", true, "results", List.of(), "totalFound", 0);
            }

            // Clamped: the search retrieves ten times this and reranks four times it, one billed model
            // call per candidate on the prompt-scoring tier - so a topK of 500, which a prompt
            // injection can ask for as easily as a user, was some 2,000 calls for one tool call.
            int k = topK != null ? Math.max(1, Math.min(MAX_TOP_K, topK)) : 5;
            double minScore = similarityThreshold != null ? similarityThreshold : 0.3;
            List<Document> docs = searchService.search(collectionIds, query, k, minScore);
            List<Map<String, Object>> results = docs.stream().map(d -> {
                Map<String, Object> r = new java.util.LinkedHashMap<String, Object>();
                r.put("content", d.getText());
                // A single "source" field, not two - for online connectors (Jira/Confluence/...)
                // "source" metadata is an internal id (issue key, page id), never something worth
                // showing, so the real link ("url" metadata) is what's surfaced. For Files, no
                // "url" is ever set, so this falls back to the file path - the only case that
                // needs sanitizing down to a bare filename (an absolute filesystem path leaks
                // local directory structure; a URL doesn't).
                Object url = d.getMetadata().get("url");
                String source = (url != null && !url.toString().isBlank())
                        ? url.toString()
                        : sanitizeSource(d.getMetadata().getOrDefault("source", "").toString());
                r.put("source", source);
                // WHICH SOURCE, beside it. An item's id is unique only within its source - a
                // Zendesk and a Freshdesk source both hold `ticket:1` - so two results could read
                // the same while naming different things, and readSourceItem, which takes the
                // source's id, could not be pointed at either. Absent for a chunk that belongs to
                // no source the caller searched, which a search of visible sources never returns.
                DocumentKey document = DocumentKey.of(d);
                RegisteredConnector from = document == null ? null : searched.get(document.connectorId());
                if (from != null) {
                    r.put("sourceName", from.getName());
                    r.put("sourceId", from.getId().toString());
                }
                Object type = d.getMetadata().get("connectorType");
                if (type != null && !type.toString().isBlank()) {
                    r.put("type", ai.proxyma.core.connector.ConnectorType.normalizeWireName(type.toString()));
                }
                // Raw retriever scores are incomparable across legs (cosine similarity is 0-1,
                // Lucene BM25 is unbounded) - expose the fused, normalized 0-1 score instead.
                Object normalized = d.getMetadata().get("normalizedScore");
                r.put("score", normalized instanceof Number n ? n.doubleValue() : d.getScore());
                return r;
            }).collect(Collectors.toList());
            return Map.of("success", true, "results", results, "totalFound", results.size());
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    static String sanitizeSource(String source) {
        if (source == null || source.isEmpty()) return "";
        try {
            Path p = Path.of(source);
            if (p.isAbsolute()) {
                return p.getFileName() != null ? p.getFileName().toString() : source;
            }
        } catch (Exception ignored) {}
        return source;
    }
}
