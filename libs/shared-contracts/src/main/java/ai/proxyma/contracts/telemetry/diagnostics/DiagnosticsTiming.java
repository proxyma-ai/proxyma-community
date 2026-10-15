package ai.proxyma.contracts.telemetry.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How long one operation took over one day: a count, a sum, the extremes and a fixed-bucket
 * histogram, from which the server derives percentiles. Never an individual sample.
 *
 * <p>{@code op} is one of a fixed vocabulary of operation names ("search.total", "ingest.convert")
 * and every {@code labels} value is a code-defined token - a connector type, a provider protocol, a
 * file-type class. Nothing a user typed or named can reach either.
 */
public class DiagnosticsTiming {

    private String op;
    private Map<String, String> labels = new LinkedHashMap<>();
    private long count;
    private long sumMs;
    private long minMs;
    private long maxMs;
    private int bucketVersion = LatencyBuckets.VERSION;
    private long[] buckets = new long[LatencyBuckets.COUNT];

    public DiagnosticsTiming() {}

    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels == null ? new LinkedHashMap<>() : labels; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public long getSumMs() { return sumMs; }
    public void setSumMs(long sumMs) { this.sumMs = sumMs; }

    public long getMinMs() { return minMs; }
    public void setMinMs(long minMs) { this.minMs = minMs; }

    public long getMaxMs() { return maxMs; }
    public void setMaxMs(long maxMs) { this.maxMs = maxMs; }

    public int getBucketVersion() { return bucketVersion; }
    public void setBucketVersion(int bucketVersion) { this.bucketVersion = bucketVersion; }

    public long[] getBuckets() { return buckets; }
    public void setBuckets(long[] buckets) { this.buckets = buckets == null ? new long[LatencyBuckets.COUNT] : buckets; }
}
