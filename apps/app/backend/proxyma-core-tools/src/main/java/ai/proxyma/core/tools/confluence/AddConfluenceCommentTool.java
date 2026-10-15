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
public class AddConfluenceCommentTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public AddConfluenceCommentTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Add a comment to a Confluence page. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the page the comment was added to."),
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the newly created comment."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> addConfluenceComment(
            @ToolParam(required = true, description = "Page ID, e.g. 123456789") String pageId,
            @ToolParam(required = true, description = "Comment in Confluence storage-format HTML") String body) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            var commentId = confluenceToolService.addConfluenceComment(pageId, body);
            return ToolResults.written("pageId", pageId, "commentId", commentId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
