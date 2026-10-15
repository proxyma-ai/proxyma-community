package ai.proxyma.core.tools;

import ai.proxyma.core.search.domain.SearchContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The fetch every web tool shares, and what it may save.
 *
 * <ul>
 *   <li>The size cap is applied AS the body arrives. The body was read whole into memory first
 *       (toEntity(Resource.class) copies it), so an endless response never returned and a huge one
 *       was an OutOfMemoryError for the whole server.</li>
 *   <li>Media is saved by the type the response declares, under that type's extension. A page's
 *       image link answered with HTML was saved as .html and an SVG kept its scripts, both then
 *       served from the application's own origin.</li>
 * </ul>
 */
class WebMediaStorageTest {

    @TempDir
    Path conversations;

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/endless", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, 0); // chunked, no length
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = ex.getResponseBody()) {
                while (true) out.write(chunk);
            } catch (Exception closedByClient) {
                // the reader stopped, which is the point
            }
        });
        serve("/photo.html", "image/png", "PNGDATA");
        serve("/page.png", "text/html", "<script>alert(1)</script>");
        serve("/vector.svg", "image/svg+xml", "<svg onload=alert(1)/>");
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        SearchContext.setConversationId("c1");
    }

    private void serve(String path, String type, String body) {
        server.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @AfterEach
    void stop() {
        SearchContext.setConversationId(null);
        server.stop(0);
    }

    private WebMediaStorage storage() {
        return new WebMediaStorage(RestClient.builder(), conversations.toString(), false);
    }

    @Test
    @DisplayName("an endless response is cut at the cap as it arrives, and returns")
    void capIsAppliedWhileReading() {
        WebMediaStorage.FetchedBody body = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> storage().fetchCapped(base + "/endless", 1024 * 1024));
        assertThat(body.truncated()).isTrue();
        assertThat(body.bytes()).hasSize(1024 * 1024);
    }

    @Test
    @DisplayName("media is saved by the response's type, under that type's extension - never HTML or SVG")
    void onlyAllowedTypesAreSaved() throws Exception {
        List<WebMediaStorage.SavedMedia> saved = storage().downloadAndSave(
                List.of(base + "/photo.html", base + "/page.png", base + "/vector.svg"));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).mediaUrl()).endsWith("/photo.png");
        try (var files = Files.list(conversations.resolve("c1").resolve("web-media"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("photo.png");
        }
        assertThat(WebMediaStorage.savedExtension("image/svg+xml")).isNull();
        assertThat(WebMediaStorage.savedExtension("text/html; charset=utf-8")).isNull();
        assertThat(WebMediaStorage.savedExtension("image/JPEG; q=1")).isEqualTo("jpg");
    }
}
