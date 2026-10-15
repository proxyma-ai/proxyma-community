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
public class SearchJiraIssuesTool {

    private final JiraToolService jiraToolService;

    public SearchJiraIssuesTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "Search Jira issues with JQL; put any scope in the JQL. For 'my issues' use assignee = "
            + "currentUser() (or reporter); ask who the user is only if that finds nothing.")
    @ToolResult({
            @ToolResultField(name = "issues", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Matching issues, each with key, summary, status, description and url. Empty "
                            + "when the JQL matched nothing - which is a SUCCESS, not an error.")
    })
    public Map<String, Object> searchJiraIssues(
            @ToolParam(required = true, description = "JQL, e.g. project = MYPROJ AND status = Open") String jql,
            @ToolParam(required = false, description = "Max results (default 20, at most 100; no paging)") Integer maxResults) {
        try {
            var result = jiraToolService.searchJiraIssues(jql, maxResults != null ? maxResults : 20);
            return Map.of("success", true, "issues", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
