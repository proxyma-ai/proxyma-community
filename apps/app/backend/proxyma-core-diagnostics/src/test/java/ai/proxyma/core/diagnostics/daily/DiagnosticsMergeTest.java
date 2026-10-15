package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.DiagnosticsAggregator;
import ai.proxyma.core.common.diagnostics.ErrorFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A day on disk is several flushed chunks; the report must be their exact sum, and a chunk must
 * survive the JSON round trip it takes through the DIAGNOSTIC_DAILY table.
 */
class DiagnosticsMergeTest {

    @Test
    void chunksOfOneDayMergeToTheirSumAfterAJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<DiagnosticsDay> stored = new ArrayList<>();
        for (String at : new String[] {"2026-09-20T09:00:00Z", "2026-09-20T09:10:00Z", "2026-09-20T17:45:00Z"}) {
            DiagnosticsAggregator agg = new DiagnosticsAggregator(Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
            agg.time(DiagOp.SEARCH_TOTAL, 100, "rerank", "on");
            agg.time(DiagOp.SEARCH_TOTAL, 300, "rerank", "on");
            agg.count(DiagCounter.CHAT_TURN, 2);
            agg.error(ErrorFingerprint.of("ai.proxyma.core.x.Foo", "WARN", null, null));
            for (DiagnosticsDay d : agg.drain()) {
                stored.add(mapper.readValue(mapper.writeValueAsString(d), DiagnosticsDay.class));
            }
        }

        List<DiagnosticsDay> merged = DiagnosticsReportBuilder.mergeByDay(stored);

        assertThat(merged).hasSize(1);
        DiagnosticsDay day = merged.get(0);
        DiagnosticsTiming t = day.getTimings().get(0);
        assertThat(t.getCount()).isEqualTo(6);
        assertThat(t.getSumMs()).isEqualTo(1200);
        assertThat(t.getMinMs()).isEqualTo(100);
        assertThat(t.getMaxMs()).isEqualTo(300);
        assertThat(day.getCounters().get(0).getCount()).isEqualTo(6);
        assertThat(day.getErrors()).hasSize(1);
        assertThat(day.getErrors().get(0).getCount()).isEqualTo(3);
        assertThat(day.getErrors().get(0).getFirstSeen()).isEqualTo("09:00");
        assertThat(day.getErrors().get(0).getLastSeen()).isEqualTo("17:45");
    }
}
