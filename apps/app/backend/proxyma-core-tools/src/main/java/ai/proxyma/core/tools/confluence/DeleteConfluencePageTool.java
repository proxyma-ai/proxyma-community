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
public class DeleteConfluencePageTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public DeleteConfluencePageTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Delete a Confluence page. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the deleted page - echoed back from the request.")
    })
    public Map<String, Object> deleteConfluencePage(
            @ToolParam(required = true, description = "Page ID, e.g. 123456789") String pageId) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            confluenceToolService.deleteConfluencePage(pageId);
            return Map.of("success", true, "pageId", pageId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
