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
public class TransitionJiraIssueTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public TransitionJiraIssueTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Change a Jira issue's workflow status. Omit targetStatus to list the available transitions "
            + "and change nothing. The only way to set Resolution (updateJiraIssue cannot).")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue."),
            @ToolResultField(name = "result", type = ToolFieldType.ANY, always = true,
                    description = "Depends on the request: with a targetStatus, a confirmation string naming the "
                            + "new status; without one, the ARRAY of available transitions (id, name, toStatus) "
                            + "and nothing is changed.")
    })
    public Map<String, Object> transitionJiraIssue(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = false, description = "Status name, e.g. In Progress, Done") String targetStatus,
            @ToolParam(required = false, description = "Fields set with the transition, e.g. "
                    + "{\"resolution\":{\"name\":\"Done\"}}") Map<String, Object> fields) {
        try {
            if (targetStatus != null && !targetStatus.isBlank()) {
                mcpGuard.checkJiraWrite(SearchContext.getUserId());
            }
            var result = jiraToolService.transitionJiraIssue(issueKey, targetStatus, fields);
            return Map.of("success", true, "issueKey", issueKey, "result", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
