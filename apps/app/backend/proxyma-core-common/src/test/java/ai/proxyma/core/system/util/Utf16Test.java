package ai.proxyma.core.system.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cut at a character count keeps every emoji whole, and a lone half is replaced (2026-11-06: a cut through
 * an emoji in a workspace file made every scheduled run of a team fail with Anthropic's "unexpected end of
 * hex escape").
 */
class Utf16Test {

    private static final String EMOJI = "\uD83D\uDE0A";
    // "Cam on " (with its Vietnamese marks), then an emoji at index 7 and 8.
    private static final String TEXT = "C\u1ea3m \u01a1n " + EMOJI + " b\u1ea1n";

    @Test
    @DisplayName("a cut between the halves of a pair stops one char short; anywhere else it is the plain cut")
    void cut() {
        assertThat(Utf16.cut(TEXT, 8)).isEqualTo("C\u1ea3m \u01a1n ");
        assertThat(Utf16.cut(TEXT, 9)).isEqualTo("C\u1ea3m \u01a1n " + EMOJI);
        assertThat(Utf16.cut(TEXT, 7)).isEqualTo("C\u1ea3m \u01a1n ");
        assertThat(Utf16.cut(TEXT, 3)).isEqualTo("C\u1ea3m");
        assertThat(Utf16.cut(TEXT, 100)).isSameAs(TEXT);
        assertThat(Utf16.cut(TEXT, 0)).isEmpty();
        assertThat(Utf16.cut(null, 5)).isNull();
        for (int max = 0; max <= TEXT.length(); max++) {
            assertThat(Utf16.isWellFormed(Utf16.cut(TEXT, max))).as("cut at %d", max).isTrue();
            assertThat(Utf16.cut(TEXT, max).length()).isLessThanOrEqualTo(max);
        }
    }

    @Test
    @DisplayName("cutIndex is what StringBuilder.append(text, 0, n) needs")
    void cutIndex() {
        assertThat(Utf16.cutIndex(TEXT, 8)).isEqualTo(7);
        assertThat(Utf16.cutIndex(TEXT, 9)).isEqualTo(9);
        assertThat(Utf16.cutIndex(TEXT, 100)).isEqualTo(TEXT.length());
        assertThat(Utf16.cutIndex(new StringBuilder(EMOJI), 1)).isZero();
    }

    @Test
    @DisplayName("a tail never starts on the second half of a pair")
    void tail() {
        String t = EMOJI + "ab";
        assertThat(Utf16.tail(t, 3)).isEqualTo("ab");
        assertThat(Utf16.tail(t, 4)).isSameAs(t);
        assertThat(Utf16.tail(t, 2)).isEqualTo("ab");
        assertThat(Utf16.tail(null, 2)).isNull();
    }

    @Test
    @DisplayName("an unpaired surrogate becomes U+FFFD and a pair is left alone")
    void wellFormed() {
        assertThat(Utf16.wellFormed(TEXT)).isSameAs(TEXT);
        assertThat(Utf16.isWellFormed(TEXT)).isTrue();
        String high = TEXT.substring(0, 8);
        assertThat(Utf16.isWellFormed(high)).isFalse();
        assertThat(Utf16.wellFormed(high)).isEqualTo("C\u1ea3m \u01a1n \uFFFD");
        String low = TEXT.substring(8);
        assertThat(Utf16.wellFormed(low)).isEqualTo("\uFFFD b\u1ea1n");
        assertThat(Utf16.wellFormed("\uDE0A\uD83D")).isEqualTo("\uFFFD\uFFFD");
        assertThat(Utf16.wellFormed(EMOJI + "\uD83D")).isEqualTo(EMOJI + "\uFFFD");
        assertThat(Utf16.isWellFormed(null)).isTrue();
    }
}
