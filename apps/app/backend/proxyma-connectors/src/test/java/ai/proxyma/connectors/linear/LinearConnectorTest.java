package ai.proxyma.connectors.linear;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.TokenSupplier;
import ai.proxyma.core.connector.testing.LoopbackApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Linear connector against a loopback Linear. Every query is a POST to one GraphQL address, so
 * the fake answers in the order the session asks, and each test reads back what was asked from the
 * recorded bodies: the cursor pages, the incremental filter, archived and trashed issues as gone,
 * the rendered issue, the key as the bare Authorization value, and Linear's rate limit - an HTTP
 * 400 with RATELIMITED - waited out or reported like any other service's 429.
 */
class LinearConnectorTest {

    private static final String KEY = "lin_api_test-key";
    private static final String GRAPHQL = "/graphql";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final LinearConnectorPlugin plugin = new LinearConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), KEY));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(LinearConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.LINEAR, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private JsonNode body(int index) {
        try {
            return JSON.readTree(api.requests(GRAPHQL).get(index).body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String node(String id, String identifier, String title, String extra) {
        return "{'id':'" + id + "','identifier':'" + identifier + "','title':'" + title + "','url':'https://linear.app/acme/issue/"
                + identifier + "','updatedAt':'2026-10-0" + id.length() + "T10:00:00.000Z','archivedAt':null,'trashed':null,"
                + "'creator':{'name':'Ana'}" + extra + "}";
    }

    @Test
    @DisplayName("a full listing pages through the chosen teams' issues and leaves archived and trashed ones out")
    void fullListing() {
        api.on("POST", GRAPHQL).json("{'data':{'issues':{'nodes':[" + node("u1", "ENG-1", "Login fails", "") + ","
                        + node("u22", "ENG-2", "Old idea", ",'archivedAt':'2026-09-01T00:00:00Z'") + "],"
                        + "'pageInfo':{'hasNextPage':true,'endCursor':'c1'}}}}")
                .then().json("{'data':{'issues':{'nodes':[" + node("u333", "OPS-3", "Pager rota", "") + ","
                        + node("u4444", "OPS-4", "Spam", ",'trashed':true") + "],"
                        + "'pageInfo':{'hasNextPage':false,'endCursor':'c2'}}}}");

        try (ConnectorSession s = session(Map.of("teams", "ops, eng"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("issue:u1", "issue:u333");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("ENG-1 Login fails");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo("https://linear.app/acme/issue/ENG-1");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(KEY));
        JsonNode firstAsk = body(0);
        assertThat(firstAsk.path("operationName").asText()).isEqualTo("Issues");
        assertThat(firstAsk.path("variables").path("filter").path("team").path("key").path("in").toString())
                .isEqualTo("[\"ENG\",\"OPS\"]");
        assertThat(firstAsk.path("variables").path("filter").has("updatedAt")).isFalse();
        assertThat(firstAsk.path("variables").path("includeArchived").asBoolean()).isFalse();
        assertThat(firstAsk.path("variables").path("after").isNull()).isTrue();
        assertThat(body(1).path("variables").path("after").asText()).isEqualTo("c1");
    }

    @Test
    @DisplayName("every team when none is chosen")
    void allTeams() {
        api.on("POST", GRAPHQL).json("{'data':{'issues':{'nodes':[],'pageInfo':{'hasNextPage':false}}}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).isEmpty();
        }
        assertThat(body(0).path("variables").path("filter").has("team")).isFalse();
    }

    @Test
    @DisplayName("a changes pass asks for what changed since the last pass, archived ones included so their fetch can say gone")
    void incrementalListing() {
        api.on("POST", GRAPHQL).json("{'data':{'issues':{'nodes':[" + node("u1", "ENG-1", "Login fails", "") + ","
                + node("u22", "ENG-2", "Old idea", ",'archivedAt':'2026-10-12T09:30:00Z'") + "],"
                + "'pageInfo':{'hasNextPage':false}}}}");
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        try (ConnectorSession s = session(Map.of("teams", "ENG"))) {
            assertThat(drain(s.list(ListRequest.changes(since, null)))).extracting(ItemMetadata::getId)
                    .containsExactly("issue:u1", "issue:u22");
        }
        JsonNode variables = body(0).path("variables");
        assertThat(variables.path("filter").path("updatedAt").path("gt").asText())
                .isEqualTo(since.minus(LinearSession.OVERLAP).toString());
        assertThat(variables.path("includeArchived").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an issue is fetched as one markdown document with its fields and every page of comments")
    void fetchIssue() throws IOException {
        api.on("POST", GRAPHQL).json("{'data':{'issue':{'id':'u1','identifier':'ENG-1','title':'Login fails',"
                        + "'description':'The login page loops.','url':'https://linear.app/acme/issue/ENG-1','priorityLabel':'High',"
                        + "'estimate':3,'dueDate':'2026-10-20','createdAt':'2026-10-01T09:00:00Z','updatedAt':'2026-10-01T10:00:00Z',"
                        + "'completedAt':null,'canceledAt':null,'archivedAt':null,'trashed':null,"
                        + "'state':{'name':'In Progress','type':'started'},'assignee':{'name':'Bo'},'creator':{'name':'Ana'},"
                        + "'team':{'key':'ENG','name':'Engineering'},'project':{'name':'Auth revamp'},'cycle':{'number':12,'name':null},"
                        + "'parent':{'identifier':'ENG-0','title':'Auth epic'},'labels':{'nodes':[{'name':'bug'},{'name':'auth'}]},"
                        + "'comments':{'nodes':[{'body':'Reproduced on Safari.','createdAt':'2026-10-01T09:30:00Z','user':{'name':'Bo'}}],"
                        + "'pageInfo':{'hasNextPage':true,'endCursor':'k1'}}}}}")
                .then().json("{'data':{'issue':{'comments':{'nodes':[{'body':'Fixed in ENG-5.','createdAt':'2026-10-01T09:40:00Z',"
                        + "'user':{'name':'Ana'}}],'pageInfo':{'hasNextPage':false,'endCursor':'k2'}}}}}");

        String md = text(fetch("issue:u1"));

        assertThat(md).startsWith("# ENG-1 Login fails")
                .contains("- Team: Engineering (ENG)", "- State: In Progress (started)", "- Priority: High",
                        "- Assignee: Bo", "- Creator: Ana", "- Project: Auth revamp", "- Cycle: Cycle 12",
                        "- Labels: bug, auth", "- Parent: ENG-0 Auth epic", "- Estimate: 3", "- Due: 2026-10-20",
                        "- Link: https://linear.app/acme/issue/ENG-1", "The login page loops.",
                        "**Bo**, 2026-10-01T09:30:00Z:", "Reproduced on Safari.", "Fixed in ENG-5.")
                .doesNotContain("Completed", "Canceled");
        assertThat(body(0).path("variables").path("id").asText()).isEqualTo("u1");
        assertThat(body(1).path("operationName").asText()).isEqualTo("IssueComments");
        assertThat(body(1).path("variables").path("after").asText()).isEqualTo("k1");
    }

    @Test
    @DisplayName("an archived, trashed or unknown issue is gone - removed at once rather than retried")
    void goneItems() {
        api.on("POST", GRAPHQL).json("{'data':{'issue':{'id':'u2','identifier':'ENG-2','archivedAt':'2026-10-12T00:00:00Z'}}}")
                .then().json("{'data':{'issue':{'id':'u3','identifier':'ENG-3','archivedAt':null,'trashed':true}}}")
                .then().json("{'errors':[{'message':'Entity not found: Issue','extensions':{'code':'INPUT_ERROR'}}],'data':null}");
        assertThatThrownBy(() -> fetch("issue:u2")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:u3")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:u9")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("ticket:1")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Linear source.");
    }

    @Test
    @DisplayName("Linear's rate limit - a 400 with RATELIMITED - is waited out when it resets soon, and reported when it does not")
    void rateLimits() {
        long soon = Instant.now().toEpochMilli() + 3000;
        api.on("POST", GRAPHQL).status(400).header("X-RateLimit-Requests-Remaining", "0")
                .header("X-RateLimit-Requests-Reset", String.valueOf(soon))
                .json("{'errors':[{'message':'Rate limit exceeded','extensions':{'code':'RATELIMITED'}}]}")
                .then().json("{'data':{'issues':{'nodes':[],'pageInfo':{'hasNextPage':false}}}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isLessThanOrEqualTo(Duration.ofSeconds(5));

        // An hour away: not sat out, but the sentence every service's rate limit gets.
        api.reset();
        long later = Instant.now().toEpochMilli() + 3_600_000;
        api.on("POST", GRAPHQL).status(400).header("X-RateLimit-Complexity-Remaining", "0")
                .header("X-RateLimit-Complexity-Reset", String.valueOf(later))
                .json("{'errors':[{'message':'Rate limit exceeded','extensions':{'code':'RATELIMITED'}}]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Linear is limiting how often it is asked. The next sync will try again.");
        }

        // Only the body says so: still a rate limit, not "refused the request".
        api.reset();
        api.on("POST", GRAPHQL).status(400).json("{'errors':[{'message':'Rate limit exceeded','extensions':{'code':'RATELIMITED'}}]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Linear is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("any other 400 is Linear's own reason, and a refused key says what to do")
    void refusals() {
        api.on("POST", GRAPHQL).status(400).json("{'errors':[{'message':'Unknown argument foo','extensions':{'code':'GRAPHQL_VALIDATION_FAILED'}}]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Linear refused the query: Unknown argument foo");
        }
        api.reset();
        api.on("POST", GRAPHQL).status(400).json("{'errors':[{'message':'Authentication required','extensions':{'code':'AUTHENTICATION_ERROR'}}]}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Linear did not accept the key. Replace it in Settings > Connections.");
        }
        api.reset();
        api.on("POST", GRAPHQL).status(401).json("{'errors':[{'message':'Authentication required'}]}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Linear refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("registration checks every chosen team is visible")
    void accessProbe() {
        api.on("POST", GRAPHQL).json("{'data':{'teams':{'nodes':[{'key':'ENG','name':'Engineering'}],"
                + "'pageInfo':{'hasNextPage':true,'endCursor':'t1'}}}}")
                .then().json("{'data':{'teams':{'nodes':[{'key':'OPS','name':'Operations'}],'pageInfo':{'hasNextPage':false}}}}");
        try (ConnectorSession s = session(Map.of("teams", "eng,ops"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.reset();
        api.on("POST", GRAPHQL).json("{'data':{'teams':{'nodes':[{'key':'ENG','name':'Engineering'}],'pageInfo':{'hasNextPage':false}}}}");
        try (ConnectorSession s = session(Map.of("teams", "ENG, DES"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Linear has no team DES, or the connection cannot see it.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is sent as a bearer, and refreshed once when Linear refuses it")
    void signInIsABearer() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.LINEAR, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.on("POST", GRAPHQL).status(401).json("{'errors':[{'message':'Authentication required'}]}")
                .then().json("{'data':{'viewer':{'id':'v1','name':'Ana'}}}");
        try (ConnectorSession s = session(Map.of(), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test is who the key belongs to, and its sign-in asks Linear for read")
    void connectionTest() {
        api.on("POST", GRAPHQL).json("{'data':{'viewer':{'id':'v1','name':'Ana Silva','email':'ana@example.com'}}}");
        LinearConnectionPlugin connection = new LinearConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), KEY), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Silva");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().header("Authorization")).isEqualTo(KEY);
        assertThat(api.last().header("Content-Type")).startsWith("application/json");

        api.reset();
        api.on("POST", GRAPHQL).status(400).json("{'errors':[{'message':'Authentication required','extensions':{'code':'AUTHENTICATION_ERROR'}}]}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), KEY), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Linear did not accept the key.");

        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://linear.app/oauth/authorize");
        assertThat(connection.oauth2().tokenUrl()).isEqualTo("https://api.linear.app/oauth/token");
        assertThat(connection.oauth2().scopes()).containsExactly("read");
    }

    @Test
    @DisplayName("the API address may be typed with or without /graphql")
    void baseUrl() {
        api.on("POST", "/linear/graphql").json("{'data':{'viewer':{'id':'v1','name':'Ana'}}}");
        for (String typed : List.of(api.baseUrl() + "/linear", api.baseUrl() + "/linear/graphql")) {
            ConnectionTestResult ok = new LinearConnectionPlugin().test(ResolvedCredentials.token(typed, KEY), ConnectorHttp.direct());
            assertThat(ok.ok()).isTrue();
            assertThat(api.last().path()).isEqualTo("/linear/graphql");
        }
    }

    @Test
    @DisplayName("the descriptor: identity is the team keys sorted without case, or all teams")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(LinearConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("teams", " ops, Eng ,OPS")));
        assertThat(plugin.identity(config)).isEqualTo("ENG,OPS");
        assertThat(plugin.displayName(config)).isEqualTo("Linear ENG, OPS");
        Map<String, String> all = ConfigValidator.validate(LinearConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("all");
        assertThat(plugin.displayName(all)).isEqualTo("Linear");
        assertThatThrownBy(() -> ConfigValidator.validate(LinearConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("teams", "https://linear.app/acme/team/ENG")))
                .hasMessage("Team keys is not in the form expected (for example ENG, OPS).");
    }

    private InputStream fetch(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(Map.of())) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
