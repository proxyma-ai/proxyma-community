package ai.proxyma.core.tools.svn;

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
 * Directory listing for the SVN server, without a checkout.
 *
 * <p>`svn list` queries the repository directly, so this costs one request no matter how large the
 * working tree would have been - the same reason the Bitbucket tools browse over the API instead
 * of cloning.
 */
@Component
public class ListSvnFilesTool {

    private final SvnToolService svnToolService;

    public ListSvnFilesTool(SvnToolService svnToolService) {
        this.svnToolService = svnToolService;
    }

    @Tool(description = "List SVN files and folders (type FILE or DIRECTORY) live, on the SVN server set in "
            + "Settings - no checkout and no registration needed. One level unless recursive. Read a file "
            + "with readSvnFile.")
    @ToolResult({
            @ToolResultField(name = "path", type = ToolFieldType.STRING, always = true,
                    description = "The path listed, echoed back."),
            @ToolResultField(name = "entries", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Each with path (from the root, as readSvnFile takes it), kind (FILE or "
                            + "DIRECTORY), and size/revision/lastModified where reported. Empty is a success."),
            @ToolResultField(name = "count", type = ToolFieldType.INTEGER, always = true,
                    description = "Length of 'entries'."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when the listing was cut - narrow 'path'.")
    })
    public Map<String, Object> listSvnFiles(
            @ToolParam(required = false, description = "Path below the SVN server set in Settings, e.g. trunk/src, or a full URL on that "
                    + "server; omit for the root")
            String path,
            @ToolParam(required = false, description = "Whole subtree (slow on a large path)") Boolean recursive) {
        try {
            Map<String, Object> listed = svnToolService.listFiles(
                    path, Boolean.TRUE.equals(recursive));
            Map<String, Object> result = new LinkedHashMap<>(listed);
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
