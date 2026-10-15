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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CreateBitbucketBranchTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public CreateBitbucketBranchTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Create a branch in a Bitbucket repo.")
    @ToolResult({
            @ToolResultField(name = "branch", type = ToolFieldType.OBJECT, always = true,
                    description = "The created branch: name, ref, commitId.")
    })
    public Map<String, Object> createBitbucketBranch(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "Branch name, e.g. feature/PROJ-12-login") String name,
            @ToolParam(required = true, description = "Branch, tag or commit hash to start from") String startPoint) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            Map<String, Object> branch = bitbucketToolService.createBranch(projectKey, repoSlug, name, startPoint);
            return Map.of("success", true, "branch", branch);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
