package ai.proxyma.core.system.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 0.36.0 feedback: an error raised on a response already under way - the page's event stream
 * timed out by Tomcat as the server stops ("Async operation timeout."), a response whose client went
 * away ("An established connection was aborted") - reached GlobalExceptionHandler, which tried to
 * send a problem body onto it. The headers of a committed response are read-only, so every such
 * error became an UnsupportedOperationException, "ServerHttpResponse already committed" and a
 * servlet stack trace, and a closed tab was logged as an "Unhandled exception".
 *
 * <p>Through the real WebFlux dispatch, with the real advice. A filter commits the response before
 * the handler fails - what a stream that has sent its first event looks like to the advice - since
 * the mock connector delivers a stream's own error to the client rather than to the advice.
 */
class CommittedResponseTest {

    // Mono<Void> with the exchange: a handler that writes its own response, as a stream does, so no
    // result handler touches the response before the error reaches the advice.
    @RestController
    static class Endpoints {
        @GetMapping("/committed/timed-out")
        Mono<Void> timedOut(ServerWebExchange exchange) {
            return Mono.error(new IllegalStateException("Async operation timeout."));
        }

        @GetMapping("/committed/client-gone")
        Mono<Void> clientGone(ServerWebExchange exchange) {
            return Mono.error(new IOException("An established connection was aborted by the software in your host machine"));
        }

        @GetMapping("/not-started")
        Mono<Void> notStarted(ServerWebExchange exchange) {
            return Mono.error(new IllegalStateException("not ready"));
        }
    }

    // The response goes out before the handler runs: status and headers are sent, and read-only.
    private static final WebFilter COMMIT_FIRST = (exchange, chain) ->
            exchange.getRequest().getPath().value().startsWith("/committed/")
                    ? exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                            .wrap("first".getBytes(StandardCharsets.UTF_8))))
                            .then(chain.filter(exchange))
                    : chain.filter(exchange);

    private final WebTestClient client = WebTestClient.bindToController(new Endpoints())
            .controllerAdvice(new GlobalExceptionHandler())
            .webFilter(COMMIT_FIRST)
            .build();

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> watched = List.of(
            (Logger) LoggerFactory.getLogger(HttpWebHandlerAdapter.class),
            (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class));

    @BeforeEach
    void watchLogs() {
        logs.start();
        watched.forEach(l -> l.addAppender(logs));
    }

    @AfterEach
    void stopWatching() {
        watched.forEach(l -> l.detachAppender(logs));
    }

    private List<String> warningsAndErrors() {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    @Test
    @DisplayName("a stream timed out at shutdown keeps what it sent, with nothing written over it")
    void timedOutStreamEndsQuietly() {
        String body = client.get().uri("/committed/timed-out").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo("first");
        assertThat(warningsAndErrors()).isEmpty();
    }

    @Test
    @DisplayName("a client that went away mid-response is not an unhandled server error")
    void clientGoneIsNotAnError() {
        String body = client.get().uri("/committed/client-gone").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo("first");
        assertThat(warningsAndErrors()).isEmpty();
    }

    @Test
    @DisplayName("a response not yet started still gets its problem body")
    void uncommittedStillAnswered() {
        client.get().uri("/not-started").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.title").isEqualTo("Service unavailable");
    }
}
