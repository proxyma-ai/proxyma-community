package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.usage.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each conversation's cached prompt expires, and why a call did not read it (the maintainer's
 * proposal, 2026-10-22: start a timer with the lifetime asked for when a response says it wrote the
 * cache, restart it on every read - Anthropic refreshes an entry each time it is read - and read
 * every later response against it).
 *
 * <p>One LINE per conversation, sub-agent run or condensation, on one provider and model: the
 * prompt it last sent (as a {@link PromptFingerprint}), when, and how long what it wrote lives.
 * Each call is then one of:
 * <ul>
 *   <li>{@link Outcome#HIT} - it read the cache;</li>
 *   <li>{@link Outcome#FIRST} - it wrote one and the line had none to read;</li>
 *   <li>{@link Outcome#EXPIRED} - it wrote again after the lifetime ran out;</li>
 *   <li>{@code CHANGED_*} - it wrote again because its prompt no longer starts with the one before
 *       (the tools, the system prompt or the history changed) - the miss nothing but a code change
 *       prevents, and this project has paid for twice;</li>
 *   <li>{@link Outcome#NOT_KEPT} - the same prompt, within its lifetime, and still nothing was read:
 *       the provider did not keep it;</li>
 *   <li>{@link Outcome#NONE} - no cache activity at all (caching off, or a prompt under the
 *       provider's minimum).</li>
 * </ul>
 *
 * <p>THE ONE-HOUR CHECK. Where a response does not say which lifetime it wrote, a write is priced at
 * the lifetime the request asked for, and a gateway has been seen keeping a one-hour request for
 * five minutes (2026-10-08). So when the next call on the same prompt comes 5 minutes to an hour
 * after such a write, a read proves the hour was kept and a miss proves it was not; the verdict is
 * kept per provider, and {@link #longWritesKept} then stops one-hour pricing where it was refused.
 *
 * <p>In memory: a restart starts every line afresh, so the first call after one reads as FIRST.
 */
public final class PromptCacheClock {

    public enum Outcome { NONE, FIRST, HIT, EXPIRED, CHANGED_TOOLS, CHANGED_SYSTEM, CHANGED_MESSAGES, NOT_KEPT }

    /** What one call did with the cache, and until when what it left is warm (null for NONE). */
    public record Observation(Outcome outcome, Instant warmUntil) {
        public static final Observation NONE = new Observation(Outcome.NONE, null);
    }

    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    static final Duration ONE_HOUR = Duration.ofHours(1);
    /** Clocks differ and a request takes time to arrive: a call this close to a boundary is not judged by it. */
    static final Duration SLACK = Duration.ofSeconds(15);
    private static final int MAX_LINES = 20_000;

    private record Line(String provider, String model, PromptFingerprint prompt, Instant lastUse,
                        Duration lifetime, boolean lifetimeStated, boolean longAsked) {
        Instant expiresAt() {
            return lastUse.plus(lifetime);
        }
    }

    private final Map<String, Line> lines = new ConcurrentHashMap<>();
    private final Map<String, Boolean> longKept = new ConcurrentHashMap<>();

    /**
     * Reads one finished call against its line and moves the line on.
     *
     * @param line      the conversation (or run) the call belongs to
     * @param start     when the request was sent - an entry's lifetime counts from there
     * @param usage     what the response said it read and wrote
     * @param longAsked whether the request asked for one-hour writes
     */
    public Observation observe(String line, String provider, String model, PromptFingerprint prompt,
                               Instant start, TokenUsage usage, boolean longAsked) {
        if (line == null || usage == null || start == null) return Observation.NONE;
        boolean read = usage.cacheReadTokens() > 0;
        boolean wrote = usage.cacheWriteTokens() > 0;
        if (!read && !wrote) return Observation.NONE;

        String key = line + "|" + provider + "|" + model;
        Line before = lines.get(key);
        if (before != null && start.isBefore(before.lastUse())) {
            // Reported after a call that started later - a failed attempt whose end is signalled
            // after its retry ran. It cannot be judged against what came after it, and the line
            // stays on the newer call.
            Duration lifetime = wrote && longAsked && longWritesKept(provider) ? ONE_HOUR : FIVE_MINUTES;
            return new Observation(read ? Outcome.HIT : Outcome.FIRST, start.plus(lifetime));
        }
        Duration sinceLastUse = before == null ? null : Duration.between(before.lastUse(), start);
        boolean sameStart = before != null && before.prompt() != null && before.prompt().isPrefixOf(prompt);

        Outcome outcome;
        if (read) {
            outcome = Outcome.HIT;
            // A read 5 minutes to an hour after an unstated one-hour write proves the hour was kept.
            if (sameStart && checksTheHour(before, sinceLastUse)) longKept.put(provider, true);
        } else if (before == null) {
            outcome = Outcome.FIRST;
        } else if (!sameStart) {
            outcome = changed(before.prompt(), prompt);
        } else if (start.isAfter(before.expiresAt().plus(SLACK))) {
            outcome = Outcome.EXPIRED;
        } else {
            outcome = Outcome.NOT_KEPT;
            if (checksTheHour(before, sinceLastUse)) longKept.put(provider, false);
        }

        Duration lifetime;
        boolean stated;
        if (wrote) {
            stated = usage.cacheLifetimeStated();
            lifetime = stated
                    ? (usage.longCacheWriteTokens() > 0 ? ONE_HOUR : FIVE_MINUTES)
                    : (longAsked && longWritesKept(provider) ? ONE_HOUR : FIVE_MINUTES);
        } else {
            // A read refreshes what was read, for the lifetime it was written with.
            stated = before != null && before.lifetimeStated();
            lifetime = before != null ? before.lifetime() : FIVE_MINUTES;
        }
        boolean asked = wrote ? longAsked : before != null && before.longAsked();
        if (lines.size() > MAX_LINES) prune(start);
        Line after = new Line(provider, model, prompt, start, lifetime, stated, asked);
        lines.put(key, after);
        return new Observation(outcome, after.expiresAt());
    }

    /** A line's cache as it stands: warm until when, and for how long each write or read keeps it. */
    public record State(Instant warmUntil, Duration lifetime) {}

    /**
     * The cache of {@code line} if it is still warm at {@code now} - on whichever provider and model
     * the line used last. Empty once it expired, or where the line never cached.
     */
    public java.util.Optional<State> state(String line, Instant now) {
        if (line == null || now == null) return java.util.Optional.empty();
        String prefix = line + "|";
        Line latest = null;
        for (Map.Entry<String, Line> e : lines.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            Line l = e.getValue();
            if (latest == null || l.lastUse().isAfter(latest.lastUse())) latest = l;
        }
        if (latest == null || !latest.expiresAt().isAfter(now)) return java.util.Optional.empty();
        return java.util.Optional.of(new State(latest.expiresAt(), latest.lifetime()));
    }

    /** False once a provider was seen keeping a one-hour write for less than an hour. */
    public boolean longWritesKept(String provider) {
        return provider == null || longKept.getOrDefault(provider, true);
    }

    // The previous call asked for an hour, the response did not say what it wrote, and this call
    // comes clearly after five minutes and clearly within the hour.
    private static boolean checksTheHour(Line before, Duration since) {
        return before.longAsked() && !before.lifetimeStated() && since != null
                && since.compareTo(FIVE_MINUTES.plus(SLACK)) > 0
                && since.compareTo(ONE_HOUR.minus(SLACK)) < 0;
    }

    private static Outcome changed(PromptFingerprint before, PromptFingerprint now) {
        if (before == null || now == null) return Outcome.CHANGED_MESSAGES;
        return switch (before.firstDifference(now)) {
            case TOOLS -> Outcome.CHANGED_TOOLS;
            case SYSTEM -> Outcome.CHANGED_SYSTEM;
            default -> Outcome.CHANGED_MESSAGES;
        };
    }

    // Lines nothing will read again: an hour past their expiry.
    private void prune(Instant now) {
        lines.entrySet().removeIf(e -> e.getValue().expiresAt().plus(ONE_HOUR).isBefore(now));
    }

    /** For a test: forget every line and verdict. */
    void reset() {
        lines.clear();
        longKept.clear();
    }

    /** The lines currently held - for a test. */
    int size() {
        return lines.size();
    }
}
