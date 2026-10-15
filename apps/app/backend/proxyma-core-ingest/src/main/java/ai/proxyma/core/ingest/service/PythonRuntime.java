package ai.proxyma.core.ingest.service;

import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.system.util.PythonLayout;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// The one place that finds a Python interpreter and runs a program in it. Both Python-backed
// conversion paths need this - pdfplumber extraction (PdfTableExtractionService) and OCR
// rasterization/tesseract (OcrService) - and interpreter resolution is fiddly enough (desktop
// install root vs. server venv vs. PATH, Windows vs. Linux) that having two copies of it would
// guarantee they drift.
//
// Programs are passed as strings and fed to `python -` over stdin, never written to disk: there is
// no .py file to bundle, copy, or keep in sync across the desktop installer, the server image, and
// downstream deployments. Each caller keeps its own program text as a constant next to the code
// that parses its output.
//
// Interpreter resolution order: ${proxyma.python.exe}, then the legacy
// ${proxyma.pdf-extract.python} (what server/Docker already sets, kept working), then the bundled
// desktop runtime (data directory first, legacy install root second - see PythonLayout), then
// /usr/bin and /usr/local/bin, then PATH.
@Service
public class PythonRuntime {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntime.class);

    @Value("${proxyma.python.exe:}")
    private String configuredPythonExe;

    // Pre-existing property name, still honoured so an already-deployed server/Docker environment
    // that sets PROXYMA_PDF_EXTRACT_PYTHON keeps working untouched.
    @Value("${proxyma.pdf-extract.python:}")
    private String legacyPythonExe;

    private final AppPaths appPaths;

    private volatile String resolvedPythonExe;

    public PythonRuntime(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    @PostConstruct
    public void init() {
        resolvePython();
        if (resolvedPythonExe == null) {
            log.warn("No Python interpreter found - pdfplumber extraction and OCR are unavailable");
        } else {
            log.debug("Python interpreter: {}", resolvedPythonExe);
        }
    }

    public boolean isAvailable() {
        return resolvedPythonExe != null;
    }

    public String interpreter() {
        return resolvedPythonExe;
    }

    // stdout of a successful run. `args` become sys.argv[1:]. Throws IllegalStateException when no
    // interpreter was resolved, IOException on timeout or a non-zero exit (message carries the
    // last line of the program's own stderr, its paths scrubbed). See ChildProcess for how the
    // timeout, both output streams and the text encoding are handled.
    public String run(String program, List<String> args, int timeoutMinutes) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("No Python interpreter is available.");
        }
        List<String> command = new ArrayList<>();
        command.add(resolvedPythonExe);
        command.add("-");
        command.addAll(args);
        // `python -` reads the whole program from stdin until EOF and only then executes it.
        ChildProcess.Result result;
        try {
            result = ChildProcess.run(command, program.getBytes(StandardCharsets.UTF_8),
                    Duration.ofMinutes(timeoutMinutes));
        } catch (IOException e) {
            throw new IOException("Python program " + e.getMessage(), e);
        }
        if (result.exitCode() != 0) {
            String reason = result.lastErrorLine();
            throw new IOException(reason.isBlank() ? "Python program exited with code " + result.exitCode() : reason);
        }
        return result.stdout();
    }

    // True when the resolved interpreter can actually import the given module - a truer
    // availability signal than the interpreter merely existing. Run at startup by each caller for
    // the module it needs; ~1-3s per import, bounded by a generous timeout that now really bounds it.
    public boolean canImport(String module) {
        if (resolvedPythonExe == null) return false;
        try {
            return ChildProcess.run(List.of(resolvedPythonExe, "-c", "import " + module), null,
                    Duration.ofSeconds(30)).exitCode() == 0;
        } catch (Exception e) {
            log.debug("Python import probe for '{}' failed: {}", module, e.getMessage());
            return false;
        }
    }

    private void resolvePython() {
        for (String configured : new String[]{configuredPythonExe, legacyPythonExe}) {
            if (configured != null && !configured.isBlank() && new File(configured).isFile()) {
                resolvedPythonExe = configured;
                log.debug("Resolved python interpreter from configuration: {}", configured);
                return;
            }
        }
        // The bundled desktop runtime: data directory first, legacy install root second.
        Path installRoot = Path.of(System.getProperty("user.dir"));
        Path bundled = PythonLayout.resolveInterpreter(appPaths.dataRoot(), installRoot);
        if (bundled != null) {
            resolvedPythonExe = bundled.toString();
            log.debug("Resolved python interpreter from the bundled runtime: {}", bundled);
            return;
        }
        for (String candidate : new String[]{"/usr/bin/python3", "/usr/local/bin/python3"}) {
            if (new File(candidate).isFile()) {
                resolvedPythonExe = candidate;
                log.debug("Resolved python interpreter at a well-known location: {}", candidate);
                return;
            }
        }
        for (String exe : new String[]{"python", "python3"}) {
            try {
                if (ChildProcess.run(List.of(exe, "--version"), null, Duration.ofSeconds(5)).exitCode() == 0) {
                    resolvedPythonExe = exe;
                    log.debug("Resolved python interpreter from system PATH: {}", exe);
                    return;
                }
            } catch (Exception ignored) {}
        }
        log.debug("No python interpreter found - looked in {} and on PATH",
                PythonLayout.roots(appPaths.dataRoot(), Path.of(System.getProperty("user.dir"))));
    }
}
