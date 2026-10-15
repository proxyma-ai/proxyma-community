package ai.proxyma.core.tools.office;

import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop only: the two ways a file crosses between the user's own disk and the conversation's
 * workspace. Each call waits for the user's approval, showing the path (WriteToolRegistry.LOCAL_FILE_NAMES)
 * - the maintainer's rule, 2026-09-26: anything outside the workspace asks, every time. Registered
 * only where code runs on this machine (McpToolConfig), because on Home Server "a path on disk" is a
 * path inside a container the user cannot see.
 */
@Component
public class LocalFileTools {

    private final OfficeWorkspace workspace;

    public LocalFileTools(OfficeWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(name = "openLocalFile", description = "Desktop: copy a file from anywhere on the user's computer into "
            + "attachments/ so runPython can read or edit it and convertDocument can read it by its 'path'. "
            + "The user approves each call.")
    @ToolResult({
            @ToolResultField(name = "name", type = ToolFieldType.STRING, always = true,
                    description = "The file's name in attachments/."),
            @ToolResultField(name = "path", type = ToolFieldType.STRING, always = true,
                    description = "The copy's full path - what convertDocument takes.")
    })
    public Map<String, Object> openLocalFile(@ToolParam(description = "Absolute path of the file") String path) {
        Path conversation = workspace.conversationDir();
        if (conversation == null) return Map.of("success", false, "error", "Only inside a chat conversation.");
        try {
            Path source = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return Map.of("success", false, "error", "Not a file: " + path);
            }
            if (Files.size(source) > PythonSandbox.Limits.MAX_FILE_BYTES) {
                return Map.of("success", false, "error", "Larger than 25 MB.");
            }
            Path dir = Files.createDirectories(conversation.resolve("attachments"));
            Path target = dir.resolve(source.getFileName().toString());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            // The copy's PATH as well as its name, since 2026-09-29: convertDocument takes a path, and
            // with only a name the model had no exact path to hand it after bringing a file in.
            return Map.of("success", true, "name", target.getFileName().toString(), "path", target.toString());
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }

    @Tool(name = "saveLocalFile", description = "Desktop: save a file from outputs/ to a path on the user's "
            + "computer - a new file, or over an existing one when overwrite is true. The user approves each call.")
    @ToolResult({
            @ToolResultField(name = "savedTo", type = ToolFieldType.STRING, always = true, description = "Where it was saved.")
    })
    public Map<String, Object> saveLocalFile(
            @ToolParam(description = "Name of the file in outputs/") String fileName,
            @ToolParam(description = "Absolute destination path, including the file name") String path,
            @ToolParam(required = false, description = "Replace an existing file. Default false.") Boolean overwrite) {
        Path conversation = workspace.conversationDir();
        if (conversation == null) return Map.of("success", false, "error", "Only inside a chat conversation.");
        try {
            Path outputs = conversation.resolve("outputs");
            Path source = outputs.resolve(fileName).normalize();
            if (!source.getParent().equals(outputs.normalize()) || !Files.isRegularFile(source)) {
                return Map.of("success", false, "error", "No such file in outputs/: " + fileName);
            }
            Path target = Path.of(path).toAbsolutePath().normalize();
            if (Files.exists(target) && !Boolean.TRUE.equals(overwrite)) {
                return Map.of("success", false, "error", "A file already exists there. Ask the user before "
                        + "overwriting it, then call again with overwrite=true.");
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("savedTo", target.toString());
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", Failures.describe(e));
        }
    }
}
