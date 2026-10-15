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
 * Recursive path listing for a Bitbucket repository, without cloning it.
 *
 * <p>Paired with browseBitbucketPath, which reads one level or one file. The split matters for how
 * an agent should work: this answers "does this repo contain X" in one call over the whole tree,
 * and browse answers "what is in this directory" without dragging 50k sibling paths along.
 */
@Component
public class ListBitbucketFilesTool {

    private static final int DEFAULT_MAX = 2000;
    private static final int HARD_MAX = 10000;

    private final BitbucketToolService bitbucketToolService;

    public ListBitbucketFilesTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Recursively list a Bitbucket repo's file paths without cloning, to find where something "
            + "lives (read it with browseBitbucketPath). Scope a large repo with path.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "The project key the call was made with."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "The repository slug the call was made with."),
            @ToolResultField(name = "files", type = ToolFieldType.STRING_ARRAY, always = true,
                    description = "Repository-relative file paths. Directories are implied, not listed."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "Length of 'files'."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when maxEntries cut the list - narrow 'path' to see the rest.")
    })
    public Map<String, Object> listBitbucketFiles(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Subdirectory, e.g. src/main/java; omit for the whole repo")
            String path,
            @ToolParam(required = false, description = "Branch, tag or commit; omit for the default branch") String at,
            @ToolParam(required = false, description = "Max paths, 1-10000 (default 2000)") Integer maxEntries) {
        try {
            int max = maxEntries == null ? DEFAULT_MAX : Math.min(HARD_MAX, Math.max(1, maxEntries));
            Map<String, Object> listed = bitbucketToolService.listFiles(
                    projectKey, repoSlug, path, at, max);
            Map<String, Object> result = new LinkedHashMap<>(listed);
            result.put("success", true);
            result.put("projectKey", projectKey);
            result.put("repoSlug", repoSlug);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
