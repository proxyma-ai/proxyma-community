package ai.proxyma.core.indexstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ChromaDB is waited for off the main thread (the 0.36.0 feedback, 2026-10-16): starting the
 * service returns while chroma is still coming up, so the rest of the application context is built
 * meanwhile - and a caller that arrives before chroma answers WAITS for it and then does its work,
 * rather than finding no client and quietly doing nothing.
 *
 * <p>Against a loopback server that refuses the heartbeat until the test lets it answer, standing
 * in for a chroma that is still starting.
 */
class ChromaStartupTest {

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile boolean up;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        int status = 200;
        String body = "{}";
        if (!up) {
            // Not listening yet, as far as the caller can tell.
            status = 503;
        } else {
            seen.add(method + " " + path);
            String collections = "/api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase/collections/";
            if (path.endsWith("/heartbeat")) {
                body = "{\"nanosecond heartbeat\": 1}";
            } else if (path.equals("/api/v2/tenants/SpringAiTenant")) {
                body = "{\"name\": \"SpringAiTenant\"}";
            } else if (path.equals("/api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase")) {
                body = "{\"id\": \"d1\", \"name\": \"SpringAiDatabase\", \"tenant\": \"SpringAiTenant\"}";
            } else if (method.equals("GET") && path.equals(collections + "docs")) {
                body = "{\"id\": \"uuid-docs\", \"name\": \"docs\", \"metadata\": null}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ChromaStoreService store() {
        ChromaStoreService store = new ChromaStoreService(mock(EmbeddingModel.class), RestClient.builder(),
                new ObjectMapper(), null, new EmbeddingConcurrency(() -> 4));
        ReflectionTestUtils.setField(store, "managed", false);
        ReflectionTestUtils.setField(store, "configuredPort", server.getAddress().getPort());
        ReflectionTestUtils.setField(store, "chromaHost", "127.0.0.1");
        ReflectionTestUtils.setField(store, "authToken", "");
        ReflectionTestUtils.setField(store, "authHeader", "X-Chroma-Token");
        return store;
    }

    @Test
    @DisplayName("starting the service does not wait for chroma to answer")
    void startDoesNotWait() throws Exception {
        ChromaStoreService store = store();
        CompletableFuture<Void> started = CompletableFuture.runAsync(() -> {
            try {
                store.startInfrastructure();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // Chroma has not answered and will not until the test says so; the start is over anyway.
        started.get(5, TimeUnit.SECONDS);
        assertThat(seen).isEmpty();

        up = true;
        store.awaitStartup();
        assertThat(seen).anyMatch(s -> s.endsWith("/heartbeat"));
    }

    @Test
    @DisplayName("a call that comes before chroma answers waits for it, then does its work")
    void earlyCallerWaits() throws Exception {
        ChromaStoreService store = store();
        store.startInfrastructure();

        CompletableFuture<Void> delete = CompletableFuture.runAsync(
                () -> store.deleteBySourceMetadata("docs", "a.md"));
        Thread.sleep(1_200);
        assertThat(delete).as("the delete went ahead before chroma answered").isNotDone();

        up = true;
        delete.get(10, TimeUnit.SECONDS);

        // Not skipped for want of a client: the collection was looked up and the delete sent.
        assertThat(seen).contains(
                "GET /api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase/collections/docs",
                "POST /api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase/collections/uuid-docs/delete");
    }
}
