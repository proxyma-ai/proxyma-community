package ai.proxyma.core.tools.jira;


import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GetJiraIssueTool {

    private final JiraToolService jiraToolService;

    public GetJiraIssueTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "Fetch a Jira issue live: description, comments, attachments, custom fields.")
    @ToolResult({
            @ToolResultField(name = "issue", type = ToolFieldType.OBJECT, always = true,
                    description = "{url, content}: the issue's browse link, and the issue rendered as Markdown - "
                            + "fields, comments, attachments, links.")
    })
    public Map<String, Object> getJiraIssue(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey) {
        try {
            var result = jiraToolService.getJiraIssue(issueKey);
            return Map.of("success", true, "issue", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
