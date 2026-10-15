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
public class SearchConfluencePagesTool {

    private final ConfluenceToolService confluenceToolService;

    public SearchConfluencePagesTool(ConfluenceToolService confluenceToolService) {
        this.confluenceToolService = confluenceToolService;
    }

    @Tool(description = "Search Confluence pages with CQL; put any scope in the CQL. For 'pages I created' use creator "
            + "= currentUser() (or contributor/watcher); ask who the user is only if that finds nothing.")
    @ToolResult({
            @ToolResultField(name = "pages", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Matching pages, each with id, title, url and a short excerpt. Empty when the "
                            + "CQL matched nothing.")
    })
    public Map<String, Object> searchConfluencePages(
            @ToolParam(required = true, description = "CQL, e.g. space = DOCS AND type = page AND text ~ \"deployment "
                    + "guide\"") String cql,
            @ToolParam(required = false, description = "Max results (default 20, at most 100; no paging)") Integer maxResults) {
        try {
            var result = confluenceToolService.searchConfluencePages(cql, maxResults != null ? maxResults : 20);
            return Map.of("success", true, "pages", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
