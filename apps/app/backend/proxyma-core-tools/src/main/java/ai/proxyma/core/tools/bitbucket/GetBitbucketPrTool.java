package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GetBitbucketPrTool {

    private final BitbucketToolService bitbucketToolService;

    public GetBitbucketPrTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "A Bitbucket pull request: description, branches, reviewers, comments.")
    @ToolResult({
            @ToolResultField(name = "pullRequest", type = ToolFieldType.OBJECT, always = true,
                    description = "Flattened by this platform: url, fromBranch, toBranch, state, content (the PR "
                            + "as Markdown - description, reviewers, comments), and jiraIssueKeys when any.")
    })
    public Map<String, Object> getBitbucketPr(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "PR id") int prId) {
        try {
            var result = bitbucketToolService.getPr(projectKey, repoSlug, prId);
            return Map.of("success", true, "pullRequest", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
