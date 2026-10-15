package ai.proxyma.core.guardrail;

import java.util.List;

/**
 * What a guardrail decided about one piece of text.
 *
 * <p>The text comes back WITH the decision rather than the caller re-deriving it. A REDACT verdict
 * is only useful together with the redacted string, and a caller that had to apply the redaction
 * itself would need the matched spans - which is exactly what {@link GuardrailFinding} refuses to
 * carry. Keeping the rewrite on this side is what lets the finding stay free of content.
 *
 * @param verdict  what to do
 * @param text     the text to use from here on. Unchanged for ALLOW, rewritten for REDACT, and
 *                 for BLOCK the ORIGINAL - a blocked turn discards it rather than showing it,
 *                 and handing back a mangled string would invite someone to display it
 * @param findings everything that fired, in the order it was checked. Empty for ALLOW
 */
public record GuardrailDecision(Verdict verdict, String text, List<GuardrailFinding> findings) {

    public enum Verdict {
        /** Nothing fired. */
        ALLOW,
        /** Something fired and was removed; the rest is fine to use. */
        REDACT,
        /** Something fired and the turn must not proceed on this text. */
        BLOCK
    }

    public static GuardrailDecision allow(String text) {
        return new GuardrailDecision(Verdict.ALLOW, text, List.of());
    }

    public static GuardrailDecision redact(String redactedText, List<GuardrailFinding> findings) {
        return new GuardrailDecision(Verdict.REDACT, redactedText, List.copyOf(findings));
    }

    public static GuardrailDecision block(String originalText, List<GuardrailFinding> findings) {
        return new GuardrailDecision(Verdict.BLOCK, originalText, List.copyOf(findings));
    }

    public boolean isAllowed() { return verdict == Verdict.ALLOW; }

    public boolean isBlocked() { return verdict == Verdict.BLOCK; }

    /** The distinct categories that fired, for a message a person has to read. */
    public List<String> categories() {
        return findings.stream().map(GuardrailFinding::category).distinct().toList();
    }
}
