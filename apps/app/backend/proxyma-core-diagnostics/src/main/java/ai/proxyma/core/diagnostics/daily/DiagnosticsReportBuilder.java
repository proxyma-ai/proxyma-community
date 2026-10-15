package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsCounter;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsError;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsReport;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;
import ai.proxyma.core.common.diagnostics.LatencyHistogram;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.telemetryclient.InstanceIdProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the {@link DiagnosticsReport} - for sending AND for the "See what would be sent" preview,
 * through the same {@link #build} call, so the preview cannot show something other than what goes.
 *
 * <p>Takes partial days (flushed chunks plus whatever is still in memory), merges each day's
 * partials into one, and stamps the header: the random installation id, version, tier, edition.
 * Nothing else is added here; everything in a day was recorded through the aggregator's label
 * sanitizer, and the feature snapshot through {@link DiagnosticsSnapshot#sanitize}.
 */
@Component
public class DiagnosticsReportBuilder {

    @Value("${spring.application.version:0.0.0}")
    private String appVersion;

    private final InstanceIdProvider instanceIdProvider;
    private final FeatureGateService featureGateService;
    private final DiagnosticsSnapshot snapshot;

    public DiagnosticsReportBuilder(InstanceIdProvider instanceIdProvider, FeatureGateService featureGateService,
                                    DiagnosticsSnapshot snapshot) {
        this.instanceIdProvider = instanceIdProvider;
        this.featureGateService = featureGateService;
        this.snapshot = snapshot;
    }

    public DiagnosticsReport build(List<DiagnosticsDay> partials) {
        DiagnosticsReport report = new DiagnosticsReport();
        report.setInstanceId(instanceIdProvider.getInstanceId());
        report.setAppVersion(appVersion);
        report.setTier(featureGateService.getCurrentTier().name());
        report.setEdition(snapshot.edition());
        report.setGeneratedAt(Instant.now().toString());
        List<DiagnosticsDay> days = mergeByDay(partials);
        for (DiagnosticsDay day : days) {
            // A day flushed before its snapshot was taken (or today, still in memory) gets the
            // current one. Environment and features change rarely; "as of when it was sent" is the
            // honest reading of a snapshot attached this late, and it is only ever the latest day.
            if (day.getEnvironment() == null) day.setEnvironment(snapshot.environment());
            if (day.getFeatures() == null || day.getFeatures().isEmpty()) day.setFeatures(snapshot.features());
            if (day.getAppVersions().isEmpty() && appVersion != null) day.getAppVersions().add(appVersion);
        }
        report.setDays(days);
        return report;
    }

    /** Merges partial days into one per date, oldest first. */
    public static List<DiagnosticsDay> mergeByDay(List<DiagnosticsDay> partials) {
        Map<String, List<DiagnosticsDay>> byDay = new TreeMap<>();
        for (DiagnosticsDay p : partials) {
            if (p == null || p.getDay() == null) continue;
            byDay.computeIfAbsent(p.getDay(), k -> new ArrayList<>()).add(p);
        }
        List<DiagnosticsDay> out = new ArrayList<>(byDay.size());
        for (Map.Entry<String, List<DiagnosticsDay>> e : byDay.entrySet()) {
            out.add(merge(e.getKey(), e.getValue()));
        }
        return out;
    }

    static DiagnosticsDay merge(String dayKey, List<DiagnosticsDay> parts) {
        DiagnosticsDay day = new DiagnosticsDay(dayKey);
        Map<String, DiagnosticsTiming> timings = new LinkedHashMap<>();
        Map<String, DiagnosticsCounter> counters = new LinkedHashMap<>();
        Map<String, DiagnosticsError> errors = new LinkedHashMap<>();
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        long dropped = 0;
        for (DiagnosticsDay p : parts) {
            versions.addAll(p.getAppVersions());
            dropped += p.getDropped();
            // Last snapshot wins - parts arrive oldest first.
            if (p.getEnvironment() != null) day.setEnvironment(p.getEnvironment());
            if (p.getFeatures() != null && !p.getFeatures().isEmpty()) day.setFeatures(p.getFeatures());
            for (DiagnosticsTiming t : p.getTimings()) {
                String key = t.getOp() + new TreeMap<>(t.getLabels());
                DiagnosticsTiming into = timings.get(key);
                if (into == null) {
                    into = new DiagnosticsTiming();
                    into.setOp(t.getOp());
                    into.setLabels(new LinkedHashMap<>(new TreeMap<>(t.getLabels())));
                    into.setBucketVersion(t.getBucketVersion());
                    timings.put(key, into);
                }
                LatencyHistogram.merge(into, t);
            }
            for (DiagnosticsCounter c : p.getCounters()) {
                String key = c.getName() + new TreeMap<>(c.getLabels());
                DiagnosticsCounter into = counters.computeIfAbsent(key,
                        k -> new DiagnosticsCounter(c.getName(), new LinkedHashMap<>(new TreeMap<>(c.getLabels())), 0));
                into.setCount(into.getCount() + c.getCount());
            }
            for (DiagnosticsError err : p.getErrors()) {
                DiagnosticsError into = errors.get(err.getFingerprint());
                if (into == null) {
                    into = copy(err);
                    into.setCount(0);
                    errors.put(err.getFingerprint(), into);
                }
                into.setCount(into.getCount() + err.getCount());
                into.setFirstSeen(min(into.getFirstSeen(), err.getFirstSeen()));
                into.setLastSeen(max(into.getLastSeen(), err.getLastSeen()));
            }
        }
        day.setAppVersions(new ArrayList<>(versions));
        day.setTimings(new ArrayList<>(timings.values()));
        day.setCounters(new ArrayList<>(counters.values()));
        List<DiagnosticsError> errs = new ArrayList<>(errors.values());
        errs.sort(Comparator.comparingLong(DiagnosticsError::getCount).reversed()
                .thenComparing(DiagnosticsError::getFingerprint));
        day.setErrors(errs);
        day.setDropped(dropped);
        return day;
    }

    private static DiagnosticsError copy(DiagnosticsError e) {
        DiagnosticsError c = new DiagnosticsError();
        c.setFingerprint(e.getFingerprint());
        c.setLevel(e.getLevel());
        c.setLogger(e.getLogger());
        c.setException(e.getException());
        c.setRootCause(e.getRootCause());
        c.setFrames(new ArrayList<>(e.getFrames()));
        c.setTrace(new ArrayList<>(e.getTrace()));
        c.setCauses(new ArrayList<>(e.getCauses()));
        c.setCount(e.getCount());
        c.setFirstSeen(e.getFirstSeen());
        c.setLastSeen(e.getLastSeen());
        return c;
    }

    // "HH:mm" compares correctly as text.
    private static String min(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String max(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
}
