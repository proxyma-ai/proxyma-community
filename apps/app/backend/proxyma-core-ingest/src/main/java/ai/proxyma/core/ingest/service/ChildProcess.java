package ai.proxyma.core.ingest.service;

import ai.proxyma.core.system.util.SensitivePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs one converter or probe process to completion: the one way this package starts a child.
 *
 * <p>Five copies of start / read stdout / read stderr / waitFor drifted apart here, and all five had
 * the same three faults, which is why they are one class now:
 *
 * <ul>
 *   <li><b>The timeout never fired.</b> Each read its output to the end and only then waited with a
 *       timeout - but reading to the end returns only when the child exits, so a hung converter held
 *       its drain worker for ever and a hung startup probe held every reader of its result. The
 *       wait is now what is bounded, with stdout and stderr drained beside it, and a child past its
 *       time is killed with everything it started.</li>
 *   <li><b>stderr could deadlock the child.</b> It was read after stdout, so a child that filled the
 *       stderr pipe before closing stdout - pdfminer logs a warning per malformed object - blocked
 *       writing while this side blocked reading. Both are drained at once.</li>
 *   <li><b>Text came back in the wrong encoding on Windows.</b> Python writes to a pipe in the ANSI
 *       code page there (cp1252: an e-acute is one byte 0xE9, a Vietnamese letter outside it is
 *       '?') and this side decoded UTF-8, so every PDF and Office file indexed on Windows lost its
 *       accents, smart quotes and Vietnamese. Every child gets PYTHONUTF8=1 and
 *       PYTHONIOENCODING=utf-8, harmless for anything that is not Python.</li>
 * </ul>
 *
 * <p>stderr is kept apart from the result - merged into stdout, a converter's warnings were indexed
 * as part of the document - and only its tail is kept, for the failure message.
 */
final class ChildProcess {

    /** How much of stderr is kept: enough for a traceback's last lines, never a flood of warnings. */
    static final int STDERR_TAIL_BYTES = 16 * 1024;

    /** How long the output readers are given to finish once the child has exited or been killed. */
    private static final long DRAIN_GRACE_SECONDS = 5;

    private ChildProcess() {
    }

    /** What a finished child left: its exit code, all of stdout, and the end of stderr. */
    record Result(int exitCode, String stdout, String stderr) {

        /** The last non-blank line of stderr - a traceback's own message - path-scrubbed; blank if none. */
        String lastErrorLine() {
            String[] lines = stderr.strip().split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) return SensitivePaths.scrub(lines[i].strip());
            }
            return "";
        }
    }

    /**
     * Runs {@code command}, writes {@code stdin} to it (null for none) and waits at most
     * {@code timeout}. Returns whatever the exit code; throws IOException when it cannot start,
     * runs past its time (it is killed first) or the wait is interrupted.
     */
    static Result run(List<String> command, byte[] stdin, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        utf8(builder.environment());
        Process process = builder.start();
        CompletableFuture<byte[]> out = drain(process.getInputStream(), Integer.MAX_VALUE);
        CompletableFuture<byte[]> err = drain(process.getErrorStream(), STDERR_TAIL_BYTES);
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (IOException closedEarly) {
            // The child exited without reading its input; its exit code and stderr say why.
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new IOException("timed out after " + describe(timeout));
            }
            return new Result(process.exitValue(), text(out), text(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new IOException("interrupted");
        }
    }

    // UTF-8 for Python's stdio whatever the platform's locale - see the class comment.
    static void utf8(Map<String, String> environment) {
        environment.put("PYTHONUTF8", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
    }

    // Everything it started first: a converter that spawned a helper must not leave it holding the
    // pipes this side is draining.
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String text(CompletableFuture<byte[]> drained) throws IOException {
        try {
            return new String(drained.get(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (Exception e) {
            throw new IOException("could not read the process output: " + e.getMessage(), e);
        }
    }

    // Reads a stream to its end on a thread of its own, keeping at most the LAST {@code keep} bytes.
    private static CompletableFuture<byte[]> drain(InputStream stream, int keep) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread.ofVirtual().name("child-process-output").start(() -> {
            try (stream) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = stream.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 2L * keep) {
                        byte[] all = buffer.toByteArray();
                        buffer.reset();
                        buffer.write(all, all.length - keep, keep);
                    }
                }
                byte[] all = buffer.toByteArray();
                result.complete(all.length > keep ? Arrays.copyOfRange(all, all.length - keep, all.length) : all);
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static String describe(Duration timeout) {
        long minutes = timeout.toMinutes();
        return minutes > 0 && timeout.toSeconds() % 60 == 0
                ? minutes + " minute(s)" : timeout.toSeconds() + " second(s)";
    }
}
