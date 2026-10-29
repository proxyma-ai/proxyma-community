package ai.proxyma.core.tools;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.activity.ActivityCounterStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

// Replaces Spring AI's stock ToolCallbackConverterAutoConfiguration#syncTools (kept out by
// StockMcpToolConverterExclusionFilter, registered in spring.factories) so that the webFetch
// tool can return a real MCP ImageContent block for a direct-image URL, while every other tool is
// converted to a text tool exactly as the stock converter would.
//
// Why a custom spec is unavoidable: the stock ToolCallback->MCP bridge (McpToolUtils) decides
// text-vs-image from a SINGLE MimeType fixed per tool at registration time
// (spring.ai.mcp.server.tool-response-mime-type.<tool>). webFetch is a mixed tool - markdown for an
// HTML page, an image for a direct-image URL - so no single fixed mime fits it; it needs to choose
// the MCP content type PER RESULT, which only a hand-built SyncToolSpecification can do. Chat is
// unaffected: ChatService still consumes the full ToolCallbackProvider (webFetch included) directly,
// where images are handled via the conversation media-folder URL, not MCP ImageContent.
//
// Scoped to the SYNC MCP server (proxyma's default and only configured mode - protocol=streamable,
// server type defaults to SYNC). If a deployment ever sets spring.ai.mcp.server.type=ASYNC this bean
// backs off and, since the stock converter is excluded, tools would need an async equivalent added.
@Configuration
public class WebFetchMcpToolConfig {

    private static final Logger log = LoggerFactory.getLogger(WebFetchMcpToolConfig.class);
    private static final String WEB_FETCH_TOOL = "webFetch";
    private static final String RENDER_WEB_FETCH_TOOL = "renderWebFetch";

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "type", havingValue = "SYNC", matchIfMissing = true)
    public List<McpServerFeatures.SyncToolSpecification> proxymaMcpSyncTools(
            ToolCallbackProvider toolCallbackProvider,
            WebFetchTool webFetchTool,
            ActivityCounterStore activityCounterStore,
            ObjectMapper objectMapper,
            SettingsService settingsService) {

        // Dedupe by tool name keeping the first occurrence - mirrors the stock converter, which
        // collects callbacks into a name-keyed map with a keep-first merge before converting.
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback cb : toolCallbackProvider.getToolCallbacks()) {
            // A tool that asks the person in a chat has nobody to ask over MCP - the client's own
            // agent asks its user itself.
            if (AskUserTool.CHAT_ONLY.contains(cb.getToolDefinition().name())) continue;
            byName.putIfAbsent(cb.getToolDefinition().name(), cb);
        }

        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (ToolCallback cb : byName.values()) {
            String name = cb.getToolDefinition().name();
            McpServerFeatures.SyncToolSpecification spec;
            if (WEB_FETCH_TOOL.equals(name)) {
                spec = imageAwareFetchSpec(cb, name, webFetchTool::webFetchForMcp, activityCounterStore, objectMapper);
            } else if (RENDER_WEB_FETCH_TOOL.equals(name)) {
                spec = imageAwareFetchSpec(cb, name, webFetchTool::renderWebFetchForMcp, activityCounterStore, objectMapper);
            } else {
                // Identical to the stock converter for a tool with no fixed response mime: text-only.
                spec = McpToolUtils.toSyncToolSpecification(cb);
            }
            specs.add(bounded(spec, timeoutFor(name, settingsService)));
        }
        log.info("[WebFetchMcpToolConfig] registered {} MCP sync tool spec(s); webFetch/renderWebFetch are image-aware", specs.size());
        return specs;
    }

    // Reuses the exact Tool schema/description the stock converter would advertise for the given
    // tool, but swaps in a handler that returns ImageContent (base64 + real per-image mime) for a
    // direct-image URL and TextContent (the normal structured JSON result) for everything else. The
    // `fetch` function selects the light (webFetchForMcp) vs heavy (renderWebFetchForMcp) path. Wraps
    // the call in the same usage accounting ActivityRecordingToolCallback applies to every other
    // tool, so MCP usage is still metered even though this handler bypasses that callback wrapper.
    private McpServerFeatures.SyncToolSpecification imageAwareFetchSpec(
            ToolCallback webFetchCallback,
            String toolName,
            java.util.function.Function<String, WebFetchTool.McpFetchResult> fetch,
            ActivityCounterStore counterStore,
            ObjectMapper objectMapper) {

        McpSchema.Tool tool = McpToolUtils.toSyncToolSpecification(webFetchCallback).tool();

        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            long startNanos = System.nanoTime();
            boolean ok = false;
            String recorded = null;
            Map<String, Object> args = request.arguments();
            Object rawUrl = args == null ? null : args.get("url");
            String url = rawUrl == null ? null : rawUrl.toString();
            try {
                WebFetchTool.McpFetchResult result = fetch.apply(url);
                if (result.isImage()) {
                    byte[] bytes = result.imageBytes();
                    McpSchema.ImageContent image = new McpSchema.ImageContent(
                            null, Base64.getEncoder().encodeToString(bytes), result.imageMimeType());
                    // Pair the rendered image with a short text block carrying the source URL/type so
                    // the model still has something machine-readable to cite and reason about (the
                    // image block itself carries no metadata). This is the small metadata JSON, not
                    // the base64 - that lives in the image block above.
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("success", true);
                    meta.put("url", url);
                    meta.put("mediaType", "image");
                    meta.put("mimeType", result.imageMimeType());
                    meta.put("sizeBytes", bytes.length);
                    recorded = toJson(objectMapper, meta);
                    ok = true;
                    return CallToolResult.builder()
                            .addContent(image)
                            .addTextContent(recorded)
                            .isError(false)
                            .build();
                }
                recorded = toJson(objectMapper, result.textResult());
                ok = true;
                return CallToolResult.builder()
                        .addTextContent(recorded)
                        .isError(false)
                        .build();
            } finally {
                // The diagnostics, the usage count and the Audit Logs row every other tool gets
                // from its wrapper - this handler runs the fetch without one.
                ActivityRecordingToolCallback.recorded(toolName, counterStore,
                        toJson(objectMapper, url == null ? Map.of() : Map.of("url", url)), recorded, ok, startNanos);
            }
        };
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }

    // How long an MCP client's call may run - the chat's own limits: a minute, and the search limit
    // for the search tools.
    private static Duration timeoutFor(String toolName, SettingsService settings) {
        if (SEARCH_TOOLS.contains(toolName)) {
            try {
                return Duration.ofSeconds(settings.getSettings(ChatSettings.PREFIX, ChatSettings.class).searchTimeoutSeconds());
            } catch (Exception e) {
                return Duration.ofMinutes(ChatSettings.DEFAULT_SEARCH_TIMEOUT_MINUTES);
            }
        }
        return TOOL_TIMEOUT;
    }

    static final Duration TOOL_TIMEOUT = Duration.ofSeconds(60);

    private static final java.util.Set<String> SEARCH_TOOLS = java.util.Set.of(
            "searchIndexedSources", "searchKnowledgeGraph", "findGraphPath", "findGraphPeople",
            "findGraphTimeline", "findGraphThemes", "searchConversations", "recall");

    /**
     * The same tool, run on BlockingWork and given up on after {@code timeout}. A sync MCP tool ran
     * on the WebFlux server's boundedElastic scheduler - the pool every blocking database read
     * shares - with no limit at all, so an MCP client's slow webFetch or Jira call held one of its
     * workers for minutes. The caller's identity goes with the call to the worker. Null: no limit.
     */
    static McpServerFeatures.SyncToolSpecification bounded(McpServerFeatures.SyncToolSpecification spec,
                                                           Duration timeout) {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> inner = spec.callHandler();
        if (inner == null) return spec;
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            String userId = SearchContext.getUserId();
            CompletableFuture<CallToolResult> run = CompletableFuture.supplyAsync(() -> {
                SearchContext.setUserId(userId);
                try {
                    return inner.apply(exchange, request);
                } finally {
                    SearchContext.setUserId(null);
                }
            }, BlockingWork.executor());
            try {
                return timeout == null ? run.get() : run.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                run.cancel(true);
                return CallToolResult.builder()
                        .addTextContent("{\"success\":false,\"error\":\"The tool did not finish within "
                                + timeout.toSeconds() + " seconds.\"}")
                        .isError(true).build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                run.cancel(true);
                throw new IllegalStateException("Interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException(cause);
            }
        };
        return McpServerFeatures.SyncToolSpecification.builder().tool(spec.tool()).callHandler(handler).build();
    }

    private static String toJson(ObjectMapper mapper, Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"failed to serialize webFetch result\"}";
        }
    }
}
