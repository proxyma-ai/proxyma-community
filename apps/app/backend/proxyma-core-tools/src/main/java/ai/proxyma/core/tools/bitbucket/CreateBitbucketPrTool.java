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

import java.util.List;
import java.util.Map;

@Component
public class CreateBitbucketPrTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public CreateBitbucketPrTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Open a Bitbucket pull request.")
    @ToolResult({
            @ToolResultField(name = "pullRequest", type = ToolFieldType.OBJECT, always = true,
                    description = "Flattened by this platform: prId, title, state, fromBranch, toBranch, url.")
    })
    public Map<String, Object> createBitbucketPr(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "Title") String title,
            @ToolParam(required = true, description = "Source branch, e.g. feature/MYPROJ-123-x") String fromBranch,
            @ToolParam(required = true, description = "Target branch, e.g. main") String toBranch,
            @ToolParam(required = false, description = "Description (markdown)") String description,
            @ToolParam(required = false, description = "Reviewer login names, not display names")
            List<String> reviewers) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            var result = bitbucketToolService.createPr(projectKey, repoSlug, title, description,
                    fromBranch, toBranch, reviewers);
            return Map.of("success", true, "pullRequest", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
