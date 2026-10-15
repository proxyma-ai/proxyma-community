package ai.proxyma.contracts.telemetry.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * One kind of warning or error, counted over one day.
 *
 * <p>A FINGERPRINT, NEVER A MESSAGE. What identifies the kind is where it was logged, the exception
 * type and the Proxyma code it passed through - {@code frames} holds at most five
 * {@code ai.proxyma...Class#method} entries and nothing else: no line numbers, no third-party
 * frames. The readable {@code trace} beside it adds line numbers, still Proxyma frames only. Exception
 * messages and log text are never read, because either can carry a file path, a URL, a query or a
 * document title.
 */
public class DiagnosticsError {

    /** A short hash of the fields below - the same kind hashes the same on every installation. */
    private String fingerprint;
    /** WARN or ERROR. */
    private String level;
    /** The logger that reported it - always an ai.proxyma class name. */
    private String logger;
    /** The exception's class name, or null for a warning logged without one. */
    private String exception;
    /** The innermost cause's class name, when it differs from {@link #exception}. */
    private String rootCause;
    private List<String> frames = new ArrayList<>();
    /**
     * The STACK TRACE, as far as it can be sent: up to 25 {@code ai.proxyma...Class#method:line}
     * frames, innermost cause first. Added 2026-09-24 so an error can be read, not just counted.
     * Still code only - Proxyma frames, no messages, no third-party frames - so it is inside what
     * the Privacy Policy calls "the Proxyma code location". Not part of the fingerprint: line
     * numbers change every build and would split one error into one per release. Empty from
     * installations older than the field.
     */
    private List<String> trace = new ArrayList<>();
    /** Each exception's class name down the cause chain, outermost first. Empty for a warning. */
    private List<String> causes = new ArrayList<>();
    private long count;
    /** First and last occurrence that day, UTC, to the minute ("HH:mm"). */
    private String firstSeen;
    private String lastSeen;

    public DiagnosticsError() {}

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getLogger() { return logger; }
    public void setLogger(String logger) { this.logger = logger; }

    public String getException() { return exception; }
    public void setException(String exception) { this.exception = exception; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public List<String> getFrames() { return frames; }
    public void setFrames(List<String> frames) { this.frames = frames == null ? new ArrayList<>() : frames; }

    public List<String> getTrace() { return trace; }
    public void setTrace(List<String> trace) { this.trace = trace == null ? new ArrayList<>() : trace; }

    public List<String> getCauses() { return causes; }
    public void setCauses(List<String> causes) { this.causes = causes == null ? new ArrayList<>() : causes; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public String getFirstSeen() { return firstSeen; }
    public void setFirstSeen(String firstSeen) { this.firstSeen = firstSeen; }

    public String getLastSeen() { return lastSeen; }
    public void setLastSeen(String lastSeen) { this.lastSeen = lastSeen; }
}
