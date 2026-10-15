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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

// Separate from UpdateJiraIssueTool because Jira treats estimates as sub-fields of the composite
// `timetracking` field rather than plain field sets - see JiraConnector.updateEstimate() for why
// that needs its own request shape. Keeping it a distinct tool also means the agent's intent is
// explicit in the tool call ("change the estimate", not "edit some fields").
@Component
public class UpdateJiraEstimateTool {

    // Jira duration shorthand: a sequence of weeks/days/hours/minutes units, e.g. "3d 4h", "1w",
    // "90m", "2h30m". Validated here so a typo comes back as a usable message instead of Jira's
    // opaque HTTP 400, which says nothing about which value it rejected.
    private static final Pattern DURATION = Pattern.compile("^\\s*(\\d+(\\.\\d+)?[wdhm]\\s*)+$",
            Pattern.CASE_INSENSITIVE);

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public UpdateJiraEstimateTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Set the original and/or remaining estimate on a Jira issue; an omitted one is unchanged. For "
            + "time already worked use logJiraWork.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "Key of the issue whose estimates were changed."),
            @ToolResultField(name = "originalEstimate", type = ToolFieldType.STRING,
                    description = "The new original estimate, present ONLY if the request set one. Absent means "
                            + "it was left untouched, not that it is empty."),
            @ToolResultField(name = "remainingEstimate", type = ToolFieldType.STRING,
                    description = "The new remaining estimate, present ONLY if the request set one. Absent "
                            + "means it was left untouched."),
            @ToolResultField(name = "message", type = ToolFieldType.STRING, always = true,
                    description = "Fixed confirmation text.")
    })
    public Map<String, Object> updateJiraEstimate(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = false, description = "Jira duration, e.g. '3d 4h', '1w', "
                    + "'90m'") String originalEstimate,
            @ToolParam(required = false, description = "Jira duration, e.g. '2d'") String remainingEstimate) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            boolean hasOriginal = originalEstimate != null && !originalEstimate.isBlank();
            boolean hasRemaining = remainingEstimate != null && !remainingEstimate.isBlank();
            if (!hasOriginal && !hasRemaining) {
                return Map.of("success", false, "error",
                        "Pass originalEstimate, remainingEstimate, or both - there is nothing to update otherwise.");
            }
            if (hasOriginal && !DURATION.matcher(originalEstimate).matches()) {
                return Map.of("success", false, "error", invalidDuration("originalEstimate", originalEstimate));
            }
            if (hasRemaining && !DURATION.matcher(remainingEstimate).matches()) {
                return Map.of("success", false, "error", invalidDuration("remainingEstimate", remainingEstimate));
            }
            jiraToolService.updateJiraEstimate(issueKey, originalEstimate, remainingEstimate);
            // Echoes only what was actually sent, so the agent can report the change precisely
            // without claiming to have touched the estimate it left alone. Jira may normalize the
            // stored value ("90m" becomes "1h 30m"), so these are the requested values, not a
            // read-back - re-read the issue with getJiraIssue if the exact stored form matters.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("issueKey", issueKey);
            if (hasOriginal) result.put("originalEstimate", originalEstimate.strip());
            if (hasRemaining) result.put("remainingEstimate", remainingEstimate.strip());
            result.put("message", "Estimate updated");
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    private static String invalidDuration(String param, String value) {
        return "'" + value + "' is not valid Jira duration shorthand for " + param
                + ". Use weeks/days/hours/minutes units, e.g. '1w', '3d 4h', '90m'.";
    }
}
