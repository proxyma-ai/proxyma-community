package ai.proxyma.core.guardrail;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every guardrail check reaches the Audit Logs page - and the MATCHED TEXT never does.
 *
 * <p>With "Keep the matched text" on, a rule hands its matched span to the in-memory window, and
 * GuardrailEvidence promises that span never reaches disk. The audit log is on disk. This pins
 * that the entry carries rule ids and categories alone, whatever evidence the check was given.
 */
class GuardrailAuditTest {

    private static final String SECRET = "AKIAIOSFODNN7EXAMPLE";

    @BeforeEach
    void empty() {
        Audit.drain(Integer.MAX_VALUE);
    }

    private static AuditEntry recorded(GuardrailDecision decision, boolean enforced, List<GuardrailEvidence> evidence) {
        new GuardrailMetrics().record(GuardrailStage.RETRIEVAL, decision, enforced, evidence, 3_000_000);
        List<AuditEntry> all = Audit.drain(10);
        assertEquals(1, all.size());
        return all.get(0);
    }

    @Test
    @DisplayName("a check that fired is recorded with its rules and categories, and never the text it matched")
    void matchedTextNeverReachesTheLog() {
        GuardrailDecision decision = GuardrailDecision.redact("key [REDACTED]",
                List.of(GuardrailFinding.of("secret.aws-access-key", "secret", "an AWS access key")));
        AuditEntry e = recorded(decision, true,
                List.of(new GuardrailEvidence("secret.aws-access-key", "secret", SECRET, false)));

        assertEquals(AuditCategory.GUARDRAIL, e.category());
        assertEquals("guardrail.retrieval", e.action());
        assertEquals(AuditEntry.REDACTED, e.outcome());
        assertEquals("secret", e.target());
        assertEquals(List.of("secret.aws-access-key"), e.detail().get("rules"));
        assertEquals(3L, e.durationMs());
        assertFalse(e.toString().contains(SECRET), "the matched span reached the audit entry");
        assertNull(e.detail().get("matched"));
    }

    @Test
    @DisplayName("a check that let the text through is recorded too, so counts and timings cover every check")
    void allowIsRecorded() {
        AuditEntry e = recorded(GuardrailDecision.allow("fine"), true, List.of());
        assertEquals(AuditEntry.ALLOWED, e.outcome());
        assertNull(e.target());
    }

    @Test
    @DisplayName("in monitor mode a check that would have blocked is OBSERVED, not BLOCKED")
    void monitorModeIsObserved() {
        GuardrailDecision decision = GuardrailDecision.block("x",
                List.of(GuardrailFinding.of("pii.email", "pii", "an email address")));
        AuditEntry e = recorded(decision, false, List.of());
        assertEquals("OBSERVED", e.outcome());
        assertEquals("BLOCK", e.detail().get("verdict"));
        assertEquals(false, e.detail().get("enforced"));
    }
}
