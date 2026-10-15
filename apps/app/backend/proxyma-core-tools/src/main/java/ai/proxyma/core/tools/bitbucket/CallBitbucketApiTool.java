package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.common.net.HttpVerb;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.search.domain.SearchContext;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Bitbucket escape hatch: any endpoint the configured credential can reach.
 *
 * <p>Sits alongside the specific Bitbucket tools rather than replacing them. Those exist because
 * tool with named parameters is measurably easier for a model to call correctly than a raw path and
 * a JSON body, so anything an agent reaches for often should stay a tool of its own. This is for
 * the long tail - repository settings, hooks, permissions, build statuses, raw file browsing,
 * and whatever Atlassian ships next - which is unbounded and cannot all be hand-written.
 *
 * <p><b>Permission is decided per REQUEST, not per tool.</b> A GET needs Bitbucket READ; every
 * other verb needs Bitbucket WRITE. The name is also in WriteToolRegistry.BITBUCKET_NAMES, which keeps unattended sub-agents
 * from calling it at all. ChatService pauses for the user's approval on every call that is NOT a
 * GET - WriteToolRegistry.isWriteCall - since that pause is what stands between a mistaken path
 * and a deleted branch. A GET changes nothing and runs like any read tool (since 2026-09-24).
 */
@Component
public class CallBitbucketApiTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public CallBitbucketApiTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Call any Bitbucket REST endpoint (Data Center, /rest/api/1.0), only for what the dedicated "
            + "tools lack: repo settings, hooks, permissions, build statuses. On non-2xx, fix the request from the "
            + "error body.")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.INTEGER, always = true,
                    description = "HTTP status. 4xx/5xx is returned, not thrown - check 'ok'."),
            @ToolResultField(name = "ok", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True for 2xx. False means the body holds Bitbucket's error detail."),
            @ToolResultField(name = "body", type = ToolFieldType.ANY, always = true,
                    description = "Parsed JSON response (an object or a list), or raw text when the server "
                            + "sent no JSON."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when the response was cut at 100,000 characters.")
    })
    public Map<String, Object> callBitbucketApi(
            @ToolParam(required = true, description = "GET, POST, PUT, DELETE or PATCH") String method,
            @ToolParam(required = true, description = "Server path, e.g. "
                    + "/rest/api/1.0/projects/KEY/repos/slug/files; query string allowed, no host")
            String path,
            @ToolParam(required = false, description = "JSON body, for POST/PUT/PATCH") String body) {
        try {
            String verb = HttpVerb.normalize(method);
            // Read for GET, write for anything that can change state. Checked here rather than in
            // BitbucketToolService because only this tool's arguments say which of the two it is.
            if ("GET".equals(verb)) {
                mcpGuard.checkBitbucketRead(SearchContext.getUserId());
            } else {
                mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            }
            Map<String, Object> result = new LinkedHashMap<>(
                    bitbucketToolService.callBitbucketApi(verb, path, body));
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
