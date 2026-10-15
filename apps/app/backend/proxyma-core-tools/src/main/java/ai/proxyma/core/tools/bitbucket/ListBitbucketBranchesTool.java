package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ListBitbucketBranchesTool {

    private final BitbucketToolService bitbucketToolService;

    public ListBitbucketBranchesTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "List a Bitbucket repo's branches, each with Jira issue keys found in its name "
            + "(jiraIssueKeys).")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project queried."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository queried."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "Number of entries in 'branches'."),
            @ToolResultField(name = "branches", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "The matching branches.")
    })
    public Map<String, Object> listBitbucketBranches(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Case-insensitive substring, e.g. release, or a Jira key "
                    + "(MYPROJ-123) to find its branch; omit for all") String filter) {
        try {
            List<Map<String, Object>> branches = bitbucketToolService.listBranches(projectKey, repoSlug, filter);
            return Map.of(
                    "success", true,
                    "projectKey", projectKey,
                    "repoSlug", repoSlug,
                    "count", branches.size(),
                    "branches", branches);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
