package ai.proxyma.core.guardrail;

import ai.proxyma.core.guardrail.rule.GuardrailRule;
import ai.proxyma.core.guardrail.rule.GuardrailRules;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A match is never released because a flush boundary fell inside it - at ANY position, in deltas of
 * one, two or three characters.
 *
 * <p>The credential assignment redacts its VALUE only, and the scrubber pulled its cut back only to
 * the start of the redacted part. A boundary inside {@code password=} or inside the value released
 * the key and held the value back alone; on the next flush the pattern no longer matched a bare
 * value, and it went out in clear - for 24 of 27 cut positions of one sample. A single-chunk test
 * could not see it. One-character deltas walk the cut through every position of the sample.
 */
class StreamingScrubberSplitTest {

    private static final String FILLER = "The quick brown fox jumps over the lazy dog. ";

    private static List<GuardrailRule> outputRules() {
        GuardrailSettings s = new GuardrailSettings();
        s.setDetectSecrets(true);
        s.setDetectPii(true);
        s.setDenyTerms("acme corp");
        return GuardrailRules.enabledFor(s, GuardrailStage.OUTPUT);
    }

    private static String streamed(String text, int delta, int firstChunk) {
        StreamingScrubber scrubber = new StreamingScrubber(outputRules());
        StringBuilder out = new StringBuilder();
        int i = Math.min(firstChunk, text.length());
        out.append(scrubber.accept(text.substring(0, i)));
        while (i < text.length()) {
            int next = Math.min(text.length(), i + delta);
            out.append(scrubber.accept(text.substring(i, next)));
            i = next;
        }
        out.append(scrubber.drain());
        return out.toString();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "assignment            | Your config: password=hunter2hunter2X9abcdef and more | hunter2hunter2X9abcdef",
            "quoted assignment     | api_key: 'Zx9QwErTy7UiOp3AsDfGh' was set               | Zx9QwErTy7UiOp3AsDfGh",
            "client secret         | client_secret = q8W3e5R7t9Y1u3I5o7P9a2S4 ok          | q8W3e5R7t9Y1u3I5o7P9a2S4",
            "aws key               | the key AKIAIOSFODNN7EXAMPLE is old                  | AKIAIOSFODNN7EXAMPLE",
            "openai key            | use sk-proj-abcdefghijklmnopqrstuv12345 here         | sk-proj-abcdefghijklmnopqrstuv12345",
            "github token          | token ghp_abcdefghijklmnopqrstuvwxyz0123456789 end    | ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "jwt                   | bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U done | eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0",
            "email                 | write to alice.smith@corp.example today              | alice.smith@corp.example",
            "card                  | card 4111 1111 1111 1111 on file                     | 4111 1111 1111 1111",
            "deny over a value     | token=Q7w9E2r4T6y8U1i3vB7.acme corp done            | Q7w9E2r4T6y8U1i3vB7",
    })
    @DisplayName("every split position and delta size keeps the match out of what is emitted")
    void everySplitIsRedacted(String name, String sample, String secret) {
        String text = FILLER.repeat(2) + sample.trim() + " " + FILLER.repeat(14);
        for (int delta = 1; delta <= 3; delta++) {
            for (int first = 0; first < 3; first++) {
                String out = streamed(text, delta, first);
                assertThat(out).as("%s, delta %d, first chunk %d", name, delta, first).doesNotContain(secret.trim());
                assertThat(out).contains("[redacted: ");
            }
        }
    }
}
