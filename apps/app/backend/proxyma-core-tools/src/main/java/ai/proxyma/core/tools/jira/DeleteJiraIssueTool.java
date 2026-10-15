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
public class DeleteJiraIssueTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public DeleteJiraIssueTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Permanently delete a Jira issue. Cannot be undone. Fails when the issue has subtasks.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the deleted issue."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text.")
    })
    public Map<String, Object> deleteJiraIssue(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.deleteJiraIssue(issueKey);
            return Map.of("success", true, "issueKey", issueKey, "message", "Issue deleted");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
