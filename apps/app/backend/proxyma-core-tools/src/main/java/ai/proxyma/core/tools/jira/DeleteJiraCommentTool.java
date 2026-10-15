package ai.proxyma.core.tools.jira;

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
public class DeleteJiraCommentTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public DeleteJiraCommentTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Permanently delete a comment from a Jira issue.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue the comment was deleted from."),
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the deleted comment."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text. Carries no detail the other fields do not.")
    })
    public Map<String, Object> deleteJiraComment(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = true, description = "Comment ID") String commentId) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.deleteJiraComment(issueKey, commentId);
            return Map.of("success", true, "issueKey", issueKey, "commentId", commentId, "message", "Comment deleted");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
