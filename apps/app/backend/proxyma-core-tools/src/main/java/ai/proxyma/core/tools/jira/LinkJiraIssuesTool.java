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
public class LinkJiraIssuesTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public LinkJiraIssuesTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Link two Jira issues. Omit linkType to list the server's link types (both wordings) and "
            + "change nothing. DIRECTION: inwardIssueKey is the SUBJECT of the type's OUTWARD wording - for 'Blocks', "
            + "inwardIssueKey blocks outwardIssueKey. A reversed link reads fine but points the wrong way: read it "
            + "back with getJiraIssue and check the wording before creating more.")
    @ToolResult({
            @ToolResultField(name = "result", type = ToolFieldType.ANY, always = true, passthrough = true,
                    description = "Depends on the request: with a linkType, fixed confirmation text; without one, "
                            + "the ARRAY of available link types and nothing is changed. Check which before "
                            + "binding to it.")
    })
    public Map<String, Object> linkJiraIssues(
            @ToolParam(required = false, description = "Link type, e.g. Blocks, Relates, Duplicate. "
                    + "Omit to list types") String linkType,
            @ToolParam(required = false, description = "SUBJECT key, e.g. MYPROJ-1 in "
                    + "'MYPROJ-1 blocks ...'") String inwardIssueKey,
            @ToolParam(required = false, description = "OBJECT key, e.g. MYPROJ-2 in '... "
                    + "blocks MYPROJ-2'") String outwardIssueKey,
            @ToolParam(required = false, description = "Comment added to the issues with the link") String comment) {
        try {
            if (linkType == null || linkType.isBlank()) {
                return Map.of("success", true, "result", jiraToolService.listJiraIssueLinkTypes());
            }
            if (inwardIssueKey == null || inwardIssueKey.isBlank()
                    || outwardIssueKey == null || outwardIssueKey.isBlank()) {
                throw new IllegalArgumentException(
                        "linkType needs both inwardIssueKey and outwardIssueKey - omit linkType to list the available types");
            }
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            jiraToolService.linkJiraIssues(linkType, inwardIssueKey, outwardIssueKey, comment);
            return Map.of("success", true, "result", "Linked " + inwardIssueKey + " " + linkType + " " + outwardIssueKey);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
