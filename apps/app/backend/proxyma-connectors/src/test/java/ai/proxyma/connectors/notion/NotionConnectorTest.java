package ai.proxyma.connectors.notion;

import ai.proxyma.core.connector.AccessCheck;
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
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Notion connector against a loopback Notion: the search paged by {@code start_cursor} in a POST
 * body, the incremental pass that stops at the last one, the walk of chosen pages and databases, a
 * changes pass that keeps only what is under them, what a pass may and may not remove, the page as
 * markdown under its properties, the token and the Notion-Version on every request, and every
 * failure as a sentence.
 */
class NotionConnectorTest {

    private static final String TOKEN = "ntn_test-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    // The pages the fixtures are written with.
    private static final String HANDBOOK = "11111111-1111-1111-1111-111111111111";
    private static final String ONBOARDING = "22222222-2222-2222-2222-222222222222";
    private static final String TOGGLE = "33333333-3333-3333-3333-333333333333";
    private static final String PROJECTS_DB = "44444444-4444-4444-4444-444444444444";
    private static final String PROJECTS_DS = "55555555-5555-5555-5555-555555555555";
    private static final String APOLLO = "66666666-6666-6666-6666-666666666666";
    private static final String NOTES = "77777777-7777-7777-7777-777777777777";
    private static final String OLD = "88888888-8888-8888-8888-888888888888";
    private static final String MISSING = "99999999-9999-9999-9999-999999999999";
    private static final String ALICE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final NotionConnectorPlugin plugin = new NotionConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/users/" + ALICE).json("{'object':'user','id':'" + ALICE + "','name':'Alice Nguyen','type':'person'}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(NotionConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.NOTION, config,
                ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String page(String id, String title, String edited, String parent) {
        return "{'object':'page','id':'" + id + "','created_time':'2026-09-01T09:00:00.000Z',"
                + "'last_edited_time':'" + edited + "','created_by':{'object':'user','id':'" + ALICE + "'},"
                + "'last_edited_by':{'object':'user','id':'" + ALICE + "'},'in_trash':false,"
                + "'url':'https://www.notion.so/" + title.replace(' ', '-') + "-" + id.replace("-", "") + "',"
                + "'parent':" + parent + ","
                + "'properties':{'title':{'id':'title','type':'title','title':[{'plain_text':'" + title + "'}]}}}";
    }

    private static String workspace() {
        return "{'type':'workspace','workspace':true}";
    }

    private static String underPage(String id) {
        return "{'type':'page_id','page_id':'" + id + "'}";
    }

    // Everything shared: two pages of search results, newest edit first, one of them in the trash.
    private void searchInTwoPages() {
        api.on("POST", "/search").json("{'object':'list','results':["
                + page(NOTES, "Meeting notes", "2026-10-12T10:00:00.000Z", workspace()) + ","
                + page(HANDBOOK, "Team Handbook", "2026-10-10T10:00:00.000Z", workspace()) + "],"
                + "'has_more':true,'next_cursor':'cursor-2','request_status':{'type':'complete'}}")
                .then().json("{'object':'list','results':["
                + page(OLD, "Old plan", "2026-10-05T10:00:00.000Z", workspace()).replace("'in_trash':false", "'in_trash':true") + ","
                + page(ONBOARDING, "Onboarding", "2026-10-01T10:00:00.000Z", underPage(HANDBOOK)) + "],"
                + "'has_more':false,'next_cursor':null}");
    }

    @Test
    @DisplayName("everything shared: every page of the search, newest first, the trash left out, the token and version on every request")
    void fullListing() throws IOException {
        searchInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("page:" + NOTES, "page:" + HANDBOOK, "page:" + ONBOARDING);
            ItemMetadata notes = items.get(0);
            assertThat(notes.getTitle()).isEqualTo("Meeting notes");
            assertThat(notes.getContentType()).isEqualTo("text/markdown");
            assertThat(notes.getLastModified()).isEqualTo(Instant.parse("2026-10-12T10:00:00Z"));
            assertThat(notes.getExtra("url")).isEqualTo("https://www.notion.so/Meeting-notes-" + NOTES.replace("-", ""));
            assertThat(notes.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Alice Nguyen");
            // A complete pass: the engine may remove what it did not list.
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> {
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(r.header("Notion-Version")).isEqualTo(NotionApi.VERSION);
        });
        List<LoopbackApi.Recorded> searches = api.requests("/search");
        assertThat(searches).hasSize(2);
        JsonNode first = JSON.readTree(searches.get(0).body());
        assertThat(first.path("filter").path("value").asText()).isEqualTo("page");
        assertThat(first.path("sort").path("timestamp").asText()).isEqualTo("last_edited_time");
        assertThat(first.path("sort").path("direction").asText()).isEqualTo("descending");
        assertThat(first.path("page_size").asInt()).isEqualTo(100);
        assertThat(first.has("start_cursor")).isFalse();
        assertThat(JSON.readTree(searches.get(1).body()).path("start_cursor").asText()).isEqualTo("cursor-2");
        // A person's name is asked for once per session, however often they appear.
        assertThat(api.requests("/users/" + ALICE)).hasSize(1);
    }

    @Test
    @DisplayName("a changes pass reads the search until it reaches a minute before the last pass, and asks no further page")
    void incrementalListing() {
        searchInTwoPages();
        Instant since = Instant.parse("2026-10-10T10:00:30Z");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(since, null));
            // Handbook was edited at 10:00 - inside the minute of overlap, so it is listed again.
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("page:" + NOTES, "page:" + HANDBOOK);
        }
        // The second page's first result is older than that, and ends the listing - Onboarding is not read.
        assertThat(api.requests("/search")).hasSize(2);

        api.reset();
        searchInTwoPages();
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-11T00:00:00Z"), null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("page:" + NOTES);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(api.requests("/search")).hasSize(1);
    }

    @Test
    @DisplayName("a search Notion cut short at its result limit is a partial listing that removes nothing")
    void incompleteSearch() {
        api.on("POST", "/search").json("{'results':[" + page(NOTES, "Meeting notes", "2026-10-12T10:00:00.000Z", workspace())
                + "],'has_more':false,'request_status':{'type':'incomplete','incomplete_reason':'query_result_limit_reached'}}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).hasSize(1);
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).isEqualTo("Notion listed only part of the pages, so nothing was removed.");
        }
    }

    // Handbook holds a paragraph, a toggle with Onboarding inside it, and the Projects database,
    // whose one data source holds two rows - one archived.
    private void handbookTree() {
        api.on("GET", "/pages/" + HANDBOOK).json(page(HANDBOOK, "Team Handbook", "2026-10-10T10:00:00.000Z", workspace()));
        api.on("GET", "/blocks/" + HANDBOOK + "/children").json("{'results':["
                + "{'object':'block','id':'b1','type':'paragraph','has_children':false,'paragraph':{'rich_text':[]}},"
                + "{'object':'block','id':'" + TOGGLE + "','type':'toggle','has_children':true,'toggle':{'rich_text':[]}}],"
                + "'has_more':true,'next_cursor':'blocks-2'}");
        api.on("GET", "/blocks/" + HANDBOOK + "/children").query("start_cursor", "blocks-2").json("{'results':["
                + "{'object':'block','id':'" + PROJECTS_DB + "','type':'child_database','has_children':false,"
                + "'child_database':{'title':'Projects'}}],'has_more':false}");
        api.on("GET", "/blocks/" + TOGGLE + "/children").json("{'results':["
                + "{'object':'block','id':'" + ONBOARDING + "','type':'child_page','has_children':true,"
                + "'last_edited_time':'2026-10-01T10:00:00.000Z','created_by':{'object':'user','id':'" + ALICE + "'},"
                + "'child_page':{'title':'Onboarding'}}],'has_more':false}");
        api.on("GET", "/blocks/" + ONBOARDING + "/children").json("{'results':[],'has_more':false}");
        api.on("GET", "/databases/" + PROJECTS_DB).json("{'object':'database','id':'" + PROJECTS_DB + "',"
                + "'parent':" + underPage(HANDBOOK) + ",'data_sources':[{'id':'" + PROJECTS_DS + "','name':'Projects'}]}");
        api.on("POST", "/data_sources/" + PROJECTS_DS + "/query").json("{'results':["
                + page(APOLLO, "Apollo launch", "2026-10-11T10:00:00.000Z", "{'type':'data_source_id','data_source_id':'"
                + PROJECTS_DS + "','database_id':'" + PROJECTS_DB + "'}") + ","
                + page(OLD, "Old row", "2026-09-01T10:00:00.000Z", workspace()).replace("'in_trash':false", "'is_archived':true")
                + "],'has_more':false}");
    }

    @Test
    @DisplayName("a chosen page brings every page under it - inside toggles too - and the rows of its databases")
    void walkOfChosenPages() {
        handbookTree();
        try (ConnectorSession s = session(Map.of("pages", "https://www.notion.so/acme/Team-Handbook-" + HANDBOOK.replace("-", "")))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("page:" + HANDBOOK, "page:" + ONBOARDING, "page:" + APOLLO);
            ItemMetadata onboarding = items.get(1);
            assertThat(onboarding.getTitle()).isEqualTo("Onboarding");
            assertThat(onboarding.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(onboarding.getExtra("url")).isEqualTo("https://www.notion.so/" + ONBOARDING.replace("-", ""));
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests("/search")).isEmpty();
    }

    @Test
    @DisplayName("a chosen page the integration cannot read makes the pass partial: nothing under it is removed")
    void unreadableRoot() {
        handbookTree();
        try (ConnectorSession s = session(Map.of("pages", HANDBOOK + "\nhttps://www.notion.so/Lost-" + MISSING.replace("-", "")))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).hasSize(3);
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).isEqualTo("Notion did not let the integration read Lost, so nothing "
                    + "under it was removed. Share it with the integration again, or leave it out.");
        }
        try (ConnectorSession s = session(Map.of("pages", MISSING))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Notion integration cannot read " + MISSING
                    + ". Share each page or database with it under ... > Connections, or check the links.");
        }
    }

    @Test
    @DisplayName("with pages chosen, a changes pass keeps a changed page only when its parents lead to one of them")
    void incrementalUnderChosenPages() {
        handbookTree();
        String elsewhere = "12121212-1212-1212-1212-121212121212";
        api.on("POST", "/search").json("{'results':["
                + page(APOLLO, "Apollo launch", "2026-10-11T10:00:00.000Z", "{'type':'data_source_id','data_source_id':'"
                + PROJECTS_DS + "','database_id':'" + PROJECTS_DB + "'}") + ","
                + page(elsewhere, "Elsewhere", "2026-10-11T09:00:00.000Z", workspace()) + ","
                + page(ONBOARDING, "Onboarding", "2026-10-11T08:00:00.000Z", "{'type':'block_id','block_id':'" + TOGGLE + "'}")
                + "],'has_more':false}");
        api.on("GET", "/blocks/" + TOGGLE).json("{'object':'block','id':'" + TOGGLE + "','type':'toggle',"
                + "'parent':" + underPage(HANDBOOK) + "}");
        try (ConnectorSession s = session(Map.of("pages", HANDBOOK))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-11T00:00:00Z"), null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("page:" + APOLLO, "page:" + ONBOARDING);
        }
        // No walk on a changes pass: the search, and the parents of what it found.
        assertThat(api.requests("/blocks/" + HANDBOOK + "/children")).isEmpty();
    }

    @Test
    @DisplayName("a database row is fetched as Notion's markdown under its database and properties")
    void fetchRow() throws IOException {
        handbookTree();
        api.on("GET", "/pages/" + APOLLO).json("{'object':'page','id':'" + APOLLO + "','created_time':'2026-09-01T09:00:00.000Z',"
                + "'last_edited_time':'2026-10-11T10:00:00.000Z','created_by':{'object':'user','id':'" + ALICE + "'},"
                + "'last_edited_by':{'object':'user','id':'" + ALICE + "'},'in_trash':false,"
                + "'url':'https://www.notion.so/Apollo-launch-" + APOLLO.replace("-", "") + "',"
                + "'parent':{'type':'data_source_id','data_source_id':'" + PROJECTS_DS + "','database_id':'" + PROJECTS_DB + "'},"
                + "'properties':{"
                + "'Name':{'id':'title','type':'title','title':[{'plain_text':'Apollo launch'}]},"
                + "'Status':{'id':'s','type':'status','status':{'name':'In progress'}},"
                + "'Tags':{'id':'t','type':'multi_select','multi_select':[{'name':'launch'},{'name':'q4'}]},"
                + "'Owner':{'id':'o','type':'people','people':[{'object':'user','id':'" + ALICE + "'}]},"
                + "'Budget':{'id':'b','type':'number','number':12000},"
                + "'Due':{'id':'d','type':'date','date':{'start':'2026-11-01','end':null}},"
                + "'Shipped':{'id':'c','type':'checkbox','checkbox':false},"
                + "'Key':{'id':'k','type':'unique_id','unique_id':{'prefix':'PRJ','number':7}},"
                + "'Empty':{'id':'e','type':'rich_text','rich_text':[]}}}");
        api.on("GET", "/data_sources/" + PROJECTS_DS).json("{'object':'data_source','id':'" + PROJECTS_DS + "',"
                + "'title':[{'plain_text':'Projects'}]}");
        api.on("GET", "/pages/" + APOLLO + "/markdown").json("{'object':'page_markdown','id':'" + APOLLO + "',"
                + "'markdown':'## Plan\\n\\nThe launch codeword is SAFFRON.','truncated':false,'unknown_block_ids':[]}");

        String md = text(fetch("page:" + APOLLO, Map.of("pages", HANDBOOK)));

        assertThat(md).startsWith("# Apollo launch\n\n").contains("- Database: Projects", "- Status: In progress",
                "- Tags: launch, q4", "- Owner: Alice Nguyen", "- Budget: 12000", "- Due: 2026-11-01", "- Shipped: No",
                "- Key: PRJ-7", "- Created: 2026-09-01T09:00:00.000Z by Alice Nguyen",
                "- Link: https://www.notion.so/Apollo-launch-" + APOLLO.replace("-", ""), "## Plan", "SAFFRON");
        assertThat(md).doesNotContain("Empty").doesNotContain("- Name:");
    }

    @Test
    @DisplayName("a truncated page asks for the blocks Notion left out, and says the rest is not indexed")
    void truncatedPage() throws IOException {
        api.on("GET", "/pages/" + NOTES).json(page(NOTES, "Meeting notes", "2026-10-12T10:00:00.000Z", workspace()));
        api.on("GET", "/pages/" + NOTES + "/markdown").json("{'markdown':'First part <unknown id=\\\"x\\\"/>',"
                + "'truncated':true,'unknown_block_ids':['" + TOGGLE + "']}");
        api.on("GET", "/pages/" + TOGGLE + "/markdown").json("{'markdown':'Second part TAMARIND','truncated':false,"
                + "'unknown_block_ids':[]}");

        String md = text(fetch("page:" + NOTES, Map.of()));

        assertThat(md).contains("First part", "Second part TAMARIND",
                "(This page is longer than Notion returns at once; the rest is not indexed.)");
    }

    @Test
    @DisplayName("a page the markdown endpoint will not render is rendered from its blocks, nested ones included")
    void blockFallback() throws IOException {
        api.on("GET", "/pages/" + NOTES).json(page(NOTES, "Meeting notes", "2026-10-12T10:00:00.000Z", workspace()));
        api.on("GET", "/pages/" + NOTES + "/markdown").status(400).json("{'object':'error','code':'validation_error'}");
        api.on("GET", "/blocks/" + NOTES + "/children").json("{'results':["
                + "{'id':'h','type':'heading_2','has_children':false,'heading_2':{'rich_text':[{'plain_text':'Agenda'}]}},"
                + "{'id':'" + TOGGLE + "','type':'bulleted_list_item','has_children':true,"
                + "'bulleted_list_item':{'rich_text':[{'plain_text':'Budget','href':null}]}},"
                + "{'id':'t','type':'to_do','has_children':false,'to_do':{'checked':true,'rich_text':[{'plain_text':'Book room'}]}},"
                + "{'id':'c','type':'code','has_children':false,'code':{'language':'SQL','rich_text':[{'plain_text':'select 1'}]}}],"
                + "'has_more':false}");
        api.on("GET", "/blocks/" + TOGGLE + "/children").json("{'results':["
                + "{'id':'n','type':'bulleted_list_item','has_children':false,'bulleted_list_item':{'rich_text':"
                + "[{'plain_text':'the wiki','href':'https://wiki.example/budget'}]}}],'has_more':false}");

        String md = text(fetch("page:" + NOTES, Map.of()));

        assertThat(md).contains("## Agenda", "- Budget\n  - [the wiki](https://wiki.example/budget)", "- [x] Book room",
                "```sql\nselect 1\n```");
    }

    @Test
    @DisplayName("a page Notion no longer has, or has in the trash, is gone; one outside the chosen pages is not this source's")
    void goneAndForeignPages() {
        api.on("GET", "/pages/" + MISSING).status(404).json("{'object':'error','status':404,'code':'object_not_found'}");
        api.on("GET", "/pages/" + OLD).json(page(OLD, "Old plan", "2026-10-05T10:00:00.000Z", workspace())
                .replace("'in_trash':false", "'in_trash':true"));
        assertThatThrownBy(() -> fetch("page:" + MISSING, Map.of())).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("page:" + OLD, Map.of())).isInstanceOf(ItemGoneException.class);

        handbookTree();
        api.on("GET", "/pages/" + NOTES).json(page(NOTES, "Meeting notes", "2026-10-12T10:00:00.000Z", workspace()));
        assertThatThrownBy(() -> fetch("page:" + NOTES, Map.of("pages", HANDBOOK)))
                .isInstanceOf(ConnectorException.class).isNotInstanceOf(ItemGoneException.class)
                .hasMessage("That page is not under a page or database this Notion source reads.");
        assertThatThrownBy(() -> fetch("issue:1", Map.of())).hasMessage("That is not an item of a Notion source.");
    }

    @Test
    @DisplayName("registration: nothing shared yet, a refused token and an entry that is no Notion link each say what to do")
    void accessProbe() {
        api.on("POST", "/search").json("{'results':[],'has_more':false}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("No page is shared with the Notion integration yet. In "
                    + "Notion, open a page's ... menu, choose Connections, and add the integration.");
        }
        api.reset();
        api.on("POST", "/search").status(401).json("{'object':'error','status':401,'code':'unauthorized'}");
        try (ConnectorSession s = session(Map.of())) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Notion did not accept the token. Replace it, or sign in "
                    + "again, in Settings > Connections.");
        }
        try (ConnectorSession s = session(Map.of("pages", "my handbook"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("my handbook is not a link to a Notion page or database, or its ID.");
        }
        api.reset();
        handbookTree();
        try (ConnectorSession s = session(Map.of("pages", HANDBOOK))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
    }

    @Test
    @DisplayName("Notion's 429 is waited out for its Retry-After, and reported - not sat out - when it is too long")
    void rateLimits() {
        api.on("POST", "/search").status(429).header("Retry-After", "4").json("{'object':'error','code':'rate_limited'}")
                .then().json("{'results':[],'has_more':false}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(4));

        api.reset();
        api.on("POST", "/search").status(429).header("Retry-After", "3600").json("{'object':'error','code':'rate_limited'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Notion is limiting how often it is asked. The next sync will try again.");
        }

        // Notion's own overload is waited out like a 429; a blocked integration is reported at once.
        api.reset();
        waits.clear();
        api.on("POST", "/search").status(529).header("Retry-After", "5").json("{'object':'error','code':'service_overload'}")
                .then().json("{'results':[],'has_more':false}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(5));

        api.reset();
        api.on("POST", "/search").status(429).json("{'object':'error','code':'public_api_request_blocked'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Notion is limiting how often it is asked. The next sync will try again.");
        }
        assertThat(api.requests("/search")).hasSize(1);

        api.reset();
        api.on("POST", "/search").status(401).json("{'object':'error','code':'unauthorized'}");
        try (ConnectorSession s = session(Map.of())) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Notion refused the connection's credential. Replace it, "
                    + "or sign in again, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("the connection's Test names the workspace and the integration")
    void connectionTest() {
        api.on("GET", "/users/me").json("{'object':'user','id':'bot-1','name':'Proxyma','type':'bot',"
                + "'bot':{'owner':{'type':'workspace','workspace':true},'workspace_name':'Acme'}}");
        NotionConnectionPlugin connection = new NotionConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in to Acme as Proxyma");
        assertThat(ok.template()).isEqualTo("Signed in to {workspace} as {user}");
        assertThat(api.last().header("Notion-Version")).isEqualTo(NotionApi.VERSION);

        api.reset();
        api.on("GET", "/users/me").status(401).json("{'object':'error','code':'unauthorized'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Notion did not accept the token.");
        assertThat(refused.template()).isEqualTo("{service} did not accept the token.");
    }

    @Test
    @DisplayName("Sign in with Notion: owner=user on the authorize URL, the client as HTTP Basic, a JSON token request with the API version, no scopes")
    void signIn() {
        OAuth2Spec spec = new NotionConnectionPlugin().oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://api.notion.com/v1/oauth/authorize");
        assertThat(spec.tokenUrl()).isEqualTo("https://api.notion.com/v1/oauth/token");
        assertThat(spec.extraAuthorizeParams()).containsEntry("owner", "user");
        assertThat(spec.clientAuth()).isEqualTo(OAuth2Spec.ClientAuth.BASIC);
        // Notion documents its token endpoint with a JSON body only, versioned like the rest of its API.
        assertThat(spec.tokenBody()).isEqualTo(OAuth2Spec.TokenBody.JSON);
        assertThat(spec.tokenHeaders()).containsEntry("Notion-Version", NotionApi.VERSION);
        assertThat(spec.scopes()).isEmpty();
        assertThat(NotionConnectionPlugin.DESCRIPTOR.offers(AuthKind.TOKEN)).isTrue();
        assertThat(NotionConnectionPlugin.DESCRIPTOR.offers(AuthKind.OAUTH2_CODE)).isTrue();
    }

    @Test
    @DisplayName("the descriptor: identity is the chosen IDs, a link and its bare ID being one page, or everything")
    void descriptor() {
        Map<String, String> linked = ConfigValidator.validate(NotionConnectorPlugin.DESCRIPTOR.fields(), Map.of("pages",
                "https://www.notion.so/acme/Team-Handbook-" + HANDBOOK.replace("-", "") + "?pvs=4\n"
                        + "https://www.notion.so/" + PROJECTS_DB.replace("-", "") + "?v=" + TOGGLE.replace("-", "")));
        assertThat(plugin.identity(linked)).isEqualTo(HANDBOOK + "," + PROJECTS_DB);
        assertThat(plugin.displayName(linked)).isEqualTo("Team Handbook, " + PROJECTS_DB);
        Map<String, String> bare = ConfigValidator.validate(NotionConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("pages", PROJECTS_DB.toUpperCase() + "\n" + HANDBOOK));
        assertThat(plugin.identity(bare)).isEqualTo(plugin.identity(linked));

        Map<String, String> all = ConfigValidator.validate(NotionConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(plugin.identity(all)).isEqualTo("*");
        assertThat(plugin.displayName(all)).isEqualTo("Everything shared with the integration");
        assertThat(plugin.webUrl(Map.of("pages", "https://www.notion.so/x-" + NOTES.replace("-", "")), null))
                .isEqualTo("https://www.notion.so/x-" + NOTES.replace("-", ""));
        assertThat(plugin.webUrl(Map.of("pages", NOTES), null)).isNull();
        assertThat(NotionConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(NotionConnectorPlugin.DESCRIPTOR.capabilities().incremental()).isTrue();
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
