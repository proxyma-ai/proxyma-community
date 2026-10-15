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
public class ListBitbucketTagsTool {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 25;

    private final BitbucketToolService bitbucketToolService;

    public ListBitbucketTagsTool(BitbucketToolService bitbucketToolService) {
        this.bitbucketToolService = bitbucketToolService;
    }

    @Tool(description = "List a Bitbucket repo's tags, in the server's own order (not by date).")
    @ToolResult({
            @ToolResultField(name = "projectKey", type = ToolFieldType.STRING, always = true,
                    description = "Project queried."),
            @ToolResultField(name = "repoSlug", type = ToolFieldType.STRING, always = true,
                    description = "Repository queried."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "Number of entries in 'tags'."),
            @ToolResultField(name = "tags", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "The matching tags.")
    })
    public Map<String, Object> listBitbucketTags(
            @ToolParam(required = true, description = "Project key, e.g. MYPROJ") String projectKey,
            @ToolParam(required = true, description = "Repo slug") String repoSlug,
            @ToolParam(required = false, description = "Substring, e.g. v2.; omit for all tags")
            String filter,
            @ToolParam(required = false, description = "Default 25, max 100") Integer limit) {
        try {
            int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            List<Map<String, Object>> tags =
                    bitbucketToolService.listTags(projectKey, repoSlug, filter, effectiveLimit);
            return Map.of(
                    "success", true,
                    "projectKey", projectKey,
                    "repoSlug", repoSlug,
                    "count", tags.size(),
                    "tags", tags);
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
