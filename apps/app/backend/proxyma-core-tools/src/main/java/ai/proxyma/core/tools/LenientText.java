package ai.proxyma.core.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading a file the model asked for as text, whatever its bytes are, and matching a pattern the
 * model wrote without letting it run for ever.
 */
final class LenientText {

    private LenientText() {}

    /**
     * Its lines, as UTF-8 with any invalid byte replaced. Files.readAllLines decodes strictly, so
     * one Latin-1 byte in a saved tool result or an attachment failed read and grep outright with
     * "Input length = 1".
     */
    static List<String> readLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    /** Thrown when a match ran past its deadline. */
    static final class TooSlowException extends RuntimeException {
        TooSlowException() {
            super("The pattern took too long to match; simplify it or search for a literal string.");
        }
    }

    /**
     * The text, for a Matcher that must stop at {@code deadlineNanos}. java.util.regex cannot be
     * interrupted, so a catastrophic pattern from the model kept a worker thread busy long after the
     * tool's timeout had abandoned it; reading a character past the deadline ends the match instead.
     */
    static CharSequence withDeadline(CharSequence text, long deadlineNanos) {
        return new CharSequence() {
            private int reads;

            @Override
            public int length() {
                return text.length();
            }

            @Override
            public char charAt(int index) {
                if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadlineNanos) throw new TooSlowException();
                return text.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return withDeadline(text.subSequence(start, end), deadlineNanos);
            }

            @Override
            public String toString() {
                return text.toString();
            }
        };
    }
}
