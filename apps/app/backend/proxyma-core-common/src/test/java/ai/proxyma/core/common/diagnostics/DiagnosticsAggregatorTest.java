package ai.proxyma.core.common.diagnostics;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsCounter;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day bucketing, draining, the series cap and label cleaning - the in-memory half every
 * instrumented call site feeds.
 */
class DiagnosticsAggregatorTest {

    /** A clock a test can move. */
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void recordsAreBucketedByUtcDayAndDrainedOldestFirst() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T23:59:00Z"));
        DiagnosticsAggregator agg = new DiagnosticsAggregator(clock);
        agg.time(DiagOp.SEARCH_TOTAL, 120, "rerank", "on");
        clock.advance(Duration.ofMinutes(2));
        agg.time(DiagOp.SEARCH_TOTAL, 80, "rerank", "on");
        agg.count(DiagCounter.CHAT_TURN);

        List<DiagnosticsDay> days = agg.drain();
        assertThat(days).extracting(DiagnosticsDay::getDay).containsExactly("2026-09-20", "2026-09-21");
        assertThat(days.get(0).getTimings().get(0).getCount()).isEqualTo(1);
        assertThat(days.get(1).getCounters()).extracting(DiagnosticsCounter::getName).containsExactly("chat.turn");
        assertThat(agg.drain()).isEmpty();
    }

    @Test
    void peekDoesNotConsume() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        agg.count(DiagCounter.APP_START);
        assertThat(agg.peek()).hasSize(1);
        assertThat(agg.peek()).hasSize(1);
        assertThat(agg.drain()).hasSize(1);
    }

    @Test
    void labelOrderDoesNotSplitASeries() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        agg.time(DiagOp.INGEST_ITEM, 10, "connector", "JIRA", "outcome", "ok");
        agg.time(DiagOp.INGEST_ITEM, 20, "outcome", "ok", "connector", "JIRA");
        DiagnosticsTiming t = agg.drain().get(0).getTimings().get(0);
        assertThat(t.getCount()).isEqualTo(2);
        assertThat(t.getLabels()).containsEntry("connector", "JIRA").containsEntry("outcome", "ok");
    }

    @Test
    void aValueThatIsNotATokenBecomesOther() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        agg.count(DiagCounter.SOURCE_REGISTERED, "connector", "C:\\Users\\alice\\secret folder");
        agg.count(DiagCounter.SOURCE_REGISTERED, "connector", "https://jira.example.com/browse/ABC-1");
        agg.count(DiagCounter.SOURCE_REGISTERED, "connector", "a very long sentence that someone typed as a name");
        DiagnosticsDay day = agg.drain().get(0);
        assertThat(day.getCounters()).hasSize(1);
        assertThat(day.getCounters().get(0).getLabels()).containsEntry("connector", "other");
        assertThat(day.getCounters().get(0).getCount()).isEqualTo(3);
    }

    @Test
    void tokenShapedSecretsAndIdsAreRejectedButRealLabelsAreKept() {
        assertThat(DiagLabels.clean("sk-proj-4f9Qa7Zk2Lm8Xw1Rt6Yb")).isEqualTo("other");
        assertThat(DiagLabels.clean("0b7e3c517f0e4a4e")).isEqualTo("other");
        assertThat(DiagLabels.clean("ghp_16C7e42F292c6912E7710c838347Ae178B4a")).isEqualTo("other");
        for (String legit : new String[] {"OPENAI_COMPATIBLE", "search_documents", "10m-100m", "1k-10k",
                "complete_with_skips", "LOCAL_FOLDER", "rate_limited", "100m+", "guardrail.phase"}) {
            assertThat(DiagLabels.clean(legit)).as(legit).isEqualTo(legit);
        }
    }

    @Test
    void theSeriesCapHoldsAndCountsWhatItRefused() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        for (int i = 0; i < DiagnosticsAggregator.MAX_SERIES_PER_DAY + 10; i++) {
            agg.count(DiagCounter.UPDATE_EVENT, "event", "e" + i);
        }
        DiagnosticsDay day = agg.drain().get(0);
        assertThat(day.getCounters()).hasSize(DiagnosticsAggregator.MAX_SERIES_PER_DAY);
        assertThat(day.getDropped()).isEqualTo(10);
    }

    @Test
    void discardBeforeTodayKeepsOnlyToday() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T10:00:00Z"));
        DiagnosticsAggregator agg = new DiagnosticsAggregator(clock);
        agg.count(DiagCounter.APP_START);
        clock.advance(Duration.ofDays(1));
        agg.count(DiagCounter.CHAT_TURN);
        agg.discardBeforeToday();
        assertThat(agg.peek()).extracting(DiagnosticsDay::getDay).containsExactly("2026-09-21");
    }

    @Test
    void recordingNeverThrowsOnBadInput() {
        DiagnosticsAggregator agg = new DiagnosticsAggregator();
        agg.time(null, 5);
        agg.count(null);
        agg.count(DiagCounter.APP_START, -1);
        agg.time(DiagOp.TOOL_CALL, 5, "odd-count");
        agg.time(DiagOp.TOOL_CALL, 5, (String[]) null);
        agg.error(null);
        assertThat(agg.drain()).hasSize(1);
    }

    @Test
    void errorCategoriesComeFromTypesAndStatusesNeverMessages() {
        assertThat(ErrorCategory.of(new SocketTimeoutException("C:\\secret"))).isEqualTo("timeout");
        assertThat(ErrorCategory.of(new java.net.ConnectException("refused"))).isEqualTo("network");
        assertThat(ErrorCategory.of(new RuntimeException("wrapped", new java.net.UnknownHostException("h"))))
                .isEqualTo("network");
        assertThat(ErrorCategory.of(new java.nio.file.NoSuchFileException("/home/alice/x"))).isEqualTo("io");
        assertThat(ErrorCategory.of(new HttpLike(401))).isEqualTo("auth");
        assertThat(ErrorCategory.of(new HttpLike(429))).isEqualTo("rate_limited");
        assertThat(ErrorCategory.of(new HttpLike(503))).isEqualTo("http_5xx");
        assertThat(ErrorCategory.of(new HttpLike(422))).isEqualTo("http_4xx");
        assertThat(ErrorCategory.of(new RuntimeException("timeout while reading"))).isEqualTo("other");
        assertThat(ErrorCategory.of(null)).isEqualTo("none");
    }

    @Test
    void fileClassesAndBuckets() {
        assertThat(DiagLabels.fileClass("C:\\Users\\alice\\Q3 plan.DOCX")).isEqualTo("office");
        assertThat(DiagLabels.fileClass("application/pdf")).isEqualTo("pdf");
        assertThat(DiagLabels.fileClass("scan.tiff")).isEqualTo("image");
        assertThat(DiagLabels.fileClass("notes.md")).isEqualTo("text");
        assertThat(DiagLabels.fileClass("Main.java")).isEqualTo("code");
        assertThat(DiagLabels.fileClass("archive.xyz123")).isEqualTo("other");
        assertThat(DiagLabels.fileClass(null)).isEqualTo("other");
        assertThat(DiagLabels.sizeBucket(5_000)).isEqualTo("lt100k");
        assertThat(DiagLabels.sizeBucket(-1)).isEqualTo("unknown");
        assertThat(DiagLabels.countBucket(0)).isEqualTo("0");
        assertThat(DiagLabels.countBucket(5_000)).isEqualTo("1k-10k");
        assertThat(DiagLabels.memoryBucket(16L * 1024 * 1024 * 1024)).isEqualTo("16-32");
        assertThat(DiagLabels.osFamily("Windows 11")).isEqualTo("Windows");
    }

    /** Shaped like Spring's RestClientResponseException: an int from getStatusCode(). */
    static final class HttpLike extends RuntimeException {
        private final int status;
        HttpLike(int status) { super("HTTP " + status + " from https://api.example.com/v1?key=abc"); this.status = status; }
        public int getStatusCode() { return status; }
    }
}
