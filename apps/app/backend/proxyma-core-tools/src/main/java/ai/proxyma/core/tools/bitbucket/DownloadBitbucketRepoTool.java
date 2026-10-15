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
 * The whole repository on local disk, at one ref.
 *
 * <p>Deliberately the LAST resort of the three Bitbucket reading tools, and its description says
 * so. listBitbucketFiles answers "where is X" in one request and browseBitbucketPath reads a file
 * in one more; this transfers the entire tree. It earns its place when the answer needs many files
 * at once - a cross-cutting refactor, a dependency audit, anything wanting grep over the lot -
 * and is waste for anything narrower.
 */
@Component
public class DownloadBitbucketRepoTool {

    private static final int DEFAULT_MAX_MB = 500;
    private static final int HARD_MAX_MB = 4096;

    private final BitbucketToolService bitbucketToolService;

    public DownloadBitbucketRepoTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Download a Bitbucket repo's files (no git history) to local disk at one ref. You cannot "
            + "read the snapshot (read and grep reach only conversation files); read code with listBitbucketFiles "
            + "and browseBitbucketPath. Downloading the same repo and ref again replaces it.")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "The project key the call was made with."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "The repository slug the call was made with."),
            @ToolResultField(name = "directory", type = ToolFieldType.STRING, always = true,
                    description = "Absolute path the repository was unpacked into. Outside the "
                            + "conversation directory, so read and grep cannot open files under it."),
            @ToolResultField(name = "files", type = ToolFieldType.INTEGER, always = true,
                    description = "Number of files written."),
            @ToolResultField(name = "bytes", type = ToolFieldType.INTEGER, always = true,
                    description = "Total size on disk. Tell the user, and mention "
                            + "deleteBitbucketRepo when it is large."),
            @ToolResultField(name = "ref", type = ToolFieldType.STRING, always = true,
                    description = "The ref downloaded, or '(default branch)'.")
    })
    public Map<String, Object> downloadBitbucketRepo(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Branch, tag or commit; omit for the default branch") String at,
            @ToolParam(required = false, description = "Only this subdirectory, e.g. src/main; omit for the whole "
                    + "repo") String path,
            @ToolParam(required = false, description = "Abort above this size in MB, 1-4096 (default 500)")
            Integer maxMegabytes) {
        try {
            int cap = maxMegabytes == null ? DEFAULT_MAX_MB
                    : Math.min(HARD_MAX_MB, Math.max(1, maxMegabytes));
            Map<String, Object> downloaded = bitbucketToolService.downloadSnapshot(
                    projectKey, repoSlug, at, path, cap);
            Map<String, Object> result = new LinkedHashMap<>(downloaded);
            result.put("success", true);
            result.put("projectKey", projectKey);
            result.put("repoSlug", repoSlug);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
