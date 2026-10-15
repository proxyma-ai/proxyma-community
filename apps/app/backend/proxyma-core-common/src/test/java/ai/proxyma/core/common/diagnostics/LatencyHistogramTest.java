package ai.proxyma.core.common.diagnostics;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;
import ai.proxyma.contracts.telemetry.diagnostics.LatencyBuckets;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The histogram math the server's percentiles are read back from. The client counts, the server
 * interpolates, and both go through LatencyBuckets - so a wrong bound or an off-by-one here is a
 * wrong p90 on every chart, silently, for every installation.
 */
class LatencyHistogramTest {

    @Test
    void bucketsAreInclusiveUpperBoundsWithAnOverflow() {
        long[] bounds = LatencyBuckets.boundsMs();
        assertThat(LatencyBuckets.indexOf(0)).isZero();
        assertThat(LatencyBuckets.indexOf(1)).isZero();
        assertThat(LatencyBuckets.indexOf(2)).isEqualTo(1);
        assertThat(LatencyBuckets.indexOf(3)).isEqualTo(2);
        assertThat(LatencyBuckets.indexOf(-5)).isZero();
        assertThat(LatencyBuckets.indexOf(bounds[bounds.length - 1])).isEqualTo(bounds.length - 1);
        assertThat(LatencyBuckets.indexOf(bounds[bounds.length - 1] + 1)).isEqualTo(bounds.length);
        assertThat(LatencyBuckets.COUNT).isEqualTo(bounds.length + 1);
    }

    @Test
    void countSumAndExtremesAreExact() {
        LatencyHistogram h = new LatencyHistogram();
        for (long v : new long[] {7, 3, 250, 1200, 40}) h.record(v);
        DiagnosticsTiming t = h.toTiming("search.total", Map.of());
        assertThat(t.getCount()).isEqualTo(5);
        assertThat(t.getSumMs()).isEqualTo(1500);
        assertThat(t.getMinMs()).isEqualTo(3);
        assertThat(t.getMaxMs()).isEqualTo(1200);
        long inBuckets = 0;
        for (long b : t.getBuckets()) inBuckets += b;
        assertThat(inBuckets).isEqualTo(5);
        assertThat(t.getBuckets()[LatencyBuckets.indexOf(250)]).isEqualTo(1);
    }

    @Test
    void anEmptyHistogramReportsZeroNotLongMax() {
        DiagnosticsTiming t = new LatencyHistogram().toTiming("x", Map.of());
        assertThat(t.getMinMs()).isZero();
        assertThat(LatencyBuckets.percentile(t.getBuckets(), 0.5, t.getMinMs(), t.getMaxMs())).isZero();
    }

    @Test
    void percentilesOfAConstantSeriesAreThatConstant() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 100; i++) h.record(7);
        DiagnosticsTiming t = h.toTiming("x", Map.of());
        for (double q : new double[] {0.5, 0.9, 0.99}) {
            assertThat(LatencyBuckets.percentile(t.getBuckets(), q, t.getMinMs(), t.getMaxMs())).isEqualTo(7);
        }
    }

    @Test
    void percentilesLandInTheRightBucketForASpreadSeries() {
        // 90 fast calls and 10 slow ones: p50 must be fast, p99 must be slow, and neither may
        // exceed what was actually observed.
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 90; i++) h.record(15);
        for (int i = 0; i < 10; i++) h.record(4000);
        DiagnosticsTiming t = h.toTiming("x", Map.of());
        long p50 = LatencyBuckets.percentile(t.getBuckets(), 0.5, t.getMinMs(), t.getMaxMs());
        long p99 = LatencyBuckets.percentile(t.getBuckets(), 0.99, t.getMinMs(), t.getMaxMs());
        assertThat(p50).isBetween(10L, 20L);
        assertThat(p99).isBetween(2000L, 4000L);
        assertThat(LatencyBuckets.percentile(t.getBuckets(), 1.0, t.getMinMs(), t.getMaxMs())).isEqualTo(4000);
    }

    @Test
    void theOverflowBucketIsBoundedByTheObservedMaximum() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(5_000_000);
        DiagnosticsTiming t = h.toTiming("x", Map.of());
        assertThat(t.getBuckets()[LatencyBuckets.COUNT - 1]).isEqualTo(1);
        assertThat(LatencyBuckets.percentile(t.getBuckets(), 0.5, t.getMinMs(), t.getMaxMs())).isEqualTo(5_000_000);
    }

    @Test
    void mergingAddsBucketwiseAndKeepsTheExtremes() {
        LatencyHistogram a = new LatencyHistogram();
        a.record(5);
        a.record(100);
        LatencyHistogram b = new LatencyHistogram();
        b.record(2);
        b.record(900);
        DiagnosticsTiming into = a.toTiming("x", Map.of());
        LatencyHistogram.merge(into, b.toTiming("x", Map.of()));
        assertThat(into.getCount()).isEqualTo(4);
        assertThat(into.getSumMs()).isEqualTo(1007);
        assertThat(into.getMinMs()).isEqualTo(2);
        assertThat(into.getMaxMs()).isEqualTo(900);
        assertThat(into.getBuckets()[LatencyBuckets.indexOf(900)]).isEqualTo(1);
        assertThat(into.getBuckets()[LatencyBuckets.indexOf(5)]).isEqualTo(1);
    }

    @Test
    void mergingIntoAnEmptyTimingTakesTheOtherMinimum() {
        DiagnosticsTiming into = new LatencyHistogram().toTiming("x", Map.of());
        LatencyHistogram b = new LatencyHistogram();
        b.record(40);
        LatencyHistogram.merge(into, b.toTiming("x", Map.of()));
        assertThat(into.getMinMs()).isEqualTo(40);
    }
}
