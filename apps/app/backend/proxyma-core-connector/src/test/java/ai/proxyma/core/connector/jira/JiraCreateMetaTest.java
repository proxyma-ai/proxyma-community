package ai.proxyma.core.connector.jira;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a project can create, asked the way the server in front of it answers: the per-type
 * createmeta endpoints (Jira 8.4+, the only form Jira 9 keeps) and, where those 404, the older
 * single createmeta call. It is how the assistant learns an Epic, a custom type, or the "Epic Link"
 * field exists before it tries to create one.
 */
class JiraCreateMetaTest {

    private HttpServer server;
    private final Map<String, String> routes = new HashMap<>();
    private String baseUrl;
    private final JiraConnector connector = new JiraConnector(RestClient.builder());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = routes.get(exchange.getRequestURI().getRawPath());
            byte[] bytes = (body != null ? body : "{\"errorMessages\":[\"not found\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
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

    private void newStyleServer() {
        routes.put("/rest/api/2/issue/createmeta/PX/issuetypes", """
                {"values":[{"id":"10000","name":"Epic","subtask":false},
                           {"id":"10001","name":"Story","subtask":false},
                           {"id":"10005","name":"Sub-task","subtask":true},
                           {"id":"10100","name":"Hazard Analysis","subtask":false}]}""");
        routes.put("/rest/api/2/issue/createmeta/PX/issuetypes/10001", """
                {"values":[
                  {"fieldId":"summary","name":"Summary","required":true,"schema":{"type":"string"}},
                  {"fieldId":"customfield_10101","name":"Epic Link","required":false,
                   "schema":{"type":"any","custom":"com.pyxis.greenhopper.jira:gh-epic-link"}},
                  {"fieldId":"priority","name":"Priority","required":false,"schema":{"type":"priority"},
                   "allowedValues":[{"name":"High","id":"2"},{"name":"Low","id":"4"}]}]}""");
    }

    @Test
    void listsEveryTypeIncludingCustomOnes() {
        newStyleServer();
        Map<String, Object> result = connector.listCreateMeta(baseUrl, "t", "PX", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) result.get("issueTypes");
        assertEquals(List.of("Epic", "Story", "Sub-task", "Hazard Analysis"),
                types.stream().map(t -> t.get("name")).toList());
        assertEquals(true, types.get(2).get("subtask"));
    }

    @Test
    void aTypesFieldsShowTheEpicLinkAndAllowedValues() {
        newStyleServer();
        Map<String, Object> result = connector.listCreateMeta(baseUrl, "t", "PX", "story");

        assertEquals("Story", result.get("issueType"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        Map<String, Object> epicLink = fields.stream()
                .filter(f -> "Epic Link".equals(f.get("name"))).findFirst().orElseThrow();
        assertEquals("customfield_10101", epicLink.get("id"));
        assertEquals("gh-epic-link", epicLink.get("customType"));
        assertEquals(true, fields.get(0).get("required"));
        assertEquals(List.of("High", "Low"), fields.get(2).get("allowedValues"));
    }

    @Test
    void anUnknownTypeNamesTheOnesThatExist() {
        newStyleServer();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> connector.listCreateMeta(baseUrl, "t", "PX", "Feature"));
        assertTrue(e.getMessage().contains("Epic, Story, Sub-task, Hazard Analysis"), e.getMessage());
    }

    @Test
    void anOlderServerIsAskedTheOlderWay() {
        // No per-type endpoints: they 404, and the single call answers instead.
        routes.put("/rest/api/2/issue/createmeta", """
                {"projects":[{"key":"PX","issuetypes":[
                  {"id":"10000","name":"Epic","subtask":false,"fields":{
                    "customfield_10102":{"name":"Epic Name","required":true,"schema":{"type":"string",
                      "custom":"com.pyxis.greenhopper.jira:gh-epic-label"}}}},
                  {"id":"10002","name":"Task","subtask":false,"fields":{}}]}]}""");

        Map<String, Object> types = connector.listCreateMeta(baseUrl, "t", "PX", null);
        assertEquals(2, ((List<?>) types.get("issueTypes")).size());

        Map<String, Object> epic = connector.listCreateMeta(baseUrl, "t", "PX", "Epic");
        @SuppressWarnings("unchecked")
        Map<String, Object> epicName = ((List<Map<String, Object>>) epic.get("fields")).get(0);
        assertEquals("customfield_10102", epicName.get("id"));
        assertEquals(true, epicName.get("required"));
    }

    @Test
    void aProjectNobodyCanCreateInIsSaidPlainly() {
        routes.put("/rest/api/2/issue/createmeta", "{\"projects\":[]}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> connector.listCreateMeta(baseUrl, "t", "NOPE", null));
        assertFalse(e.getMessage().isBlank());
    }
}
