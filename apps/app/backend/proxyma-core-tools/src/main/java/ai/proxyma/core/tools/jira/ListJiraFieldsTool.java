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
public class ListJiraFieldsTool {

    private final JiraToolService jiraToolService;

    public ListJiraFieldsTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "List this Jira server's fields: id, name, custom or not, type. Call before writing a custom "
            + "field: customfield_NNNNN ids differ per server.")
    @ToolResult({
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "How many fields matched."),
            @ToolResultField(name = "fields", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Matching fields, each with id, name, custom and type. Empty when the filter "
                            + "matched nothing - which is a SUCCESS, not an error.")
    })
    public Map<String, Object> listJiraFields(
            @ToolParam(required = false, description = "Case-insensitive substring of name or id, e.g. 'sprint'. Omit "
                    + "for all") String nameFilter) {
        try {
            var result = jiraToolService.listJiraFields(nameFilter);
            return Map.of("success", true, "count", result.size(), "fields", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
