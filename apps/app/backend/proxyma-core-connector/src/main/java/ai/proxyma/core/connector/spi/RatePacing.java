package ai.proxyma.core.connector.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Slowing down BEFORE a service says 429. Many services state their budget on every answer, a 200
 * included - how many requests are left in this window and when it resets. A {@link RateLimitHint}
 * only acts once the budget is spent and a request has been refused; a pacing reads the budget as it
 * runs down and holds the next request back, so a pass spreads what is left over the time left
 * instead of spending it at once and then sitting out a penalty (Freshdesk's, ClickUp's, Zendesk's
 * are per minute, and an account's integrations share them).
 *
 * <p>A connector passes one to its client ({@code ConnectorHttp.client(...).pacing(...)}); ApiClient
 * asks it after every answer and waits before the next request - through the framework's sleeper, so
 * a test sees the wait - never longer than the longest wait a rate limit is believed for.
 */
@FunctionalInterface
public interface RatePacing {

    /** How long the next request should wait, after {@code response} - empty to send it at once. */
    Optional<Duration> holdNext(ApiResponse response, Instant now);

    /** This pacing, else {@code other} - for a service that states its budget in one of two ways. */
    default RatePacing or(RatePacing other) {
        return (response, now) -> {
            Optional<Duration> mine = holdNext(response, now);
            return mine.isPresent() ? mine : other.holdNext(response, now);
        };
    }

    /** How a service states when its window resets. */
    enum Reset {
        /** A Unix time in seconds (ClickUp's {@code X-RateLimit-Reset}). */
        EPOCH_SECONDS,
        /** Seconds from now (the IETF {@code RateLimit-Reset}, Zendesk's {@code ratelimit-reset}). */
        DELTA_SECONDS,
        /** Not stated: the window is the fixed one given (Freshdesk's minute). */
        NONE
    }

    /**
     * The budget headers most services send: once {@code remaining} is at or below {@code low}, the
     * time until the window resets is shared out over what is left - so the last requests of a window
     * are spread across it rather than spent at once. Where the service does not say when its window
     * resets ({@link Reset#NONE}), the whole {@code window} is assumed to be left, which is slower than
     * needed and never faster. An answer without the headers holds nothing back.
     *
     * @param remainingHeader the header with the requests left in the window
     * @param resetHeader     the header saying when it resets, or null with {@link Reset#NONE}
     * @param reset           how {@code resetHeader} states it
     * @param window          the window's length, used when the reset is not stated
     * @param low             how few requests left start the pacing
     */
    static RatePacing budget(String remainingHeader, String resetHeader, Reset reset, Duration window, int low) {
        return (response, now) -> {
            Long remaining = number(response.header(remainingHeader));
            if (remaining == null || remaining > low) return Optional.empty();
            Duration left = window;
            Long stated = resetHeader == null ? null : number(response.header(resetHeader));
            if (stated != null && reset == Reset.EPOCH_SECONDS) left = Duration.between(now, Instant.ofEpochSecond(stated));
            else if (stated != null && reset == Reset.DELTA_SECONDS) left = Duration.ofSeconds(stated);
            if (left == null || left.isNegative() || left.isZero()) return Optional.empty();
            return Optional.of(left.dividedBy(Math.max(1, remaining) + 1));
        };
    }

    private static Long number(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            // A budget header with a unit or a list in it (an IETF draft's "100;w=60") is read for
            // its first number; anything else says nothing.
            String first = value.strip().split("[;,\\s]", 2)[0];
            try {
                return Long.parseLong(first);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
