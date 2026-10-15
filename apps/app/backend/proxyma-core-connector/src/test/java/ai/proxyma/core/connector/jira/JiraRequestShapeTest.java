package ai.proxyma.core.connector.jira;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Jira requests look like on the wire, against a loopback server that records them.
 *
 * <ul>
 *   <li>A '+' in a JQL search ("C++") went out raw and the server read it as a space.</li>
 *   <li>A model-supplied id with "../" reached another endpoint; such a request is never sent.</li>
 * </ul>
 */
class JiraRequestShapeTest {

    private HttpServer server;
    private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
    private String baseUrl;
    private final JiraConnector connector = new JiraConnector(RestClient.builder());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
            byte[] bytes = "{\"issues\":[],\"total\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a '+' in a JQL search is sent encoded, not as a space")
    void plusInJqlIsEncoded() {
        connector.searchIssues(baseUrl, "token", "text ~ \"C++\"", 10);
        String sent = queries.get(0);
        assertTrue(sent.contains("C%2B%2B"), sent);
        assertFalse(sent.contains("C++"), sent);
    }

    @Test
    @DisplayName("an issue key that climbs with '../' is never sent")
    void climbingKeyIsRefused() {
        assertThrows(RuntimeException.class,
                () -> connector.fetchIssueDetails(baseUrl, "token", "PROJ-1/../../../rest/api/2/project"));
        for (String q : queries) assertFalse(q.contains(".."), q);
    }
}
