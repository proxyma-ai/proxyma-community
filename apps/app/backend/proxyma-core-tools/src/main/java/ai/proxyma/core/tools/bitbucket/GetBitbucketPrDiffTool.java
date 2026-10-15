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
public class GetBitbucketPrDiffTool {

    private static final int DEFAULT_CONTEXT_LINES = 5;
    private static final int MAX_CONTEXT_LINES = 25;

    private final BitbucketToolService bitbucketToolService;

    public GetBitbucketPrDiffTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Unified diff plus per-file summary of a Bitbucket pull request. Review from this, never "
            + "from the title or description. Files cut for size are listed in omittedFiles; fetch each via path.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project queried."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository queried."),
            @ToolResultField(name = "prId", type = ToolFieldType.INTEGER, always = true,
                    description = "Pull request whose diff this is."),
            @ToolResultField(name = "result", type = ToolFieldType.OBJECT, always = true,
                    description = "The rendered diff, built by this platform: the changed paths and their "
                            + "hunks.")
    })
    public Map<String, Object> getBitbucketPrDiff(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "PR id") int prId,
            @ToolParam(required = false, description = "Diff only this file, e.g. src/main/java/Foo.java") String path,
            @ToolParam(required = false, description = "Context lines per change (default 5, max 25)")
            Integer contextLines) {
        try {
            int ctx = contextLines == null || contextLines < 0
                    ? DEFAULT_CONTEXT_LINES : Math.min(contextLines, MAX_CONTEXT_LINES);
            var result = bitbucketToolService.prDiff(projectKey, repoSlug, prId, path, ctx);
            return Map.of("success", true, "projectKey", projectKey, "repoSlug", repoSlug,
                    "prId", prId, "result", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
