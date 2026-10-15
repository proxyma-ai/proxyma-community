package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReviewBitbucketPrTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public ReviewBitbucketPrTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Set your review on a Bitbucket pull request. An approval counts toward merge checks: read "
            + "the PR and its diff first; approve only on the user's explicit go-ahead.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project the pull request belongs to."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository the pull request belongs to."),
            @ToolResultField(name = "prId", type = ToolFieldType.INTEGER, always = true,
                    description = "Pull request reviewed."),
            @ToolResultField(name = "status", type = ToolFieldType.STRING, always = true,
                    description = "The review status actually applied, normalized, e.g. APPROVED.")
    })
    public Map<String, Object> reviewBitbucketPr(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "PR id") int prId,
            @ToolParam(required = true, description = "APPROVED, NEEDS_WORK, or UNAPPROVED (withdraws a review)")
            String status) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            String applied = bitbucketToolService.reviewPr(projectKey, repoSlug, prId, status);
            return Map.of("success", true, "projectKey", projectKey, "repoSlug", repoSlug,
                    "prId", prId, "status", applied);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
