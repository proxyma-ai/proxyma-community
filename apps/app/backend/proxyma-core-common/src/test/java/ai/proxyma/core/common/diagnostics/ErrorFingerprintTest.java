package ai.proxyma.core.common.diagnostics;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsError;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error half of diagnostics reads log events - the one place in the product where user text is
 * routinely in reach, because messages carry paths and titles. These pin that nothing but code
 * identifiers ever leaves the fingerprint, that the frames are Proxyma's own, and that the per-day
 * cap holds against a warning logged in a loop.
 */
class ErrorFingerprintTest {

    private static final String SECRET_PATH = "C:\\Users\\alice\\Documents\\merger-plan.pdf";
    private static final String SECRET_QUERY = "what is the acquisition price for Contoso";

    private final LoggerContext context = new LoggerContext();

    @Test
    void neitherTheLogMessageNorTheExceptionMessageReachesTheReport() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        DiagnosticsLogAppender appender = started(agg);
        IOException error = new IOException("Could not read " + SECRET_PATH,
                new IllegalStateException(SECRET_QUERY));
        appender.doAppend(event("ai.proxyma.core.registry.service.SyncService", Level.ERROR,
                "Failed item " + SECRET_PATH + " for query " + SECRET_QUERY, error));

        List<DiagnosticsDay> days = agg.drain();
        assertThat(days).hasSize(1);
        DiagnosticsError e = days.get(0).getErrors().get(0);
        String everything = e.getFingerprint() + e.getLevel() + e.getLogger() + e.getException()
                + e.getRootCause() + e.getFrames() + e.getFirstSeen() + e.getLastSeen();
        assertThat(everything).doesNotContain("alice", "merger", "Contoso", "acquisition", "Could not read");
        assertThat(e.getException()).isEqualTo("java.io.IOException");
        assertThat(e.getRootCause()).isEqualTo("java.lang.IllegalStateException");
        assertThat(e.getLevel()).isEqualTo("ERROR");
    }

    @Test
    void framesAreOnlyProxymaClassAndMethodWithNoLineNumbers() {
        ErrorFingerprint fp = ErrorFingerprint.of("ai.proxyma.core.x.Foo", "ERROR",
                new ThrowableProxy(thrownHere()), null);
        assertThat(fp.frames()).isNotEmpty();
        assertThat(fp.frames()).hasSizeLessThanOrEqualTo(ErrorFingerprint.MAX_FRAMES);
        assertThat(fp.frames()).allSatisfy(f -> {
            assertThat(f).startsWith("ai.proxyma.");
            assertThat(f).contains("#");
            assertThat(f).doesNotContain(":", "(", "org.junit", "java.");
        });
        assertThat(fp.frames().get(0)).isEqualTo(ErrorFingerprintTest.class.getName() + "#thrownHere");
    }

    @Test
    void theSameKindWithDifferentMessagesIsOneFingerprint() {
        ErrorFingerprint a = ErrorFingerprint.of("ai.proxyma.core.x.Foo", "WARN",
                new ThrowableProxy(failWith("first.pdf")), null);
        ErrorFingerprint b = ErrorFingerprint.of("ai.proxyma.core.x.Foo", "WARN",
                new ThrowableProxy(failWith("second report about something else.docx")), null);
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.fingerprint()).hasSize(12);
    }

    @Test
    void aWarningWithoutAnExceptionIsIdentifiedByItsCallSite() {
        StackTraceElement[] caller = {
                new StackTraceElement("ai.proxyma.core.search.service.SearchService", "search", "SearchService.java", 212),
                new StackTraceElement("org.springframework.Foo", "bar", "Foo.java", 1)
        };
        ErrorFingerprint fp = ErrorFingerprint.of("ai.proxyma.core.search.service.SearchService", "WARN", null, caller);
        assertThat(fp.exception()).isNull();
        assertThat(fp.frames()).containsExactly("ai.proxyma.core.search.service.SearchService#search");
    }

    @Test
    void generatedNamesAreNormalized() {
        assertThat(ErrorFingerprint.frame("ai.proxyma.core.a.Svc$$SpringCGLIB$$0", "run"))
                .isEqualTo("ai.proxyma.core.a.Svc#run");
        assertThat(ErrorFingerprint.frame("ai.proxyma.core.a.Svc", "lambda$processItem$7"))
                .isEqualTo("ai.proxyma.core.a.Svc#processItem(lambda)");
    }

    @Test
    void thirdPartyLoggersAndInfoLevelAreIgnored() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        DiagnosticsLogAppender appender = started(agg);
        appender.doAppend(event("org.hibernate.SQL", Level.ERROR, "boom", new RuntimeException()));
        appender.doAppend(event("ai.proxyma.core.x.Foo", Level.INFO, "fine", null));
        assertThat(agg.drain()).isEmpty();
    }

    @Test
    void theDailyCapOnDistinctKindsHoldsAndOverflowIsCounted() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        for (int i = 0; i < DiagnosticsAggregator.MAX_ERRORS_PER_DAY + 25; i++) {
            agg.error(ErrorFingerprint.of("ai.proxyma.core.x.Foo" + i, "WARN", null, null));
        }
        // A repeat of an ALREADY-KNOWN kind is still counted, cap or no cap.
        agg.error(ErrorFingerprint.of("ai.proxyma.core.x.Foo0", "WARN", null, null));
        DiagnosticsDay day = agg.drain().get(0);
        assertThat(day.getErrors()).hasSize(DiagnosticsAggregator.MAX_ERRORS_PER_DAY);
        assertThat(day.getDropped()).isEqualTo(25);
        assertThat(day.getErrors().get(0).getCount()).isEqualTo(2);
    }

    private DiagnosticsLogAppender started(DiagnosticsAggregator agg) {
        DiagnosticsLogAppender appender = new DiagnosticsLogAppender(agg);
        appender.setContext(context);
        appender.start();
        return appender;
    }

    private LoggingEvent event(String loggerName, Level level, String message, Throwable error) {
        Logger logger = context.getLogger(loggerName);
        return new LoggingEvent(Logger.class.getName(), logger, level, message, error, null);
    }

    private static Exception thrownHere() {
        try {
            throw new IllegalArgumentException("x");
        } catch (IllegalArgumentException e) {
            return e;
        }
    }

    private static Exception failWith(String message) {
        return new IllegalStateException(message);
    }
}
