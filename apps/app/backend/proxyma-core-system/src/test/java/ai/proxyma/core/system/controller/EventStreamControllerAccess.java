package ai.proxyma.core.system.controller;

import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.event.EventSinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

/** The stream controller's package-private parts, for the event tests in a sibling package. */
public final class EventStreamControllerAccess {

    private EventStreamControllerAccess() {}

    /** Publishes one update the way the @Async listener does, synchronously. */
    public static void publish(EventSinkService sink, ConnectorService connectors, ConnectorUpdateEvent event) {
        new EventStreamController(sink, connectors, new ObjectMapper()).on(event);
    }

    /** The page's stream, as {@code exchange}'s caller opens it. */
    public static Flux<ServerSentEvent<String>> stream(EventSinkService sink, ConnectorService connectors,
                                                       ServerWebExchange exchange) {
        return new EventStreamController(sink, connectors, new ObjectMapper()).stream(exchange);
    }

    public static ServerSentEvent<String> keepalive() {
        return EventStreamController.KEEPALIVE;
    }
}
