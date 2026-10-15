package ai.proxyma.core.tools.office;

import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-written Python for creating and editing Word, Excel and PowerPoint files - the maintainer's
 * choice over fixed document tools, 2026-09-26, after Anthropic's own office skills. Where it runs
 * is the {@link PythonSandbox}'s business; this tool moves the conversation's files in and out.
 *
 * <p>Not a write tool in WriteToolRegistry's sense, deliberately: it writes only into this
 * conversation's own outputs/ folder, and the maintainer chose no approval inside the workspace.
 * Reading a file from elsewhere on disk, or saving over one, are separate Desktop tools that do ask.
 * It is still kept from a sub-agent started outside a conversation, which has no workspace
 * (WriteToolRegistry.WORKSPACE_NAMES); one inside a conversation may call it, as the main agent may.
 */
@Component
public class RunPythonTool {

    // Under ChatService's 60s per-tool ceiling, with room to move the files either side.
    static final Duration TIMEOUT = Duration.ofSeconds(50);

    private final PythonSandbox sandbox;
    private final OfficeWorkspace workspace;

    public RunPythonTool(PythonSandbox sandbox, OfficeWorkspace workspace) {
        this.sandbox = sandbox;
        this.workspace = workspace;
    }

    @Tool(name = "runPython", description = "Run Python 3 to create or edit Word, Excel and PowerPoint files "
            + "(python-docx, openpyxl, python-pptx, pandas, matplotlib). Working directory: attachments/ holds the "
            + "user's uploads (read-only), outputs/ is where every file you make goes and where earlier outputs can "
            + "be edited. No network, no subprocesses, 50s limit. Returns stdout/stderr (last 8,000 characters each) "
            + "and each output file with a url to link and a path for convertDocument. Only files directly in "
            + "outputs/ are returned, at most 25 MB each and 100 MB together - subfolders and larger files are "
            + "dropped.")
    @ToolResult({
            @ToolResultField(name = "files", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Every file directly in outputs/ within the size limits: name, sizeBytes, "
                            + "changed (by this run), url, path."),
            @ToolResultField(name = "stdout", type = ToolFieldType.STRING, always = true, description = "The program's output."),
            @ToolResultField(name = "stderr", type = ToolFieldType.STRING, always = true, description = "Errors and tracebacks.")
    })
    public Map<String, Object> runPython(@ToolParam(description = "The program. Save files under outputs/.") String code) {
        String conversationId = SearchContext.getConversationId();
        Path conversation;
        try {
            conversation = workspace.conversationDir();
        } catch (IllegalArgumentException e) {
            // An id that names no folder of the caller's own - nothing is read, run or written.
            return Map.of("success", false, "error", e.getMessage());
        }
        if (conversation == null) {
            return Map.of("success", false, "error", "runPython works inside a chat conversation only.");
        }
        if (!sandbox.isAvailable()) {
            // Two different causes: the local sandbox is the bundled Python runtime (Desktop, Home
            // Server), which no administrator sets up; only the container sandbox is an operator's.
            return Map.of("success", false, "error", sandbox instanceof LocalPythonSandbox
                    ? "Running code is not available: this installation's bundled Python runtime is missing "
                            + "or failed to start. Tell the user; do not retry."
                    : "Running code is not available on this installation. Tell the user an administrator "
                            + "has to set up the sandbox; do not retry.");
        }
        try {
            Map<String, byte[]> inputs = workspace.inputs(conversation);
            PythonSandbox.Result run = sandbox.run(code, inputs, TIMEOUT);
            List<OfficeWorkspace.SavedFile> saved = workspace.saveOutputs(conversation, inputs, run.outputs());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", run.exitCode() == 0 && !run.timedOut());
            result.put("exitCode", run.exitCode());
            if (run.timedOut()) result.put("error", "Stopped after " + TIMEOUT.toSeconds() + "s.");
            result.put("stdout", run.stdout());
            result.put("stderr", run.stderr());
            result.put("files", saved.stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("sizeBytes", f.sizeBytes());
                m.put("changed", f.changed());
                m.put("url", OfficeWorkspace.fileUrl(conversationId, f.name()));
                m.put("path", f.path().toString());
                return m;
            }).toList());
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", "The run failed: " + Failures.describe(e));
        }
    }
}
