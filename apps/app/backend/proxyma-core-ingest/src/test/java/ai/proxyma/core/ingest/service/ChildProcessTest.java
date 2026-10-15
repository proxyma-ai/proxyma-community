package ai.proxyma.core.ingest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a converter's process is run (ingest-03, -14): a timeout that fires, stderr drained beside
 * stdout and kept out of the result, and Python told to write UTF-8.
 *
 * <p>The child is this JVM's own java launcher running a one-file program, so the test needs nothing
 * the build machine does not already have.
 */
class ChildProcessTest {

    private static String java() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static List<String> program(Path dir, String body) throws IOException {
        Path source = dir.resolve("Child.java");
        Files.writeString(source, """
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                public class Child {
                    public static void main(String[] args) throws Exception {
                        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
                        %s
                    }
                }
                """.formatted(body));
        return List.of(java(), source.toString());
    }

    @Test
    @DisplayName("ingest-03: the child is told to write UTF-8, and its output is read as UTF-8")
    void utf8(@TempDir Path dir) throws Exception {
        ChildProcess.Result result = ChildProcess.run(program(dir,
                "out.print(System.getenv(\"PYTHONUTF8\") + \" \" + System.getenv(\"PYTHONIOENCODING\") + \" Vi\\u1ec7t\");"),
                null, Duration.ofMinutes(2));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("1 utf-8 Vi\u1ec7t");
    }

    @Test
    @DisplayName("ingest-14: a child that floods stderr before it writes stdout finishes, and its warnings stay out of the result")
    void stderrIsDrainedAndKeptApart(@TempDir Path dir) throws Exception {
        ChildProcess.Result result = ChildProcess.run(program(dir, """
                for (int i = 0; i < 20000; i++) System.err.println("WARNING: malformed object " + i);
                System.err.println("last warning");
                out.print("# The document");
                """), null, Duration.ofMinutes(2));

        assertThat(result.stdout()).isEqualTo("# The document");
        assertThat(result.stderr().length()).isLessThanOrEqualTo(ChildProcess.STDERR_TAIL_BYTES);
        assertThat(result.lastErrorLine()).isEqualTo("last warning");
    }

    @Test
    @DisplayName("ingest-14: a child past its time is killed and reported, rather than waited on for ever")
    void timeoutFires(@TempDir Path dir) throws Exception {
        List<String> command = program(dir, "Thread.sleep(120_000);");
        long start = System.nanoTime();

        assertThatThrownBy(() -> ChildProcess.run(command, null, Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(60));
    }
}
