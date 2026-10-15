package ai.proxyma.core.common.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * Counts Proxyma's own warnings and errors by KIND, for the diagnostics report.
 *
 * <p>Reads the logger name, the level, the exception's class and its stack - and never
 * {@code getMessage()} or {@code getFormattedMessage()}, nor the exception's message. See
 * {@link ErrorFingerprint} for why each of those is left out.
 *
 * <p>Only {@code ai.proxyma.*} loggers, at WARN and above: a third-party library's warning is
 * usually about its own configuration, and anything that actually failed a Proxyma operation is
 * logged again by the Proxyma code that caught it. Unhandled exceptions reach this through
 * {@code GlobalExceptionHandler}, which logs them at ERROR.
 *
 * <p>Attached programmatically by proxyma-core-diagnostics at startup rather than declared in a
 * logback file, so both editions get it without either one's logging configuration changing.
 */
public class DiagnosticsLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    public static final String NAME = "PROXYMA_DIAGNOSTICS";

    // Recording never logs, so this should never trip - it is here because the cost of being wrong
    // about that is a stack overflow inside the logging system, which takes the log with it.
    private static final ThreadLocal<Boolean> IN_APPEND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final DiagnosticsAggregator aggregator;

    public DiagnosticsLogAppender(DiagnosticsAggregator aggregator) {
        this.aggregator = aggregator;
        setName(NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || !event.getLevel().isGreaterOrEqual(Level.WARN)) return;
        String logger = event.getLoggerName();
        if (logger == null || !logger.startsWith("ai.proxyma.")) return;
        if (IN_APPEND.get()) return;
        IN_APPEND.set(Boolean.TRUE);
        try {
            // Caller data is a stack walk - only worth paying for when there is no exception to
            // take frames from, and only at WARN and above, which is already the rare path.
            StackTraceElement[] caller = event.getThrowableProxy() == null ? event.getCallerData() : null;
            aggregator.error(ErrorFingerprint.of(logger, event.getLevel().toString(),
                    event.getThrowableProxy(), caller));
        } catch (RuntimeException ignored) {
            // Never let diagnostics break logging.
        } finally {
            IN_APPEND.set(Boolean.FALSE);
        }
    }
}
