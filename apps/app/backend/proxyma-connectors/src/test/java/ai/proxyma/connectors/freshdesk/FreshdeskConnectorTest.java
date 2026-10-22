package ai.proxyma.connectors.freshdesk;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Freshdesk connector against a loopback Freshdesk: tickets by {@code updated_since}, paged by
 * the Link header and moved on to a new window at Freshdesk's 300-page ceiling, deleted and spam
 * tickets left out and gone at their fetch, the conversation with private notes only when chosen,
 * the knowledge base category by category and folder by folder, the API key as {@code KEY:X} Basic,
 * and the per-minute rate limit - Retry-After, or an exhausted X-Ratelimit-Remaining.
 */
class FreshdeskConnectorTest {

    private static final String KEY = "fd-key";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final FreshdeskConnectorPlugin plugin = new FreshdeskConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials key() {
        return new ResolvedCredentials(null, ConnectionProtocol.FRESHDESK, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("token", KEY), Map.of("domain", "acme"), null);
    }

    private FreshdeskSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(FreshdeskConnectorPlugin.DESCRIPTOR.fields(), fields);
        return (FreshdeskSession) plugin.open(SourceContext.of(ConnectorType.FRESHDESK, config, key(), ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String ticket(int id, String subject, String updated, String extra) {
        return "{'id':" + id + ",'subject':'" + subject + "','updated_at':'" + updated + "','requester':{'name':'Mai Tran'}"
                + extra + "}";
    }

    private String next(String query) {
        return "<" + api.url("/api/v2/tickets?" + query) + ">; rel=\"next\"";
    }

    @Test
    @DisplayName("a full pass lists every ticket from 1970, page by page, and leaves deleted and spam tickets out")
    void fullTicketListing() {
        api.on("GET", "/api/v2/tickets").query("page", "1").json("[" + ticket(1, "Broken charger", "2026-10-01T10:00:00Z", "")
                + "," + ticket(2, "Win a prize", "2026-10-02T10:00:00Z", ",'spam':true") + "]", "Link", next("page=2"));
        api.on("GET", "/api/v2/tickets").query("page", "2").json("[" + ticket(3, "Late delivery", "2026-10-03T10:00:00Z", "")
                + "," + ticket(4, "Duplicate", "2026-10-03T11:00:00Z", ",'deleted':true") + "]");

        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("ticket:1", "ticket:3");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("#1 Broken charger");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo(api.baseUrl() + "/a/tickets/1");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Mai Tran");
            assertThat(listing.hasErrored()).isFalse();
        }
        String basic = "Basic " + Base64.getEncoder().encodeToString((KEY + ":X").getBytes(StandardCharsets.UTF_8));
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(basic));
        LoopbackApi.Recorded ask = api.requests("/api/v2/tickets").get(0);
        assertThat(ask.param("updated_since")).isEqualTo("1970-01-01T00:00:00Z");
        assertThat(ask.param("order_by")).isEqualTo("updated_at");
        assertThat(ask.param("order_type")).isEqualTo("asc");
        assertThat(ask.param("per_page")).isEqualTo("100");
        assertThat(ask.param("include")).isEqualTo("requester");
    }

    @Test
    @DisplayName("at Freshdesk's last page a listing moves its window on to the last change it saw, a ticket seen twice listed once")
    void windowMovesOn() {
        api.on("GET", "/api/v2/tickets").query("updated_since", "1970-01-01T00:00:00Z").json("["
                + ticket(1, "One", "2026-10-01T10:00:00Z", "") + "," + ticket(2, "Two", "2026-10-02T10:00:00Z", "") + "]",
                "Link", next("page=2"));
        api.on("GET", "/api/v2/tickets").query("updated_since", "2026-10-02T10:00:00Z").json("["
                + ticket(2, "Two", "2026-10-02T10:00:00Z", "") + "," + ticket(3, "Three", "2026-10-03T10:00:00Z", "") + "]");
        FreshdeskSession s = session(Map.of());
        s.maxPage = 1;
        try (s) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("ticket:1", "ticket:2", "ticket:3");
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(api.requests("/api/v2/tickets")).extracting(r -> r.param("page")).containsExactly("1", "1");
    }

    @Test
    @DisplayName("a changes pass asks for tickets updated since a minute before the last pass")
    void incrementalTickets() {
        api.on("GET", "/api/v2/tickets").json("[" + ticket(5, "Refund", "2026-10-12T10:00:00Z", "") + "]");
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.changes(since, null)))).extracting(ItemMetadata::getId).containsExactly("ticket:5");
        }
        assertThat(api.requests("/api/v2/tickets").get(0).param("updated_since"))
                .isEqualTo(since.minus(FreshdeskSession.OVERLAP).toString());
    }

    @Test
    @DisplayName("a ticket is one markdown document: its fields, its description and its conversation, private notes only when chosen")
    void fetchTicket() throws IOException {
        api.on("GET", "/api/v2/tickets/1").json("{'id':1,'subject':'Broken charger','status':2,'priority':3,'type':'Problem',"
                + "'requester_id':7,'requester':{'name':'Mai Tran'},'responder_id':8,'tags':['hardware'],"
                + "'created_at':'2026-10-01T09:00:00Z','updated_at':'2026-10-01T10:00:00Z',"
                + "'description_text':'The charger stopped working after a week.'}");
        api.on("GET", "/api/v2/agents/8").json("{'id':8,'contact':{'name':'Linh Vo'}}");
        api.on("GET", "/api/v2/contacts/9").json("{'id':9,'name':'Bao Nguyen'}");
        api.on("GET", "/api/v2/tickets/1/conversations").json("[{'id':100,'user_id':8,'private':false,'source':0,"
                        + "'body_text':'Please send a photo of the label.','created_at':'2026-10-01T09:10:00Z'},"
                        + "{'id':101,'user_id':8,'private':true,'source':2,'body_text':'Batch 42 is faulty.','created_at':'2026-10-01T09:20:00Z'}]",
                "Link", "<" + api.url("/api/v2/tickets/1/conversations?page=2") + ">; rel=\"next\"");
        api.on("GET", "/api/v2/tickets/1/conversations").query("page", "2").json("[{'id':102,'user_id':9,'private':false,"
                + "'source':2,'body_text':'Forwarded to the warehouse.','created_at':'2026-10-01T09:30:00Z'}]");

        String md = text(fetch("ticket:1", Map.of()));
        assertThat(md).startsWith("# #1 Broken charger")
                .contains("- Type: Ticket (Problem)", "- Status: Open", "- Priority: High", "- Requester: Mai Tran",
                        "- Agent: Linh Vo", "- Tags: hardware", "- Link: " + api.baseUrl() + "/a/tickets/1",
                        "## Description", "The charger stopped working after a week.", "## Conversation",
                        "**Linh Vo**, 2026-10-01T09:10:00Z:", "Please send a photo of the label.",
                        "**Bao Nguyen** (note), 2026-10-01T09:30:00Z:", "Forwarded to the warehouse.")
                .doesNotContain("Batch 42");
        // A person who is not an agent is looked up as a contact - each once.
        assertThat(api.requests("/api/v2/agents/9")).hasSize(1);
        assertThat(api.requests("/api/v2/agents/8")).hasSize(1);

        assertThat(text(fetch("ticket:1", Map.of("privateNotes", "true"))))
                .contains("**Linh Vo** (private note), 2026-10-01T09:20:00Z:", "Batch 42 is faulty.");
    }

    @Test
    @DisplayName("a deleted, spam or unknown ticket is gone - removed at once rather than retried")
    void goneTickets() {
        api.on("GET", "/api/v2/tickets/4").json("{'id':4,'deleted':true}");
        api.on("GET", "/api/v2/tickets/2").json("{'id':2,'spam':true}");
        assertThatThrownBy(() -> fetch("ticket:4", Map.of())).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("ticket:2", Map.of())).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("ticket:9", Map.of())).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:1", Map.of())).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Freshdesk source.");
    }

    @Test
    @DisplayName("a full pass reads every category's folders and subfolders, published articles only")
    void fullArticleListing() {
        api.on("GET", "/api/v2/solutions/categories").json("[{'id':10,'name':'Getting started'}]");
        api.on("GET", "/api/v2/solutions/categories/10/folders").json("[{'id':20,'name':'Accounts'}]");
        api.on("GET", "/api/v2/solutions/folders/20/articles").json("[{'id':31,'title':'Reset a password','status':2,"
                + "'updated_at':'2026-09-01T10:00:00Z'},{'id':32,'title':'Unfinished','status':1,'updated_at':'2026-09-02T10:00:00Z'}]");
        api.on("GET", "/api/v2/solutions/folders/20/subfolders").json("[{'id':21,'name':'Security'}]");
        api.on("GET", "/api/v2/solutions/folders/21/articles").json("[{'id':33,'title':'Two-factor sign-in','status':2,"
                + "'updated_at':'2026-09-03T10:00:00Z'}]");
        // Folder 21 has no subfolders route: Freshdesk's 404 is an empty list.

        try (ConnectorSession s = session(Map.of("tickets", "false", "articles", "true"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("article:31", "article:33");
            assertThat(items.get(0).getTitle()).isEqualTo("Reset a password");
            assertThat(items.get(0).getExtra("url")).isEqualTo(api.baseUrl() + "/support/solutions/articles/31");
            assertThat(listing.hasErrored()).isFalse();
        }
    }

    @Test
    @DisplayName("a changes pass lists the articles changed since the last pass, a draft among them so its fetch says gone")
    void incrementalArticles() {
        api.on("GET", "/api/v2/solutions/categories/10/folders").json("[{'id':20}]");
        api.on("GET", "/api/v2/solutions/folders/20/articles").json("[{'id':31,'title':'Old','status':2,"
                + "'updated_at':'2026-09-01T10:00:00Z'},{'id':32,'title':'Pulled','status':1,'updated_at':'2026-10-12T10:00:00Z'},"
                + "{'id':34,'title':'New','status':2,'updated_at':'2026-10-12T11:00:00Z'}]");
        api.on("GET", "/api/v2/solutions/folders/20/subfolders").json("[]");
        try (ConnectorSession s = session(Map.of("tickets", "false", "articles", "true", "categories", "10"))) {
            assertThat(drain(s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), null))))
                    .extracting(ItemMetadata::getId).containsExactly("article:32", "article:34");
        }
        assertThat(api.requests("/api/v2/solutions/categories")).isEmpty();

        api.on("GET", "/api/v2/solutions/articles/32").json("{'id':32,'status':1,'category_id':10}");
        api.on("GET", "/api/v2/solutions/articles/35").json("{'id':35,'status':2,'category_id':11}");
        assertThatThrownBy(() -> fetch("article:32", Map.of())).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("article:35", Map.of("categories", "10"))).isInstanceOf(ItemGoneException.class);
    }

    @Test
    @DisplayName("an article is rendered from its text, with its category, folder and author")
    void fetchArticle() throws IOException {
        api.on("GET", "/api/v2/solutions/articles/31").json("{'id':31,'title':'Reset a password','status':2,'category_id':10,"
                + "'folder_id':20,'agent_id':8,'tags':['account'],'updated_at':'2026-09-01T10:00:00Z',"
                + "'description_text':'Open Settings, then choose Reset.'}");
        api.on("GET", "/api/v2/solutions/categories/10").json("{'id':10,'name':'Getting started'}");
        api.on("GET", "/api/v2/solutions/folders/20").json("{'id':20,'name':'Accounts'}");
        api.on("GET", "/api/v2/agents/8").json("{'id':8,'contact':{'name':'Linh Vo'}}");
        assertThat(text(fetch("article:31", Map.of()))).startsWith("# Reset a password")
                .contains("- Type: Knowledge base article", "- Category: Getting started", "- Folder: Accounts",
                        "- Author: Linh Vo", "- Tags: account", "- Link: " + api.baseUrl() + "/support/solutions/articles/31",
                        "Open Settings, then choose Reset.");
    }

    @Test
    @DisplayName("the per-minute limit is waited out - by Retry-After, or a minute when only X-Ratelimit-Remaining says so")
    void rateLimits() {
        api.on("GET", "/api/v2/tickets").status(429).header("Retry-After", "5").json("{'code':'rate_limited'}")
                .then().json("[]");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(5));

        api.reset();
        waits.clear();
        api.on("GET", "/api/v2/tickets").status(429).header("X-Ratelimit-Total", "200").header("X-Ratelimit-Remaining", "0")
                .json("{'code':'rate_limited'}").then().json("[]");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofMinutes(1));

        api.reset();
        api.on("GET", "/api/v2/tickets").status(429).header("Retry-After", "3600").json("{'code':'rate_limited'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Freshdesk is limiting how often it is asked. The next sync will try again.");
        }

        api.reset();
        api.on("GET", "/api/v2/tickets").status(401).json("{'code':'invalid_credentials'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Freshdesk refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
            assertThat(s.verifyAccess().detail()).isEqualTo("Freshdesk did not accept the API key. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("registration checks what the source reads: a choice, the ticket list and the categories")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("tickets", "false"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Choose tickets, knowledge base articles or both.");
        }
        api.on("GET", "/api/v2/tickets").json("[]");
        api.on("GET", "/api/v2/solutions/categories/10").json("{'id':10}");
        try (ConnectorSession s = session(Map.of("articles", "true", "categories", "10"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("articles", "true", "categories", "11"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The knowledge base has no category 11.");
        }
    }

    @Test
    @DisplayName("the connection's Test names the agent the key belongs to")
    void connectionTest() {
        FreshdeskConnectionPlugin connection = new FreshdeskConnectionPlugin();
        api.on("GET", "/api/v2/agents/me").json("{'id':8,'contact':{'name':'Linh Vo','email':'linh@example.com'}}");
        ConnectionTestResult ok = connection.test(key(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Linh Vo");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");

        api.reset();
        api.on("GET", "/api/v2/agents/me").status(401).json("{'code':'invalid_credentials'}");
        assertThat(connection.test(key(), ConnectorHttp.direct()).message()).isEqualTo("Freshdesk did not accept the API key.");

        ResolvedCredentials noAccount = new ResolvedCredentials(null, ConnectionProtocol.FRESHDESK, AuthKind.TOKEN, null, null,
                Map.of("token", KEY), Map.of(), null);
        assertThat(connection.test(noAccount, ConnectorHttp.direct()).message())
                .isEqualTo("This connection has no Freshdesk domain. Add it in Settings > Connections, "
                        + "or for your own domain, the Freshdesk address under More options.");
        ResolvedCredentials byDomain = new ResolvedCredentials(null, ConnectionProtocol.FRESHDESK, AuthKind.TOKEN, null, null,
                Map.of("token", KEY), Map.of("domain", "https://Acme.freshdesk.com/a/"), null);
        assertThat(FreshdeskApi.accountUrl(byDomain)).isEqualTo("https://acme.freshdesk.com");

        // Nothing at a typed address: the sentence names the address, not a domain nobody typed.
        api.reset();
        ConnectionTestResult nothing = connection.test(key(), ConnectorHttp.direct());
        assertThat(nothing.template()).isEqualTo("Freshdesk has nothing at {address}. Check the connection's address.");
        assertThat(nothing.message()).isEqualTo("Freshdesk has nothing at " + api.baseUrl() + ". Check the connection's address.");
        ResolvedCredentials byDomainOnly = new ResolvedCredentials(null, ConnectionProtocol.FRESHDESK, AuthKind.TOKEN, null, null,
                Map.of("token", KEY), Map.of("domain", "acme"), null);
        assertThat(FreshdeskApi.accountUrl(byDomainOnly)).isEqualTo("https://acme.freshdesk.com");
    }

    @Test
    @DisplayName("a Freshdesk address copied from the browser is the account's root, where the API is")
    void pastedAddresses() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("https://acme.freshdesk.com/api/v2/", "https://acme.freshdesk.com");
        cases.put("https://Acme.freshdesk.com/a/tickets/12?filter=open", "https://acme.freshdesk.com");
        cases.put("https://acme.freshdesk.com/support/solutions/articles/31", "https://acme.freshdesk.com");
        cases.put("https://support.acme.example/support/home", "https://support.acme.example");
        cases.put("https://support.acme.example/a/dashboard/default", "https://support.acme.example");
        cases.put("https://support.acme.example/helpdesk/tickets", "https://support.acme.example");
        cases.put("https://support.acme.example/", "https://support.acme.example");
        // A test server or a relay under a path of its own keeps it.
        cases.put("http://127.0.0.1:8798/freshdesk/a/tickets", "http://127.0.0.1:8798/freshdesk");
        cases.forEach((typed, account) -> assertThat(FreshdeskApi.trimAddress(typed)).as(typed).isEqualTo(account));

        // Every call goes to the root's /api/v2, whatever page was typed.
        api.on("GET", "/api/v2/agents/me").json("{'id':8,'contact':{'name':'Linh Vo'}}");
        ResolvedCredentials pasted = new ResolvedCredentials(null, ConnectionProtocol.FRESHDESK, AuthKind.TOKEN,
                api.baseUrl() + "/a/tickets/1", null, Map.of("token", KEY), Map.of("domain", "acme"), null);
        assertThat(new FreshdeskConnectionPlugin().test(pasted, ConnectorHttp.direct()).message()).isEqualTo("Signed in as Linh Vo");
    }

    @Test
    @DisplayName("the descriptor: identity is what the source reads, its categories sorted")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(FreshdeskConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("articles", "true", "categories", "9, 3,9")));
        assertThat(plugin.identity(config)).isEqualTo("tickets+articles(3,9)");
        assertThat(plugin.displayName(config)).isEqualTo("Freshdesk tickets and articles");
        Map<String, String> articles = ConfigValidator.validate(FreshdeskConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("tickets", "false", "articles", "true"));
        assertThat(plugin.identity(articles)).isEqualTo("articles(all)");
        assertThat(plugin.displayName(articles)).isEqualTo("Freshdesk articles");
        assertThat(plugin.webUrl(articles, "https://acme.freshdesk.com/api/v2")).isEqualTo("https://acme.freshdesk.com/support/solutions");
        assertThatThrownBy(() -> ConfigValidator.validate(FreshdeskConnectorPlugin.DESCRIPTOR.fields(), Map.of("categories", "Getting started")))
                .isInstanceOf(IllegalArgumentException.class);
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
    @Test
    @DisplayName("Freshdesk states what is left of the minute and never when it ends: from ten left, the minute is spread over them")
    void pacing() {
        java.time.Instant now = java.time.Instant.parse("2026-10-14T08:00:00Z");
        assertThat(FreshdeskApi.PACING.holdNext(budget("X-Ratelimit-Total", "200", "X-Ratelimit-Remaining", "150"), now)).isEmpty();
        assertThat(FreshdeskApi.PACING.holdNext(budget("X-Ratelimit-Total", "200", "X-Ratelimit-Remaining", "9"), now))
                .contains(Duration.ofSeconds(6));
    }

    private static ai.proxyma.core.connector.spi.ApiResponse budget(String... headers) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        for (int i = 0; i + 1 < headers.length; i += 2) h.add(headers[i], headers[i + 1]);
        return new ai.proxyma.core.connector.spi.ApiResponse(null, 200, h, new byte[0]);
    }
}
