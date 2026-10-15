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
 * Frees a snapshot that downloadBitbucketRepo wrote.
 *
 * <p>Exists because nothing else will. Snapshots live in the data directory so they survive a
 * restart, which also means they are the one thing there that grows without bound and that no
 * retention job prunes - and the deployment already has no aggregate-disk-usage alert. A feature
 * that can fill a disk needs the matching way to empty it, in the same change rather than the
 * next one.
 *
 * <p>Deletes only inside the snapshots directory, and only a path assembled from sanitized
 * segments - it cannot be aimed at the repository being browsed, at the data root, or anywhere
 * else on the host.
 */
@Component
public class DeleteBitbucketRepoTool {

    private final BitbucketToolService bitbucketToolService;

    public DeleteBitbucketRepoTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "Delete a local snapshot from downloadBitbucketRepo to free disk (nothing prunes them). "
            + "Bitbucket is untouched; a missing snapshot is a success.")
    @ToolResult({
            @ToolResultField(name = "directory", type = ToolFieldType.STRING, always = true,
                    description = "The path removed."),
            @ToolResultField(name = "deleted", type = ToolFieldType.BOOLEAN, always = true,
                    description = "False when there was no snapshot for that repo and ref.")
    })
    public Map<String, Object> deleteBitbucketRepo(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Ref that was downloaded; omit for the default branch")
            String at) {
        try {
            Map<String, Object> removed = bitbucketToolService.deleteSnapshot(projectKey, repoSlug, at);
            Map<String, Object> result = new LinkedHashMap<>(removed);
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
