package ai.proxyma.core.connector.spi;

import ai.proxyma.core.common.net.RetryAfter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A vendor's way of saying "slow down", for the cases a plain 429 with {@code Retry-After} does not
 * cover: GitHub's 403 with {@code x-ratelimit-remaining: 0} and a reset time, Linear's 400 with
 * RATELIMITED, Airtable's penalty window. A connector passes its hint to its {@link ApiClient};
 * ApiClient waits it out (up to its ceiling) and otherwise reports the source as rate limited. The
 * answer a hint is shown carries its body, so a limit named only there (Google's 403
 * {@code rateLimitExceeded}) is a limit, and one named there that waiting cannot cure is
 * {@link #NEXT_PASS}.
 */
@FunctionalInterface
public interface RateLimitHint {

    /**
     * How long to wait before asking again, when {@code response} is a rate-limit answer - empty
     * when it is not one.
     */
    Optional<Duration> waitFor(ApiResponse response, Instant now);

    /**
     * A limit not worth sitting out in this pass - a day's allowance spent, an integration the service
     * has blocked: longer than any wait ApiClient believes, so the request is not asked again and
     * fails as rate limited, and the next pass tries again. A hint returns it rather than empty, so
     * the answer is reported as the limit it is and not as a refusal.
     */
    Duration NEXT_PASS = Duration.ofDays(1);

    /** The standard: 429, or a 503 that says when to come back, honoring {@code Retry-After}. */
    RateLimitHint STANDARD = (response, now) -> {
        int status = response.status();
        if (status != 429 && !(status == 503 && response.header("Retry-After") != null)) return Optional.empty();
        return Optional.of(RetryAfter.parse(response.header("retry-after-ms"), response.header("Retry-After"), now)
                .orElse(Duration.ZERO));
    };

    /** This hint, else {@code other}. */
    default RateLimitHint or(RateLimitHint other) {
        return (response, now) -> {
            Optional<Duration> mine = waitFor(response, now);
            return mine.isPresent() ? mine : other.waitFor(response, now);
        };
    }

    /**
     * The {@code x-ratelimit-remaining} / {@code x-ratelimit-reset} pair GitHub, GitLab and others
     * send: a 403 or 429 with nothing remaining waits until the reset (epoch seconds). Also honors a
     * {@code Retry-After} on a 403, which is how GitHub's secondary limit answers.
     */
    RateLimitHint RESET_HEADERS = (response, now) -> {
        int status = response.status();
        if (status != 403 && status != 429) return Optional.empty();
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) return RetryAfter.parse(retryAfter, now).or(() -> Optional.of(Duration.ZERO));
        String remaining = response.header("x-ratelimit-remaining");
        String reset = response.header("x-ratelimit-reset");
        if (!"0".equals(remaining == null ? null : remaining.trim()) || reset == null) return Optional.empty();
        try {
            long epoch = Long.parseLong(reset.trim());
            Duration wait = Duration.between(now, Instant.ofEpochSecond(epoch));
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait.plusSeconds(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    };
}
