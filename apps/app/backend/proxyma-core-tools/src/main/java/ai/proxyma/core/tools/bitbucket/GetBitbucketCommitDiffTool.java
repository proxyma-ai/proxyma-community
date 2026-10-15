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
public class GetBitbucketCommitDiffTool {

    private static final int DEFAULT_CONTEXT_LINES = 5;
    private static final int MAX_CONTEXT_LINES = 25;

    private final BitbucketToolService bitbucketToolService;

    public GetBitbucketCommitDiffTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Unified diff plus per-file summary of a Bitbucket commit - what it actually did, not its "
            + "message. Files cut for size are listed in omittedFiles; fetch each via path.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project queried."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository queried."),
            @ToolResultField(name = "commitId", type = ToolFieldType.STRING, always = true,
                    description = "Commit whose diff this is."),
            @ToolResultField(name = "result", type = ToolFieldType.OBJECT, always = true,
                    description = "The rendered diff, built by this platform: the changed paths and their "
                            + "hunks.")
    })
    public Map<String, Object> getBitbucketCommitDiff(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "Commit hash, full or short") String commitId,
            @ToolParam(required = false, description = "Diff only this file") String path,
            @ToolParam(required = false, description = "Context lines per change (default 5, max 25)")
            Integer contextLines) {
        try {
            int ctx = contextLines == null || contextLines < 0
                    ? DEFAULT_CONTEXT_LINES : Math.min(contextLines, MAX_CONTEXT_LINES);
            var result = bitbucketToolService.commitDiff(projectKey, repoSlug, commitId, path, ctx);
            return Map.of("success", true, "projectKey", projectKey, "repoSlug", repoSlug,
                    "commitId", commitId, "result", result);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
