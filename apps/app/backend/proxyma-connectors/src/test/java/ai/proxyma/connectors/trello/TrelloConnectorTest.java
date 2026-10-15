package ai.proxyma.connectors.trello;

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
import ai.proxyma.core.connector.testing.LoopbackApi;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Trello connector against a loopback Trello: the boards by link, ID or membership, the cards a
 * thousand at a time asked for {@code before} the oldest seen, the CHANGES pass kept to cards active
 * since the last one (archived ones included, so their fetch can say gone), the rendered card, the
 * key and token in the OAuth header on every request, and Trello's 429 waited out for its window.
 */
class TrelloConnectorTest {

    private static final String KEY = "trello-key";
    private static final String TOKEN = "trello-token";
    private static final String BOARD_A = "5f0000000000000000000a01";
    private static final String BOARD_B = "5f0000000000000000000b01";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final TrelloConnectorPlugin plugin = new TrelloConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        boards();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private void boards() {
        api.on("GET", "/boards/AbCd1234").json("{'id':'" + BOARD_A + "','name':'Roadmap','closed':false}");
        api.on("GET", "/boards/" + BOARD_B).json("{'id':'" + BOARD_B + "','name':'Support','closed':false}");
    }

    private ResolvedCredentials credentials() {
        return new ResolvedCredentials(null, ConnectionProtocol.TRELLO, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("apiKey", KEY, "token", TOKEN), Map.of(), null);
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(TrelloConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.TRELLO, config, credentials(), ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String card(String id, String name, String active, boolean closed) {
        return "{'id':'" + id + "','name':'" + name + "','dateLastActivity':'" + active + "','closed':" + closed
                + ",'shortUrl':'https://trello.com/c/" + id.substring(id.length() - 4) + "','url':'https://trello.com/c/x/long'}";
    }

    @Test
    @DisplayName("a full listing reads every open card of each board, a thousand at a time, asking before the oldest seen")
    void fullListing() {
        StringBuilder full = new StringBuilder("[");
        for (int i = 0; i < TrelloSession.PAGE; i++) {
            if (i > 0) full.append(',');
            full.append(card(String.format("6a%022d", 2000 - i), "Card " + i, "2026-10-01T10:00:00.000Z", false));
        }
        full.append(']');
        String oldest = String.format("6a%022d", 2000 - TrelloSession.PAGE + 1);
        api.on("GET", "/boards/" + BOARD_A + "/cards/open").json(full.toString());
        api.on("GET", "/boards/" + BOARD_A + "/cards/open").query("before", oldest)
                .json("[" + card("6a0000000000000000000001", "The very first", "2026-09-01T10:00:00.000Z", false) + "]");
        api.on("GET", "/boards/" + BOARD_B + "/cards/open").json("[" + card("6b0000000000000000000001", "Refund flow",
                "2026-10-03T10:00:00.000Z", false) + "]");

        try (ConnectorSession s = session(Map.of("boards", "https://trello.com/b/AbCd1234/roadmap, " + BOARD_B))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).hasSize(TrelloSession.PAGE + 2);
            // The boards in the source's order (sorted), each board's pages in turn.
            assertThat(items.get(items.size() - 1).getId()).isEqualTo("card:6a0000000000000000000001");
            ItemMetadata refund = items.get(0);
            assertThat(refund.getId()).isEqualTo("card:6b0000000000000000000001");
            assertThat(refund.getTitle()).isEqualTo("Refund flow");
            assertThat(refund.getLastModified()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
            assertThat(refund.getExtra("url")).isEqualTo("https://trello.com/c/0001");
            assertThat(refund.getContentType()).isEqualTo("text/markdown");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization"))
                .isEqualTo("OAuth oauth_consumer_key=\"" + KEY + "\", oauth_token=\"" + TOKEN + "\""));
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.query()).doesNotContainKeys("key", "token"));
        assertThat(api.requests("/boards/" + BOARD_A + "/cards/open").get(0).param("limit")).isEqualTo("1000");
    }

    @Test
    @DisplayName("every open board of the member when none is chosen; an archived board lists nothing")
    void everyBoard() {
        api.on("GET", "/members/me/boards").query("filter", "open").json("[{'id':'" + BOARD_A + "','name':'Roadmap','closed':false},"
                + "{'id':'" + BOARD_B + "','name':'Old','closed':true}]");
        api.on("GET", "/boards/" + BOARD_A + "/cards/open").json("[" + card("6a0000000000000000000001", "Ship it",
                "2026-10-01T10:00:00.000Z", false) + "]");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("card:6a0000000000000000000001");
        }
        assertThat(api.requests("/boards/" + BOARD_B + "/cards/open")).isEmpty();
    }

    @Test
    @DisplayName("a changes pass keeps the cards active since the last pass, archived ones included so their fetch can say gone")
    void incrementalListing() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("GET", "/boards/" + BOARD_B + "/cards/all").json("["
                + card("6b0000000000000000000001", "Quiet", "2026-10-01T10:00:00.000Z", false) + ","
                + card("6b0000000000000000000002", "Edited", "2026-10-12T10:00:00.000Z", false) + ","
                + card("6b0000000000000000000003", "Archived today", "2026-10-12T11:00:00.000Z", true) + ","
                + card("6b0000000000000000000004", "Just before, inside the overlap", "2026-10-12T08:59:30.000Z", false) + "]");
        try (ConnectorSession s = session(Map.of("boards", BOARD_B))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly(
                    "card:6b0000000000000000000002", "card:6b0000000000000000000003", "card:6b0000000000000000000004");
            assertThat(listing.hasErrored()).isFalse();
        }
    }

    @Test
    @DisplayName("a card is fetched as one markdown document: list, labels, members, dates, checklists, attachments and comments")
    void fetchCard() throws IOException {
        api.on("GET", "/cards/6b0000000000000000000002").json("{'id':'6b0000000000000000000002','name':'Refund flow',"
                + "'desc':'Customers ask twice.','due':'2026-10-20T12:00:00.000Z','dueComplete':true,'start':null,'closed':false,"
                + "'dateLastActivity':'2026-10-12T10:00:00.000Z','url':'https://trello.com/c/abcd/refund','shortUrl':'https://trello.com/c/abcd',"
                + "'idBoard':'" + BOARD_B + "','labels':[{'name':'billing','color':'red'},{'name':'','color':'green'}],"
                + "'list':{'name':'Doing'},'board':{'name':'Support','closed':false,'shortLink':'XyZ98765'},"
                + "'members':[{'fullName':'Ana Silva','username':'ana'}],"
                + "'checklists':[{'name':'Release','pos':2,'checkItems':[{'name':'Tag','state':'incomplete','pos':2},"
                + "{'name':'Build','state':'complete','pos':1}]},{'name':'Design','pos':1,'checkItems':[{'name':'Sketch','state':'complete','pos':1}]}],"
                + "'attachments':[{'name':'flow.png','url':'https://trello.example/flow.png'}]}");
        api.on("GET", "/cards/6b0000000000000000000002/actions").query("filter", "commentCard").json("["
                + "{'id':'a2','date':'2026-10-12T09:00:00.000Z','memberCreator':{'fullName':'Bo','username':'bo'},'data':{'text':'Done on staging.'}},"
                + "{'id':'a1','date':'2026-10-11T09:00:00.000Z','memberCreator':{'fullName':'Ana Silva','username':'ana'},'data':{'text':'Who owns this?'}}]");

        String md = text(fetch("card:6b0000000000000000000002", Map.of("boards", BOARD_B)));

        assertThat(md).startsWith("# Refund flow\n")
                .contains("- Board: Support", "- List: Doing", "- Labels: billing, green", "- Members: Ana Silva",
                        "- Due: 2026-10-20T12:00:00.000Z (complete)", "- Link: https://trello.com/c/abcd",
                        "## Description\n\nCustomers ask twice.", "## Checklist: Design\n\n- [x] Sketch",
                        "## Checklist: Release\n\n- [x] Build\n- [ ] Tag", "## Attachments\n\n- flow.png", "## Comments")
                .containsSubsequence("**Ana Silva**, 2026-10-11T09:00:00.000Z:\n\nWho owns this?", "**Bo**, 2026-10-12T09:00:00.000Z:\n\nDone on staging.")
                .containsSubsequence("## Checklist: Design", "## Checklist: Release");
        assertThat(api.requests("/cards/6b0000000000000000000002").get(0).param("checklists")).isEqualTo("all");
    }

    @Test
    @DisplayName("a deleted card, an archived one, one on an archived board and one moved to another board are gone")
    void goneCards() {
        api.on("GET", "/cards/6b0000000000000000000009").status(404).text("The requested resource was not found.");
        api.on("GET", "/cards/6b0000000000000000000008").json("{'id':'x','name':'Old','closed':true,'idBoard':'" + BOARD_B + "','board':{'closed':false}}");
        api.on("GET", "/cards/6b0000000000000000000007").json("{'id':'x','name':'Old','closed':false,'idBoard':'" + BOARD_B + "','board':{'closed':true}}");
        api.on("GET", "/cards/6b0000000000000000000006").json("{'id':'x','name':'Moved','closed':false,'idBoard':'zzz','board':{'closed':false,'shortLink':'Qq'}}");
        Map<String, Object> source = Map.of("boards", BOARD_B);
        assertThatThrownBy(() -> fetch("card:6b0000000000000000000009", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("Trello no longer has card:6b0000000000000000000009.");
        assertThatThrownBy(() -> fetch("card:6b0000000000000000000008", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("Trello's card 6b0000000000000000000008 is archived.");
        assertThatThrownBy(() -> fetch("card:6b0000000000000000000007", source)).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("card:6b0000000000000000000006", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("Trello's card 6b0000000000000000000006 is no longer on a board this source reads.");
        assertThatThrownBy(() -> fetch("list:1/2", source)).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Trello source.");
    }

    @Test
    @DisplayName("registration checks each chosen board, and a refused key and token say what to do")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("boards", "AbCd1234," + BOARD_B))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.on("GET", "/boards/Gone1234").status(404).text("The requested resource was not found.");
        try (ConnectorSession s = session(Map.of("boards", "AbCd1234, Gone1234"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Trello has no board Gone1234, or the connection cannot see it.");
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
        }
        api.on("GET", "/members/me").status(401).text("invalid token");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("Trello did not accept the API key and token. Replace them in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("Trello's 429 is waited out for the window of the spent budget, and reported when that is too long")
    void rateLimits() {
        api.on("GET", "/boards/" + BOARD_B + "/cards/open").status(429).header("x-rate-limit-api-token-remaining", "0")
                .header("x-rate-limit-api-token-interval-ms", "10000")
                .json("{'error':'API_TOKEN_LIMIT_EXCEEDED','message':'Rate limit exceeded'}").then().json("[]");
        try (ConnectorSession s = session(Map.of("boards", BOARD_B))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(10));

        api.reset();
        boards();
        api.on("GET", "/boards/" + BOARD_B + "/cards/open").status(429).header("x-rate-limit-member-remaining", "0")
                .header("x-rate-limit-member-interval-ms", "900000").json("{'error':'API_TOKEN_LIMIT_EXCEEDED'}");
        try (ConnectorSession s = session(Map.of("boards", BOARD_B))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Trello is limiting how often it is asked. The next sync will try again.");
        }
        api.reset();
        boards();
        api.on("GET", "/boards/" + BOARD_B + "/cards/open").status(401).text("invalid token");
        try (ConnectorSession s = session(Map.of("boards", BOARD_B))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Trello refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("the connection's Test is whose token it is, and a connection without a key says so")
    void connectionTest() {
        api.on("GET", "/members/me").json("{'id':'m1','fullName':'Ana Silva','username':'ana'}");
        TrelloConnectionPlugin connection = new TrelloConnectionPlugin();
        ConnectionTestResult ok = connection.test(credentials(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Silva");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");

        api.reset();
        api.on("GET", "/members/me").status(401).text("invalid key");
        assertThat(connection.test(credentials(), ConnectorHttp.direct()).message())
                .isEqualTo("Trello did not accept the API key and token.");
        ConnectionTestResult noKey = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(noKey.ok()).isFalse();
        assertThat(noKey.message()).isEqualTo("This connection has no Trello API key. Add it in Settings > Connections.");
    }

    @Test
    @DisplayName("the descriptor: identity is the boards' short links or IDs, sorted, case kept - or every board")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(TrelloConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("boards", "https://trello.com/b/XyZ98765/support, AbCd1234 ,XyZ98765")));
        assertThat(plugin.identity(config)).isEqualTo("AbCd1234,XyZ98765");
        assertThat(plugin.displayName(config)).isEqualTo("Trello AbCd1234, XyZ98765");
        assertThat(plugin.webUrl(Map.of("boards", "AbCd1234"), null)).isEqualTo("https://trello.com/b/AbCd1234");
        Map<String, String> all = ConfigValidator.validate(TrelloConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("all");
        assertThat(plugin.webUrl(all, null)).isNull();
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
