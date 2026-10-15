package ai.proxyma.core.system.event;

import ai.proxyma.core.registry.event.ConnectorUpdateEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.controller.EventStreamControllerAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("the keepalive is an SSE comment, not a data event every listener on the page receives")
    void keepaliveIsAComment() {
        assertThat(EventStreamControllerAccess.keepalive().comment()).isEqualTo("keepalive");
        assertThat(EventStreamControllerAccess.keepalive().data()).isNull();
    }
}
