package ai.proxyma.core.connector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The passthrough behind callJiraApi, callConfluenceApi and callBitbucketApi. Its whole security
 * property is that a privileged token goes to the CONFIGURED host and nowhere else, and nothing
 * pinned that; nor that a read cannot pull a whole download into memory.
 */
@org.junit.jupiter.api.Timeout(60)
class RestPassthroughTest {

    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private String baseUrl;
    private RestClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            byte[] bytes;
            String type;
            int status = 200;
            if (path.endsWith("/big")) {
                bytes = ("[\"" + "x".repeat(1_000_000) + "\"]").getBytes(StandardCharsets.UTF_8);
                type = "application/json";
            } else if (path.endsWith("/archive")) {
                bytes = new byte[2048];
                type = "application/zip";
            } else if (path.endsWith("/missing")) {
                bytes = "{\"errorMessages\":[\"Issue does not exist\"]}".getBytes(StandardCharsets.UTF_8);
                type = "application/json";
                status = 404;
            } else {
                bytes = "{\"key\":\"PROJ-1\"}".getBytes(StandardCharsets.UTF_8);
                type = "application/json";
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // The JDK client, as production uses (RestClientConfig).
        client = RestClient.builder().requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .requestInterceptor(new DotSegmentGuard()).build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> get(String path) {
        return RestPassthrough.call(client, h -> h.setBearerAuth("token"), baseUrl, "GET", path, null);
    }

    @Test
    @DisplayName("a path that names another host, a scheme or a climb is refused before anything is sent")
    void escapingPathsAreRefused() {
        for (String path : List.of("//evil.example/x", "https://evil.example/x", "\\\\evil.example\\x",
                "/rest/../../admin", "/rest/api/2/%2e%2e/%2e%2e/admin")) {
            assertThrows(IllegalArgumentException.class, () -> get(path), path);
        }
        assertThat(paths).isEmpty();
    }

    @Test
    @DisplayName("an allowed path reaches the configured host, and an error status comes back as an answer")
    void allowedPathAndErrorStatus() {
        Map<String, Object> ok = get("/rest/api/2/issue/PROJ-1?expand=names");
        assertThat(ok.get("ok")).isEqualTo(true);
        assertThat(ok.get("body")).isEqualTo(Map.of("key", "PROJ-1"));

        Map<String, Object> missing = get("/rest/api/2/issue/missing");
        assertThat(missing.get("status")).isEqualTo(404);
        assertThat(missing.get("ok")).isEqualTo(false);
    }

    @Test
    @DisplayName("a large body is read no further than its bound, and a download is not read at all")
    void bodiesAreBounded() {
        Map<String, Object> big = get("/rest/api/2/big");
        assertThat(big.get("truncated")).isEqualTo(true);
        assertThat((String) big.get("body")).hasSizeLessThanOrEqualTo(100_000);

        Map<String, Object> archive = get("/rest/api/1.0/projects/P/repos/r/archive");
        assertThat((String) archive.get("body")).contains("application/zip").contains("not returned");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/rest/%2E%2e/x", "http://evil.example", "/rest/%2e%2E/admin?x=1"})
    @DisplayName("mixed-case encoded climbs and a bare absolute URL are refused too")
    void moreEscapesAreRefused(String path) {
        assertThrows(IllegalArgumentException.class, () -> RestPassthrough.resolveForTest("https://jira.corp.example", path));
    }

    @Test
    @DisplayName("an ordinary path stays on the configured host, query and all; a blank verb is a GET")
    void ordinaryPathsJoin() {
        assertThat(RestPassthrough.resolveForTest("https://jira.corp.example/", "/rest/api/2/search?jql=a%3Db&next=//x"))
                .isEqualTo("https://jira.corp.example/rest/api/2/search?jql=a%3Db&next=//x");
        assertThat(RestPassthrough.resolveForTest("https://jira.corp.example", "rest/api/2/myself"))
                .isEqualTo("https://jira.corp.example/rest/api/2/myself");
        Map<String, Object> blank = RestPassthrough.call(client, h -> { }, baseUrl, " ", "/rest/api/2/issue/PROJ-1", null);
        assertThat(blank.get("ok")).isEqualTo(true);
    }
}
