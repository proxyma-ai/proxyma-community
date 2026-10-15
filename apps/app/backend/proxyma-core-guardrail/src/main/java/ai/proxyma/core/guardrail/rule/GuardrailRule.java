package ai.proxyma.core.guardrail.rule;

import ai.proxyma.core.guardrail.GuardrailStage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One deterministic check: a pattern, the stages it applies to, and an optional validator that
 * gets the final say.
 *
 * <p>THE VALIDATOR IS NOT DECORATION. Two of the rules that matter most cannot be expressed as a
 * regular expression alone. A payment card number is "13 to 19 digits that pass a Luhn checksum",
 * and the checksum is what separates a card from an order reference; without it the rule redacts
 * every long number in every answer. A credential assignment is "a key-ish name, then a value that
 * looks random", and "looks random" is a property of the characters rather than of their shape.
 *
 * <p>Rules never report the text they matched - see {@code GuardrailFinding}.
 */
public record GuardrailRule(String id,
                            String category,
                            Pattern pattern,
                            List<GuardrailStage> stages,
                            Predicate<String> validator) {

    /**
     * A span of the input a rule objected to, half-open as {@code [start, end)}.
     *
     * <p>{@code matchStart}/{@code matchEnd} are the whole MATCH, which for a rule with a group 1 is
     * wider than the part redacted - {@code password=} as well as its value.
     *
     * <p>IT CARRIES THE RULE ID AS WELL AS THE CATEGORY as of 2026-09-09, and it is still only
     * OFFSETS - never the characters between them. Redaction only ever needed the category, which
     * is what the marker prints; the id is here because the Activity panel names the rule that
     * fired, and because evidence capture - see {@code GuardrailEvidence}, which is off by default
     * - has to be able to say WHICH rule a captured string came from. A category is a bucket that
     * a dozen rules share, and "secret" is not an answer to "why was this taken out".
     */
    public record Span(int start, int end, String rule, String category, int matchStart, int matchEnd) {

        /** A span whose match is exactly what is redacted. */
        public Span(int start, int end, String rule, String category) {
            this(start, end, rule, category, start, end);
        }
    }

    public static GuardrailRule of(String id, String category, String regex, GuardrailStage... stages) {
        return new GuardrailRule(id, category, Pattern.compile(regex), List.of(stages), null);
    }

    public static GuardrailRule of(String id, String category, String regex,
                                   Predicate<String> validator, GuardrailStage... stages) {
        return new GuardrailRule(id, category, Pattern.compile(regex), List.of(stages), validator);
    }

    public boolean appliesTo(GuardrailStage stage) {
        return stages.contains(stage);
    }

    /**
     * Every span this rule objects to, in order.
     *
     * <p>Group 1 wins when the pattern declares one. That is how a rule can require surrounding
     * context to fire without redacting the context along with the value - a credential assignment
     * has to see {@code api_key =} to be sure, and redacting those characters too would remove the
     * one part of the line a reader needs in order to understand what was taken out.
     */
    public List<Span> spans(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (m.groupCount() >= 1 && m.start(1) >= 0) {
                start = m.start(1);
                end = m.end(1);
            }
            if (validator != null && !validator.test(text.substring(start, end))) {
                continue;
            }
            // The whole match travels with the redacted part: a streamed cut must keep a credential
            // assignment's key and value together, or the value is released on the next flush,
            // alone, where the pattern no longer matches it (StreamingScrubber).
            out.add(new Span(start, end, id, category, m.start(), m.end()));
        }
        return out;
    }
}
