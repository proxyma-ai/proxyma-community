package ai.proxyma.core.system.controller;

import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.event.EventSinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;

/** The stream controller's package-private parts, for the event tests in a sibling package. */
public final class EventStreamControllerAccess {

    private EventStreamControllerAccess() {}

    /** Publishes one update the way the @Async listener does, synchronously. */
    public static void publish(EventSinkService sink, ConnectorService connectors, ConnectorUpdateEvent event) {
        new EventStreamController(sink, connectors, new ObjectMapper()).on(event);
    }

    public static ServerSentEvent<String> keepalive() {
        return EventStreamController.KEEPALIVE;
    }
}
