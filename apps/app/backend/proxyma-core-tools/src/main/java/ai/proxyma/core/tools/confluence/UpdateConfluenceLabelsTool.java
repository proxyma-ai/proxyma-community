package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;

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
public class UpdateConfluenceLabelsTool {

    private final ConfluenceToolService confluenceToolService;
    private final McpGuardService mcpGuard;

    public UpdateConfluenceLabelsTool(ConfluenceToolService confluenceToolService, McpGuardService mcpGuard) {
        this.confluenceToolService = confluenceToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Add and/or remove labels on a Confluence page. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "pageId", type = ToolFieldType.STRING, always = true,
                    description = "The page."),
            @ToolResultField(name = "labels", type = ToolFieldType.STRING_ARRAY, always = true,
                    description = "Every label the page has afterwards.")
    })
    public Map<String, Object> updateConfluenceLabels(
            @ToolParam(required = true, description = "Page id") String pageId,
            @ToolParam(required = false, description = "Labels to add, e.g. [\"release-notes\"]") List<String> addLabels,
            @ToolParam(required = false, description = "Labels to remove") List<String> removeLabels) {
        try {
            mcpGuard.checkConfluenceWrite(SearchContext.getUserId());
            List<String> labels = confluenceToolService.updateConfluenceLabels(pageId, addLabels, removeLabels);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("pageId", pageId);
            result.put("labels", labels);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
