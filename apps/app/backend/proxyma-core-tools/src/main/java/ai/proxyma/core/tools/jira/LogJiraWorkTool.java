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
public class LogJiraWorkTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public LogJiraWorkTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Log time spent on a Jira issue.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue the work was logged against."),
            @ToolResultField(name = "worklogId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the created worklog entry."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> logJiraWork(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = true, description = "Jira duration, e.g. '3h 30m', '1d', '45m'") String timeSpent,
            @ToolParam(required = false, description = "Worklog comment, plain text") String comment,
            @ToolParam(required = false, description = "Start, ISO-8601 with offset, e.g. "
                    + "'2026-07-27T09:00:00.000+0000'. Default now") String started) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            var result = jiraToolService.logJiraWork(issueKey, timeSpent, comment, started);
            return ToolResults.written("issueKey", issueKey, "worklogId", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
