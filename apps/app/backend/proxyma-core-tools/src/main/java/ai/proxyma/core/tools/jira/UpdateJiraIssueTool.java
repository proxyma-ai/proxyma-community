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

import java.util.List;
import java.util.Map;

@Component
public class UpdateJiraIssueTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public UpdateJiraIssueTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Update a Jira issue; only the arguments passed change. labels REPLACES the list - use "
            + "addLabels/removeLabels unless the user said 'set the labels to'. Other fields: by id in customFields.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the updated issue."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text. Does not say which fields changed.")
    })
    public Map<String, Object> updateJiraIssue(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = false, description = "New summary") String summary,
            @ToolParam(required = false, description = "New description") String description,
            @ToolParam(required = false, description = "Highest, High, Medium, Low or Lowest") String priority,
            @ToolParam(required = false, description = "Jira username (not display name), e.g. from getJiraCurrentUser") String assignee,
            @ToolParam(required = false, description = "Replaces ALL labels") List<String> labels,
            @ToolParam(required = false, description = "Labels to add") List<String> addLabels,
            @ToolParam(required = false, description = "Labels to remove") List<String> removeLabels,
            @ToolParam(required = false, description = "Fix version names, e.g. ['1.2.0']; "
                    + "must exist in the project") List<String> fixVersions,
            @ToolParam(required = false, description = "Component names; must exist in the "
                    + "project") List<String> components,
            @ToolParam(required = false, description = "yyyy-MM-dd") String dueDate,
            @ToolParam(required = false, description = "By id, e.g. {\"customfield_10001\": \"value\"}; ids differ per "
                    + "server (listJiraFields). Value shape is per field") Map<String, Object> customFields) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.updateJiraIssue(issueKey, summary, description, priority, assignee,
                    labels, addLabels, removeLabels, fixVersions, components, dueDate, customFields);
            return Map.of("success", true, "issueKey", issueKey, "message", "Issue updated");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
