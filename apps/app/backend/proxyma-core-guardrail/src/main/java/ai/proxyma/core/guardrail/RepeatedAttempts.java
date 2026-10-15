package ai.proxyma.core.guardrail;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who keeps tripping the input guardrail, and for how long they stop being served.
 *
 * <p>WHY THIS EXISTS SEPARATELY FROM THE RULES. Every other layer judges ONE message on its own
 * merits, and by that standard the fortieth jailbreak attempt in five minutes is exactly as
 * suspicious as the first: it is refused, and the person tries again. Nothing in the product
 * notices the pattern, and the pattern is most of the signal - somebody working through a list of
 * known bypasses looks nothing like somebody who pasted a log file with a token in it, and only a
 * counter can tell them apart.
 *
 * <p>OFF BY DEFAULT, because the failure mode is worse than the one it prevents. A support engineer
 * pasting five error logs that each contain a bearer token is doing their job, and locking their
 * conversation for ten minutes over it is a product that punishes its most legitimate heavy user.
 * A deployment that wants this has decided its own threshold.
 *
 * <p>IN MEMORY, AND IT RESETS ON RESTART - the same trade {@link GuardrailMetrics} makes and for
 * the same reason: persisting would mean a JPA entity, which has to be added to BOTH editions'
 * datasource lists or one of them silently fails to boot. It also means a restart forgives
 * everybody, which is a real limit rather than a rounding error, and is why this is a speed bump
 * against a person trying things by hand and not a defense against anything automated.
 *
 * <p>NO TEXT, EVER. It stores a subject key, timestamps and a count. Not the message, not the
 * category. The one thing a repeat counter must never become is a log of what people tried to say.
 */
@Component
public class RepeatedAttempts {

    /**
     * The most subjects tracked at once.
     *
     * <p>A ceiling rather than an eviction policy, because the map is keyed by user or conversation
     * and a busy deployment has a bounded number of either. When it is reached the oldest entries
     * go; a deployment with more than this many distinct offenders inside one window has a problem
     * that a cooldown was never going to solve.
     */
    private static final int MAX_SUBJECTS = 10_000;

    private record State(Deque<Instant> violations, Instant cooldownUntil) {}

    private final Map<String, State> bySubject = new ConcurrentHashMap<>();

    /** What a caller needs to know: whether to refuse, and until when. */
    public record Status(boolean coolingDown, Instant until, int recentViolations) {

        public static final Status CLEAR = new Status(false, null, 0);

        /** Whole minutes remaining, rounded UP - "0 minutes left" is not a thing to tell anyone. */
        public long minutesRemaining() {
            if (until == null) return 0;
            long seconds = Duration.between(Instant.now(), until).getSeconds();
            return seconds <= 0 ? 0 : (seconds + 59) / 60;
        }
    }

    /**
     * Whether this subject is currently refused, WITHOUT recording anything.
     *
     * <p>Called before the checks run, so a subject in cooldown costs nothing at all - no rules, no
     * retrieval, no classifier call. That is most of the point: the case this exists for is
     * somebody sending many messages quickly, and a cooldown that still paid for a classifier call
     * on each one would make the attack cheaper to mount than to survive.
     */
    public Status status(String subject, int thresholdViolations) {
        if (subject == null || thresholdViolations <= 0) return Status.CLEAR;
        State state = bySubject.get(subject);
        if (state == null) return Status.CLEAR;
        if (state.cooldownUntil() != null && state.cooldownUntil().isAfter(Instant.now())) {
            return new Status(true, state.cooldownUntil(), state.violations().size());
        }
        return new Status(false, null, state.violations().size());
    }

    /**
     * Records one refused message and reports whether it tipped the subject into a cooldown.
     *
     * @param thresholdViolations how many refusals inside the window trigger a cooldown; 0 or less
     *                            disables the whole mechanism and this method does nothing
     */
    public Status record(String subject, int thresholdViolations, Duration window, Duration cooldown) {
        if (subject == null || thresholdViolations <= 0) return Status.CLEAR;

        Instant now = Instant.now();
        State updated = bySubject.compute(subject, (key, existing) -> {
            Deque<Instant> violations = existing == null ? new ArrayDeque<>() : existing.violations();
            violations.addLast(now);
            // The window slides on every write rather than on a timer. A subject nobody is looking
            // at does not need pruning, and one that IS being looked at is being written to.
            while (!violations.isEmpty() && violations.peekFirst().isBefore(now.minus(window))) {
                violations.removeFirst();
            }
            Instant until = existing == null ? null : existing.cooldownUntil();
            if (violations.size() >= thresholdViolations) {
                // Restarts the cooldown from now, and CLEARS the counter. Without the clear, a
                // subject that reached the threshold once would re-trigger on their very next
                // refusal for as long as the window held - a ten-minute cooldown quietly becoming
                // a permanent one, which is not what anybody typed into the box.
                violations.clear();
                until = now.plus(cooldown);
            }
            return new State(violations, until);
        });

        evictIfOversized();

        if (updated.cooldownUntil() != null && updated.cooldownUntil().isAfter(now)) {
            return new Status(true, updated.cooldownUntil(), 0);
        }
        return new Status(false, null, updated.violations().size());
    }

    /** Forgets one subject. Exists so an operator action can lift a cooldown without a restart. */
    public void clear(String subject) {
        if (subject != null) bySubject.remove(subject);
    }

    public void clearAll() {
        bySubject.clear();
    }

    /** How many subjects are in a cooldown right now - the one number worth reporting. */
    public long coolingDownCount() {
        Instant now = Instant.now();
        return bySubject.values().stream()
                .filter(s -> s.cooldownUntil() != null && s.cooldownUntil().isAfter(now))
                .count();
    }

    /**
     * Drops entries that are doing nothing once the map gets large.
     *
     * <p>Expired-and-idle first, and only if that is not enough does it drop live ones. Dropping a
     * live cooldown forgives somebody early, which is the right way to be wrong here: this is a
     * speed bump, and a memory leak in a guardrail is a worse bug than a lenient one.
     */
    private void evictIfOversized() {
        if (bySubject.size() <= MAX_SUBJECTS) return;
        Instant now = Instant.now();
        bySubject.entrySet().removeIf(e ->
                (e.getValue().cooldownUntil() == null || e.getValue().cooldownUntil().isBefore(now))
                        && e.getValue().violations().isEmpty());
        if (bySubject.size() <= MAX_SUBJECTS) return;
        Iterator<String> it = bySubject.keySet().iterator();
        while (it.hasNext() && bySubject.size() > MAX_SUBJECTS) {
            it.next();
            it.remove();
        }
    }
}
