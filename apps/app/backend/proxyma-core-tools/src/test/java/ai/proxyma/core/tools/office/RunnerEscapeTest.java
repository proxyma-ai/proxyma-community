package ai.proxyma.core.tools.office;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sandbox/runner.py's audit hook, run for real against the ways out it has to close.
 *
 * <p>Windows' _winapi calls are events of their own, and none was refused: CreateProcess started
 * any program and CreateJunction pointed a folder anywhere. _posixsubprocess.fork_exec raises no
 * event at all. And outputs/ itself could be removed and replaced with a junction, which the app
 * then walked into - reading, and on cleanup deleting, whatever it pointed at. Skipped where no
 * Python 3 is on the PATH.
 */
class RunnerEscapeTest {

    @TempDir
    Path root;

    private static final String PROGRAM = String.join("\n",
            "import os, sys, shutil",
            "results = {}",
            "def attempt(name, fn):",
            "    try:",
            "        fn()",
            "        results[name] = 'ALLOWED'",
            "    except PermissionError:",
            "        results[name] = 'REFUSED'",
            "    except Exception as e:",
            "        results[name] = 'ERROR ' + type(e).__name__",
            "attempt('subprocess', lambda: __import__('subprocess').run([sys.executable, '-c', 'print(1)']))",
            "attempt('rmdir-outputs', lambda: os.rmdir('outputs'))",
            "attempt('rename-outputs', lambda: os.rename('outputs', 'outputs2'))",
            "attempt('symlink', lambda: os.symlink('..', os.path.join('outputs', 'link')))",
            "attempt('copy-out', lambda: shutil.copyfile(os.path.join('attachments', 'in.txt'), os.path.join('..', 'escaped.txt')))",
            "attempt('write-output', lambda: open(os.path.join('outputs', 'ok.txt'), 'w').write('ok'))",
            "attempt('posixsubprocess', lambda: __import__('_posixsubprocess'))",
            "attempt('gc', lambda: __import__('gc').get_objects())",
            "if os.name == 'nt':",
            "    import _winapi",
            "    attempt('winapi-process', lambda: _winapi.CreateProcess(None, 'cmd /c echo x', None, None, False, 0, None, None, None))",
            "    attempt('winapi-junction', lambda: _winapi.CreateJunction(os.path.abspath('attachments'), os.path.abspath(os.path.join('outputs', 'j'))))",
            "for k, v in results.items():",
            "    print(k + '=' + v)",
            "");

    @Test
    @DisplayName("every way out is refused, and writing an output still works")
    void escapesAreRefused() throws Exception {
        String python = findPython();
        Assumptions.assumeTrue(python != null, "no Python 3 on the PATH");

        Path runner = root.resolve("runner.py");
        try (InputStream in = RunnerEscapeTest.class.getResourceAsStream("/sandbox/runner.py")) {
            Files.copy(in, runner);
        }
        Path run = root.resolve("run");
        SandboxFiles.writeInputs(run, Map.of("attachments/in.txt", "secret".getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(run.resolve(".run"));
        Files.writeString(run.resolve(".run/main.py"), PROGRAM, StandardCharsets.UTF_8);

        Process p = new ProcessBuilder(python, "-I", runner.toString(), run.toString())
                .directory(run.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();

        Map<String, String> results = new HashMap<>();
        for (String line : out.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) results.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        assertThat(results).as(out).containsEntry("write-output", "ALLOWED");
        for (String name : List.of("subprocess", "rmdir-outputs", "rename-outputs", "symlink", "copy-out", "gc")) {
            assertThat(results.get(name)).as(name + "\n" + out).isEqualTo("REFUSED");
        }
        // There is no _posixsubprocess on Windows; where there is one, it is refused.
        assertThat(results.get("posixsubprocess")).as(out).isIn("REFUSED", "ERROR ModuleNotFoundError");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertThat(results.get("winapi-process")).as(out).isEqualTo("REFUSED");
            assertThat(results.get("winapi-junction")).as(out).isEqualTo("REFUSED");
        }
        assertThat(Files.exists(root.resolve("escaped.txt"))).isFalse();
        assertThat(SandboxFiles.readOutputs(run)).containsOnlyKeys("outputs/ok.txt");
    }

    private static String findPython() {
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                String version = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && version.startsWith("Python 3.")) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // not this one
            }
        }
        return null;
    }
}
