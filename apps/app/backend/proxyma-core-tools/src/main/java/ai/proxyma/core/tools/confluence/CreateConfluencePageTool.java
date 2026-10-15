package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.tools.ToolResults;
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
public class CreateConfluencePageTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public CreateConfluencePageTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Create a Confluence page in a space.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the newly created page."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> createConfluencePage(
            @ToolParam(required = true, description = "Space key, e.g. MYSPACE") String spaceKey,
            @ToolParam(required = true, description = "Page title") String title,
            @ToolParam(required = true, description = "Confluence storage-format HTML") String body,
            @ToolParam(required = false, description = "Parent page ID to nest under") String parentPageId) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            var pageId = confluenceToolService.createConfluencePage(spaceKey, title, body, parentPageId);
            return ToolResults.written("pageId", pageId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
