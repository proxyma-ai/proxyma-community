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
 * One file's text straight from the SVN server, without a checkout.
 *
 * <p>Text only. `svn cat` will happily stream a binary and the bytes would arrive as mojibake, so
 * a caller should reach here for source and configuration and use listSvnFiles' size field to
 * decide whether a path is worth reading at all.
 */
@Component
public class ReadSvnFileTool {

    private final SvnToolService svnToolService;

    public ReadSvnFileTool(SvnToolService svnToolService) {
        this.svnToolService = svnToolService;
    }

    @Tool(description = "Read one SVN text file live - no checkout and no registration needed. A binary file comes back garbled, not as an error.")
    @ToolResult({
            @ToolResultField(name = "path", type = ToolFieldType.STRING, always = true,
                    description = "The path read, echoed back."),
            @ToolResultField(name = "content", type = ToolFieldType.STRING, always = true,
                    description = "The file's text."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "Present and true when the file was longer than the cap.")
    })
    public Map<String, Object> readSvnFile(
            @ToolParam(required = true, description = "Path below the SVN server set in Settings, as listSvnFiles "
                    + "gives it (e.g. trunk/src/main.c), or a full URL on that server") String path) {
        try {
            Map<String, Object> read = svnToolService.readFile(path);
            Map<String, Object> result = new LinkedHashMap<>(read);
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
