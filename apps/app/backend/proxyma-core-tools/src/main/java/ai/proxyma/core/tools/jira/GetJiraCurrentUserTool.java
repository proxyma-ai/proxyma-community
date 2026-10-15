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
public class GetJiraCurrentUserTool {

    private final JiraToolService jiraToolService;

    public GetJiraCurrentUserTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "Get the Jira username of the token's owner, e.g. \"jsmith\". Use it to resolve 'assign to "
            + "me'.")
    @ToolResult({
            @ToolResultField(name = "username", type = ToolFieldType.STRING, always = true,
                    description = "Jira username owning the configured access token, e.g. 'jsmith'.")
    })
    public Map<String, Object> getJiraCurrentUser() {
        try {
            String username = jiraToolService.getCurrentJiraUsername();
            return Map.of("success", true, "username", username);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
