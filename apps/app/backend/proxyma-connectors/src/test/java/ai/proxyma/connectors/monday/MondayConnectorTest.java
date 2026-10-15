package ai.proxyma.connectors.monday;

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
 * The monday.com connector against a loopback monday.com. Every query is a POST to one GraphQL
 * address, so the fake answers in the order the session asks and each test reads back what was asked
 * from the recorded bodies: the boards, the items_page cursor and next_items_page, an item's newest
 * update moving its time, the rendered item, the token as the bare Authorization value beside the
 * pinned API-Version, and every limit monday.com answers with - waited out, or reported.
 */
class MondayConnectorTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.monday-test";
    private static final String GRAPHQL = "/v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final MondayConnectorPlugin plugin = new MondayConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(MondayConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.MONDAY, config, credentials, ConnectorHttp.direct(waits::add)));
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

    private static String item(String id, String name, String updated, String newestUpdate) {
        return "{'id':'" + id + "','name':'" + name + "','updated_at':'" + updated + "','url':'https://acme.monday.com/boards/11/pulses/"
                + id + "','creator':{'name':'Ana'},'updates':[" + (newestUpdate == null ? ""
                : "{'created_at':'" + newestUpdate + "','updated_at':'" + newestUpdate + "'}") + "]}";
    }

    @Test
    @DisplayName("a full listing reads each chosen board's items_page and follows its cursor, an update moving an item's time")
    void fullListing() {
        api.on("POST", GRAPHQL).json("{'data':{'boards':[{'id':'11','name':'Launch','state':'active','type':'board'},"
                        + "{'id':'12','name':'Old','state':'archived','type':'board'}]}}")
                .then().json("{'data':{'boards':[{'items_page':{'cursor':'c2','items':[" + item("101", "Venue", "2026-10-01T10:00:00Z", null)
                        + "," + item("102", "Press kit", "2026-10-01T10:00:00Z", "2026-10-05T08:00:00Z") + "]}}]}}")
                .then().json("{'data':{'next_items_page':{'cursor':null,'items':[" + item("103", "Catering", "2026-10-02T10:00:00Z", null) + "]}}}");

        try (ConnectorSession s = session(Map.of("boards", "12, https://acme.monday.com/boards/11/views/5"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("item:101", "item:102", "item:103");
            assertThat(items.get(0).getTitle()).isEqualTo("Venue");
            assertThat(items.get(0).getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
            assertThat(items.get(0).getExtra("url")).isEqualTo("https://acme.monday.com/boards/11/pulses/101");
            assertThat(items.get(0).getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(body(0).path("variables").path("ids").toString()).isEqualTo("[\"11\",\"12\"]");
        assertThat(body(1).path("operationName").asText()).isEqualTo("Items");
        assertThat(body(1).path("variables").path("board").toString()).isEqualTo("[\"11\"]");
        assertThat(body(2).path("variables").path("cursor").asText()).isEqualTo("c2");
        assertThat(api.requests()).hasSize(3).allSatisfy(r -> {
            assertThat(r.header("Authorization")).isEqualTo(TOKEN);
            assertThat(r.header("API-Version")).isEqualTo(MondayApi.API_VERSION);
        });
    }

    @Test
    @DisplayName("every board when none is chosen, page by page, subitem boards left out")
    void everyBoard() {
        StringBuilder full = new StringBuilder("{'data':{'boards':[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) full.append(',');
            full.append("{'id':'").append(1000 + i).append("','name':'B").append(i).append("','state':'active','type':'")
                    .append(i == 0 ? "board" : "sub_items_board").append("'}");
        }
        full.append("]}}");
        api.on("POST", GRAPHQL).json(full.toString())
                .then().json("{'data':{'boards':[]}}")
                .then().json("{'data':{'boards':[{'items_page':{'cursor':null,'items':[" + item("201", "Only", "2026-10-01T10:00:00Z", null) + "]}}]}}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("item:201");
        }
        assertThat(body(1).path("variables").path("page").asInt()).isEqualTo(2);
        assertThat(api.requests(GRAPHQL)).hasSize(3);
    }

    @Test
    @DisplayName("an item is fetched as one markdown document: columns as label and value, updates with replies, subitems")
    void fetchItem() throws IOException {
        api.on("POST", GRAPHQL).json("{'data':{'items':[{'id':'102','name':'Press kit','state':'active',"
                + "'created_at':'2026-09-20T09:00:00Z','updated_at':'2026-10-01T10:00:00Z','url':'https://acme.monday.com/boards/11/pulses/102',"
                + "'board':{'id':'11','name':'Launch'},'group':{'title':'This week'},'creator':{'name':'Ana'},"
                + "'column_values':[{'id':'status','text':'Working on it','type':'status','column':{'title':'Status'}},"
                + "{'id':'person','text':'Bo','type':'people','column':{'title':'Owner'}},"
                + "{'id':'mirror','text':null,'type':'mirror','display_value':'Venue','column':{'title':'Depends on'}},"
                + "{'id':'empty','text':'','type':'text','column':{'title':'Notes'}}],"
                + "'subitems':[{'id':'901','name':'Logo files','column_values':[{'text':'Done','column':{'title':'Status'}}]}],"
                + "'updates':[{'id':'u2','text_body':'Printer booked.','created_at':'2026-10-05T08:00:00Z','creator':{'name':'Bo'},'replies':[]},"
                + "{'id':'u1','text_body':'Who prints it?','created_at':'2026-10-04T08:00:00Z','creator':{'name':'Ana'},"
                + "'replies':[{'text_body':'I will ask.','created_at':'2026-10-04T09:00:00Z','creator':{'name':'Cy'}}]}]}]}}");

        String md = text(fetch("item:102", Map.of("boards", "11")));

        assertThat(md).startsWith("# Press kit\n")
                .contains("- Board: Launch", "- Group: This week", "- Created by: Ana", "- Status: Working on it",
                        "- Owner: Bo", "- Depends on: Venue", "- Link: https://acme.monday.com/boards/11/pulses/102",
                        "## Updates", "## Subitems\n\n- Logo files (Status: Done)")
                .containsSubsequence("**Ana**, 2026-10-04T08:00:00Z:\n\nWho prints it?",
                        "**Cy (reply)**, 2026-10-04T09:00:00Z:\n\nI will ask.", "**Bo**, 2026-10-05T08:00:00Z:\n\nPrinter booked.")
                .doesNotContain("Notes");
        assertThat(body(0).path("variables").path("ids").toString()).isEqualTo("[\"102\"]");
    }

    @Test
    @DisplayName("an item monday.com no longer has, an archived one and one moved to another board are gone")
    void goneItems() {
        api.on("POST", GRAPHQL).json("{'data':{'items':[]}}")
                .then().json("{'data':{'items':[{'id':'104','name':'Old','state':'archived','board':{'id':'11'}}]}}")
                .then().json("{'data':{'items':[{'id':'105','name':'Moved','state':'active','board':{'id':'99'}}]}}");
        Map<String, Object> source = Map.of("boards", "11");
        assertThatThrownBy(() -> fetch("item:103", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("monday.com no longer has item:103.");
        assertThatThrownBy(() -> fetch("item:104", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("monday.com's item 104 is archived or deleted.");
        assertThatThrownBy(() -> fetch("item:105", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("monday.com's item 105 is no longer on a board this source reads.");
        assertThatThrownBy(() -> fetch("board:1", source)).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a monday.com source.");
    }

    @Test
    @DisplayName("a 429 is waited out for its Retry-After; a spent daily limit and an old complexity error each say so")
    void limits() {
        api.on("POST", GRAPHQL).status(429).header("Retry-After", "3")
                .json("{'errors':[{'message':'Complexity budget exhausted','extensions':{'code':'COMPLEXITY_BUDGET_EXHAUSTED','retry_in_seconds':3}}]}")
                .then().json("{'data':{'boards':[{'id':'11','name':'Launch','state':'active','type':'board'}]}}")
                .then().json("{'data':{'boards':[{'items_page':{'cursor':null,'items':[]}}]}}");
        try (ConnectorSession s = session(Map.of("boards", "11"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(3));

        api.reset();
        api.on("POST", GRAPHQL).status(429).header("Retry-After", "40000")
                .json("{'errors':[{'message':'Daily limit exceeded','extensions':{'code':'DAILY_LIMIT_EXCEEDED','retry_in_seconds':40000}}]}");
        try (ConnectorSession s = session(Map.of("boards", "11"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("monday.com's daily limit of API calls for this account is used up. "
                    + "The sync continues after midnight UTC.");
        }

        api.reset();
        api.on("POST", GRAPHQL).json("{'error_code':'ComplexityException','error_message':'Complexity budget exhausted, reset in 20 seconds',"
                + "'status_code':429}");
        try (ConnectorSession s = session(Map.of("boards", "11"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("monday.com is limiting how often it is asked. The next sync will try again.");
        }

        api.reset();
        api.on("POST", GRAPHQL).json("{'errors':[{'message':'Field boards argument foo is unknown','extensions':{'code':'argumentNotAccepted'}}]}");
        try (ConnectorSession s = session(Map.of("boards", "11"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("monday.com refused the query: Field boards argument foo is unknown");
        }
    }

    @Test
    @DisplayName("registration checks each chosen board, and a refused token says what to do")
    void accessProbe() {
        api.on("POST", GRAPHQL).json("{'data':{'boards':[{'id':'11','name':'Launch','state':'active','type':'board'}]}}");
        try (ConnectorSession s = session(Map.of("boards", "11, 13"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("monday.com has no board 13, or the connection cannot see it.");
        }
        try (ConnectorSession s = session(Map.of("boards", "11"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.reset();
        api.on("POST", GRAPHQL).status(401).json("{'errors':[{'message':'Not Authenticated','extensions':{'code':'Unauthorized'}}]}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("monday.com did not accept the token. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is a bearer, refreshed once when monday.com refuses it")
    void signIn() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.MONDAY, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.on("POST", GRAPHQL).status(401).json("{'errors':[{'message':'Not Authenticated'}]}")
                .then().json("{'data':{'me':{'name':'Ana'}}}");
        try (ConnectorSession s = session(Map.of(), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test names the person and the account, and the sign-in asks for read scopes only")
    void connectionTest() {
        api.on("POST", GRAPHQL).json("{'data':{'me':{'name':'Ana Silva','email':'ana@example.com','account':{'name':'Acme'}}}}");
        MondayConnectionPlugin connection = new MondayConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Silva (Acme)");
        assertThat(ok.template()).isEqualTo("Signed in as {user} ({account})");
        assertThat(api.last().header("Content-Type")).startsWith("application/json");

        api.reset();
        api.on("POST", GRAPHQL).status(401).json("{'errors':[{'message':'Not Authenticated'}]}");
        assertThat(connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct()).message())
                .isEqualTo("monday.com did not accept the token.");
        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://auth.monday.com/oauth2/authorize");
        assertThat(connection.oauth2().scopes()).allSatisfy(scope -> assertThat(scope).endsWith(":read"));

        // The API address may be typed with or without /v2.
        api.reset();
        api.on("POST", GRAPHQL).json("{'data':{'me':{'name':'Ana'}}}");
        assertThat(connection.test(ResolvedCredentials.token(api.baseUrl() + "/v2", TOKEN), ConnectorHttp.direct()).message())
                .isEqualTo("Signed in as Ana");
        assertThat(api.last().path()).isEqualTo("/v2");
    }

    @Test
    @DisplayName("the descriptor: identity is the board IDs by number, links read for theirs - or every board")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(MondayConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("boards", "900, https://acme.monday.com/boards/1000, 900")));
        assertThat(plugin.identity(config)).isEqualTo("900,1000");
        assertThat(plugin.displayName(config)).isEqualTo("monday.com 900, 1000");
        Map<String, String> all = ConfigValidator.validate(MondayConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("all");
        assertThatThrownBy(() -> ConfigValidator.validate(MondayConnectorPlugin.DESCRIPTOR.fields(), Map.of("boards", "Launch")))
                .hasMessageContaining("Boards is not in the form expected");
    }

    private InputStream fetch(String id, Map<String, Object> fields) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(fields)) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
