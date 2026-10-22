package ai.proxyma.connectors.zendesk;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.TokenSupplier;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Zendesk connector against a loopback Zendesk: the cursor-based ticket export paged through and
 * resumed from its stored cursor, a deleted ticket (status {@code deleted}) left out of a full pass
 * and gone at its fetch, the Help Center's articles by language and section and from the incremental
 * article export, the rendered ticket and article, the agent's email and token as Basic, the export's
 * own ten-a-minute pace, and the sentences for a refused credential and a rate limit.
 */
class ZendeskConnectorTest {

    private static final String EMAIL = "ana@example.com";
    private static final String TOKEN = "zd-token";
    private static final String EXPORT = "/api/v2/incremental/tickets/cursor";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final ZendeskConnectorPlugin plugin = new ZendeskConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials basic() {
        return new ResolvedCredentials(null, ConnectionProtocol.ZENDESK, AuthKind.BASIC, api.baseUrl(), EMAIL,
                Map.of("token", TOKEN), Map.of("subdomain", "acme"), null);
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, basic());
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(ZendeskConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.ZENDESK, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String ticket(int id, String subject, String status, String updated) {
        return "{'id':" + id + ",'subject':'" + subject + "','status':'" + status + "','requester_id':7,"
                + "'updated_at':'" + updated + "'}";
    }

    @Test
    @DisplayName("a full pass reads the ticket export from the start, page by page, and leaves deleted tickets out")
    void fullTicketListing() {
        api.on("GET", EXPORT).query("start_time", "0").json("{'tickets':[" + ticket(1, "Printer jams", "open", "2026-10-01T10:00:00Z")
                        + "," + ticket(2, "Spam", "deleted", "2026-10-02T10:00:00Z") + "],"
                        + "'users':[{'id':7,'name':'Rosa Diaz'}],'after_cursor':'c1','end_of_stream':false}");
        api.on("GET", EXPORT).query("cursor", "c1").json("{'tickets':[" + ticket(3, "Refund", "solved", "2026-10-03T10:00:00Z")
                + "],'users':[],'after_cursor':'c2','end_of_stream':true}");

        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("ticket:1", "ticket:3");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("#1 Printer jams");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo(api.baseUrl() + "/agent/tickets/1");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Rosa Diaz");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(ConfigJson.parse(listing.nextCursor())).containsEntry("tickets", "c2");
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString((EMAIL + "/token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(expected));
        assertThat(api.requests(EXPORT).get(0).param("per_page")).isEqualTo("1000");
        assertThat(api.requests(EXPORT).get(0).param("include")).isEqualTo("users");
        // The export's own limit: the second page waited for its turn, six seconds after the first.
        assertThat(waits).anySatisfy(w -> assertThat(w).isGreaterThan(Duration.ofSeconds(4)));
    }

    @Test
    @DisplayName("a changes pass resumes from the stored cursor and lists a deleted ticket, whose fetch says it is gone")
    void incrementalTickets() {
        api.on("GET", EXPORT).query("cursor", "c2").json("{'tickets':[" + ticket(1, "Printer jams", "pending", "2026-10-12T10:00:00Z")
                + "," + ticket(2, "Spam", "deleted", "2026-10-12T11:00:00Z") + "],'users':[],'after_cursor':'c3','end_of_stream':true}");
        String stored = ConfigJson.write(Map.of("tickets", "c2"));
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), stored));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("ticket:1", "ticket:2");
            assertThat(ConfigJson.parse(listing.nextCursor())).containsEntry("tickets", "c3");
        }
        assertThat(api.requests(EXPORT).get(0).param("start_time")).isNull();

        // Deleted: Zendesk answers the ticket with status deleted, or not at all.
        api.on("GET", "/api/v2/tickets/2").json("{'ticket':{'id':2,'status':'deleted'}}");
        assertThatThrownBy(() -> fetch("ticket:2")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("ticket:9")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:1")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Zendesk source.");
    }

    @Test
    @DisplayName("without a cursor - or with one Zendesk no longer takes - a changes pass asks from a minute before the last pass")
    void incrementalByTime() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        long expected = since.minus(ZendeskSession.OVERLAP).getEpochSecond();
        api.on("GET", EXPORT).json("{'tickets':[],'after_cursor':'c9','end_of_stream':true}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(drain(s.list(ListRequest.changes(since, null)))).isEmpty();
        }
        assertThat(api.requests(EXPORT).get(0).param("start_time")).isEqualTo(String.valueOf(expected));

        api.reset();
        api.on("GET", EXPORT).query("cursor", "stale").status(422).json("{'error':'InvalidCursor'}");
        api.on("GET", EXPORT).query("start_time", String.valueOf(expected))
                .json("{'tickets':[" + ticket(4, "Wifi", "open", "2026-10-12T10:00:00Z") + "],'after_cursor':'c10','end_of_stream':true}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(since, ConfigJson.write(Map.of("tickets", "stale"))));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("ticket:4");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(ConfigJson.parse(listing.nextCursor())).containsEntry("tickets", "c10");
        }
    }

    @Test
    @DisplayName("a ticket is one markdown document: its fields and its conversation, internal notes only when chosen")
    void fetchTicket() throws IOException {
        api.on("GET", "/api/v2/tickets/1").json("{'ticket':{'id':1,'subject':'Printer jams','status':'open','priority':'high',"
                + "'type':'incident','requester_id':7,'assignee_id':8,'group_id':30,'organization_id':40,'tags':['hardware','floor2'],"
                + "'created_at':'2026-10-01T09:00:00Z','updated_at':'2026-10-01T10:00:00Z'},"
                + "'users':[{'id':7,'name':'Rosa Diaz'},{'id':8,'name':'Jake Peralta'}],"
                + "'groups':[{'id':30,'name':'Hardware'}],'organizations':[{'id':40,'name':'Nine-Nine'}]}");
        api.on("GET", "/api/v2/tickets/1/comments").json("{'comments':[{'id':100,'author_id':7,'public':true,"
                        + "'plain_body':'The printer on floor two jams on every page.','created_at':'2026-10-01T09:00:00Z'},"
                        + "{'id':101,'author_id':8,'public':false,'plain_body':'Toner vendor is late again.','created_at':'2026-10-01T09:10:00Z'}],"
                        + "'users':[{'id':7,'name':'Rosa Diaz'},{'id':8,'name':'Jake Peralta'}],"
                        + "'meta':{'has_more':true},'links':{'next':'" + api.url("/api/v2/tickets/1/comments?page%5Bafter%5D=x") + "'}}");
        api.on("GET", "/api/v2/tickets/1/comments").query("page[after]", "x").json("{'comments':[{'id':102,'author_id':8,"
                + "'public':true,'body':'A technician comes on Monday.','created_at':'2026-10-01T09:20:00Z'}],"
                + "'users':[{'id':8,'name':'Jake Peralta'}],'meta':{'has_more':false},'links':{'next':null}}");

        String md = text(fetch("ticket:1"));
        assertThat(md).startsWith("# #1 Printer jams")
                .contains("- Type: Ticket (incident)", "- Status: open", "- Priority: high", "- Requester: Rosa Diaz",
                        "- Assignee: Jake Peralta", "- Group: Hardware", "- Organization: Nine-Nine", "- Tags: hardware, floor2",
                        "- Link: " + api.baseUrl() + "/agent/tickets/1", "## Conversation",
                        "**Rosa Diaz**, 2026-10-01T09:00:00Z:", "The printer on floor two jams on every page.",
                        "**Jake Peralta**, 2026-10-01T09:20:00Z:", "A technician comes on Monday.")
                .doesNotContain("Toner vendor");
        assertThat(api.requests("/api/v2/tickets/1").get(0).param("include")).isEqualTo("users,groups,organizations");

        ItemMetadata item = new ItemMetadata();
        item.setId("ticket:1");
        try (ConnectorSession s = session(Map.of("internalNotes", "true"))) {
            assertThat(text(s.fetch(item))).contains("**Jake Peralta** (internal note), 2026-10-01T09:10:00Z:",
                    "Toner vendor is late again.");
        }
    }

    @Test
    @DisplayName("a full pass reads the published articles of the Help Center's default language, page by page")
    void fullArticleListing() {
        api.on("GET", "/api/v2/help_center/locales").json("{'locales':['en-us','de'],'default_locale':'en-us'}");
        api.on("GET", "/api/v2/help_center/en-us/articles").json("{'articles':[{'id':11,'title':'Reset a password','draft':false,"
                        + "'author_id':7,'html_url':'https://acme.zendesk.com/hc/en-us/articles/11','updated_at':'2026-09-01T10:00:00Z'},"
                        + "{'id':12,'title':'Unfinished','draft':true}],'users':[{'id':7,'name':'Rosa Diaz'}],"
                        + "'meta':{'has_more':true},'links':{'next':'" + api.url("/api/v2/help_center/en-us/articles?page%5Bafter%5D=a") + "'}}");
        api.on("GET", "/api/v2/help_center/en-us/articles").query("page[after]", "a").json("{'articles':[{'id':13,"
                + "'title':'Set up VPN','draft':false,'updated_at':'2026-09-02T10:00:00Z'}],'meta':{'has_more':false}}");

        try (ConnectorSession s = session(Map.of("tickets", "false", "articles", "true"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("article:en-us:11", "article:en-us:13");
            assertThat(items.get(0).getTitle()).isEqualTo("Reset a password");
            assertThat(items.get(0).getExtra("url")).isEqualTo("https://acme.zendesk.com/hc/en-us/articles/11");
            assertThat(items.get(0).getExtra(ItemMetadata.AUTHOR)).isEqualTo("Rosa Diaz");
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(api.requests(EXPORT)).isEmpty();
    }

    @Test
    @DisplayName("chosen languages and sections are read section by section")
    void articleSections() {
        api.on("GET", "/api/v2/help_center/de/sections/55/articles").json("{'articles':[{'id':21,'title':'Drucker','draft':false,"
                + "'updated_at':'2026-09-01T10:00:00Z'}],'meta':{'has_more':false}}");
        try (ConnectorSession s = session(Map.of("tickets", "false", "articles", "true", "locales", "DE", "sections", "55"))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("article:de:21");
        }
        assertThat(api.requests("/api/v2/help_center/locales")).isEmpty();
    }

    @Test
    @DisplayName("a changes pass reads the incremental article export from where it last ended, and keeps its end_time")
    void incrementalArticles() {
        api.on("GET", "/api/v2/help_center/incremental/articles").json("{'articles':["
                + "{'id':11,'title':'Reset a password','locale':'en-us','section_id':55,'draft':false,'updated_at':'2026-10-12T10:00:00Z'},"
                + "{'id':12,'title':'Unfinished','locale':'en-us','section_id':55,'draft':true,'updated_at':'2026-10-12T10:05:00Z'},"
                + "{'id':14,'title':'Passwort','locale':'de','section_id':55,'draft':false,'updated_at':'2026-10-12T10:06:00Z'}],"
                + "'end_time':1760263560,'next_page':'" + api.url("/api/v2/help_center/incremental/articles?start_time=1760263560") + "'}");
        String stored = ConfigJson.write(Map.of("articles", "1760260000"));
        try (ConnectorSession s = session(Map.of("tickets", "false", "articles", "true", "locales", "en-us"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), stored));
            // A draft is listed so its fetch can say it is gone; another language is not this source's.
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("article:en-us:11", "article:en-us:12");
            assertThat(ConfigJson.parse(listing.nextCursor())).containsEntry("articles", "1760263560");
        }
        assertThat(api.requests("/api/v2/help_center/incremental/articles")).hasSize(1);
        assertThat(api.requests("/api/v2/help_center/incremental/articles").get(0).param("start_time")).isEqualTo("1760260000");

        api.on("GET", "/api/v2/help_center/en-us/articles/12").json("{'article':{'id':12,'draft':true}}");
        assertThatThrownBy(() -> fetch("article:en-us:12")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("article:en-us:99")).isInstanceOf(ItemGoneException.class);
    }

    @Test
    @DisplayName("an article is rendered from its HTML body, with its section, author and link")
    void fetchArticle() throws IOException {
        api.on("GET", "/api/v2/help_center/en-us/articles/11").json("{'article':{'id':11,'title':'Reset a password','draft':false,"
                + "'locale':'en-us','section_id':55,'author_id':7,'label_names':['account'],'edited_at':'2026-09-01T10:00:00Z',"
                + "'html_url':'https://acme.zendesk.com/hc/en-us/articles/11',"
                + "'body':'<h2>Steps</h2><ol><li>Open <a href=\\\"https://acme.example/account\\\">your account</a></li>"
                + "<li>Choose <strong>Reset</strong> &amp; confirm</li></ol><p>Done.</p>'},"
                + "'users':[{'id':7,'name':'Rosa Diaz'}],'sections':[{'id':55,'name':'Accounts'}]}");
        String md = text(fetch("article:en-us:11"));
        assertThat(md).startsWith("# Reset a password")
                .contains("- Type: Help Center article", "- Section: Accounts", "- Author: Rosa Diaz", "- Language: en-us",
                        "- Labels: account", "- Link: https://acme.zendesk.com/hc/en-us/articles/11", "## Steps",
                        "- Open [your account](https://acme.example/account)", "- Choose **Reset** & confirm", "Done.");
    }

    @Test
    @DisplayName("a refused credential and a rate limit say what to do; a short Retry-After is waited out")
    void refusalsAndRateLimits() {
        api.on("GET", EXPORT).status(401).json("{'error':'Couldn\\u0027t authenticate you'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Zendesk refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
        }

        api.reset();
        waits.clear();
        api.on("GET", EXPORT).status(429).header("Retry-After", "2").json("{'error':'TooManyRequests'}")
                .then().json("{'tickets':[],'after_cursor':'c1','end_of_stream':true}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(2));

        api.reset();
        api.on("GET", EXPORT).status(429).header("Retry-After", "3600").json("{'error':'TooManyRequests'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Zendesk is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("registration checks what the source reads: a choice, the ticket list, the languages and sections")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("tickets", "false"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Choose tickets, Help Center articles or both.");
        }
        api.on("GET", "/api/v2/tickets").json("{'tickets':[]}");
        api.on("GET", "/api/v2/help_center/locales").json("{'locales':['en-us'],'default_locale':'en-us'}");
        api.on("GET", "/api/v2/help_center/sections/55").json("{'section':{'id':55}}");
        try (ConnectorSession s = session(Map.of("articles", "true", "sections", "55"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("articles", "true", "locales", "fr"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Help Center has no articles in fr. It has en-us.");
        }
        try (ConnectorSession s = session(Map.of("articles", "true", "sections", "56"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Help Center has no section 56.");
        }
        api.reset();
        api.on("GET", "/api/v2/tickets").status(403).json("{'error':'Forbidden'}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).startsWith("Zendesk refused the connection's agent.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is sent as a bearer, and refreshed once when Zendesk refuses it")
    void signInIsABearer() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.ZENDESK, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of("subdomain", "acme"), tokens);
        api.on("GET", "/api/v2/tickets").status(401).json("{'error':'invalid_token'}").then().json("{'tickets':[]}");
        try (ConnectorSession s = session(Map.of(), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test names the agent and the account, and refuses an anonymous answer")
    void connectionTest() {
        ZendeskConnectionPlugin connection = new ZendeskConnectionPlugin();
        api.on("GET", "/api/v2/users/me").json("{'user':{'id':7,'name':'Rosa Diaz','email':'rosa@example.com','role':'agent'}}");
        ConnectionTestResult ok = connection.test(basic(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Rosa Diaz on acme");
        assertThat(ok.template()).isEqualTo("Signed in as {user} on {site}");

        api.reset();
        api.on("GET", "/api/v2/users/me").json("{'user':{'id':null,'name':'Anonymous user','role':'end-user'}}");
        ConnectionTestResult anonymous = connection.test(basic(), ConnectorHttp.direct());
        assertThat(anonymous.ok()).isFalse();
        assertThat(anonymous.message()).isEqualTo("Zendesk did not accept the email address and API token.");

        api.reset();
        api.on("GET", "/api/v2/users/me").status(401).json("{'error':'Couldn\\u0027t authenticate you'}");
        assertThat(connection.test(basic(), ConnectorHttp.direct()).message())
                .isEqualTo("Zendesk did not accept the email address and API token.");

        ResolvedCredentials noAccount = new ResolvedCredentials(null, ConnectionProtocol.ZENDESK, AuthKind.BASIC, null, EMAIL,
                Map.of("token", TOKEN), Map.of(), null);
        assertThat(connection.test(noAccount, ConnectorHttp.direct()).message())
                .isEqualTo("This connection has no Zendesk subdomain. Add it in Settings > Connections, "
                        + "or for your own domain, the Zendesk address under More options.");

        // Nothing at a typed address: the sentence names the address, not the subdomain beside it.
        api.reset();
        ConnectionTestResult nothing = connection.test(basic(), ConnectorHttp.direct());
        assertThat(nothing.template()).isEqualTo("Zendesk has nothing at {address}. Check the connection's address.");
        assertThat(nothing.message()).isEqualTo("Zendesk has nothing at " + api.baseUrl() + ". Check the connection's address.");
    }

    @Test
    @DisplayName("a Zendesk address copied from the browser is the account's root, where the API is")
    void pastedAddresses() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("https://acme.zendesk.com/api/v2/", "https://acme.zendesk.com");
        cases.put("https://Acme.zendesk.com/agent/tickets/12", "https://acme.zendesk.com");
        cases.put("https://acme.zendesk.com/hc/en-us/articles/360001", "https://acme.zendesk.com");
        cases.put("https://support.acme.example/hc/en-us", "https://support.acme.example");
        cases.put("https://support.acme.example/agent/dashboard", "https://support.acme.example");
        cases.put("https://support.acme.example/access/unauthenticated?return_to=x", "https://support.acme.example");
        cases.put("https://support.acme.example/admin/home", "https://support.acme.example");
        cases.put("https://support.acme.example", "https://support.acme.example");
        // A test server or a relay under a path of its own keeps it.
        cases.put("http://127.0.0.1:8798/zendesk/agent/tickets/1", "http://127.0.0.1:8798/zendesk");
        cases.forEach((typed, account) -> assertThat(ZendeskApi.trimAddress(typed)).as(typed).isEqualTo(account));

        // Every call goes to the root's /api/v2, whatever page was typed.
        api.on("GET", "/api/v2/users/me").json("{'user':{'id':7,'name':'Rosa Diaz'}}");
        ResolvedCredentials pasted = new ResolvedCredentials(null, ConnectionProtocol.ZENDESK, AuthKind.BASIC,
                api.baseUrl() + "/agent/tickets/1", EMAIL, Map.of("token", TOKEN), Map.of("subdomain", "acme"), null);
        assertThat(new ZendeskConnectionPlugin().test(pasted, ConnectorHttp.direct()).message())
                .isEqualTo("Signed in as Rosa Diaz on acme");
    }

    @Test
    @DisplayName("a sign-in goes to the connection's own account on zendesk.com, and nowhere else")
    void signInAddresses() {
        ZendeskConnectionPlugin connection = new ZendeskConnectionPlugin();
        OAuth2Spec acme = connection.oauth2(Map.of("subdomain", "Acme"), null);
        assertThat(acme.authorizeUrl()).isEqualTo("https://acme.zendesk.com/oauth/authorizations/new");
        assertThat(acme.tokenUrl()).isEqualTo("https://acme.zendesk.com/oauth/tokens");
        assertThat(acme.scopes()).containsExactly("read");
        assertThat(connection.oauth2(Map.of(), "https://beta.zendesk.com/").tokenUrl())
                .isEqualTo("https://beta.zendesk.com/oauth/tokens");
        // A typed address off zendesk.com, or a subdomain that is not one, never receives the client secret.
        assertThat(connection.oauth2(Map.of(), "https://evil.example")).isNull();
        assertThat(connection.oauth2(Map.of("subdomain", "evil.example/x?"), null)).isNull();
        assertThat(connection.oauth2(Map.of(), null)).isNull();
        // On Enterprise Server a sign-in's fields are not shown, so its subdomain cannot be required:
        // the Zendesk address names the account there.
        assertThat(ZendeskConnectionPlugin.DESCRIPTOR.auth(AuthKind.OAUTH2_CODE).fields())
                .filteredOn(f -> f.key().equals("subdomain")).singleElement().satisfies(f -> assertThat(f.required()).isFalse());
        assertThat(ZendeskConnectionPlugin.DESCRIPTOR.auth(AuthKind.BASIC).fields())
                .filteredOn(f -> f.key().equals("subdomain")).singleElement().satisfies(f -> assertThat(f.required()).isTrue());
        // A test server, set by the operator.
        assertThat(new ZendeskConnectionPlugin("http://127.0.0.1:8798/zendesk/").oauth2(Map.of(), null).tokenUrl())
                .isEqualTo("http://127.0.0.1:8798/zendesk/oauth/tokens");
    }

    @Test
    @DisplayName("the subdomain may be typed as the label or pasted as the whole address")
    void subdomains() {
        assertThat(ZendeskApi.subdomain(Map.of("subdomain", "acme"))).isEqualTo("acme");
        assertThat(ZendeskApi.subdomain(Map.of("subdomain", "https://Acme.zendesk.com/agent/tickets"))).isEqualTo("acme");
        assertThat(ZendeskApi.subdomain(Map.of("subdomain", "acme.zendesk.com"))).isEqualTo("acme");
        assertThat(ZendeskApi.subdomain(Map.of("subdomain", "-bad"))).isNull();
        assertThat(ZendeskApi.trimAddress("https://acme.zendesk.com/api/v2/")).isEqualTo("https://acme.zendesk.com");
        ResolvedCredentials bySubdomain = new ResolvedCredentials(null, ConnectionProtocol.ZENDESK, AuthKind.BASIC, null, EMAIL,
                Map.of("token", TOKEN), Map.of("subdomain", "acme"), null);
        assertThat(ZendeskApi.accountUrl(bySubdomain)).isEqualTo("https://acme.zendesk.com");
    }

    @Test
    @DisplayName("the descriptor: identity is what the source reads, its languages and sections sorted")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(ZendeskConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("articles", "true", "locales", " de, EN-us ,de", "sections", "9, 3")));
        assertThat(plugin.identity(config)).isEqualTo("tickets+articles(de,en-us;3,9)");
        assertThat(plugin.displayName(config)).isEqualTo("Zendesk tickets and articles");
        Map<String, String> tickets = ConfigValidator.validate(ZendeskConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("internalNotes", "true"));
        assertThat(plugin.identity(tickets)).isEqualTo("tickets");
        assertThat(plugin.displayName(tickets)).isEqualTo("Zendesk tickets");
        assertThat(plugin.webUrl(tickets, "https://acme.zendesk.com/api/v2")).isEqualTo("https://acme.zendesk.com/agent");
        assertThat(plugin.webUrl(tickets, null)).isNull();
        assertThatThrownBy(() -> ConfigValidator.validate(ZendeskConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("sections", "https://acme.zendesk.com/hc/sections/55")))
                .isInstanceOf(IllegalArgumentException.class);
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
    @Test
    @DisplayName("Zendesk's minute budget slows the pass from twenty left - by its ratelimit-reset, else over a whole minute")
    void pacing() {
        java.time.Instant now = java.time.Instant.parse("2026-10-14T08:00:00Z");
        assertThat(ZendeskApi.PACING.holdNext(budget("ratelimit-remaining", "300", "ratelimit-reset", "30"), now)).isEmpty();
        assertThat(ZendeskApi.PACING.holdNext(budget("ratelimit-remaining", "19", "ratelimit-reset", "20"), now))
                .contains(Duration.ofSeconds(1));
        assertThat(ZendeskApi.PACING.holdNext(budget("X-Rate-Limit", "700", "X-Rate-Limit-Remaining", "11"), now))
                .contains(Duration.ofSeconds(5));
    }

    private static ai.proxyma.core.connector.spi.ApiResponse budget(String... headers) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        for (int i = 0; i + 1 < headers.length; i += 2) h.add(headers[i], headers[i + 1]);
        return new ai.proxyma.core.connector.spi.ApiResponse(null, 200, h, new byte[0]);
    }
}
