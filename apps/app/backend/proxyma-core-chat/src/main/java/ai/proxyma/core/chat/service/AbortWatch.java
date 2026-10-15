package ai.proxyma.core.chat.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Turns a "has Stop been pressed?" predicate into a Mono that fires shortly after it becomes true.
 *
 * Every abort-aware LLM stream in this module already carried {@code takeWhile(chunk -> !abort)},
 * which cancels the provider call - but only when the NEXT chunk arrives. A provider that has gone
 * quiet mid-generation (a long tool-call argument being assembled, a slow first token, a stalled
 * connection) therefore ignored Stop until it spoke again, or until the surrounding block() timeout
 * tripped minutes later. Pairing that predicate with {@code takeUntilOther(AbortWatch.signal(...))}
 * cancels the upstream subscription on a timer of its own instead, so Stop takes effect within
 * {@link #POLL_INTERVAL} regardless of whether anything is still being emitted.
 *
 * A poll rather than a listener because the abort flag is a plain AtomicBoolean shared across
 * threads (ChatService.abortFlags / SearchContext) with no completion hook to hang a callback on,
 * and adding one would mean every producer of that flag has to remember to fire it.
 */
final class AbortWatch {

    // Fast enough that Stop reads as instant, slow enough that an idle stream costs ~7 wakeups a
    // second on the parallel scheduler and nothing else.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);

    private AbortWatch() {}

    /**
     * Completes with a value once {@code aborted} first returns true, and never otherwise. Intended
     * solely as the argument to {@code takeUntilOther} - the emitted value is meaningless, only the
     * timing matters.
     */
    static Mono<Long> signal(BooleanSupplier aborted) {
        return Flux.interval(POLL_INTERVAL, POLL_INTERVAL)
                .filter(tick -> aborted.getAsBoolean())
                .next();
    }
}
