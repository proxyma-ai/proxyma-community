package ai.proxyma.core.system.event;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.Set;

// Shared SSE event bus. All event publishers emit here; EventStreamController subscribes and
// filters per-subscriber by Scope so a caller only ever sees events about connectors they own or
// that are marked shared (SEC-D4) - connector content itself is never in these messages, only
// metadata (ids/titles/status), but even that leaked across users previously.
@Service
public class EventSinkService {

    private static final Logger log = LoggerFactory.getLogger(EventSinkService.class);

    // userId == null means "no specific owner" (global/legacy events, or desktop mode where
    // there is no auth at all) - always visible. shared == true means visible to every caller
    // regardless of who owns the underlying connector.
    //
    // audience, when not null, is the WHOLE answer and overrides both: the event reaches exactly
    // the signed-in users it names and nobody else - not an anonymous subscriber, not a shared
    // flag. Added 2026-10-06 for key accounts, whose events go to an account's members (several
    // people, none of them "the owner"). An empty audience reaches nobody, which is the safe
    // reading of "no members".
    public record Scope(String userId, boolean shared, Set<String> audience) {
        public static final Scope GLOBAL = new Scope(null, true);

        public Scope(String userId, boolean shared) {
            this(userId, shared, null);
        }

        public Scope {
            audience = audience == null ? null : Set.copyOf(audience);
        }

        /** Only these signed-in users. */
        public static Scope audience(Collection<String> userIds) {
            return new Scope(null, false, userIds == null ? Set.of() : Set.copyOf(userIds));
        }

        /** Whether a subscriber signed in as {@code subscriberId} (null when anonymous) sees the event. */
        public boolean visibleTo(String subscriberId) {
            if (audience != null) return subscriberId != null && audience.contains(subscriberId);
            if (shared || userId == null) return true;
            return subscriberId != null && subscriberId.equals(userId);
        }
    }

    public record SseMessage(String json, Scope scope) {}

    // MULTICAST, NO REPLAY (platform-15). It was replay().latest(), which handed every new
    // connection the last message ever emitted - an old connector-operation phase, shown as a live
    // operation label. Whoever connects later asks over HTTP for state; this carries changes.
    private final Sinks.Many<SseMessage> sink = Sinks.many().multicast().directBestEffort();

    // Emitters race: every one is an @Async listener on a 16-thread pool, and a sync publishes one
    // event per document from parallel ingest workers. A Reactor sink is serialized, and an emit
    // that collides with another returns FAIL_NON_SERIALIZED and emits NOTHING - which tryEmitNext
    // reported and nobody read, so the Sources page kept stale counts and operation labels
    // (platform-04). So emits take turns. A retry loop was tried first and still lost events under
    // sixteen threads (EventDeliveryTest); a lock loses none, and what it guards is cheap - handing
    // the message to each subscriber's filter.
    private final Object emitLock = new Object();

    public void emit(String json) {
        emit(json, Scope.GLOBAL);
    }

    public void emit(String json, Scope scope) {
        Sinks.EmitResult result;
        synchronized (emitLock) {
            result = sink.tryEmitNext(new SseMessage(json, scope));
        }
        // Nobody connected is not a failure: there is nobody to tell.
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("[Events] an event could not be delivered: {}", result);
        }
    }

    public Flux<SseMessage> getFlux() {
        return sink.asFlux();
    }

    @PreDestroy
    public void close() {
        sink.tryEmitComplete();
    }
}
