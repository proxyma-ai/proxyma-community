package ai.proxyma.core.guardrail;

/**
 * One thing a guardrail objected to.
 *
 * <p>NOTE WHAT IS NOT HERE: the matched text. A finding travels into logs, into a client-facing
 * event and potentially into telemetry, and the whole reason a secret-detection rule fired is that
 * the span it matched is a credential. Carrying it would mean the control that exists to stop a
 * credential being written down is the thing that writes it down. Callers get the rule, the
 * category and a count - never the span.
 *
 * <p>THAT IS STILL TRUE OF THIS TYPE, and it is why {@link GuardrailEvidence} is a separate one
 * rather than a nullable field here. An operator tuning a policy does need to see what fired -
 * "3 matches, category secret" is a number, not an answer - and since 2026-09-09 they can, behind
 * a setting that is off by default. The reasoning above is exactly why that capability was NOT
 * added to this record: a finding goes to places evidence must never reach, and the only way to
 * keep that true is for the two to be different types with different destinations. Adding
 * {@code match} here would have shipped the credential to every log line for free.
 *
 * @param rule     which check fired, e.g. {@code secret.aws-access-key}. Stable enough to grep for
 * @param category the policy bucket a person would recognize, e.g. {@code secret}, {@code pii}
 * @param detail   a short human-readable note with NO matched content in it
 */
public record GuardrailFinding(String rule, String category, String detail) {

    public static GuardrailFinding of(String rule, String category, String detail) {
        return new GuardrailFinding(rule, category, detail);
    }
}
