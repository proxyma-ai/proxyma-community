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
public class MergeBitbucketPrTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public MergeBitbucketPrTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Merge an open Bitbucket pull request into its target branch; cannot be undone. Merge checks "
            + "apply: relay a conflict or unmet check, do not retry.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project the pull request belongs to."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository the pull request belongs to."),
            @ToolResultField(name = "prId", type = ToolFieldType.INTEGER, always = true,
                    description = "Pull request that was merged."),
            @ToolResultField(name = "state", type = ToolFieldType.STRING, always = true,
                    description = "The pull request's state after merging, as Bitbucket reports it, e.g. "
                            + "MERGED.")
    })
    public Map<String, Object> mergeBitbucketPr(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "PR id") int prId,
            @ToolParam(required = false, description = "Merge commit message; omit for Bitbucket's default")
            String message,
            @ToolParam(required = false, description = "e.g. no-ff, ff, ff-only, rebase-no-ff, squash; omit for the "
                    + "repo default") String strategy) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            String state = bitbucketToolService.mergePr(projectKey, repoSlug, prId, message, strategy);
            return Map.of("success", true, "projectKey", projectKey, "repoSlug", repoSlug,
                    "prId", prId, "state", state);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
