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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * An externally managed ChromaDB behind an authenticating proxy (sco-11): the configured token
 * reaches it on EVERY request this service makes - the startup heartbeat, the ChromaApi calls and
 * the raw rename - and with no token configured, no credential header is sent at all.
 *
 * <p>Against a loopback server standing in for the proxy, so what is asserted is what went over
 * the wire, not what a builder was told.
 */
class ChromaAuthTokenTest {

    private record Seen(String method, String path, String chromaToken, String authorization) {}

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
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
        seen.add(new Seen(method, path,
                exchange.getRequestHeaders().getFirst("X-Chroma-Token"),
                exchange.getRequestHeaders().getFirst("Authorization")));
        exchange.getRequestBody().readAllBytes();

        String base = "/api/v2/tenants/SpringAiTenant";
        String collections = base + "/databases/SpringAiDatabase/collections/";
        int status = 200;
        String body = "{}";
        if (path.endsWith("/heartbeat")) {
            body = "{\"nanosecond heartbeat\": 1}";
        } else if (method.equals("GET") && path.equals(base)) {
            body = "{\"name\": \"SpringAiTenant\"}";
        } else if (method.equals("GET") && path.equals(base + "/databases/SpringAiDatabase")) {
            body = "{\"id\": \"d1\", \"name\": \"SpringAiDatabase\", \"tenant\": \"SpringAiTenant\"}";
        } else if (method.equals("GET") && path.equals(collections + "shadow")) {
            body = "{\"id\": \"uuid-shadow\", \"name\": \"shadow\", \"metadata\": null}";
        } else if (method.equals("GET") && path.equals(collections + "live")) {
            status = 404;
            body = "{\"error\": \"NotFoundError\", \"message\": \"Collection [live] does not exist\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ChromaStoreService storeAgainstServer(String token, String header) throws Exception {
        ChromaStoreService store = new ChromaStoreService(mock(EmbeddingModel.class), RestClient.builder(),
                new ObjectMapper(), null, new EmbeddingConcurrency(() -> 4));
        ReflectionTestUtils.setField(store, "managed", false);
        ReflectionTestUtils.setField(store, "configuredPort", server.getAddress().getPort());
        ReflectionTestUtils.setField(store, "chromaHost", "127.0.0.1");
        ReflectionTestUtils.setField(store, "authToken", token);
        ReflectionTestUtils.setField(store, "authHeader", header);
        store.startInfrastructure();
        // A swap reaches every kind of call: ChromaApi lookups and the raw rename.
        store.swapCollection("live", "shadow");
        return store;
    }

    @Test
    @DisplayName("the token travels as X-Chroma-Token on the heartbeat, every ChromaApi call and the rename")
    void xChromaToken() throws Exception {
        storeAgainstServer("s3cret-token", "X-Chroma-Token");

        assertThat(seen).extracting(Seen::method).contains("GET", "PUT");
        assertThat(seen).extracting(Seen::path).anyMatch(p -> p.endsWith("/heartbeat"));
        assertThat(seen).extracting(Seen::path).contains("/api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase/collections/uuid-shadow");
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.chromaToken()).as(s.method() + " " + s.path()).isEqualTo("s3cret-token");
            assertThat(s.authorization()).as(s.method() + " " + s.path()).isNull();
        });
    }

    @Test
    @DisplayName("with the Authorization header chosen, the token is sent as a bearer")
    void bearer() throws Exception {
        storeAgainstServer("s3cret-token", "Authorization");

        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.authorization()).as(s.method() + " " + s.path()).isEqualTo("Bearer s3cret-token");
            assertThat(s.chromaToken()).as(s.method() + " " + s.path()).isNull();
        });
    }

    @Test
    @DisplayName("no token configured, no credential header on any request")
    void none() throws Exception {
        storeAgainstServer("", "X-Chroma-Token");

        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.chromaToken()).isNull();
            assertThat(s.authorization()).isNull();
        });
    }

    @Test
    @DisplayName("the injected builder is not changed, so nothing else that shares it carries the token")
    void sharedBuilderUntouched() throws Exception {
        RestClient.Builder shared = RestClient.builder();
        ChromaStoreService store = new ChromaStoreService(mock(EmbeddingModel.class), shared,
                new ObjectMapper(), null, new EmbeddingConcurrency(() -> 4));
        ReflectionTestUtils.setField(store, "managed", false);
        ReflectionTestUtils.setField(store, "configuredPort", server.getAddress().getPort());
        ReflectionTestUtils.setField(store, "chromaHost", "127.0.0.1");
        ReflectionTestUtils.setField(store, "authToken", "s3cret-token");
        ReflectionTestUtils.setField(store, "authHeader", "X-Chroma-Token");
        store.startInfrastructure();
        seen.clear();

        shared.build().get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere")
                .retrieve().toBodilessEntity();

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).chromaToken()).isNull();
        assertThat(seen.get(0).path()).isEqualTo("/elsewhere");
    }
}
