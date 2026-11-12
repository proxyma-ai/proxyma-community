package ai.proxyma.core.system.controller;

import ai.proxyma.core.common.update.UpdateStatusChangedEvent;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.system.event.EventSinkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * An update status change reaches the page's one event stream (since 2026-11-04) - the desktop
 * window's "Restart to update" control reads /api/v1/update/status at startup and again on this
 * event, rather than polling the updater.
 */
class UpdateStatusStreamTest {

    @Test
    @DisplayName("an update status change is an 'update-status' event with the status and version, for every subscriber")
    void carried() throws Exception {
        EventSinkService sink = new EventSinkService();
        List<EventSinkService.SseMessage> received = new CopyOnWriteArrayList<>();
        Disposable subscription = sink.getFlux().subscribe(received::add);

        new EventStreamController(sink, mock(ConnectorService.class), new ObjectMapper())
                .on(new UpdateStatusChangedEvent("READY", "v0.41.0"));
        subscription.dispose();

        assertThat(received).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(received.get(0).json());
        assertThat(body.path("type").asText()).isEqualTo("update-status");
        assertThat(body.path("status").asText()).isEqualTo("READY");
        assertThat(body.path("version").asText()).isEqualTo("v0.41.0");
        // Desktop and Home Server have one user, who is anonymous to the stream on Desktop.
        assertThat(received.get(0).scope().visibleTo(null)).isTrue();
    }
}
