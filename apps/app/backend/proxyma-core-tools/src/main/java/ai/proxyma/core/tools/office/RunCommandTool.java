package ai.proxyma.core.tools.office;

import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A SHELL COMMAND, as a person at a terminal runs one (the maintainer's choice, 2026-10-28: "a shell
 * everywhere, sandboxed"). Where it runs is the edition's:
 *
 * <ul>
 *   <li><b>Home Server and Enterprise Server</b>: in the sandbox container (PythonSandbox.shell) -
 *       /bin/sh as nobody, in a fresh directory holding this conversation's attachments/ and
 *       outputs/, with no network and no volume. What it leaves in outputs/ comes back as the
 *       conversation's files, as runPython's do.</li>
 *   <li><b>Desktop</b>: on the user's own machine, through the system shell (cmd on Windows,
 *       /bin/sh elsewhere), in this conversation's outputs/ folder or a directory the agent names,
 *       with a clean environment - no secret the app holds in its own is in the command's.</li>
 * </ul>
 *
 * <p>A WRITE TOOL everywhere (WriteToolRegistry.COMMAND_NAMES): Read-only refuses it and Ask shows the
 * command and waits for the user, every time. A minute at most when the agent waits for it, as any
 * tool; started with {@code background}, up to ten minutes in the container and half an hour on
 * Desktop. The output's last 8,000 characters of each stream come back.
 */
@Component
public class RunCommandTool {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(50);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    private final PythonSandbox sandbox;
    private final OfficeWorkspace workspace;

    public RunCommandTool(PythonSandbox sandbox, OfficeWorkspace workspace) {
        this.sandbox = sandbox;
        this.workspace = workspace;
    }

    @Tool(name = "runCommand", description = """
            Run a shell command, as at a terminal. Each call is shown to the user for approval first \
            (unless the conversation runs in Auto). Desktop: runs on the user's own machine (cmd on \
            Windows, sh elsewhere) in this conversation's outputs/ folder, or in workingDirectory. \
            Servers: runs in an isolated sandbox with no network, where attachments/ holds the \
            user's uploads and files left in outputs/ are kept. Waited for, a command may take 50 \
            seconds; for anything longer start it with the background tool, which allows up to 10 \
            minutes in the sandbox and 30 on Desktop. Returns exitCode, stdout and stderr (last \
            8,000 characters each).""")
    @ToolResult({
            @ToolResultField(name = "exitCode", type = ToolFieldType.INTEGER, always = true, description = "The command's exit code; -1 when it was stopped."),
            @ToolResultField(name = "stdout", type = ToolFieldType.STRING, always = true, description = "Its output."),
            @ToolResultField(name = "stderr", type = ToolFieldType.STRING, always = true, description = "Its errors."),
            @ToolResultField(name = "files", type = ToolFieldType.OBJECT_ARRAY,
                    description = "Servers: the files in outputs/ afterwards, as runPython returns them.")
    })
    public Map<String, Object> runCommand(
            @ToolParam(description = "The command line") String command,
            @ToolParam(required = false, description = "Desktop only: the directory to run it in; this conversation's outputs/ folder when omitted") String workingDirectory,
            @ToolParam(required = false, description = "Seconds it may run; 50 when waited for, more only when run with the background tool") Integer timeoutSeconds) {
        if (command == null || command.isBlank()) return Map.of("success", false, "error", "command must not be blank.");
        Path conversation;
        try {
            conversation = workspace.conversationDir();
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
        if (conversation == null) return Map.of("success", false, "error", "runCommand works inside a chat conversation only.");
        Duration timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT
                : Duration.ofSeconds(Math.max(1, Math.min(MAX_TIMEOUT.toSeconds(), timeoutSeconds)));
        try {
            if (sandbox instanceof LocalPythonSandbox) return local(command, conversation, workingDirectory, timeout);
            Map<String, byte[]> inputs = workspace.inputs(conversation);
            PythonSandbox.Result run = sandbox.shell(command, inputs, timeout);
            List<OfficeWorkspace.SavedFile> saved = workspace.saveOutputs(conversation, inputs, run.outputs());
            Map<String, Object> result = result(run.exitCode(), run.stdout(), run.stderr(), run.timedOut(), timeout);
            String conversationId = SearchContext.getConversationId();
            result.put("files", saved.stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("sizeBytes", f.sizeBytes());
                m.put("changed", f.changed());
                m.put("url", OfficeWorkspace.fileUrl(conversationId, f.name()));
                return m;
            }).toList());
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", "The command could not run: " + Failures.describe(e));
        }
    }

    // Desktop: the user's own shell, on their machine - what they approved.
    private Map<String, Object> local(String command, Path conversation, String workingDirectory, Duration timeout)
            throws IOException {
        Path dir;
        if (workingDirectory == null || workingDirectory.isBlank()) {
            dir = conversation.resolve("outputs");
            Files.createDirectories(dir);
        } else {
            dir = Path.of(workingDirectory.strip());
            if (!dir.isAbsolute() || !Files.isDirectory(dir)) {
                return Map.of("success", false, "error", "workingDirectory must be an existing absolute directory.");
            }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
        // A clean environment: nothing the app was started with - a key, a token - reaches the command.
        Map<String, String> env = pb.environment();
        Map<String, String> keep = new LinkedHashMap<>();
        for (String name : List.of("SYSTEMROOT", "SystemRoot", "WINDIR", "PATH", "Path", "PATHEXT", "COMSPEC",
                "LANG", "HOME", "USERPROFILE", "TEMP", "TMP", "USERNAME", "USER")) {
            String value = System.getenv(name);
            if (value != null) keep.put(name, value);
        }
        env.clear();
        env.putAll(keep);
        Path out = Files.createTempFile("proxyma-cmd-", ".out");
        Path err = Files.createTempFile("proxyma-cmd-", ".err");
        try {
            pb.directory(dir.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(windows ? new java.io.File("NUL") : new java.io.File("/dev/null")));
            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            return result(finished ? process.exitValue() : -1, SandboxFiles.tail(out, PythonSandbox.Limits.MAX_STREAM_CHARS),
                    SandboxFiles.tail(err, PythonSandbox.Limits.MAX_STREAM_CHARS), !finished, timeout);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static Map<String, Object> result(int exitCode, String stdout, String stderr, boolean timedOut, Duration timeout) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", exitCode == 0 && !timedOut);
        result.put("exitCode", exitCode);
        if (timedOut) result.put("error", "Stopped after " + timeout.toSeconds() + "s. Run it with the background tool to give it longer.");
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        return result;
    }
}
