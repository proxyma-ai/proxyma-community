package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.event.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ChatEventSink {

    private static final Logger log = LoggerFactory.getLogger(ChatEventSink.class);

    // Idle-and-unsubscribed threshold for the reaper below - comfortably past ChatService's own
    // 30-minute write-tool approval wait, the longest a conversation can legitimately sit with no
    // new events and no connected subscriber.
    private static final Duration REAP_IDLE_THRESHOLD = Duration.ofMinutes(45);

    private final ConcurrentHashMap<String, Sinks.Many<ChatEvent>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderingState> orderingStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();
    // The first sequence number of each conversation's latest turn - see turnStartSequence().
    private final ConcurrentHashMap<String, Long> turnStarts = new ConcurrentHashMap<>();

    // Per-conversation drain state: events can arrive at onChatEvent() out of publish order
    // (parallel tool-call threads, @Async listener dispatch on a new thread per invocation), so
    // each one is held in `pending` until every lower sequence number has already been emitted.
    // Every sequence number handed out by nextSequence() is guaranteed to eventually reach
    // onChatEvent() (it's baked into the ChatEvent at construction, in the same expression as the
    // publishEvent() call that hands it to Spring), so this can never stall waiting on a number
    // that was never issued.
    private static final class OrderingState {
        final AtomicLong nextExpected = new AtomicLong(0);
        final ConcurrentSkipListMap<Long, ChatEvent> pending = new ConcurrentSkipListMap<>();
    }

    // Called synchronously (on the ChatService caller's own thread, before publishEvent()) so the
    // returned number reflects true chronological call order regardless of which thread later
    // races to process it.
    public long nextSequence(String conversationId) {
        return sequenceCounters.computeIfAbsent(conversationId, k -> new AtomicLong(0)).getAndIncrement();
    }

    // Called before a turn's or a manual condensation's work is kicked off (ChatController.dispatch
    // and condenseNow; a report's continuation calls startTurn itself). Always installs a *fresh* sink (unlike subscribe()'s computeIfAbsent), so that each
    // turn's replay buffer holds that turn and not the whole conversation's history.
    // sequenceCounters/orderingStates deliberately keep counting across turns (computeIfAbsent,
    // never reset) so they stay self-consistent with whatever nextSequence() hands out next -
    // only the delivery sink itself needs to be new.
    //
    // replay().all() (unbounded) rather than a fixed limit: a long, many-iteration turn (several
    // tool calls per iteration, each streamed response broken into many small text chunks) can
    // easily emit several hundred events, and a bounded replay buffer silently drops the oldest
    // ones once full - invisible to a subscriber connected from the start, but any reconnect
    // partway through (tab backgrounded, a corporate proxy's idle-connection timeout - both real
    // over a turn running tens of minutes) would then permanently miss whatever had already
    // scrolled out of the buffer, with no error anywhere - just some tool calls never appearing.
    // Unbounded is safe here because each sink is scoped to one turn (replaced by the next
    // ensureSink() call, or reaped after REAP_IDLE_THRESHOLD) rather than living for the
    // conversation's lifetime.
    public void ensureSink(String conversationId) {
        startTurn(conversationId, false);
    }

    /**
     * Marks the start of a turn, and gives it a fresh sink unless {@code keepSink}.
     *
     * <p>KEPT WHILE SOMETHING ELSE IS STILL PUBLISHING INTO THE OLD ONE - a background sub-agent,
     * since 2026-10-02. A page following that sub-agent is subscribed to the sink it is publishing
     * into; replace the sink and the page hears nothing more, of the sub-agent or of the turn that
     * was just started. The replay buffer then spans more than one turn, which a page handles by
     * skipping the events it has already been given (they are numbered) and which ends at the next
     * turn that starts with nothing in the background.
     */
    public void startTurn(String conversationId, boolean keepSink) {
        if (keepSink) {
            sinks.computeIfAbsent(conversationId, k -> Sinks.many().replay().all());
        } else {
            sinks.put(conversationId, Sinks.many().replay().all());
        }
        AtomicLong counter = sequenceCounters.computeIfAbsent(conversationId, k -> new AtomicLong(0));
        turnStarts.put(conversationId, counter.get());
        orderingStates.computeIfAbsent(conversationId, k -> new OrderingState());
        // A TURN THAT HAS NOT SPOKEN YET IS NOT AN IDLE CONVERSATION. reap() reads lastActivity
        // and treated a missing entry as "idle since forever", so a sink installed here for a turn
        // whose first event had not been published, with nobody subscribed yet, was eligible for
        // reaping the moment the reaper ran - taking the sequence counter and ordering state with
        // it. Rare, since the window is milliseconds against a five-minute tick, and silent, since
        // the events then hit the no-sink branch in onChatEvent. Stamping it here closes it.
        lastActivity.put(conversationId, Instant.now());
    }

    // Deliberately does NOT tear anything down when the returned Flux's subscriber disconnects
    // (tab backgrounded, laptop sleep, transient network blip, or the frontend's own
    // reconnect-with-backoff) - a write-tool approval can leave this idle for up to 30 minutes
    // (ChatService.APPROVAL_TIMEOUT_MINUTES), and a disconnect used to delete the sink/counter/
    // ordering-state outright, so a reconnect got a brand-new empty OrderingState that could
    // never reach an already-allocated sequence number from before the reconnect - stranding that
    // event (often the terminal DONE/TOOL_RESULT) forever and leaving the frontend's "streaming"
    // state stuck. The sink/counter/ordering-state now live for the conversation's
    // active-or-recently-active window (see the reap() scheduler below), not for one subscriber
    // connection, so a reconnect resubscribes to (and replays from) the same live state instead.
    public Flux<ChatEvent> subscribe(String conversationId) {
        Sinks.Many<ChatEvent> sink = sinks.computeIfAbsent(conversationId,
                k -> Sinks.many().replay().all());
        sequenceCounters.computeIfAbsent(conversationId, k -> new AtomicLong(0));
        orderingStates.computeIfAbsent(conversationId, k -> new OrderingState());
        return sink.asFlux();
    }

    /** The number the conversation's next event will carry; 0 when it has published none. */
    public long currentSequence(String conversationId) {
        AtomicLong counter = sequenceCounters.get(conversationId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * The number of the first event of the conversation's latest turn. Everything a running turn
     * has published is at or above it, which is how a transcript that stops where the turn began
     * says exactly which events it does not contain.
     */
    public long turnStartSequence(String conversationId) {
        Long start = turnStarts.get(conversationId);
        return start == null ? 0 : start;
    }

    // Bounds memory for conversations that never reach a terminal event (e.g. abandoned before a
    // turn starts) now that a disconnect no longer cleans up on its own - runs far less often
    // than, and only ever reaps entries well past, the 30-minute approval wait, so no live
    // approval or in-flight turn is ever at risk of being reaped out from under it.
    @Scheduled(fixedRate = 5 * 60 * 1000)
    void reap() {
        Instant cutoff = Instant.now().minus(REAP_IDLE_THRESHOLD);
        for (Iterator<Map.Entry<String, Sinks.Many<ChatEvent>>> it = sinks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Sinks.Many<ChatEvent>> entry = it.next();
            String conversationId = entry.getKey();
            Sinks.Many<ChatEvent> sink = entry.getValue();
            Instant activity = lastActivity.get(conversationId);
            boolean idle = activity == null || activity.isBefore(cutoff);
            if (sink.currentSubscriberCount() == 0 && idle) {
                sink.tryEmitComplete();
                it.remove();
                sequenceCounters.remove(conversationId);
                orderingStates.remove(conversationId);
                lastActivity.remove(conversationId);
                turnStarts.remove(conversationId);
            }
        }
    }

    // ON ITS OWN EXECUTOR (AsyncConfig), which never rejects. It shared the default one with
    // title generation and database writes, and an event rejected there left nextExpected waiting
    // for a number that would never arrive - every later event of the turn buffered behind it.
    @Async("chatEventExecutor")
    @EventListener
    public void onChatEvent(ChatEvent event) {
        Sinks.Many<ChatEvent> sink = sinks.get(event.conversationId());
        if (sink == null) {
            // Said nothing at all until 2026-09-23, and a dropped event is exactly the kind of
            // thing that has to say something: every symptom of one is an absence. It also leaves
            // nextExpected where it is, so every later event of this turn is buffered behind a
            // number that will never arrive - which is worth knowing from one line rather than
            // from a stream that stops.
            log.warn("[ChatEventSink] dropped an event with no sink: conversationId={} seq={} type={}",
                    event.conversationId(), event.sequence(), event.streamType());
            return;
        }
        OrderingState state = orderingStates.computeIfAbsent(event.conversationId(), k -> new OrderingState());
        // Serializes only the tiny "is this the next number, drain the buffer" decision per
        // conversation - tool execution and LLM streaming stay fully concurrent, only the final
        // hand-off to the sink is ordered.
        synchronized (state) {
            if (event.sequence() != state.nextExpected.get()) {
                state.pending.put(event.sequence(), event);
                return;
            }
            emit(sink, event);
            state.nextExpected.incrementAndGet();
            ChatEvent buffered;
            while ((buffered = state.pending.remove(state.nextExpected.get())) != null) {
                emit(sink, buffered);
                state.nextExpected.incrementAndGet();
            }
        }
    }

    private void emit(Sinks.Many<ChatEvent> sink, ChatEvent event) {
        // A TERMINAL EVENT NO LONGER COMPLETES THE SINK, since 2026-09-23.
        //
        // It used to: DONE, ERROR and SESSION_EXPIRED each called emitComplete. That is
        // what a terminal event is for, except that in this product a turn's DONE is NOT the last
        // event of the turn. ConversationUsageRecorder and ConversationTitleService
        // both publish after it, and both were rejected with FAIL_TERMINATED - silently, since
        // the failure handler only retried the non-serialized case. Measured on a booted instance: 18
        // events lost across 48 turns, which is the final cost figure and the generated title not
        // appearing until a reload.
        //
        // Worse, SESSION_EXPIRED is published by ChatController.chatEvents when a
        // subscriber is refused the conversation - and it went into the sink of the LIVE TURN, ending
        // it for the legitimate subscriber as well. A second tab without the guest cookie could
        // therefore blank a running answer, which is the opposite of what an authorization notice is
        // supposed to do.
        //
        // Nothing is lost by not completing, because no client ever saw the completion: the
        // SSE response merges the sink with a keepalive Flux.interval that never completes, so
        // the HTTP stream stays open either way and clients key on the DONE event itself. Memory is
        // bounded as it already was - ensureSink replaces the sink per turn, reap
        // removes idle ones.
        //
        // ChatController.condenseNow already works around the old behavior by calling
        // ensureSink first; that is now belt and braces rather than the only thing making a
        // manual condense visible.
        // @Async listener invocations for the same conversationId can each run on a different
        // thread of chatEventExecutor, so a fast-streaming response can call tryEmitNext()
        // concurrently from two threads on the same sink. Sinks.Many is not safe for concurrent producers - a losing tryEmitNext()
        // call returns FAIL_NON_SERIALIZED and silently drops the event with no exception. If the
        // dropped event happened to be the terminal DONE event, the client's EventSource never
        // sees it and the UI stays in "loading" state forever (the message is safely in the DB -
        // it only reappears after a reload/refetch). emitNext()'s busyLooping handler retries
        // tryEmitNext() instead of dropping it on that one failure. The onChatEvent() `synchronized`
        // block above additionally ensures only one thread ever calls emit() for a given
        // conversation at a time, so this is now a belt-and-suspenders guard rather than the only
        // protection.
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(2)));
        } else if (result.isFailure()) {
            log.warn("[ChatEventSink] the sink refused an event: conversationId={} seq={} type={} result={}",
                    event.conversationId(), event.sequence(), event.streamType(), result);
        }
        lastActivity.put(event.conversationId(), Instant.now());
    }

}
