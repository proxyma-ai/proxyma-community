package ai.proxyma.contracts.telemetry.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many times something happened over one day - a sync that ended incomplete, an LLM call a
 * provider refused, an update that was applied. Same vocabulary rule as {@link DiagnosticsTiming}:
 * a fixed {@code name} and code-defined label values only.
 */
public class DiagnosticsCounter {

    private String name;
    private Map<String, String> labels = new LinkedHashMap<>();
    private long count;

    public DiagnosticsCounter() {}

    public DiagnosticsCounter(String name, Map<String, String> labels, long count) {
        this.name = name;
        setLabels(labels);
        this.count = count;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels == null ? new LinkedHashMap<>() : labels; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
}
