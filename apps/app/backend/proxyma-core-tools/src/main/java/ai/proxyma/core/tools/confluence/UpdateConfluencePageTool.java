package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.search.domain.SearchContext;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UpdateConfluencePageTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public UpdateConfluencePageTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Update a Confluence page; title and body replace the current ones. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the updated page.")
    })
    public Map<String, Object> updateConfluencePage(
            @ToolParam(required = true, description = "Page ID, e.g. 123456789") String pageId,
            @ToolParam(required = true, description = "New page title") String title,
            @ToolParam(required = true, description = "New body, Confluence storage-format HTML") String body) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            confluenceToolService.updateConfluencePage(pageId, title, body);
            return Map.of("success", true, "pageId", pageId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
