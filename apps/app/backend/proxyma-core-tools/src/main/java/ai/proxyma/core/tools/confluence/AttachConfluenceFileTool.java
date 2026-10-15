package ai.proxyma.core.tools.confluence;

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
public class AttachConfluenceFileTool {

    private final ConfluenceToolService confluenceToolService;
    private final OfficeWorkspace workspace;
    private final McpGuardService mcpGuard;

    public AttachConfluenceFileTool(ConfluenceToolService confluenceToolService, OfficeWorkspace workspace, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.workspace = workspace;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Attach a file from this conversation (attachments/ or outputs/) to a Confluence page. A file of the same name is replaced with a new version. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "The page."),
            @ToolResultField(name = "attachment", type = ToolFieldType.OBJECT, always = true,
                    description = "id, fileName, sizeBytes and url of the attachment.")
    })
    public Map<String, Object> attachConfluenceFile(
            @ToolParam(required = true, description = "Page id") String pageId,
            @ToolParam(required = true, description = "File name, e.g. diagram.png, or attachments/<name> or "
                    + "outputs/<name>") String file,
            @ToolParam(required = false, description = "Version comment") String comment) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            Map<String, Object> attachment = confluenceToolService.attachConfluenceFile(pageId, workspace.file(file), comment);
            return Map.of("success", true, "pageId", pageId, "attachment", attachment);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
