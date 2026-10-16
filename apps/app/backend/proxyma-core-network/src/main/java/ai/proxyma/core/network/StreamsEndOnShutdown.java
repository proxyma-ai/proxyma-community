package ai.proxyma.core.network;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ends every streamed response - the page's {@code /api/v1/events}, a conversation's
 * {@code /api/v1/chat/events/{id}}, a workflow run's events, an MCP client's listening stream - the
 * moment the application starts to stop, so the web server's graceful shutdown has nothing left to
 * wait for.
 *
 * <p>WHY, since the 0.36.0 feedback ("restarting usually takes more than 15s"). A server-sent-event
 * stream is a request that never completes: every open tab holds one, a running turn a second, an
 * MCP client a third. Tomcat's graceful shutdown waits for active requests, so it ALWAYS ran to its
 * timeout - five seconds on Desktop, thirty on a server - and then aborted them, each stream logging
 * "Async operation timeout" and a stack trace from the error handler writing onto a response already
 * sent. Measured on the PC 2026-10-16: 5.0s of a 6s restart's shutdown was that wait.
 *
 * <p>HOW. A stream is written through {@link ServerHttpResponse#writeAndFlushWith} - Spring's SSE
 * writer always uses it, and so does any encoder for a streaming media type - so the response is
 * decorated there, at one place, rather than in each controller: a stream added later is covered
 * without anyone remembering to. The body is cut between two events, never inside one, and a stream
 * opened after the stop is ended at once. A browser's EventSource reconnects by itself after a
 * stream that ends (SSEProvider after 5s; a chat stream with backoff, resuming by sequence number),
 * which is exactly what it does after the server goes away - only now the server says so first.
 *
 * <p>WHEN. A lifecycle bean in a phase above the web server's graceful shutdown
 * ({@code WebServerGracefulShutdownLifecycle}, {@code SmartLifecycle.DEFAULT_PHASE - 1024}), so it
 * is stopped before that phase begins - and the graceful wait, and the sync work waited for beside
 * it (SyncOrchestratorService), find no stream open.
 */
@Component
public class StreamsEndOnShutdown implements WebFilter, SmartLifecycle, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StreamsEndOnShutdown.class);

    // Stopped before every other lifecycle bean, the graceful shutdown's phase included.
    static final int PHASE = Integer.MAX_VALUE;

    // Replays its value to a stream that subscribes after the stop, which therefore ends at once.
    private final Sinks.One<Boolean> stopping = Sinks.one();
    private final AtomicInteger open = new AtomicInteger();
    private volatile boolean running;

    // Outermost, so every later filter and the handler see the decorated response.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return super.writeAndFlushWith(untilStopping(Flux.from(body)));
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    // The stream, ended at the stop. Counted while it is open, for the log line below.
    <T> Flux<T> untilStopping(Flux<T> stream) {
        return stream.takeUntilOther(stopping.asMono())
                .doOnSubscribe(s -> open.incrementAndGet())
                .doFinally(signal -> open.decrementAndGet());
    }

    /** Ends every open stream, and every one opened from now on. */
    void endStreams() {
        int count = open.get();
        stopping.tryEmitValue(Boolean.TRUE);
        if (count > 0) log.info("[Shutdown] Ended {} open event stream(s)", count);
    }

    int openStreams() {
        return open.get();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        endStreams();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
