package ai.proxyma.core.connector;

import ai.proxyma.core.connector.bitbucket.BitbucketConnector;
import ai.proxyma.core.connector.confluence.ConfluenceConnector;
import ai.proxyma.core.connector.jira.JiraConnector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Jira, Confluence and Bitbucket write calls added on 2026-09-29 actually SEND - method,
 * path, headers and body - recorded by a local server. The shapes are the part a real server is
 * strict about and a compile says nothing of: an inline comment's anchor, the XSRF header an
 * Atlassian multipart upload is refused without, a branch delete that takes its name in a body.
 */
class ConnectorWriteRequestsTest {

    record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {}

    private HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    // "METHOD path" -> [status, body]
    private final Map<String, Object[]> routes = new HashMap<>();
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    new HashMap<>(exchange.getRequestHeaders()), body));
            Object[] route = routes.getOrDefault(exchange.getRequestMethod() + " " + path,
                    new Object[]{404, "{\"errors\":[{\"message\":\"not found\"}]}"});
            byte[] bytes = ((String) route[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            int status = (Integer) route[0];
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String header(Request r, String name) {
        return r.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- Bitbucket

    @Test
    void aLineCommentCarriesItsAnchorAndAReplyItsParent() {
        routes.put("POST /rest/api/1.0/projects/PX/repos/app/pull-requests/7/comments",
                new Object[]{201, "{\"id\":991}"});
        BitbucketConnector bb = new BitbucketConnector(RestClient.builder());

        long id = bb.addPrComment(baseUrl, "tok", "PX", "app", 7, "Off by one", "src/a.c", 42, null, null);
        bb.addPrComment(baseUrl, "tok", "PX", "app", 7, "Why removed?", "src/a.c", 10, "removed", null);
        bb.addPrComment(baseUrl, "tok", "PX", "app", 7, "Agreed", null, null, null, 991L);

        assertEquals(991, id);
        String line = requests.get(0).body();
        assertTrue(line.contains("\"path\":\"src/a.c\"") && line.contains("\"line\":42")
                && line.contains("\"lineType\":\"ADDED\"") && line.contains("\"fileType\":\"TO\""), line);
        String removed = requests.get(1).body();
        assertTrue(removed.contains("\"lineType\":\"REMOVED\"") && removed.contains("\"fileType\":\"FROM\""),
                "a removed line is counted in the OLD file: " + removed);
        String reply = requests.get(2).body();
        assertTrue(reply.contains("\"parent\":{\"id\":991}") && !reply.contains("anchor"), reply);
    }

    @Test
    void aBadLineTypeIsRefusedBeforeAnythingIsSent() {
        BitbucketConnector bb = new BitbucketConnector(RestClient.builder());
        assertThrows(IllegalArgumentException.class,
                () -> bb.addPrComment(baseUrl, "tok", "PX", "app", 7, "x", "a.c", 1, "MODIFIED", null));
        assertTrue(requests.isEmpty());
    }

    @Test
    void aBranchIsCreatedThroughBranchUtilsWhereTheCoreApiHasNone() {
        routes.put("POST /rest/branch-utils/1.0/projects/PX/repos/app/branches", new Object[]{200,
                "{\"id\":\"refs/heads/feature/x\",\"displayId\":\"feature/x\",\"latestCommit\":\"abc123\"}"});
        BitbucketConnector bb = new BitbucketConnector(RestClient.builder());

        Map<String, Object> branch = bb.createBranch(baseUrl, "tok", "PX", "app", "feature/x", "main");

        assertEquals("feature/x", branch.get("name"));
        assertEquals("abc123", branch.get("commitId"));
        assertEquals("/rest/api/1.0/projects/PX/repos/app/branches", requests.get(0).path());
        assertTrue(requests.get(1).body().contains("\"startPoint\":\"main\""));
    }

    @Test
    void aBranchDeleteNamesTheFullRefInItsBody() {
        routes.put("DELETE /rest/branch-utils/1.0/projects/PX/repos/app/branches", new Object[]{204, ""});
        BitbucketConnector bb = new BitbucketConnector(RestClient.builder());

        bb.deleteBranch(baseUrl, "tok", "PX", "app", "feature/old");

        Request r = requests.get(0);
        assertEquals("DELETE", r.method());
        assertTrue(r.body().contains("\"name\":\"refs/heads/feature/old\"") && r.body().contains("\"dryRun\":false"),
                r.body());
    }

    @Test
    void deletingAMissingBranchSaysSo() {
        BitbucketConnector bb = new BitbucketConnector(RestClient.builder());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bb.deleteBranch(baseUrl, "tok", "PX", "app", "nope"));
        assertTrue(e.getMessage().contains("no branch 'nope'"), e.getMessage());
    }

    // ---------------------------------------------------------------- Confluence

    @Test
    void aNewConfluenceAttachmentIsAnXsrfExemptMultipartPost() {
        routes.put("GET /rest/api/content/123/child/attachment", new Object[]{200, "{\"results\":[]}"});
        routes.put("POST /rest/api/content/123/child/attachment", new Object[]{200,
                "{\"results\":[{\"id\":\"att9\",\"title\":\"plan.pdf\",\"_links\":{\"download\":\"/download/att9\"}}]}"});
        ConfluenceConnector cf = new ConfluenceConnector(RestClient.builder());

        Map<String, Object> a = cf.attachFile(baseUrl, "tok", "123", "plan.pdf",
                "PDFBYTES".getBytes(StandardCharsets.UTF_8), "v2");

        assertEquals("filename=plan.pdf", requests.get(0).query());
        Request r = requests.get(1);
        assertEquals("POST", r.method(),
                "Server and Data Center have no PUT on /child/attachment and answer it 405");
        assertEquals("no-check", header(r, "X-Atlassian-Token"));
        assertTrue(header(r, "Content-Type").startsWith("multipart/form-data"), header(r, "Content-Type"));
        assertTrue(r.body().contains("filename=\"plan.pdf\"") && r.body().contains("PDFBYTES"), r.body());
        assertEquals("att9", a.get("id"));
        assertEquals(baseUrl + "/download/att9", a.get("url"));
    }

    @Test
    void aSameNamedConfluenceAttachmentGetsANewVersion() {
        routes.put("GET /rest/api/content/123/child/attachment", new Object[]{200,
                "{\"results\":[{\"id\":\"att9\",\"title\":\"plan.pdf\"}]}"});
        routes.put("POST /rest/api/content/123/child/attachment/att9/data", new Object[]{200,
                "{\"id\":\"att9\",\"title\":\"plan.pdf\",\"_links\":{\"download\":\"/download/att9?version=2\"}}"});
        ConfluenceConnector cf = new ConfluenceConnector(RestClient.builder());

        Map<String, Object> a = cf.attachFile(baseUrl, "tok", "123", "plan.pdf",
                "V2".getBytes(StandardCharsets.UTF_8), null);

        Request r = requests.get(1);
        assertEquals("POST", r.method());
        assertEquals("/rest/api/content/123/child/attachment/att9/data", r.path());
        assertEquals("no-check", header(r, "X-Atlassian-Token"));
        assertEquals("att9", a.get("id"));
    }

    @Test
    void labelsAreAddedInOneCallRemovedOneByOneAndReadBack() {
        routes.put("POST /rest/api/content/123/label", new Object[]{200, "{}"});
        routes.put("DELETE /rest/api/content/123/label", new Object[]{204, ""});
        routes.put("GET /rest/api/content/123/label", new Object[]{200,
                "{\"results\":[{\"name\":\"release\"},{\"name\":\"q3\"}]}"});
        ConfluenceConnector cf = new ConfluenceConnector(RestClient.builder());

        List<String> labels = cf.updateLabels(baseUrl, "tok", "123", List.of("release", "q3"), List.of("draft"));

        assertEquals(List.of("release", "q3"), labels);
        // Field order is not asserted: the body is serialized from Map.of, whose iteration order
        // varies between runs - this line compared one fixed order and failed at random.
        String added = requests.get(0).body();
        assertTrue(added.contains("\"name\":\"release\"") && added.contains("\"name\":\"q3\"")
                && added.contains("\"prefix\":\"global\""), added);
        assertEquals("DELETE", requests.get(1).method());
        assertEquals("name=draft", requests.get(1).query());
    }

    @Test
    void aMoveKeepsTheTitleAndBodyAndBumpsTheVersion() {
        routes.put("GET /rest/api/content/55", new Object[]{200,
                "{\"type\":\"page\",\"title\":\"Runbook\",\"version\":{\"number\":4},"
                        + "\"body\":{\"storage\":{\"value\":\"<p>steps</p>\"}}}"});
        routes.put("PUT /rest/api/content/55", new Object[]{200, "{}"});
        ConfluenceConnector cf = new ConfluenceConnector(RestClient.builder());

        cf.movePage(baseUrl, "tok", "55", "77");

        String put = requests.get(1).body();
        assertTrue(put.contains("\"title\":\"Runbook\"") && put.contains("<p>steps</p>")
                && put.contains("\"number\":5") && put.contains("\"ancestors\":[{\"id\":\"77\"}]"), put);
    }

    // ---------------------------------------------------------------- Jira

    @Test
    void aJiraAttachmentCarriesTheXsrfHeader() {
        routes.put("POST /rest/api/2/issue/PX-1/attachments", new Object[]{200,
                "[{\"id\":\"10500\",\"filename\":\"log.txt\",\"content\":\"http://jira/secure/attachment/10500/log.txt\"}]"});
        JiraConnector jira = new JiraConnector(RestClient.builder());

        Map<String, Object> a = jira.addAttachment(baseUrl, "tok", "PX-1", "log.txt",
                "line".getBytes(StandardCharsets.UTF_8));

        Request r = requests.get(0);
        assertEquals("no-check", header(r, "X-Atlassian-Token"),
                "Jira answers 403 XSRF check failed to a multipart post without it");
        assertTrue(r.body().contains("name=\"file\"") && r.body().contains("filename=\"log.txt\""), r.body());
        assertEquals("10500", a.get("id"));
        assertFalse(((String) a.get("url")).isBlank());
    }
}
