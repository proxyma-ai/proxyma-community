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
public class CreateJiraIssueTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public CreateJiraIssueTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Create a Jira issue of any type the project has - Epic, Story, Task, Bug, Sub-task or a "
            + "custom one. Unsure of the type names, the required fields or how to link to an Epic: "
            + "listJiraIssueTypes first.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the newly created issue, e.g. MYPROJ-123."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> createJiraIssue(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Issue type name as the project names it, e.g. Epic, Story, Task, Bug, Sub-task "
                    + "(listJiraIssueTypes lists them)") String issueType,
            @ToolParam(required = true, description = "One-line summary") String summary,
            @ToolParam(required = false, description = "Issue description") String description,
            @ToolParam(required = false, description = "Highest, High, Medium, Low or Lowest") String priority,
            @ToolParam(required = false, description = "Jira username (not display name), e.g. from getJiraCurrentUser") String assignee,
            @ToolParam(required = false, description = "Parent key, e.g. MYPROJ-100. Required for a "
                    + "sub-task. Also puts a Story under an Epic when the type's fields include 'parent'; "
                    + "otherwise set the 'Epic Link' custom field in fields (listJiraIssueTypes)") String parentKey,
            @ToolParam(required = false, description = "Other fields, applied last (they win), e.g. "
                    + "{\"labels\":[\"a\"], \"fixVersions\":[{\"name\":\"1.2.0\"}], \"versions\":[...], "
                    + "\"duedate\":\"2026-09-30\", \"components\":[{\"name\":\"x\"}], \"customfield_10004\":\"...\"}. "
                    + "Custom field ids differ per server: see listJiraFields. Not for Resolution: use "
                    + "transitionJiraIssue") Map<String, Object> fields) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            var issueKey = jiraToolService.createJiraIssue(projectKey, issueType, summary, description, priority, assignee, parentKey, fields);
            return ToolResults.written("issueKey", issueKey);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
