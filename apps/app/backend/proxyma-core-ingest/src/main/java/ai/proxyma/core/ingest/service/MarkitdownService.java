package ai.proxyma.core.ingest.service;

import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.system.util.TimeConversion;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

// Wraps the bundled markitdown Python CLI to convert Office/PDF documents to Markdown.
//
// Preferred invocation is `<python> -m markitdown` through PythonRuntime's resolved
// interpreter - markitdown ships a real `__main__.py`, so this is equivalent to the `markitdown`
// console-script entry point. This is deliberately preferred over calling the pip-generated
// markitdown/markitdown.exe shortcut directly: that shortcut has an absolute interpreter path
// baked in at install time, which only stays valid because desktop installs Python in-place -
// invoking the module through the interpreter removes that fragility entirely. ${proxyma.markitdown.exe},
// when explicitly configured to something other than the "markitdown" default, still overrides
// everything else (an operator's deliberate choice), and a legacy direct-exe/PATH lookup remains
// as a last-resort fallback for environments with no resolvable Python interpreter.
@Service
public class MarkitdownService {

    private static final Logger log = LoggerFactory.getLogger(MarkitdownService.class);
    // Macro-enabled OOXML variants (docm/xlsm/pptm etc.) are the same zip container as their
    // non-macro counterparts (docx/xlsx/pptx) plus a vbaProject.bin part, so the underlying
    // python-docx/openpyxl/python-pptx libraries markitdown wraps read them identically.
    // "doc"/"ppt" are listed here too (routes them through markitdown rather than a raw UTF-8
    // text read) even though markitdown can never actually convert them - see
    // LEGACY_UNSUPPORTED_EXTENSIONS below for why, and where that gets a clear failure instead.
    private static final Set<String> CONVERTIBLE_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "docm", "dotm", "ppt", "pptx", "pptm", "potm", "ppsm",
            "xls", "xlsx", "xlsm", "xltm", "msg", "epub");
    // markitdown (bundled, with its office extras) has no converter class at all for the legacy OLE2
    // binary Word/PowerPoint formats - only DocxConverter (.docx) and PptxConverter (.pptx) exist,
    // no DocConverter/PptConverter. Unlike .xls (which DOES have a real xlrd-based XlsConverter),
    // there is no fix that makes these convert; the only correct behavior is a clear, immediate
    // failure telling the user to re-save as the OOXML equivalent, rather than spawning a process
    // destined to fail with an opaque Python traceback.
    private static final Set<String> LEGACY_UNSUPPORTED_EXTENSIONS = Set.of("doc", "ppt");
    private static final int TIMEOUT_MINUTES = 30;

    @Value("${proxyma.markitdown.exe:markitdown}")
    private String configuredExePath;

    private final AppPaths appPaths;
    private final PythonRuntime pythonRuntime;
    private volatile String resolvedExePath;
    // true when resolvedExePath is actually a python interpreter to invoke as `<python> -m
    // markitdown ...`, rather than a markitdown executable/shortcut to invoke directly.
    private volatile boolean useModuleInvocation;
    // true when resolvedExePath is a bare name a PATH probe actually ran, rather than a file.
    private volatile boolean foundOnPath;

    public MarkitdownService(AppPaths appPaths, PythonRuntime pythonRuntime) {
        this.appPaths = appPaths;
        this.pythonRuntime = pythonRuntime;
    }

    // Completed once init()'s resolution has run - see PythonProbe. Already complete for a service
    // nobody initialized, which then answers from the fields' defaults, as it always did.
    private volatile CompletableFuture<Void> resolution = CompletableFuture.completedFuture(null);

    // Resolution imports markitdown in a fresh interpreter, which takes a second or more; it runs
    // beside the other startup Python checks rather than before them (PythonProbe).
    @PostConstruct
    public void init() {
        resolution = PythonProbe.start("markitdown", () -> {
            resolve();
            return null;
        }, null);
    }

    private void resolve() {
        boolean explicitOverride = configuredExePath != null && !configuredExePath.isBlank()
                && !"markitdown".equals(configuredExePath);
        if (explicitOverride && new File(configuredExePath).isFile()) {
            resolvedExePath = configuredExePath;
            useModuleInvocation = false;
            log.debug("Using markitdown from configured override: {}", resolvedExePath);
            killStaleProcesses();
            return;
        }
        if (pythonRuntime.isAvailable() && pythonRuntime.canImport("markitdown")) {
            resolvedExePath = pythonRuntime.interpreter();
            useModuleInvocation = true;
            log.debug("Using markitdown via '{} -m markitdown'", resolvedExePath);
            killStaleProcesses();
            return;
        }
        // Fallback: legacy direct-exe/PATH resolution, for environments where PythonRuntime
        // could not resolve an interpreter but a standalone markitdown binary is still reachable.
        // The configured value counts only when it is a FILE: the default is the bare name
        // "markitdown", and that used to count as found without anything running it - so with no
        // markitdown anywhere, isAvailable() said yes and every Office file failed with "Cannot run
        // program markitdown". A bare name counts once the PATH probe below has run it.
        useModuleInvocation = false;
        resolvedExePath = configuredExePath != null && new File(configuredExePath).isFile() ? configuredExePath : null;
        if (resolvedExePath == null) {
            resolveFromInstallRoot();
        }
        if (resolvedAvailable()) {
            log.info("Document conversion: markitdown at {}", resolvedExePath);
            killStaleProcesses();
        } else {
            log.warn("markitdown executable not found - Office/PDF conversion disabled");
        }
    }

    // Waits for init()'s resolution when it is still running: "not yet known" is not "unavailable".
    public boolean isAvailable() {
        resolution.join();
        return resolvedAvailable();
    }

    private boolean resolvedAvailable() {
        if (useModuleInvocation) return resolvedExePath != null;
        if (resolvedExePath == null) return false;
        return foundOnPath || new File(resolvedExePath).isFile();
    }

    public boolean requiresConversion(String fileName) {
        String ext = extensionOf(fileName);
        return !ext.isEmpty() && CONVERTIBLE_EXTENSIONS.contains(ext);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    // Converts a file to Markdown using the markitdown CLI. Throws if unavailable or fails.
    //
    // A failure names the FILE, never its path, and carries the last line of markitdown's stderr
    // rather than all of it: the message is stored on the document and shown to its owner, and the
    // full output was a server's temp directory and Python install layout - a traceback through
    // site-packages - after a path into the user's own folders. The full output stays in the log.
    public String convert(Path filePath) throws IOException {
        String name = filePath.getFileName().toString();
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "markitdown executable not found. Cannot process PDF/Office document: " + name);
        }
        String ext = extensionOf(name);
        if (LEGACY_UNSUPPORTED_EXTENSIONS.contains(ext)) {
            String modern = "doc".equals(ext) ? "docx" : "pptx";
            throw new IOException("Legacy ." + ext + " format is not supported by the document converter "
                    + "(no converter exists for this old binary Office format). "
                    + "Please re-save the file as ." + modern + " and re-upload.");
        }
        long start = System.currentTimeMillis();
        List<String> command = useModuleInvocation
                ? List.of(resolvedExePath, "-m", "markitdown", filePath.toString())
                : List.of(resolvedExePath, filePath.toString());
        ChildProcess.Result result;
        try {
            result = ChildProcess.run(command, null, Duration.ofMinutes(TIMEOUT_MINUTES));
        } catch (IOException e) {
            throw new IOException("markitdown " + e.getMessage() + " converting '" + name + "'", e);
        }
        if (result.exitCode() != 0) {
            log.warn("markitdown exited with code {} for {}: {}", result.exitCode(), filePath, result.stderr().strip());
            String reason = result.lastErrorLine();
            throw new IOException("markitdown exited with code " + result.exitCode() + " for '" + name + "'"
                    + (reason.isBlank() ? "" : ": " + reason));
        }
        String output = result.stdout();
        log.debug("Converted {} to markdown ({} chars) in {}",
                name, output.length(), TimeConversion.formatTime(System.currentTimeMillis() - start));
        return output;
    }

    private void resolveFromInstallRoot() {
        String installRoot = System.getProperty("user.dir");
        String sep = File.separator;
        String[] candidates = {
                installRoot + sep + "python" + sep + "Scripts" + sep + "markitdown.exe",
                installRoot + sep + "python" + sep + "bin"     + sep + "markitdown",
                "/usr/bin/markitdown",
                "/usr/local/bin/markitdown"
        };
        for (String candidate : candidates) {
            if (new File(candidate).isFile()) {
                resolvedExePath = candidate;
                log.debug("Resolved markitdown via install root: {}", candidate);
                return;
            }
        }
        // Check PATH by running it.
        try {
            if (ChildProcess.run(List.of("markitdown", "--help"), null, Duration.ofSeconds(5)).exitCode() == 0) {
                resolvedExePath = "markitdown";
                foundOnPath = true;
                log.debug("Resolved markitdown from system PATH");
                return;
            }
        } catch (Exception ignored) {}
        log.debug("markitdown not found under install root={}", installRoot);
    }

    private void killStaleProcesses() {
        try {
            long myPid = ProcessHandle.current().pid();
            String exeAbsPath = new File(resolvedExePath).getAbsolutePath();
            ProcessHandle.allProcesses()
                    .filter(ph -> {
                        if (!ph.isAlive()) return false;
                        // Skip our own process tree
                        if (ph.pid() == myPid) return false;
                        if (ph.parent().map(p -> p.pid() == myPid).orElse(false)) return false;
                        // Command line always requires both markers - this is the ONLY signal we
                        // trust in module-invocation mode, since the command path there is just
                        // the shared python interpreter (matching on that alone would risk
                        // killing an unrelated, unfinished python subprocess - e.g. an in-flight
                        // OCR/pdfplumber job using that same interpreter).
                        boolean matchArgs = ph.info().commandLine()
                                .map(cl -> cl.contains("markitdown") && cl.contains("proxyma-convert-"))
                                .orElse(false);
                        if (matchArgs) return true;
                        if (useModuleInvocation) return false;
                        // Legacy direct-exe/PATH mode: also match by exe path/name alone.
                        return ph.info().command()
                                .map(cmd -> cmd.equals(exeAbsPath) || cmd.endsWith("/markitdown")
                                            || cmd.endsWith("\\markitdown") || cmd.endsWith("\\markitdown.exe"))
                                .orElse(false);
                    })
                    .forEach(ph -> {
                        log.info("Killing orphan markitdown process (pid={}, cmd={})",
                                ph.pid(), ph.info().commandLine().orElse("?"));
                        ph.destroyForcibly();
                    });
        } catch (Exception e) {
            log.debug("Could not enumerate processes to kill stale markitdown: {}", e.getMessage());
        }
    }
}
