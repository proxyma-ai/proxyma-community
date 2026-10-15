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
public class DeleteBitbucketBranchTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public DeleteBitbucketBranchTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Delete a branch from a Bitbucket repo. Its commits stay reachable only if merged elsewhere.")
    @ToolResult({
            @ToolResultField(name = "branch", type = ToolFieldType.STRING, always = true,
                    description = "The branch deleted.")
    })
    public Map<String, Object> deleteBitbucketBranch(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "Branch name, e.g. feature/old-spike") String name) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            bitbucketToolService.deleteBranch(projectKey, repoSlug, name);
            return Map.of("success", true, "branch", name);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
