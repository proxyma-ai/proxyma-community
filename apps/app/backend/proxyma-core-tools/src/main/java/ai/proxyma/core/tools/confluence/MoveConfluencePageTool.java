package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MoveConfluencePageTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public MoveConfluencePageTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Move a Confluence page under another parent page in the same space. Title and body are kept. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "The page moved."),
            @ToolResultField(name = "parentPageId", type = ToolFieldType.STRING, always = true,
                    description = "Its new parent.")
    })
    public Map<String, Object> moveConfluencePage(
            @ToolParam(required = true, description = "Id of the page to move") String pageId,
            @ToolParam(required = true, description = "Id of the new parent page") String parentPageId) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            confluenceToolService.moveConfluencePage(pageId, parentPageId);
            return Map.of("success", true, "pageId", pageId, "parentPageId", parentPageId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
