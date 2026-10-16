package ai.proxyma.core.system.event;

import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.controller.EventStreamControllerAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The live event bus (platform-04, -13, -15): every event emitted at once arrives, a title with a
 * newline is still JSON, and a late subscriber is not handed an old event.
 */
class EventDeliveryTest {

    @Test
    @DisplayName("events emitted from many threads at once all arrive - a collision used to drop one silently")
    void concurrentEmitsAllArrive() throws Exception {
        EventSinkService sink = new EventSinkService();
        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription = sink.getFlux().subscribe(m -> received.add(m.json()));
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) sink.emit("e-" + id + "-" + i);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        assertThat(received).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("a subscriber that connects later is not handed the last event ever emitted")
    void noReplay() {
        EventSinkService sink = new EventSinkService();
        sink.emit("{\"type\":\"connector-operation\",\"phase\":\"STARTED\"}");
        List<String> late = new CopyOnWriteArrayList<>();
        Disposable subscription = sink.getFlux().subscribe(m -> late.add(m.json()));

        sink.emit("{\"type\":\"connector-synced\"}");
        subscription.dispose();

        assertThat(late).containsExactly("{\"type\":\"connector-synced\"}");
    }

    @Test
    @DisplayName("a document title with a newline, a tab and quotes is still one JSON event")
    void titlesAreEscaped() throws Exception {
        EventSinkService sink = new EventSinkService();
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.getOwnerScope(any())).thenReturn(Optional.empty());
        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription = sink.getFlux().subscribe(m -> received.add(m.json()));
        UUID connector = UUID.randomUUID();
        ConnectorUpdateEvent event = new ConnectorUpdateEvent(this, connector, UUID.randomUUID(),
                "Standup \"notes\"\n\tday 2 \\ final", "EMBEDDING", 1, 4);

        EventStreamControllerAccess.publish(sink, connectors, event);
        subscription.dispose();

        JsonNode body = new ObjectMapper().readTree(received.get(0));
        assertThat(body.path("type").asText()).isEqualTo("connector-update");
        assertThat(body.path("connectorId").asText()).isEqualTo(connector.toString());
        assertThat(body.path("documentTitle").asText()).isEqualTo("Standup \"notes\"\n\tday 2 \\ final");
        assertThat(body.path("totalChunks").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("a stream's first event names whose it is, and the rest are that user's alone")
    void theStreamNamesItsUser() throws Exception {
        EventSinkService sink = new EventSinkService();
        ConnectorService connectors = mock(ConnectorService.class);
        ServerWebExchange alice = mock(ServerWebExchange.class);
        Principal principal = () -> "alice-id";
        doReturn(Mono.just(principal)).when(alice).getPrincipal();

        CompletableFuture<List<ServerSentEvent<String>>> first = EventStreamControllerAccess
                .stream(sink, connectors, alice).take(2).collectList().toFuture();
        sink.emit("{\"type\":\"connector-registered\",\"connectorId\":\"bobs\"}",
                new EventSinkService.Scope("bob-id", false));
        sink.emit("{\"type\":\"connector-registered\",\"connectorId\":\"alices\"}",
                new EventSinkService.Scope("alice-id", false));
        List<ServerSentEvent<String>> events = first.get(5, TimeUnit.SECONDS);

        JsonNode identity = new ObjectMapper().readTree(events.get(0).data());
        assertThat(identity.path("type").asText()).isEqualTo("stream-identity");
        assertThat(identity.path("userId").asText()).isEqualTo("alice-id");
        assertThat(events.get(1).data()).contains("alices");
    }

    @Test
    @DisplayName("an anonymous stream names nobody")
    void anAnonymousStreamNamesNobody() throws Exception {
        ServerWebExchange guest = mock(ServerWebExchange.class);
        doReturn(Mono.empty()).when(guest).getPrincipal();

        ServerSentEvent<String> identity = EventStreamControllerAccess
                .stream(new EventSinkService(), mock(ConnectorService.class), guest)
                .blockFirst(Duration.ofSeconds(5));

        JsonNode body = new ObjectMapper().readTree(identity.data());
        assertThat(body.path("type").asText()).isEqualTo("stream-identity");
        assertThat(body.path("userId").isNull()).isTrue();
    }

    @Test
    @DisplayName("the keepalive is an SSE comment, not a data event every listener on the page receives")
    void keepaliveIsAComment() {
        assertThat(EventStreamControllerAccess.keepalive().comment()).isEqualTo("keepalive");
        assertThat(EventStreamControllerAccess.keepalive().data()).isNull();
    }
}
