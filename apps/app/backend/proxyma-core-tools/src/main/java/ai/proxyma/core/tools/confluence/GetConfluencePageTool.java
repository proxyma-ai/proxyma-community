package ai.proxyma.core.tools.confluence;


import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GetConfluencePageTool {

    private final ConfluenceToolService confluenceToolService;

    public GetConfluencePageTool(ConfluenceToolService confluenceToolService) {
        this.confluenceToolService = confluenceToolService;
    }

    @Tool(description = "Fetch a Confluence page live: body, comments, labels. A page id names no server: with several Confluence connections, call useServer first if the id came from another one.")
    @ToolResult({
            @ToolResultField(name = "page", type = ToolFieldType.STRING, always = true,
                    description = "The page rendered as text, body and comments together. Text, not an object - "
                            + "there are no fields to bind to.")
    })
    public Map<String, Object> getConfluencePage(
            @ToolParam(required = true, description = "Page ID, e.g. 123456789") String pageId) {
        try {
            var result = confluenceToolService.getConfluencePage(pageId);
            return Map.of("success", true, "page", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
