package ai.proxyma.core.tools;

import ai.proxyma.core.settings.dto.WebSearchSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Web search, working out of the box with nothing configured.
//
// TWO PATHS, and the unconfigured one is now the default rather than an error. KeylessWebSearch
// scrapes a keyless HTML search endpoint; Google Custom Search is used INSTEAD whenever an admin
// has supplied a key and engine id. That ordering is deliberate: a configured key is the stable,
// licensed, rate-limit-free path and should win where someone has bothered to set one up, while
// everyone else gets search rather than a message explaining how to enable it.
//
// This used to hard-require the key, which made the single most obvious capability in the product
// - "look that up" - dead on arrival for every install that had not been through a Google Cloud
// console. The trade accepted in exchange is that the keyless path scrapes markup: it can break
// when that markup changes, and it can be rate-limited. Both surface as a clear error naming the
// Google option, not as silently empty results.
//
// Uses the same proxy-aware externalRestClientBuilder as WebFetchTool/every connector (see
// RestClientConfig in proxyma-core-network) - Settings > Network / HTTPS_PROXY is respected
// automatically.
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final int DEFAULT_NUM_RESULTS = 5;
    // Google Custom Search JSON API caps "num" at 10 per request.
    private static final int MAX_NUM_RESULTS = 10;

    private final RestClient externalRestClient;
    private final SettingsService settingsService;
    private final WebMediaStorage webMediaStorage;
    private final KeylessWebSearch keylessWebSearch;

    public WebSearchTool(@Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder,
                  SettingsService settingsService, WebMediaStorage webMediaStorage,
                  KeylessWebSearch keylessWebSearch) {
        this.externalRestClient = externalRestClientBuilder.build();
        this.settingsService = settingsService;
        this.webMediaStorage = webMediaStorage;
        this.keylessWebSearch = keylessWebSearch;
    }

    @Tool(description = "Search the public web; each result has title, link, snippet and, when one exists, "
            + "thumbnailUrl.")
    @ToolResult({
            @ToolResultField(name = "query", type = ToolFieldType.STRING, always = true,
                    description = "The query actually searched - echoed back so a caller can confirm what ran."),
            @ToolResultField(name = "results", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Matches, each with title, link, snippet and optionally thumbnailUrl. Empty "
                            + "is a success.")
    })
    public Map<String, Object> webSearch(
            @ToolParam(description = "Search query") String query,
            @ToolParam(required = false, description = "Results, 1-10 (default 5)") Integer numResults) {
        if (query == null || query.isBlank()) {
            return Map.of("success", false, "error", "query must not be blank");
        }
        WebSearchSettings settings = settingsService.getSettings(WebSearchSettings.PREFIX, WebSearchSettings.class);
        int n = numResults == null ? DEFAULT_NUM_RESULTS : Math.min(MAX_NUM_RESULTS, Math.max(1, numResults));

        // No key: search anyway. This is the branch almost every deployment takes.
        if (settings == null || !settings.isConfigured()) {
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("query", query);
                result.put("results", keylessWebSearch.search(query, n));
                return result;
            } catch (Exception e) {
                log.warn("[WebSearchTool] keyless search failed for '{}': {}", query, e.getMessage());
                return Map.of("success", false, "error", vendorFree(e));
            }
        }

        try {
            JsonNode response = externalRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("www.googleapis.com").path("/customsearch/v1")
                            .queryParam("key", settings.getApiKey())
                            .queryParam("cx", settings.getSearchEngineId())
                            .queryParam("q", query)
                            .queryParam("num", n)
                            .build())
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new IllegalStateException("Web search returned HTTP "
                                + res.getStatusCode().value() + " - check the search API key in Settings.");
                    })
                    .body(JsonNode.class);

            List<Map<String, Object>> results = new ArrayList<>();
            List<String> thumbCandidates = new ArrayList<>();
            List<String> resultThumbCandidate = new ArrayList<>();
            if (response != null && response.has("items")) {
                for (JsonNode item : response.get("items")) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("title", text(item, "title"));
                    r.put("link", text(item, "link"));
                    r.put("snippet", text(item, "snippet"));
                    String thumbUrl = extractThumbnail(item);
                    if (thumbUrl != null) thumbCandidates.add(thumbUrl);
                    resultThumbCandidate.add(thumbUrl);
                    results.add(r);
                }
            }

            List<WebMediaStorage.SavedMedia> savedThumbs = webMediaStorage.downloadAndSave(thumbCandidates);
            Map<String, String> mediaUrlByOriginal = new HashMap<>();
            for (WebMediaStorage.SavedMedia m : savedThumbs) {
                mediaUrlByOriginal.put(m.originalUrl(), m.mediaUrl());
            }
            for (int i = 0; i < results.size(); i++) {
                String candidate = resultThumbCandidate.get(i);
                if (candidate != null && mediaUrlByOriginal.containsKey(candidate)) {
                    results.get(i).put("thumbnailUrl", mediaUrlByOriginal.get(candidate));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("query", query);
            result.put("results", results);
            return result;
        } catch (Exception e) {
            log.warn("[WebSearchTool] search failed for '{}': {}", query, e.getMessage());
            return Map.of("success", false, "error", vendorFree(e));
        }
    }

    // The model must never learn which search engine is behind this tool (the Web skill says so),
    // and a transport exception's message is "I/O error on GET request for <the engine's URL>" -
    // with the API key in its query string on the keyed path. Only the messages this class and
    // KeylessWebSearch write themselves (IllegalStateException, all vendor-free) pass through;
    // anything else is replaced, and the detail stays in the log line above.
    private static String vendorFree(Exception e) {
        if (e instanceof IllegalStateException && e.getMessage() != null) return e.getMessage();
        return "Web search could not reach the search service. Check the network or the proxy in "
                + "Settings > Network, then try again.";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String extractThumbnail(JsonNode item) {
        JsonNode pagemap = item.get("pagemap");
        if (pagemap == null) return null;
        for (String key : new String[]{"cse_thumbnail", "cse_image"}) {
            JsonNode arr = pagemap.get(key);
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                JsonNode src = arr.get(0).get("src");
                if (src != null && !src.isNull()) return src.asText();
            }
        }
        return null;
    }
}
