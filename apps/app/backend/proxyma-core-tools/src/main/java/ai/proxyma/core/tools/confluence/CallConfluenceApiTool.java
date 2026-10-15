package ai.proxyma.core.tools.confluence;

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
 * The Confluence escape hatch: any endpoint the configured credential can reach.
 *
 * <p>Sits alongside the specific Confluence tools rather than replacing them. Those exist because
 * tool with named parameters is measurably easier for a model to call correctly than a raw path and
 * a JSON body, so anything an agent reaches for often should stay a tool of its own. This is for
 * the long tail - labels, attachments, restrictions, page ancestors, templates, watchers, and
 * whatever Atlassian ships next - which is unbounded and cannot all be hand-written.
 *
 * <p><b>Permission is decided per REQUEST, not per tool.</b> A GET needs Confluence READ; every
 * other verb needs Confluence WRITE. The name is in WriteToolRegistry.CONFLUENCE_NAMES, which keeps unattended sub-agents
 * from calling it at all. ChatService pauses for the user's approval on every call that is NOT a
 * GET - WriteToolRegistry.isWriteCall - since that pause is what stands between a mistaken path
 * and a deleted page. A GET changes nothing and runs like any read tool (since 2026-09-24).
 */
@Component
public class CallConfluenceApiTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public CallConfluenceApiTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Call any Confluence REST endpoint (Server/Data Center, /rest/api). Only for what the "
            + "dedicated Confluence tools lack: labels, attachments, restrictions, ancestors, templates, watchers. On "
            + "non-2xx, fix the request from the returned error body; do not resend it unchanged.")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.INTEGER, always = true,
                    description = "HTTP status. 4xx/5xx is returned, not thrown - check 'ok'."),
            @ToolResultField(name = "ok", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True for 2xx. False means the body holds Confluence's error detail."),
            @ToolResultField(name = "body", type = ToolFieldType.ANY, always = true,
                    description = "Parsed JSON response (an object or a list), or raw text when the server "
                            + "sent no JSON."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when the response was cut at 100,000 characters.")
    })
    public Map<String, Object> callConfluenceApi(
            @ToolParam(required = true, description = "GET, POST, PUT, DELETE or PATCH") String method,
            @ToolParam(required = true, description = "Server path, e.g. /rest/api/content/12345/label. Query string "
                    + "allowed; no host or scheme.")
            String path,
            @ToolParam(required = false, description = "JSON body, for POST/PUT/PATCH")
            String body) {
        try {
            String verb = HttpVerb.normalize(method);
            // Read for GET, write for anything that can change state. Checked here rather than in
            // ConfluenceToolService because only this tool's arguments say which of the two it is.
            if ("GET".equals(verb)) {
                mcpGuard.checkConfluenceRead(SearchContext.getUserId());
            } else {
                mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            }
            Map<String, Object> result = new LinkedHashMap<>(
                    confluenceToolService.callConfluenceApi(verb, path, body));
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
