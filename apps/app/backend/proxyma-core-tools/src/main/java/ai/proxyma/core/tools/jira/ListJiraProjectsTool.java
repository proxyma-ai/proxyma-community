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
public class ListJiraProjectsTool {

    private final JiraToolService jiraToolService;

    public ListJiraProjectsTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "List visible Jira projects (key, name). Use to find a project key instead of asking the user.")
    @ToolResult({
            @ToolResultField(name = "projects", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Every visible project, each an object of key and name. Empty if the token "
                            + "sees none.")
    })
    public Map<String, Object> listJiraProjects() {
        try {
            var result = jiraToolService.listJiraProjects();
            return Map.of("success", true, "projects", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
