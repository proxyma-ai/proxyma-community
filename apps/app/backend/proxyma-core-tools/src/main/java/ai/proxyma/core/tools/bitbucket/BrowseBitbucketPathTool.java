package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One directory level, or one file's text, from a Bitbucket repository without cloning it.
 *
 * <p>One tool rather than two because Bitbucket serves both from one endpoint and the caller
 * usually does not know which it has until it looks - walking a tree means alternating between
 * "list this" and "read that" on paths whose type came from the previous response.
 */
@Component
public class BrowseBitbucketPathTool {

    private static final int DEFAULT_MAX_LINES = 1000;
    // One page of Bitbucket's browse endpoint, which is all BitbucketConnector.browsePath fetches
    // (limit=1000). It said 5000, and a longer file came back cut at 1000 lines regardless.
    private static final int HARD_MAX_LINES = 1000;

    private final BitbucketToolService bitbucketToolService;

    public BrowseBitbucketPathTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "One directory level (type DIRECTORY, entries) or one file's text (type FILE, content) of a "
            + "Bitbucket repo, without cloning. Whole-tree search: listBitbucketFiles.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "The project key the call was made with."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "The repository slug the call was made with."),
            @ToolResultField(name = "path", type = ToolFieldType.STRING, always = true,
                    description = "The path that was read, empty for the repository root."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER,
                    description = "Directories only: how many entries were listed."),
            @ToolResultField(name = "lines", type = ToolFieldType.INTEGER,
                    description = "Files only: how many lines were returned."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "UNKNOWN only: why nothing could be shown (usually a binary file)."),
            @ToolResultField(name = "type", type = ToolFieldType.STRING, always = true,
                    description = "DIRECTORY, FILE, or UNKNOWN for a binary file. A missing path is an error "
                            + "(404), not UNKNOWN."),
            @ToolResultField(name = "entries", type = ToolFieldType.OBJECT_ARRAY,
                    description = "Directories only: name, path, type and size per child."),
            @ToolResultField(name = "content", type = ToolFieldType.STRING,
                    description = "Files only: the text, up to maxLines."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when the listing or the file was cut short.")
    })
    public Map<String, Object> browseBitbucketPath(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Repo-relative path; omit for the root") String path,
            @ToolParam(required = false, description = "Branch, tag or commit; omit for the default branch") String at,
            @ToolParam(required = false, description = "Max file lines, 1-1000 (default 1000)") Integer maxLines) {
        try {
            int max = maxLines == null ? DEFAULT_MAX_LINES
                    : Math.min(HARD_MAX_LINES, Math.max(1, maxLines));
            Map<String, Object> browsed = bitbucketToolService.browsePath(
                    projectKey, repoSlug, path, at, max);
            Map<String, Object> result = new LinkedHashMap<>(browsed);
            result.put("success", true);
            result.put("projectKey", projectKey);
            result.put("repoSlug", repoSlug);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
