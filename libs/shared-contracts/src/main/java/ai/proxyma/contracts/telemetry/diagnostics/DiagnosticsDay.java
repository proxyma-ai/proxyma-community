package ai.proxyma.contracts.telemetry.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one installation measured on one UTC day.
 *
 * <p>{@code features} is a flat map of setting-level facts (whether reranking is on, how many
 * sources of each type exist, a document-count range). Its values are booleans, whole numbers or
 * code-defined tokens; the client builds it through a sanitizer that drops anything else, so a
 * free-text value cannot reach it even by mistake.
 */
public class DiagnosticsDay {

    /** ISO date, UTC: "2026-09-21". */
    private String day;
    /** Every app version that recorded something this day - more than one on an upgrade day. */
    private List<String> appVersions = new ArrayList<>();
    private DiagnosticsEnvironment environment;
    private Map<String, Object> features = new LinkedHashMap<>();
    private List<DiagnosticsTiming> timings = new ArrayList<>();
    private List<DiagnosticsCounter> counters = new ArrayList<>();
    private List<DiagnosticsError> errors = new ArrayList<>();
    /** Error kinds or series past the per-day caps: counted here, never itemized. */
    private long dropped;

    public DiagnosticsDay() {}

    public DiagnosticsDay(String day) {
        this.day = day;
    }

    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }

    public List<String> getAppVersions() { return appVersions; }
    public void setAppVersions(List<String> appVersions) { this.appVersions = appVersions == null ? new ArrayList<>() : appVersions; }

    public DiagnosticsEnvironment getEnvironment() { return environment; }
    public void setEnvironment(DiagnosticsEnvironment environment) { this.environment = environment; }

    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> features) { this.features = features == null ? new LinkedHashMap<>() : features; }

    public List<DiagnosticsTiming> getTimings() { return timings; }
    public void setTimings(List<DiagnosticsTiming> timings) { this.timings = timings == null ? new ArrayList<>() : timings; }

    public List<DiagnosticsCounter> getCounters() { return counters; }
    public void setCounters(List<DiagnosticsCounter> counters) { this.counters = counters == null ? new ArrayList<>() : counters; }

    public List<DiagnosticsError> getErrors() { return errors; }
    public void setErrors(List<DiagnosticsError> errors) { this.errors = errors == null ? new ArrayList<>() : errors; }

    public long getDropped() { return dropped; }
    public void setDropped(long dropped) { this.dropped = dropped; }
}
