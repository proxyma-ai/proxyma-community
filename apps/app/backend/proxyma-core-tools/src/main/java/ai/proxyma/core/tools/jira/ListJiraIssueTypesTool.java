package ai.proxyma.core.tools.jira;


import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ListJiraIssueTypesTool {

    private final JiraToolService jiraToolService;

    public ListJiraIssueTypesTool(JiraToolService jiraToolService) {
        this.jiraToolService = jiraToolService;
    }

    @Tool(description = "List the issue types a Jira project can create (Epic, Story, Sub-task, custom ones). With "
            + "issueType: the fields that type takes at creation - id, required, allowed values - and so whether a "
            + "Story goes under an Epic by 'parent' or by an 'Epic Link' field, and whether an Epic needs 'Epic Name'.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "The project asked about."),
            @ToolResultField(name = "issueTypes", type = ToolFieldType.OBJECT_ARRAY,
                    description = "Without issueType: each with id, name, subtask (true for sub-task types) and "
                            + "description."),
            @ToolResultField(name = "issueType", type = ToolFieldType.STRING,
                    description = "With issueType: the type's name as Jira spells it."),
            @ToolResultField(name = "subtask", type = ToolFieldType.BOOLEAN,
                    description = "With issueType: true for a sub-task type, which needs a parent."),
            @ToolResultField(name = "fields", type = ToolFieldType.OBJECT_ARRAY,
                    description = "With issueType: each with id (what createJiraIssue's fields takes), name, "
                            + "required, type, and allowedValues where Jira lists them.")
    })
    public Map<String, Object> listJiraIssueTypes(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = false, description = "An issue type name, e.g. Story, for its create fields. Omit "
                    + "for the list of types") String issueType) {
        try {
            Map<String, Object> result = new LinkedHashMap<>(jiraToolService.listJiraIssueTypes(projectKey, issueType));
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
