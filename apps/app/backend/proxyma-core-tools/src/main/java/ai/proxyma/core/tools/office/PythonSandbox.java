package ai.proxyma.core.tools.office;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Where runPython's model-written code runs. Two implementations, chosen per edition by
 * {@link SandboxConfig}: a child process of the app on Desktop ({@link LocalPythonSandbox}), and a
 * separate container on Home Server and Enterprise Server ({@link ContainerPythonSandbox}).
 *
 * <p>Files travel BY VALUE both ways - the inputs in, the outputs back - never as a shared
 * directory. That is what lets the container mount nothing at all: it never sees the conversations
 * directory, the database, the keys or another user's files, whatever the code it runs tries.
 */
public interface PythonSandbox {

    /** Whether this edition can run code at all - false where no sandbox is configured. */
    boolean isAvailable();

    /**
     * Runs {@code code} with {@code inputs} laid out in its working directory - keys are relative
     * paths such as {@code attachments/report.docx} and {@code outputs/draft.xlsx} - and returns
     * every file under {@code outputs/} afterwards.
     */
    Result run(String code, Map<String, byte[]> inputs, Duration timeout) throws IOException;

    record Result(int exitCode, String stdout, String stderr, boolean timedOut, Map<String, byte[]> outputs) {}

    /** The limits every implementation enforces the same way. */
    final class Limits {
        public static final int MAX_STREAM_CHARS = 8_000;
        public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
        public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

        // The END of a long stream is what matters - a traceback's last line is the error.
        public static String tail(String s) {
            return s.length() <= MAX_STREAM_CHARS ? s : "...(truncated)\n" + s.substring(s.length() - MAX_STREAM_CHARS);
        }

        private Limits() {}
    }
}
