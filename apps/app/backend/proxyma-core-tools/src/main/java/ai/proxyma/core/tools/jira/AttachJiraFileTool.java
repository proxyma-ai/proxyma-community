package ai.proxyma.core.tools.jira;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.office.OfficeWorkspace;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AttachJiraFileTool {

    private final JiraToolService jiraToolService;
    private final OfficeWorkspace workspace;
    private final McpGuardService mcpGuard;

    public AttachJiraFileTool(JiraToolService jiraToolService, OfficeWorkspace workspace, McpGuardService mcpGuard) {
        this.jiraToolService = jiraToolService;
        this.workspace = workspace;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Attach a file from this conversation (attachments/ or outputs/) to a Jira issue.")
    @ToolResult({
            @ToolResultField(name = "issueKey", type = ToolFieldType.STRING, always = true,
                    description = "The issue the file was attached to."),
            @ToolResultField(name = "attachment", type = ToolFieldType.OBJECT, always = true,
                    description = "id, fileName, sizeBytes and url of the attachment.")
    })
    public Map<String, Object> attachJiraFile(
            @ToolParam(required = true, description = "Issue key, e.g. MYPROJ-123") String issueKey,
            @ToolParam(required = true, description = "File name, e.g. report.pdf, or attachments/<name> or "
                    + "outputs/<name>") String file) {
        try {
            mcpGuard.checkJiraWrite(SearchContext.getUserId());
            Map<String, Object> attachment = jiraToolService.attachJiraFile(issueKey, workspace.file(file));
            return Map.of("success", true, "issueKey", issueKey, "attachment", attachment);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
