package ai.proxyma.core.network;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.ServerSentEventHttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server-sent-event stream ends when the application starts to stop, so the web server's graceful
 * shutdown has nothing to wait for. 0.36.0 feedback: every open tab's /api/v1/events held a restart
 * for the whole five-second graceful phase, and Tomcat's own unload waited two more seconds for the
 * same requests before the process could exit.
 */
class StreamsEndOnShutdownTest {

    private static final Duration SOON = Duration.ofSeconds(5);

    // The page's stream as EventStreamController writes it: events that never end, and a keepalive.
    private static Flux<ServerSentEvent<String>> endless() {
        return Flux.interval(Duration.ofMillis(20)).map(i -> ServerSentEvent.builder("event " + i).build())
                .mergeWith(Flux.interval(Duration.ofSeconds(30)).map(i -> ServerSentEvent.<String>builder().comment("keepalive").build()));
    }

    // Writes `body` through the filter with Spring's own SSE writer, as the handler would.
    private static CompletableFuture<Void> serve(StreamsEndOnShutdown streams, MockServerWebExchange exchange,
                                                 Flux<ServerSentEvent<String>> body) {
        ServerSentEventHttpMessageWriter writer = new ServerSentEventHttpMessageWriter(new Jackson2JsonEncoder());
        return streams.filter(exchange, filtered -> writer.write(body,
                        ResolvableType.forClassWithGenerics(ServerSentEvent.class, String.class),
                        MediaType.TEXT_EVENT_STREAM, filtered.getResponse(), Map.of()))
                .toFuture();
    }

    @Test
    void anOpenStreamEndsAtTheStopAndKeepsWhatItSent() throws Exception {
        StreamsEndOnShutdown streams = new StreamsEndOnShutdown();
        streams.start();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        CompletableFuture<Void> response = serve(streams, exchange, endless());

        // Running, it does not end by itself.
        assertThrows(TimeoutException.class, () -> response.get(300, TimeUnit.MILLISECONDS));
        assertEquals(1, streams.openStreams());

        streams.stop();
        response.get(SOON.toMillis(), TimeUnit.MILLISECONDS);
        assertFalse(streams.isRunning());
        assertEquals(0, streams.openStreams());
        String sent = exchange.getResponse().getBodyAsString().block(SOON);
        assertTrue(sent != null && sent.contains("data:event 0"), "the events sent before the stop were written: " + sent);
    }

    @Test
    void aStreamOpenedAfterTheStopEndsAtOnce() throws Exception {
        StreamsEndOnShutdown streams = new StreamsEndOnShutdown();
        streams.start();
        streams.stop();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/chat/events/c1"));
        serve(streams, exchange, endless()).get(SOON.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void anOrdinaryResponseIsUntouched() {
        StreamsEndOnShutdown streams = new StreamsEndOnShutdown();
        streams.start();
        streams.stop();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/health"));
        streams.filter(exchange, filtered -> filtered.getResponse().writeWith(
                Mono.just(filtered.getResponse().bufferFactory().wrap("ok".getBytes())))).block(SOON);
        assertEquals("ok", exchange.getResponse().getBodyAsString().block(SOON));
    }

    // Stopped BEFORE the phase in which the web server waits for requests to finish - stopping in
    // the same phase or after it would end the streams only once the wait had run out.
    @Test
    void itStopsBeforeTheGracefulShutdownWaits() {
        assertTrue(new StreamsEndOnShutdown().getPhase() > WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }
}
