package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.search.domain.SearchContext;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AddBitbucketPrCommentTool {

    private final BitbucketToolService bitbucketToolService;
    private final McpGuardService mcpGuard;

    public AddBitbucketPrCommentTool(BitbucketToolService bitbucketToolService, McpGuardService mcpGuard) {
        this.bitbucketToolService = bitbucketToolService;
        this.mcpGuard = mcpGuard;
    }

    @Tool(description = "Comment on a Bitbucket pull request: generally, on a file, on one line of its diff, "
            + "or as a reply to another comment.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project the pull request belongs to."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository the pull request belongs to."),
            @ToolResultField(name = "prId", type = ToolFieldType.INTEGER, always = true,
                    description = "Pull request the comment was added to."),
            @ToolResultField(name = "commentId", type = ToolFieldType.INTEGER, always = true,
                    description = "The new comment's id - what parentCommentId takes to reply to it.")
    })
    public Map<String, Object> addBitbucketPrComment(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = true, description = "PR id") int prId,
            @ToolParam(required = true, description = "Comment text") String text,
            @ToolParam(required = false, description = "File path in the repo, as the diff names it, for a comment "
                    + "on that file") String path,
            @ToolParam(required = false, description = "Line number in that file, for a comment on one line. In the "
                    + "new file, or the old one for a REMOVED line") Integer line,
            @ToolParam(required = false, description = "ADDED (default), REMOVED or CONTEXT - the kind of diff line")
            String lineType,
            @ToolParam(required = false, description = "Id of the comment to reply to") Long parentCommentId) {
        try {
            mcpGuard.checkBitbucketWrite(SearchContext.getUserId());
            if (line != null && (path == null || path.isBlank())) {
                return Map.of("success", false, "error", "A line comment needs the file's path as well.");
            }
            long commentId = bitbucketToolService.addPrComment(projectKey, repoSlug, prId, text, path, line,
                    lineType, parentCommentId);
            return Map.of("success", true, "projectKey", projectKey, "repoSlug", repoSlug, "prId", prId,
                    "commentId", commentId);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
