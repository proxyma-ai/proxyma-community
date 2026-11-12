package ai.proxyma.core.system.util;

/**
 * TEXT CUT AT A CHARACTER COUNT NEVER SPLITS AN EMOJI (2026-11-06).
 *
 * <p>A Java {@code char} is a UTF-16 code unit, and every character outside the Basic Multilingual
 * Plane - every emoji - is two of them, a surrogate pair. {@code s.substring(0, max)} that lands between
 * the two leaves a lone surrogate, which no UTF-8 encoder can write: Jackson emits it as a single
 * hex escape with no partner, which Java's parsers accept and Anthropic's refuses with "The request body
 * is not valid JSON: unexpected end of hex escape". A Constellations team on a Vietnamese channel failed
 * every scheduled run that way, its opening message cut through an emoji in a workspace file.
 *
 * <p>So anything bounded on its way to a model is cut here: {@link #cut} keeps a pair whole by stopping one
 * char short, and {@link #wellFormed} replaces a surrogate that has already lost its partner with U+FFFD,
 * as every UTF-8 encoder does.
 */
public final class Utf16 {

    /** U+FFFD, what an unpaired surrogate becomes. */
    public static final char REPLACEMENT = '\uFFFD';

    private Utf16() {}

    /**
     * The index to cut {@code s} at so that at most {@code max} chars are kept and no surrogate pair is
     * split: {@code max}, or one less when {@code max} falls between the two halves of a pair.
     */
    public static int cutIndex(CharSequence s, int max) {
        if (s == null) return 0;
        if (max <= 0) return 0;
        if (max >= s.length()) return s.length();
        return Character.isHighSurrogate(s.charAt(max - 1)) && Character.isLowSurrogate(s.charAt(max)) ? max - 1 : max;
    }

    /** The first {@code max} chars of {@code s}, one fewer when the last would be half a pair; null stays null. */
    public static String cut(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, cutIndex(s, max));
    }

    /**
     * The index to start the last {@code max} chars of {@code s} at, one later when it would start on the
     * second half of a pair.
     */
    public static int tailIndex(CharSequence s, int max) {
        if (s == null) return 0;
        if (max >= s.length()) return 0;
        int from = s.length() - Math.max(0, max);
        return from > 0 && Character.isLowSurrogate(s.charAt(from)) && Character.isHighSurrogate(s.charAt(from - 1))
                ? from + 1 : from;
    }

    /** The last {@code max} chars of {@code s}, one fewer when the first would be half a pair; null stays null. */
    public static String tail(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(tailIndex(s, max));
    }

    /** Whether every surrogate in {@code s} is half of a pair. */
    public static boolean isWellFormed(CharSequence s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isSurrogate(c)) continue;
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
                continue;
            }
            return false;
        }
        return true;
    }

    /** {@code s} with every unpaired surrogate replaced by U+FFFD; the same instance when there is none. */
    public static String wellFormed(String s) {
        if (isWellFormed(s)) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isSurrogate(c)) {
                out.append(c);
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i));
            } else {
                out.append(REPLACEMENT);
            }
        }
        return out.toString();
    }
}
