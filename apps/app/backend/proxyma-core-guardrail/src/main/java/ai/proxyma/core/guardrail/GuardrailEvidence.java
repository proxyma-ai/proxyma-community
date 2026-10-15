package ai.proxyma.core.guardrail;

import ai.proxyma.core.guardrail.rule.GuardrailRule;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE MATCHED STRING, AND THE ONLY TYPE IN THIS MODULE THAT IS ALLOWED TO HOLD ONE.
 *
 * <p>{@link GuardrailFinding} refuses to carry matched text and still does. That refusal is the
 * right default and is what every log line, every client-facing event and every telemetry payload
 * in this product is built on. This type is the deliberate, narrow exception to it, added on
 * 2026-09-09 because an operator tuning a policy cannot tell a true positive from a false one
 * without seeing what fired - "3 matches, category secret" is a number, not an answer, and the
 * usual next step was to reproduce the message by hand and guess.
 *
 * <p>THE COST IS REAL AND IS NOT ARGUED AWAY. A secret-detection rule fires precisely because the
 * span it matched is a credential, so capturing it means the control that exists to stop a
 * credential being written down is, for the length of one in-memory window, the thing writing it
 * down. Everything below exists to bound that:
 *
 * <ul>
 *   <li>IT IS OFF BY DEFAULT, as {@code guardrail.captureMatchedText}. An install that updates
 *       into this version does not begin retaining credentials because it was upgraded. Somebody
 *       has to go to Settings and turn it on, which is also where the warning is.
 *   <li>IT NEVER REACHES DISK. The only holder is {@link GuardrailMetrics}, a 200-entry in-memory
 *       ring that a restart empties. There is no entity, no column and no file.
 *   <li>IT NEVER REACHES TELEMETRY. Telemetry sends counts and categories; the recent-decision
 *       window is served only to a local operator through the settings screen.
 *   <li>IT NEVER REACHES A LOG. Every log line in {@code GuardrailService} prints categories, and
 *       this type is not passed to any of them.
 *   <li>IT IS TRUNCATED. A rule can match a whole private-key block; {@link #MAX_CHARS} characters
 *       is enough to recognize what fired and not enough to be a usable copy of most of what this
 *       catches. That is a mitigation and not a guarantee - an API key is shorter than the limit,
 *       and capturing it whole is the entire point of the feature.
 * </ul>
 *
 * <p>ONLY DETERMINISTIC RULES PRODUCE EVIDENCE, and that is a property rather than a gap. A rule
 * knows the offsets it matched. The LLM classifier returns a verdict and categories over a whole
 * message and has no span to point at, so a classifier-only decision carries no evidence and the
 * panel says so instead of inventing one.
 *
 * @param rule     which check fired, e.g. {@code secret.aws-access-key}
 * @param category the policy bucket, e.g. {@code secret}, {@code pii}
 * @param match    the characters the rule objected to, truncated to {@link #MAX_CHARS}
 * @param truncated whether {@code match} is shorter than what actually matched
 */
public record GuardrailEvidence(String rule, String category, String match, boolean truncated) {

    /**
     * How much of a match is kept.
     *
     * <p>120 rather than a round 100 or an unbounded string: an AWS key is 20 characters, a JWT
     * runs to hundreds and a PEM block to thousands. This shows the whole of the short ones - which
     * are the ones an operator is trying to identify - and the opening of the long ones, which is
     * the part that says what KIND of thing it is.
     */
    public static final int MAX_CHARS = 120;

    /**
     * Pulls the matched characters out of the ORIGINAL text.
     *
     * <p>Original, not normalized: {@code RuleScanner} maps every span back to source coordinates
     * before it returns them, precisely so that a cut lands on the string that really gets stored
     * and shown. An operator reading this panel needs to see what they actually typed, homoglyphs
     * and zero-width characters included - a match displayed in its folded form would hide the
     * evasion attempt that is the whole reason the folding exists.
     *
     * <p>Defensive on the offsets rather than trusting them. These have been through a coordinate
     * mapping, and a {@code substring} that walks off the end of a string would turn a settings
     * panel into a 500 on the one screen an operator opens when something is already wrong.
     */
    public static List<GuardrailEvidence> from(String text, List<GuardrailRule.Span> spans) {
        if (text == null || text.isEmpty() || spans.isEmpty()) return List.of();

        List<GuardrailEvidence> out = new ArrayList<>();
        for (GuardrailRule.Span s : spans) {
            int start = Math.max(0, Math.min(s.start(), text.length()));
            int end = Math.max(start, Math.min(s.end(), text.length()));
            if (start == end) continue;

            String matched = text.substring(start, end);
            boolean truncated = matched.length() > MAX_CHARS;
            out.add(new GuardrailEvidence(s.rule(), s.category(),
                    truncated ? matched.substring(0, MAX_CHARS) : matched, truncated));
        }
        return List.copyOf(out);
    }
}
