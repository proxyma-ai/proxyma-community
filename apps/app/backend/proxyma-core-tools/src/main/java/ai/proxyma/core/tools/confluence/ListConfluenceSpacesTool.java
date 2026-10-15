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
public class ListConfluenceSpacesTool {

    private final ConfluenceToolService confluenceToolService;

    public ListConfluenceSpacesTool(ConfluenceToolService confluenceToolService) {
        this.confluenceToolService = confluenceToolService;
    }

    @Tool(description = "List visible Confluence spaces (key, name). Use to find a space key instead of asking the "
            + "user.")
    @ToolResult({
            @ToolResultField(name = "spaces", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Every visible space, each an object of key and name.")
    })
    public Map<String, Object> listConfluenceSpaces() {
        try {
            var result = confluenceToolService.listConfluenceSpaces();
            return Map.of("success", true, "spaces", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
