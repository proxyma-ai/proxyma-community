package ai.proxyma.contracts.telemetry.diagnostics;

/**
 * The fixed latency buckets every {@link DiagnosticsTiming} is counted into.
 *
 * <p>PART OF THE WIRE CONTRACT, not a detail of either side. A client counts a duration into
 * {@code buckets[i]} and the telemetry server reads percentiles back out of the same array, so the
 * two have to agree on what bucket {@code i} means. Changing a bound is therefore a breaking change:
 * {@link DiagnosticsTiming#getBucketVersion()} says which layout a timing was counted in, and a new
 * layout needs a new version rather than an edit to this one.
 *
 * <p>Roughly log-scale, in milliseconds, from 1 ms (a keyword lookup) to 30 minutes (an OCR run on a
 * long scanned PDF). Bucket {@code i} counts durations {@code <= boundsMs()[i]} and greater than the
 * previous bound; the final bucket, one past the last bound, is the overflow.
 */
public final class LatencyBuckets {

    /** The layout version the bounds below describe. */
    public static final int VERSION = 1;

    /** Inclusive upper bounds, milliseconds. */
    private static final long[] BOUNDS = {
            1, 2, 5, 10, 20, 50, 100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 60_000,
            120_000, 300_000, 600_000, 1_800_000
    };

    /** Number of buckets, the overflow included. */
    public static final int COUNT = BOUNDS.length + 1;

    private LatencyBuckets() {}

    /** A copy of the inclusive upper bounds, milliseconds. */
    public static long[] boundsMs() {
        return BOUNDS.clone();
    }

    /** The bucket a duration falls into. Negative durations count as zero. */
    public static int indexOf(long ms) {
        long v = Math.max(0, ms);
        for (int i = 0; i < BOUNDS.length; i++) {
            if (v <= BOUNDS[i]) return i;
        }
        return BOUNDS.length;
    }

    /**
     * An estimate of the {@code q}-quantile (0..1) of the durations counted into {@code buckets}.
     *
     * <p>Linear interpolation inside the bucket the quantile falls in, then clamped to the observed
     * {@code [minMs, maxMs]} - so a series whose every sample was 7 ms reports 7 ms rather than a
     * point somewhere in the 5..10 bucket. Returns 0 for an empty series.
     */
    public static long percentile(long[] buckets, double q, long minMs, long maxMs) {
        if (buckets == null) return 0;
        long total = 0;
        for (long b : buckets) total += Math.max(0, b);
        if (total == 0) return 0;
        double qq = Math.min(1.0, Math.max(0.0, q));
        double rank = Math.max(1.0, qq * total);
        long cumulative = 0;
        int n = Math.min(buckets.length, COUNT);
        for (int i = 0; i < n; i++) {
            long inBucket = Math.max(0, buckets[i]);
            if (inBucket == 0) continue;
            if (cumulative + inBucket >= rank) {
                long lower = i == 0 ? 0 : BOUNDS[i - 1];
                long upper = i < BOUNDS.length ? BOUNDS[i] : Math.max(maxMs, lower);
                double fraction = (rank - cumulative) / inBucket;
                long estimate = Math.round(lower + fraction * (upper - lower));
                if (maxMs > 0) estimate = Math.min(estimate, maxMs);
                return Math.max(estimate, Math.max(0, minMs));
            }
            cumulative += inBucket;
        }
        return Math.max(0, maxMs);
    }
}
