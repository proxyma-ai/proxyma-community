package ai.proxyma.core.common.diagnostics;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;
import ai.proxyma.contracts.telemetry.diagnostics.LatencyBuckets;

import java.util.Map;

/**
 * Count, sum, extremes and a {@link LatencyBuckets} histogram for one series.
 *
 * <p>Constant memory whatever the traffic: a day of a thousand searches and a day of ten occupy the
 * same 22 longs. Synchronized rather than lock-free because a record is a handful of arithmetic
 * operations and contention on one series is rare - and because {@link #toTiming} must read a
 * consistent set, which separate atomics would not give.
 */
public final class LatencyHistogram {

    private final long[] buckets = new long[LatencyBuckets.COUNT];
    private long count;
    private long sumMs;
    private long minMs = Long.MAX_VALUE;
    private long maxMs;

    public synchronized void record(long ms) {
        long v = Math.max(0, ms);
        buckets[LatencyBuckets.indexOf(v)]++;
        count++;
        sumMs += v;
        if (v < minMs) minMs = v;
        if (v > maxMs) maxMs = v;
    }

    public synchronized long count() {
        return count;
    }

    public synchronized DiagnosticsTiming toTiming(String op, Map<String, String> labels) {
        DiagnosticsTiming t = new DiagnosticsTiming();
        t.setOp(op);
        t.setLabels(new java.util.LinkedHashMap<>(labels));
        t.setCount(count);
        t.setSumMs(sumMs);
        t.setMinMs(count == 0 ? 0 : minMs);
        t.setMaxMs(maxMs);
        t.setBucketVersion(LatencyBuckets.VERSION);
        t.setBuckets(buckets.clone());
        return t;
    }

    /**
     * Adds {@code from} into {@code into}. Both must be the same series; a timing counted under a
     * different bucket layout is not merged bucket-wise, only its count, sum and extremes.
     */
    public static void merge(DiagnosticsTiming into, DiagnosticsTiming from) {
        if (from == null || from.getCount() == 0) return;
        if (into.getCount() == 0) {
            into.setMinMs(from.getMinMs());
        } else {
            into.setMinMs(Math.min(into.getMinMs(), from.getMinMs()));
        }
        into.setMaxMs(Math.max(into.getMaxMs(), from.getMaxMs()));
        into.setCount(into.getCount() + from.getCount());
        into.setSumMs(into.getSumMs() + from.getSumMs());
        if (into.getBucketVersion() == from.getBucketVersion()) {
            long[] a = into.getBuckets();
            long[] b = from.getBuckets();
            long[] out = new long[Math.max(a.length, b.length)];
            for (int i = 0; i < out.length; i++) {
                out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
            }
            into.setBuckets(out);
        }
    }
}
