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
public class UpdateJiraCommentTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public UpdateJiraCommentTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Replace the body of a Jira comment.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue the comment belongs to."),
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the updated comment."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text.")
    })
    public Map<String, Object> updateJiraComment(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = true, description = "Comment ID") String commentId,
            @ToolParam(required = true, description = "New comment text") String comment) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.updateJiraComment(issueKey, commentId, comment);
            return Map.of("success", true, "issueKey", issueKey, "commentId", commentId, "message", "Comment updated");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
