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
public class CreateBitbucketTagTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public CreateBitbucketTagTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Create a tag in a Bitbucket repo.")
    @ToolResult({
            @ToolResultField(name = "tag", type = ToolFieldType.OBJECT, always = true,
                    description = "The created tag, flattened by this platform: name, ref, commitId, url.")
    })
    public Map<String, Object> createBitbucketTag(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "Tag name, e.g. v1.4.0") String name,
            @ToolParam(required = true, description = "Commit hash, branch or existing tag to tag") String startPoint,
            @ToolParam(required = false, description = "Annotation; omit for a lightweight tag") String message) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            var result = bitbucketToolService.createTag(projectKey, repoSlug, name, startPoint, message);
            return Map.of("success", true, "tag", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
