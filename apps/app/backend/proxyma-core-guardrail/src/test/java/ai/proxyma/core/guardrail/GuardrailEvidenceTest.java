package ai.proxyma.core.guardrail;

import ai.proxyma.core.guardrail.rule.GuardrailRule;
import ai.proxyma.core.guardrail.rule.GuardrailRules;
import ai.proxyma.core.guardrail.rule.RuleScanner;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence capture, which is a SECURITY TEST wearing a feature's clothes.
 *
 * <p>This is the one place in the product where matched text - which for the rule that matters
 * most IS a credential - is lifted out of a message and kept. Everything here pins a property that
 * would be a leak if it stopped holding, and every one of them is the kind of thing a compile has
 * no opinion about:
 *
 * <ul>
 *   <li>the DEFAULT is off, so an install that updates into this version does not begin retaining
 *       credentials because somebody applied an update;
 *   <li>{@link GuardrailFinding} still carries no matched text, which is what every log line,
 *       client event and telemetry payload in this product is built on;
 *   <li>a long match is truncated, so a private-key block is not copied whole;
 *   <li>the offsets are ORIGINAL coordinates, so what an operator reads is what was actually
 *       typed rather than the normalized fold - which is the difference between seeing an evasion
 *       attempt and seeing the string it was disguising.
 * </ul>
 *
 * <p>The parallel is deliberate: DemoProviderSeeder's tests exist because that code writes an API
 * key to a public instance, and these exist because this code writes one to a settings panel.
 */
class GuardrailEvidenceTest {

    /** Not a real key. The prefix and shape are what the rule matches on. */
    private static final String AWS_KEY = "AKIAIOSFODNN7EXAMPLE";

    private static GuardrailSettings settings() {
        GuardrailSettings s = new GuardrailSettings();
        s.setDetectSecrets(true);
        s.setDetectPii(true);
        s.setDetectPromptInjection(true);
        s.setDetectPathLeak(true);
        return s;
    }

    private static List<GuardrailRule> secretRules() {
        return GuardrailRules.enabledFor(settings(), GuardrailStage.INPUT).stream()
                .filter(r -> r.id().startsWith("secret.")).toList();
    }

    private static RuleScanner.Result scan(String text) {
        return RuleScanner.scan(text, secretRules());
    }

    @Nested
    @DisplayName("the default")
    class Default {

        @Test
        @DisplayName("capture is OFF on a fresh settings object")
        void offByDefault() {
            // If this ever flips, an existing install begins retaining credentials in memory
            // because it was updated. That is the whole reason the field has a default at all.
            assertFalse(new GuardrailSettings().isCaptureMatchedText());
        }
    }

    @Nested
    @DisplayName("the finding contract")
    class FindingContract {

        @Test
        @DisplayName("a finding still carries no matched text, only a count")
        void findingHasNoMatch() {
            RuleScanner.Result result = scan("my key is " + AWS_KEY + " ok");
            assertFalse(result.clean(), "the secret rule should have fired");

            for (GuardrailFinding f : result.findings()) {
                assertFalse(f.detail().contains(AWS_KEY),
                        "GuardrailFinding.detail must never quote the match - it reaches logs, "
                                + "client events and telemetry");
                assertFalse(f.rule().contains(AWS_KEY));
                assertFalse(f.category().contains(AWS_KEY));
            }
        }
    }

    @Nested
    @DisplayName("extraction")
    class Extraction {

        @Test
        @DisplayName("the matched span comes back with the rule that found it")
        void carriesRuleAndMatch() {
            String text = "aws key " + AWS_KEY + " end";
            List<GuardrailEvidence> evidence = GuardrailEvidence.from(text, scan(text).spans());

            assertFalse(evidence.isEmpty(), "a fired rule should produce evidence");
            GuardrailEvidence first = evidence.get(0);
            assertEquals(AWS_KEY, first.match());
            assertEquals("secret", first.category());
            assertNotNull(first.rule());
            assertTrue(first.rule().startsWith("secret."),
                    "the rule id is what the manual promises in the Activity panel, not the bucket");
            assertFalse(first.truncated());
        }

        @Test
        @DisplayName("a long match is truncated and says so")
        void truncates() {
            // A private key block is thousands of characters. Whatever rule catches it, the
            // evidence must not be a usable copy of the whole thing.
            String longSecret = "A".repeat(GuardrailEvidence.MAX_CHARS + 500);
            List<GuardrailRule.Span> spans =
                    List.of(new GuardrailRule.Span(0, longSecret.length(), "secret.test", "secret"));

            GuardrailEvidence e = GuardrailEvidence.from(longSecret, spans).get(0);
            assertEquals(GuardrailEvidence.MAX_CHARS, e.match().length());
            assertTrue(e.truncated());
        }

        @Test
        @DisplayName("the match is the ORIGINAL text, not the normalized fold")
        void originalCoordinates() {
            // A zero-width space inside the key. The rules match a normalized copy and return
            // ORIGINAL offsets, so what an operator reads has to be what was actually sent -
            // otherwise the panel hides the evasion attempt it exists to reveal.
            String zwsp = "​";
            String text = "key " + AWS_KEY.substring(0, 8) + zwsp + AWS_KEY.substring(8);

            RuleScanner.Result result = scan(text);
            assertFalse(result.clean(), "normalization should let the rule fire through the ZWSP");

            List<GuardrailEvidence> evidence = GuardrailEvidence.from(text, result.spans());
            assertFalse(evidence.isEmpty());
            assertTrue(evidence.get(0).match().contains(zwsp),
                    "the captured span must show the zero-width character that was really there");
        }

        @Test
        @DisplayName("no spans means no evidence, and null text does not throw")
        void emptyCases() {
            assertTrue(GuardrailEvidence.from("anything", List.of()).isEmpty());
            assertTrue(GuardrailEvidence.from(null, List.of()).isEmpty());
            assertTrue(GuardrailEvidence.from("", List.of()).isEmpty());
        }

        @Test
        @DisplayName("offsets past the end of the text are clamped rather than thrown")
        void clampsBadOffsets() {
            // These have been through a coordinate mapping. A substring walking off the end would
            // turn the settings panel into a 500 on the one screen somebody opens when something
            // is already wrong.
            List<GuardrailEvidence> e = GuardrailEvidence.from(
                    "short", List.of(new GuardrailRule.Span(2, 9999, "secret.test", "secret")));
            assertEquals(1, e.size());
            assertEquals("ort", e.get(0).match());
        }
    }

    @Nested
    @DisplayName("what the metrics window keeps")
    class MetricsWindow {

        private static final GuardrailFinding FINDING =
                GuardrailFinding.of("secret.aws-access-key", "secret", "1 match");

        private static GuardrailDecision blocked() {
            return GuardrailDecision.block("does not matter", List.of(FINDING));
        }

        @SuppressWarnings("unchecked")
        private static List<GuardrailMetrics.RecentDecision> recentOf(GuardrailMetrics m) {
            Map<String, Object> snap = m.snapshot();
            return (List<GuardrailMetrics.RecentDecision>) snap.get("recent");
        }

        @Test
        @DisplayName("an empty evidence list leaves the row with nothing quoted")
        void noEvidenceWhenNotCaptured() {
            GuardrailMetrics m = new GuardrailMetrics();
            m.record(GuardrailStage.INPUT, blocked(), true, List.of(), 1_000_000);

            GuardrailMetrics.RecentDecision row = recentOf(m).get(0);
            assertTrue(row.evidence().isEmpty(),
                    "with capture off the window must hold no matched text at all");
        }

        @Test
        @DisplayName("the row names the RULE, not just the category")
        void rowCarriesRules() {
            // The manual has promised a rule name in this panel since it was written, while the
            // row carried only categories - so the screen said "secret" where the documentation
            // said secret.aws-access-key.
            GuardrailMetrics m = new GuardrailMetrics();
            m.record(GuardrailStage.INPUT, blocked(), true, List.of(), 1_000_000);

            GuardrailMetrics.RecentDecision row = recentOf(m).get(0);
            assertEquals(List.of("secret.aws-access-key"), row.rules());
            assertEquals(List.of("secret"), row.categories());
        }

        @Test
        @DisplayName("captured evidence reaches the row when it is passed")
        void evidenceReachesTheRow() {
            GuardrailMetrics m = new GuardrailMetrics();
            m.record(GuardrailStage.INPUT, blocked(), true,
                    List.of(new GuardrailEvidence("secret.aws-access-key", "secret", AWS_KEY, false)),
                    1_000_000);

            GuardrailMetrics.RecentDecision row = recentOf(m).get(0);
            assertEquals(1, row.evidence().size());
            assertEquals(AWS_KEY, row.evidence().get(0).match());
        }

        @Test
        @DisplayName("an ALLOW is not kept at all, so nothing it carried is retained")
        void allowIsNotKept() {
            GuardrailMetrics m = new GuardrailMetrics();
            m.record(GuardrailStage.INPUT, GuardrailDecision.allow("clean text"), true,
                    List.of(new GuardrailEvidence("secret.test", "secret", AWS_KEY, false)),
                    1_000_000);

            assertTrue(recentOf(m).isEmpty(),
                    "an ALLOW carries no row, so evidence handed to it must not survive either");
        }

        @Test
        @DisplayName("a null evidence list is treated as none rather than thrown")
        void nullEvidence() {
            GuardrailMetrics m = new GuardrailMetrics();
            m.record(GuardrailStage.INPUT, blocked(), true, null, 1_000_000);
            assertTrue(recentOf(m).get(0).evidence().isEmpty());
        }
    }
}
