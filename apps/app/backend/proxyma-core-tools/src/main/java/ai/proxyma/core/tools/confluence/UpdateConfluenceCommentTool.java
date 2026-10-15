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
public class UpdateConfluenceCommentTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public UpdateConfluenceCommentTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Replace the text of a Confluence comment. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "The comment edited.")
    })
    public Map<String, Object> updateConfluenceComment(
            @ToolParam(required = true, description = "Comment id") String commentId,
            @ToolParam(required = true, description = "New body, Confluence storage-format HTML") String bodyHtml) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            confluenceToolService.updateConfluenceComment(commentId, bodyHtml);
            return Map.of("success", true, "commentId", commentId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
