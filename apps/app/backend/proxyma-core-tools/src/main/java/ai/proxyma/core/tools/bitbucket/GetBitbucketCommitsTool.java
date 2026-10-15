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
public class GetBitbucketCommitsTool {

    // A single Bitbucket page; also the ceiling on how much commit history is worth pushing into
    // one model context. Callers asking for more are clamped rather than rejected.
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 10;

    private final BitbucketToolService bitbucketToolService;

    public GetBitbucketCommitsTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Commit history of a Bitbucket branch, newest first.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project queried."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository queried."),
            @ToolResultField(name = "branch", type = ToolFieldType.STRING, always = true,
                    description = "Branch queried, or the literal '(default branch)' when the request named "
                            + "none."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "Number of entries in 'commits'. Length of what was returned, not the "
                            + "repository total."),
            @ToolResultField(name = "commits", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "The commits, newest first.")
    })
    public Map<String, Object> getBitbucketCommits(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Branch; omit for the default branch (listBitbucketBranches "
                    + "lists them)") String branch,
            @ToolParam(required = false, description = "Default 10, max 100") Integer limit) {
        try {
            int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            List<Map<String, Object>> commits =
                    bitbucketToolService.listCommits(projectKey, repoSlug, branch, effectiveLimit);
            return Map.of(
                    "success", true,
                    "projectKey", projectKey,
                    "repoSlug", repoSlug,
                    "branch", branch != null && !branch.isBlank() ? branch : "(default branch)",
                    "count", commits.size(),
                    "commits", commits);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
