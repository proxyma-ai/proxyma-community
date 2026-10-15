package ai.proxyma.core.guardrail.rule;

import ai.proxyma.core.guardrail.GuardrailFinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a rule set over one string, and rewrites what it found. */
public final class RuleScanner {

    private RuleScanner() {}

    /** Everything a scan produced: where to cut, and what to say about it. */
    public record Result(List<GuardrailRule.Span> spans, List<GuardrailFinding> findings) {

        public boolean clean() { return spans.isEmpty(); }

        public static final Result CLEAN = new Result(List.of(), List.of());
    }

    /**
     * What appears in place of a removed span.
     *
     * <p>Deliberately visible. A silent deletion leaves a sentence that reads as though the model
     * wrote something incoherent, and the reader debugs the model instead of discovering there is
     * a policy. Naming the category also tells them which setting to look at.
     */
    public static String marker(String category) {
        return "[redacted: " + category + "]";
    }

    /**
     * Runs every rule over the text.
     *
     * <p>Rules match against a NORMALIZED copy - invisible characters removed, compatibility forms
     * and mixed-script homoglyphs folded (see {@link GuardrailText}) - because without that a
     * single zero-width space defeated every rule here, credential detection included. The spans
     * that come back are in ORIGINAL coordinates, mapped through {@code GuardrailText.Normalized},
     * so redaction still cuts the string that actually gets persisted and shown.
     *
     * <p>Getting only half of that right would be worse than neither: matching on normalized text
     * and cutting with normalized offsets would redact the wrong characters of the real string.
     */
    public static Result scan(String text, List<GuardrailRule> rules) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return Result.CLEAN;

        GuardrailText.Normalized normalized = GuardrailText.normalize(text);
        String haystack = normalized.text();

        List<GuardrailRule.Span> spans = new ArrayList<>();
        // Counted per rule so the finding can say "3 matches" without ever carrying one of them.
        Map<String, int[]> hitsByRule = new LinkedHashMap<>();
        Map<String, String> categoryByRule = new LinkedHashMap<>();

        for (GuardrailRule rule : rules) {
            List<GuardrailRule.Span> found = rule.spans(haystack);
            if (found.isEmpty()) continue;
            for (GuardrailRule.Span s : found) {
                spans.add(new GuardrailRule.Span(
                        startOf(text, normalized.sourceStart(s.start())),
                        endOf(text, normalized.sourceEnd(s.end(), text.length())),
                        s.rule(),
                        s.category(),
                        startOf(text, normalized.sourceStart(s.matchStart())),
                        endOf(text, normalized.sourceEnd(s.matchEnd(), text.length()))));
            }
            hitsByRule.computeIfAbsent(rule.id(), k -> new int[1])[0] += found.size();
            categoryByRule.put(rule.id(), rule.category());
        }
        if (spans.isEmpty()) return Result.CLEAN;

        List<GuardrailFinding> findings = new ArrayList<>();
        for (Map.Entry<String, int[]> e : hitsByRule.entrySet()) {
            int count = e.getValue()[0];
            findings.add(GuardrailFinding.of(e.getKey(), categoryByRule.get(e.getKey()),
                    count == 1 ? "1 match" : count + " matches"));
        }
        return new Result(spans, findings);
    }

    // A span never cuts a surrogate pair in half: the normalized text maps a character outside the
    // Basic Multilingual Plane to the first of its two UTF-16 units.
    private static int startOf(String text, int start) {
        return start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1)) ? start - 1 : start;
    }

    private static int endOf(String text, int end) {
        return end > 0 && end < text.length() && Character.isLowSurrogate(text.charAt(end))
                && Character.isHighSurrogate(text.charAt(end - 1)) ? end + 1 : end;
    }

    /**
     * The text with every span replaced by its marker.
     *
     * <p>Overlaps are merged first and the rewrite runs BACK TO FRONT. Two rules matching the same
     * region is normal - a private key block is also an assignment value, an email inside a path
     * is both - and replacing left to right would invalidate every later offset the moment the
     * first replacement changed the string's length. Going backwards means no index is ever stale.
     *
     * <p>A merged region keeps the rule and category of the span that started it, which is the
     * outermost and therefore the most informative of the two.
     *
     * <p>MERGING IS FOR REDACTION ONLY. Evidence is taken from the UNMERGED spans, so two rules
     * that both objected to the same region are reported as two matches rather than as one
     * belonging to whichever happened to start first.
     */
    public static String redact(String text, List<GuardrailRule.Span> spans) {
        if (spans.isEmpty()) return text;

        List<GuardrailRule.Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(GuardrailRule.Span::start)
                .thenComparing(Comparator.comparingInt(GuardrailRule.Span::end).reversed()));

        List<GuardrailRule.Span> merged = new ArrayList<>();
        for (GuardrailRule.Span s : sorted) {
            if (merged.isEmpty()) {
                merged.add(s);
                continue;
            }
            GuardrailRule.Span last = merged.get(merged.size() - 1);
            if (s.start() <= last.end()) {
                if (s.end() > last.end()) {
                    merged.set(merged.size() - 1,
                            new GuardrailRule.Span(last.start(), s.end(), last.rule(), last.category()));
                }
            } else {
                merged.add(s);
            }
        }

        StringBuilder sb = new StringBuilder(text);
        for (int i = merged.size() - 1; i >= 0; i--) {
            GuardrailRule.Span s = merged.get(i);
            sb.replace(s.start(), s.end(), marker(s.category()));
        }
        return sb.toString();
    }
}
