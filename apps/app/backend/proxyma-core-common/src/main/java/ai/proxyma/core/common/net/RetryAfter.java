package ai.proxyma.core.common.net;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * How long a server asked to be left alone: a {@code Retry-After} header, as delta-seconds or an
 * HTTP date, or the {@code retry-after-ms} some OpenAI-compatible services send instead.
 *
 * <p>One parser for every retry layer, so they cannot disagree about what a header means. Each
 * caller still decides how long it is willing to believe - see {@link #capped}.
 */
public final class RetryAfter {

    private RetryAfter() {}

    /**
     * The wait the headers ask for, or empty where neither is present or readable. {@code ms} wins
     * over {@code retryAfter} when both parse; a date in the past is a wait of zero.
     */
    public static Optional<Duration> parse(String ms, String retryAfter, Instant now) {
        if (ms != null && !ms.isBlank()) {
            try {
                return Optional.of(Duration.ofMillis(Math.max(0, (long) Double.parseDouble(ms.trim()))));
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        if (retryAfter == null || retryAfter.isBlank()) return Optional.empty();
        String value = retryAfter.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value))));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO);
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }

    /** {@link #parse} with only a {@code Retry-After} value. */
    public static Optional<Duration> parse(String retryAfter, Instant now) {
        return parse(null, retryAfter, now);
    }

    /** {@code wait}, but never more than {@code max} - a server's "come back in an hour" is not believed. */
    public static Duration capped(Duration wait, Duration max) {
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
