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
public class DeleteJiraIssueLinkTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public DeleteJiraIssueLinkTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Remove one link between two Jira issues; the issues are untouched. linkId is the LINK's id, "
            + "shown only in getJiraIssue's Linked Issues lines as '[link 10501]'.")
    @ToolResult({
            @ToolResultField(name = "linkId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the deleted link."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text.")
    })
    public Map<String, Object> deleteJiraIssueLink(
            @ToolParam(required = true, description = "Link id, e.g. 10501") String linkId) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.deleteJiraIssueLink(linkId);
            return Map.of("success", true, "linkId", linkId, "message", "Issue link deleted");
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
