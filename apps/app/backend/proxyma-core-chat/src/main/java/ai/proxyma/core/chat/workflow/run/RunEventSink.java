package ai.proxyma.core.chat.workflow.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One live event stream per run.
 *
 * <p>Modelled on {@code ChatEventSink}, with one part deliberately left out. That class carries a
 * per-conversation reordering buffer, because chat events are published from parallel tool threads
 * and an {@code @Async} listener, so they can reach it out of order. A run does not have that
 * problem: the interpreter is a single thread walking a graph, and it emits in the order things
 * happened. Copying the buffer across would have been machinery guarding against something that
 * cannot occur here.
 *
 * <p>What IS kept is the reaper. A sink whose client closed the tab is a subscription nobody reads,
 * and a run that ends leaves one behind; without the sweep they accumulate for the life of the
 * process.
 *
 * <p>A dropped event is not a lost event: every one is written to {@code workflow_run_events}
 * first, so a client that missed something reconnects with {@code ?after=} and gets it from the
 * table. That is why {@link #emit} may fail quietly - the stream is an optimisation over polling,
 * not the record.
 */
@Service
public class RunEventSink {

    private static final Logger log = LoggerFactory.getLogger(RunEventSink.class);

    /** Comfortably past the longest an Approval node may legitimately sit unanswered. */
    private static final Duration REAP_IDLE = Duration.ofHours(2);

    private final ConcurrentHashMap<UUID, Sinks.Many<Map<String, Object>>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Instant> lastActivity = new ConcurrentHashMap<>();

    /** Created when a run starts, so a client subscribing a moment later still finds one. */
    public void open(UUID runId) {
        sinks.put(runId, Sinks.many().multicast().onBackpressureBuffer(256, false));
        lastActivity.put(runId, Instant.now());
    }

    public Flux<Map<String, Object>> subscribe(UUID runId) {
        lastActivity.put(runId, Instant.now());
        return sinks.computeIfAbsent(runId,
                        k -> Sinks.many().multicast().onBackpressureBuffer(256, false))
                .asFlux();
    }

    public void emit(UUID runId, Map<String, Object> event) {
        lastActivity.put(runId, Instant.now());
        Sinks.Many<Map<String, Object>> sink = sinks.get(runId);
        if (sink == null) return;
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            // Not escalated: the event is already in the table by the time this is called, and
            // failing a RUN because a browser stopped reading would be the tail wagging the dog.
            log.debug("[WorkflowRun] {} could not emit ({})", runId, result);
        }
    }

    /** Completes the stream so a subscriber's SSE connection closes cleanly rather than hanging
     *  open on a run that will never send anything again. */
    public void close(UUID runId) {
        Sinks.Many<Map<String, Object>> sink = sinks.remove(runId);
        lastActivity.remove(runId);
        if (sink != null) sink.tryEmitComplete();
    }

    /** Swept on a schedule rather than only on close, because a run whose process-side work died
     *  without calling close would otherwise leak its sink. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void reapIdle() {
        Instant cutoff = Instant.now().minus(REAP_IDLE);
        lastActivity.entrySet().removeIf(entry -> {
            if (entry.getValue().isAfter(cutoff)) return false;
            Sinks.Many<Map<String, Object>> sink = sinks.remove(entry.getKey());
            if (sink != null) sink.tryEmitComplete();
            log.debug("[WorkflowRun] reaped idle sink for {}", entry.getKey());
            return true;
        });
    }
}
