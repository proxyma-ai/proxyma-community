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
public class ListJiraVersionsTool {

    private final JiraToolService jiraToolService;

    public ListJiraVersionsTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "List a Jira project's versions: id, name, released/archived, release date. Use for exact "
            + "fix/affects version names, and before createJiraVersion.")
    @ToolResult({
            @ToolResultField(name = "versions", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "The project's versions, each with id, name, released, archived and, when set, "
                            + "releaseDate. Empty when the project defines none.")
    })
    public Map<String, Object> listJiraVersions(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey) {
        try {
            var result = jiraToolService.listJiraVersions(projectKey);
            return Map.of("success", true, "versions", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
