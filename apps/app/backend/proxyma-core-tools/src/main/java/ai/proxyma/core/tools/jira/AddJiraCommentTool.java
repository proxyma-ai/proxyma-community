package ai.proxyma.core.tools.jira;

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
public class AddJiraCommentTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public AddJiraCommentTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Add a comment to a Jira issue.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue the comment was added to - echoed back from the request."),
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the newly created comment, for a later updateJiraComment or "
                            + "deleteJiraComment."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> addJiraComment(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = true, description = "Comment text") String comment) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            var result = jiraToolService.addJiraComment(issueKey, comment);
            return ToolResults.written("issueKey", issueKey, "commentId", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
