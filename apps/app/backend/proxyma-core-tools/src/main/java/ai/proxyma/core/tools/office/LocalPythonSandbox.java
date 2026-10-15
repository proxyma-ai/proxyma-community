package ai.proxyma.core.tools.office;

import ai.proxyma.core.ingest.service.PythonRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Desktop: the bundled Python, as a child process, in a throwaway directory holding copies of the
 * inputs. sandbox/runner.py's audit hook confines it there - no network, no subprocess, no native
 * library loading, writes only to outputs/ - and the environment it starts with carries nothing of
 * the app's: an API key in the app's environment is not in the program's.
 */
public class LocalPythonSandbox implements PythonSandbox {

    private final PythonRuntime python;

    public LocalPythonSandbox(PythonRuntime python) {
        this.python = python;
    }

    @Override
    public boolean isAvailable() {
        return python.isAvailable();
    }

    @Override
    public Result run(String code, Map<String, byte[]> inputs, Duration timeout) throws IOException {
        if (!isAvailable()) throw new IllegalStateException("No Python interpreter is available.");
        Path root = Files.createTempDirectory("proxyma-run-");
        try {
            Path runner = root.resolve("runner.py");
            try (InputStream in = LocalPythonSandbox.class.getResourceAsStream("/sandbox/runner.py")) {
                if (in == null) throw new IOException("sandbox/runner.py is missing from the build");
                Files.copy(in, runner);
            }
            Path run = root.resolve("run");
            SandboxFiles.writeInputs(run, inputs);
            Files.createDirectories(run.resolve(".run"));
            Files.writeString(run.resolve(".run/main.py"), code, StandardCharsets.UTF_8);

            // -I: isolated mode - no user site-packages, no PYTHON* variables, no script directory
            // on sys.path. The environment is rebuilt from nothing but what an interpreter needs.
            ProcessBuilder pb = new ProcessBuilder(python.interpreter(), "-I", runner.toString(), run.toString());
            Map<String, String> env = pb.environment();
            Map<String, String> keep = new LinkedHashMap<>();
            for (String name : List.of("SYSTEMROOT", "SystemRoot", "WINDIR", "PATH", "Path", "LANG", "HOME")) {
                String value = System.getenv(name);
                if (value != null) keep.put(name, value);
            }
            env.clear();
            env.putAll(keep);
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            Path out = root.resolve("stdout.txt");
            Path err = root.resolve("stderr.txt");
            pb.redirectOutput(out.toFile()).redirectError(err.toFile()).directory(run.toFile());

            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            // The files first: a stream that cannot be read must not lose what the program made.
            Map<String, byte[]> files = SandboxFiles.readOutputs(run);
            return new Result(finished ? process.exitValue() : -1,
                    SandboxFiles.tail(out, Limits.MAX_STREAM_CHARS),
                    SandboxFiles.tail(err, Limits.MAX_STREAM_CHARS),
                    !finished,
                    files);
        } finally {
            // Never through a link the program left behind - see SandboxFiles.deleteTree.
            SandboxFiles.deleteTree(root);
        }
    }
}
