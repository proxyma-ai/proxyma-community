package ai.proxyma.core.guardrail;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the guardrails did, and what they cost.
 *
 * <p>WHY THIS EXISTS. Without it, turning guardrails on is a leap: an operator cannot see what
 * would have fired before it starts refusing their users, cannot tell whether a threshold is too
 * strict or doing nothing at all, and cannot answer "what is this costing me per turn". The
 * zero-width-space bypass lived in this module through a release, and nothing anywhere would have
 * said so - the only reason it was found was somebody going to look.
 *
 * <p>IN MEMORY, AND IT RESETS ON RESTART. That is a deliberate limit, not an oversight. Persisting
 * would mean a new JPA entity, which has to be added to BOTH editions' datasource lists or one of
 * them silently fails to boot - and the use case this actually serves is a tuning session: turn on
 * monitor mode, use the product, look at what fired, pick a threshold. A rolling window is enough
 * for that, and it costs nothing that can break a deployment. Long-horizon retention would be a
 * different feature with a different design.
 *
 * <p>NO CONVERSATION CONTENT, EVER. A recent-decision row carries the stage, the verdict, the
 * categories and a duration. Not the message, not the matched span, not the policy clause. This is
 * the component most tempted to keep "just a snippet for debugging", and a guardrail's own
 * telemetry is the last place a credential should end up.
 *
 * <p>Thread-safe by construction: turns run concurrently on their own worker threads, so every
 * counter is atomic and the two bounded buffers are guarded by their own locks.
 */
@Component
public class GuardrailMetrics {

    /**
     * How many recent decisions are kept.
     *
     * <p>Enough to read a tuning session off, small enough that it is never a memory question. The
     * counters below are unbounded in time and are what a longer view is built from.
     */
    private static final int RECENT_LIMIT = 200;

    /** Latency samples per phase, for percentiles. Same reasoning on the size. */
    private static final int LATENCY_SAMPLES = 500;

    /** The phases a check goes through, each separately timed because each has a different cost. */
    public enum Phase {
        /** Regular expressions and the deny-list. Microseconds, no network. */
        RULES,
        /** One embedding plus a nearest-neighbour lookup against the examples corpus. */
        EXAMPLES,
        /** One embedding plus hybrid retrieval against the rules corpus. */
        RETRIEVAL,
        /** The classifier completion - almost always the whole cost of a turn's guardrail. */
        CLASSIFIER,
        /** Everything above, end to end, as the turn experienced it. */
        TOTAL
    }

    /**
     * One decision, as the Activity panel shows it.
     *
     * <p>THIS USED TO SAY "with nothing in it that could identify a person or quote their text",
     * and that stopped being true on 2026-09-09 - so it says what it is instead. {@code evidence}
     * is EMPTY unless {@code guardrail.captureMatchedText} is on, which it is not by default; when
     * it is on, it holds the matched spans and every warning about that lives on
     * {@link GuardrailEvidence}. Nothing else here quotes anything.
     *
     * <p>{@code rules} is new in the same change and is NOT gated. The manual has promised a rule
     * name in this panel since it was written, and the row carried only categories - so the panel
     * said "secret" where the documentation said {@code secret.aws-access-key}. Rule ids are the
     * same class of thing as categories: a name for a check, with no content in it.
     */
    public record RecentDecision(Instant at,
                                 GuardrailStage stage,
                                 String verdict,
                                 boolean enforced,
                                 List<String> categories,
                                 List<String> rules,
                                 List<GuardrailEvidence> evidence,
                                 long millis) {}

    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong allowed = new AtomicLong();
    private final AtomicLong redacted = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    /** Decisions that WOULD have blocked or redacted but did not, because mode is MONITOR. */
    private final AtomicLong observedOnly = new AtomicLong();
    /** Classifier calls that failed and were allowed through - the fail-open count, made visible. */
    private final AtomicLong classifierFailures = new AtomicLong();

    private final Map<String, AtomicLong> byRule = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> byCategory = new ConcurrentHashMap<>();

    private final Map<Phase, Deque<Long>> latency = new ConcurrentHashMap<>();
    private final Deque<RecentDecision> recent = new ArrayDeque<>();

    private volatile Instant since = Instant.now();

    public void recordPhase(Phase phase, long nanos) {
        // Also into the daily diagnostics, which unlike this window survives a restart and can be
        // compared across installations. The phase name is the whole label.
        Diagnostics.time(DiagOp.GUARDRAIL_PHASE, nanos / 1_000_000,
                "phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        Deque<Long> samples = latency.computeIfAbsent(phase, k -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(nanos / 1_000_000);
            while (samples.size() > LATENCY_SAMPLES) samples.removeFirst();
        }
    }

    public void recordClassifierFailure() {
        Diagnostics.count(DiagCounter.GUARDRAIL_CLASSIFIER_FAILURE);
        classifierFailures.incrementAndGet();
        AuditEntry.of(AuditCategory.GUARDRAIL, "guardrail.classifier-failed")
                .outcome(AuditEntry.FAILED)
                .user(SearchContext.getUserId())
                .conversation(SearchContext.getConversationId())
                .summary("The classifier could not be reached; the text was allowed through")
                .record();
    }

    /**
     * Records one completed check.
     *
     * <p>{@code evidence} is whatever the caller was allowed to capture, and this class does not
     * decide that - {@code GuardrailService} passes an empty list unless the deployment has turned
     * capture on. Keeping the decision there rather than here means there is exactly one place
     * that reads the setting, and this one cannot be handed a payload it then has to second-guess.
     *
     * @param enforced false when the deployment is in MONITOR mode and the verdict was observed
     *                 rather than applied
     * @param evidence matched spans, or empty - see {@link GuardrailEvidence}
     */
    public void record(GuardrailStage stage, GuardrailDecision decision, boolean enforced,
                       List<GuardrailEvidence> evidence, long totalNanos) {
        checks.incrementAndGet();
        recordPhase(Phase.TOTAL, totalNanos);
        audit(stage, decision, enforced, totalNanos);
        // Stage, verdict and whether it was enforced - never the rule or category, since a
        // deployment's own deny-list rules carry names its operator chose.
        Diagnostics.count(DiagCounter.GUARDRAIL_DECISION,
                "stage", stage == null ? "none" : stage.name(),
                "verdict", decision.verdict().name(),
                "enforced", DiagLabels.onOff(enforced));

        switch (decision.verdict()) {
            case ALLOW -> allowed.incrementAndGet();
            case REDACT -> redacted.incrementAndGet();
            case BLOCK -> blocked.incrementAndGet();
        }
        if (!enforced && decision.verdict() != GuardrailDecision.Verdict.ALLOW) {
            observedOnly.incrementAndGet();
        }
        for (GuardrailFinding f : decision.findings()) {
            byRule.computeIfAbsent(f.rule(), k -> new AtomicLong()).incrementAndGet();
            byCategory.computeIfAbsent(f.category(), k -> new AtomicLong()).incrementAndGet();
        }

        // Only decisions worth looking at. An ALLOW with nothing to say is the overwhelming
        // majority of turns and would push everything interesting out of a 200-entry window
        // within minutes.
        if (decision.verdict() != GuardrailDecision.Verdict.ALLOW) {
            List<String> rules = decision.findings().stream()
                    .map(GuardrailFinding::rule).distinct().toList();
            RecentDecision row = new RecentDecision(Instant.now(), stage,
                    decision.verdict().name().toLowerCase(java.util.Locale.ROOT), enforced,
                    decision.categories(), rules,
                    evidence == null ? List.of() : List.copyOf(evidence),
                    totalNanos / 1_000_000);
            synchronized (recent) {
                recent.addLast(row);
                while (recent.size() > RECENT_LIMIT) recent.removeFirst();
            }
        }
    }

    /**
     * Every check, for the Audit Logs page - ALLOW included, so its counts and latencies cover
     * everything that was checked rather than what fired. Rule ids and categories only - NEVER the
     * matched text, even with capture on: GuardrailEvidence promises it never reaches disk, and
     * this table is on disk. It stays in the in-memory window below, which the page shows apart.
     */
    private static void audit(GuardrailStage stage, GuardrailDecision decision, boolean enforced,
                              long totalNanos) {
        try {
            String verdict = decision.verdict().name();
            String outcome = switch (decision.verdict()) {
                case ALLOW -> AuditEntry.ALLOWED;
                case REDACT -> enforced ? AuditEntry.REDACTED : "OBSERVED";
                case BLOCK -> enforced ? AuditEntry.BLOCKED : "OBSERVED";
            };
            String stageName = stage == null ? "none" : stage.name().toLowerCase(java.util.Locale.ROOT);
            List<String> categories = decision.categories();
            List<String> rules = decision.findings().stream().map(GuardrailFinding::rule).distinct().toList();
            String verdictText = switch (decision.verdict()) {
                case ALLOW -> "allowed";
                case REDACT -> enforced ? "redacted" : "would have redacted";
                case BLOCK -> enforced ? "blocked" : "would have blocked";
            };
            String summary = Character.toUpperCase(stageName.charAt(0)) + stageName.substring(1) + " check: "
                    + verdictText
                    + (enforced || decision.verdict() == GuardrailDecision.Verdict.ALLOW ? "" : " (monitor mode, not applied)")
                    + (categories.isEmpty() ? "" : " - " + String.join(", ", categories));
            AuditEntry.of(AuditCategory.GUARDRAIL, "guardrail." + stageName)
                    .outcome(outcome)
                    .user(SearchContext.getUserId())
                    .conversation(SearchContext.getConversationId())
                    .target(categories.isEmpty() ? null : categories.get(0))
                    .summary(summary)
                    .duration(totalNanos / 1_000_000)
                    .detail("stage", stage == null ? null : stage.name())
                    .detail("verdict", verdict)
                    .detail("enforced", enforced)
                    .detail("categories", categories.isEmpty() ? null : categories)
                    .detail("rules", rules.isEmpty() ? null : rules)
                    .record();
        } catch (RuntimeException ignored) {
            // Auditing must never fail a check.
        }
    }

    /** Everything the metrics endpoint reports, as one immutable snapshot. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", since.toString());
        out.put("checks", checks.get());
        out.put("allowed", allowed.get());
        out.put("redacted", redacted.get());
        out.put("blocked", blocked.get());
        out.put("observedOnly", observedOnly.get());
        out.put("classifierFailures", classifierFailures.get());
        out.put("byRule", sortedCounts(byRule));
        out.put("byCategory", sortedCounts(byCategory));

        Map<String, Object> lat = new LinkedHashMap<>();
        for (Phase p : Phase.values()) {
            Map<String, Object> stats = latencyStats(p);
            if (stats != null) lat.put(p.name().toLowerCase(java.util.Locale.ROOT), stats);
        }
        out.put("latencyMs", lat);

        List<RecentDecision> rows;
        synchronized (recent) {
            rows = new ArrayList<>(recent);
        }
        java.util.Collections.reverse(rows);
        out.put("recent", rows);
        return out;
    }

    public void reset() {
        checks.set(0);
        allowed.set(0);
        redacted.set(0);
        blocked.set(0);
        observedOnly.set(0);
        classifierFailures.set(0);
        byRule.clear();
        byCategory.clear();
        latency.clear();
        synchronized (recent) {
            recent.clear();
        }
        since = Instant.now();
    }

    private static Map<String, Long> sortedCounts(Map<String, AtomicLong> source) {
        return source.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicLong>>comparingLong(e -> -e.getValue().get())
                        .thenComparing(Map.Entry::getKey))
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue().get()),
                        LinkedHashMap::putAll);
    }

    /**
     * Count, median and p95 for one phase.
     *
     * <p>Percentiles over the retained window rather than over all time. An average would hide the
     * thing that matters here: the classifier's cost is bimodal - nothing at all on the turns it
     * is not called, and a full completion on the ones it is - and a mean over those two describes
     * neither.
     */
    private Map<String, Object> latencyStats(Phase phase) {
        Deque<Long> samples = latency.get(phase);
        if (samples == null) return null;
        long[] values;
        synchronized (samples) {
            if (samples.isEmpty()) return null;
            values = samples.stream().mapToLong(Long::longValue).toArray();
        }
        Arrays.sort(values);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("samples", values.length);
        stats.put("p50", values[values.length / 2]);
        stats.put("p95", values[Math.min(values.length - 1, (int) (values.length * 0.95))]);
        stats.put("max", values[values.length - 1]);
        return stats;
    }
}
