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
public class CreateJiraVersionTool {

    private final JiraToolService jiraToolService;
    private final McpGuardService mcpGuard;

    public CreateJiraVersionTool(JiraToolService jiraToolService, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Create a version in a Jira project. Call listJiraVersions first and reuse an existing "
            + "version; never add a near-duplicate ('1.2' beside '1.2.0').")
    @ToolResult({
            @ToolResultField(name = "versionId", type = ToolFieldType.STRING, always = true,
                    description = "Id of the newly created version."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the server made the change without returning an identifier.")
    })
    public Map<String, Object> createJiraVersion(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Version name, e.g. 1.2.0") String name,
            @ToolParam(required = false, description = "Version description") String description,
            @ToolParam(required = false, description = "Planned release date, yyyy-MM-dd") String releaseDate) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            var versionId = jiraToolService.createJiraVersion(projectKey, name, description, releaseDate);
            return ToolResults.written("versionId", versionId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
