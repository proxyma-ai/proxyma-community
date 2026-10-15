package ai.proxyma.core.common.diagnostics;

/**
 * The one entry point instrumented code calls: {@code Diagnostics.time(DiagOp.SEARCH_TOTAL, ms, "rerank", "on")}.
 *
 * <p>STATIC, deliberately, and it is the exception in a codebase that injects everything. The same
 * aggregator is fed by a Logback appender, which Logback instantiates and Spring never sees, and by
 * code in every module from connectors to the updater - injecting a bean into all of those for a
 * map increment would widen two dozen constructors (and their tests' constructor calls) for
 * nothing. It is process-wide state in exactly the way a logger is.
 *
 * <p>What it records is never sent on its own: proxyma-core-diagnostics drains it, and only while
 * "Send diagnostics to Proxyma" is on. See {@link DiagnosticsAggregator} for the costs and caps.
 */
public final class Diagnostics {

    private static final DiagnosticsAggregator GLOBAL = new DiagnosticsAggregator();

    private Diagnostics() {}

    public static DiagnosticsAggregator aggregator() {
        return GLOBAL;
    }

    public static void time(DiagOp op, long ms, String... labels) {
        GLOBAL.time(op, ms, labels);
    }

    /** {@link #time} from a {@code System.nanoTime()} start. */
    public static void timeSince(DiagOp op, long startNanos, String... labels) {
        GLOBAL.time(op, (System.nanoTime() - startNanos) / 1_000_000, labels);
    }

    public static void count(DiagCounter counter, String... labels) {
        GLOBAL.count(counter, 1, labels);
    }

    public static void count(DiagCounter counter, long n, String... labels) {
        GLOBAL.count(counter, n, labels);
    }
}
