package ai.proxyma.it.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A child process the suite starts, waits for and reliably kills.
 *
 * <p>Everything this suite drives is a separate process - an app jar, the fake provider - so the
 * same three problems recur, and all three are the kind that produce a flaky suite rather than an
 * honest failure:
 *
 * <ul>
 *   <li><b>Output has to be drained.</b> A process whose stdout pipe fills up BLOCKS, forever. A
 *       Spring Boot app logs several hundred lines on startup, so a harness that does not read
 *       them hangs at a different point on every machine and looks like a slow app.
 *   <li><b>Readiness is not "the process started".</b> {@code Process.isAlive()} is true a
 *       millisecond after exec and the port is not open for another fifteen seconds. Waiting on
 *       the wrong signal is how a suite comes to need a {@code Thread.sleep} nobody can justify.
 *   <li><b>A dead process must take its children with it.</b> See {@link #stop()}.
 * </ul>
 *
 * <p>The captured output is also the error message. When a readiness wait times out, the last of
 * the log goes into the exception, because "the app did not start in 90s" is unactionable and
 * "...Caused by: no bean of type ChatRepository" is the answer.
 */
public final class ManagedProcess implements AutoCloseable {

    private final String name;
    private final Process process;
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final Path logFile;
    private final Thread pump;

    private ManagedProcess(String name, Process process, Path logFile) {
        this.name = name;
        this.process = process;
        this.logFile = logFile;
        // A daemon thread: a pump still draining a process that outlived the suite must never be
        // the reason the JVM will not exit.
        this.pump = new Thread(this::drain, "pump-" + name);
        this.pump.setDaemon(true);
        this.pump.start();
    }

    /**
     * Starts a process with stderr folded into stdout.
     *
     * @param env extra environment entries. A null VALUE removes the variable, which is how an
     *            isolated app run unsets the ones that would otherwise point it at the real
     *            installation.
     */
    public static ManagedProcess start(String name, List<String> command, Path workingDir, Map<String, String> env) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        env.forEach((key, value) -> {
            if (value == null) builder.environment().remove(key);
            else builder.environment().put(key, value);
        });
        try {
            Path logFile = workingDir.resolve(name + ".log");
            return new ManagedProcess(name, builder.start(), logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("could not start " + name + ": " + String.join(" ", command), e);
        }
    }

    private void drain() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
                // Mirrored to a file as well as memory. A suite that crashes hard still leaves the
                // app's own log next to the scratch data directory, which is the only record of why
                // when the failure was in startup.
                try {
                    Files.writeString(logFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // Losing the mirror is not worth failing a test over; the in-memory copy stands.
                }
            }
        } catch (IOException ignored) {
            // Normal on stop(): the stream closes under the reader.
        }
    }

    /** Everything the process has printed so far. */
    public List<String> output() {
        return new ArrayList<>(output);
    }

    /** True once any captured line contains this text. */
    public boolean logged(String text) {
        return output.stream().anyMatch(line -> line.contains(text));
    }

    /**
     * Blocks until {@code ready} answers true, the process dies, or the timeout expires.
     *
     * <p>Polls rather than watching the log, because a log line saying "Started" is a claim and an
     * answering port is evidence. Callers pass an HTTP probe.
     */
    public void awaitReady(String what, Supplier<Boolean> ready, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with code " + process.exitValue()
                        + " while waiting for " + what + tail());
            }
            try {
                if (Boolean.TRUE.equals(ready.get())) return;
            } catch (RuntimeException ignored) {
                // Not up yet. A connection refused IS the normal state of this loop.
            }
            sleep(250);
        }
        throw new IllegalStateException(name + " did not become ready (" + what + ") within "
                + timeout.toSeconds() + "s" + tail());
    }

    /** The last of the output, for an exception message. */
    public String tail() {
        List<String> lines = output();
        List<String> last = lines.subList(Math.max(0, lines.size() - 60), lines.size());
        return "\n---- last " + last.size() + " lines of " + name + " ----\n"
                + String.join("\n", last) + "\n---- end " + name + " ----";
    }

    /**
     * Stops the process, then makes sure.
     *
     * <p>{@code destroy()} is a polite SIGTERM and a JVM under load can take a while to honor it,
     * so this waits and then escalates. The escalation is not theoretical: a Proxyma desktop jar
     * holds H2 files open with {@code AUTO_SERVER=TRUE} and a Lucene write lock, and a survivor
     * makes the NEXT test fail on a locked database - a failure that points at the wrong test
     * entirely. {@code descendants()} first, because the app may itself have started a ChromaDB.
     */
    @Override
    public void close() { stop(); }

    public void stop() {
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Whether the process exits within {@code timeout} - one the product ends itself, as a restart from Settings does. */
    public boolean awaitExit(Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits for a line matching a predicate - used to read the fake provider's chosen port. */
    public String awaitLine(Predicate<String> matches, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (String line : output()) {
                if (matches.test(line)) return line;
            }
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited before printing the expected line" + tail());
            }
            sleep(50);
        }
        throw new IllegalStateException(name + " printed no matching line within "
                + timeout.toSeconds() + "s" + tail());
    }

    /** Runs a command to completion and reports whether it succeeded. Used for probing tools. */
    public static boolean succeeds(List<String> command, int timeoutSeconds) {
        try {
            Process probe = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!probe.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }
}
