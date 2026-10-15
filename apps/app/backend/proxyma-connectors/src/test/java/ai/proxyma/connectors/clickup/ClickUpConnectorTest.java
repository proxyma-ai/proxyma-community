package ai.proxyma.connectors.clickup;

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
 * The ClickUp connector against a loopback ClickUp: the workspace by name or ID, the task list's
 * numbered pages until {@code last_page}, spaces, folders and lists each asked for apart and their
 * tasks listed once, {@code date_updated_gt} on a CHANGES pass, the rendered task with its custom
 * fields and every page of comments, the personal token as the bare Authorization value, and
 * ClickUp's 429 with its reset time.
 */
class ClickUpConnectorTest {

    private static final String TOKEN = "pk_123_TESTTOKEN";
    private static final String TEAM = "/team/9001/task";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final ClickUpConnectorPlugin plugin = new ClickUpConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        teams();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private void teams() {
        api.on("GET", "/team").json("{'teams':[{'id':'9000','name':'Other'},{'id':'9001','name':'Acme'}]}");
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(ClickUpConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.CLICKUP, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String task(String id, String name, long updated) {
        return "{'id':'" + id + "','name':'" + name + "','date_updated':'" + updated + "','url':'https://app.clickup.com/t/" + id
                + "','creator':{'username':'Ana'}}";
    }

    @Test
    @DisplayName("a full listing reads every page of the workspace's tasks, closed ones and subtasks included")
    void fullListing() {
        api.on("GET", TEAM).query("page", "0").json("{'tasks':[" + task("abc1", "Draft the plan", 1_760_000_000_000L) + ","
                + task("abc2", "Review it", 1_760_000_100_000L) + "],'last_page':false}");
        api.on("GET", TEAM).query("page", "1").json("{'tasks':[" + task("abc3", "Ship it", 1_760_000_200_000L) + "],'last_page':true}");

        try (ConnectorSession s = session(Map.of("workspace", "acme"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("task:abc1", "task:abc2", "task:abc3");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("Draft the plan");
            assertThat(first.getLastModified()).isEqualTo(Instant.ofEpochMilli(1_760_000_000_000L));
            assertThat(first.getExtra("url")).isEqualTo("https://app.clickup.com/t/abc1");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(listing.hasErrored()).isFalse();
        }
        LoopbackApi.Recorded first = api.requests(TEAM).get(0);
        assertThat(first.param("include_closed")).isEqualTo("true");
        assertThat(first.param("subtasks")).isEqualTo("true");
        assertThat(first.param("date_updated_gt")).isNull();
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(TOKEN));
    }

    @Test
    @DisplayName("spaces, folders and lists are each asked for, and a task they share is listed once")
    void scopes() {
        api.on("GET", TEAM).query("space_ids[]", "901").json("{'tasks':[" + task("abc1", "In the space", 1L) + ","
                + task("abc2", "In both", 2L) + "],'last_page':true}");
        api.on("GET", TEAM).query("list_ids[]", "903").json("{'tasks':[" + task("abc2", "In both", 2L) + ","
                + task("abc4", "In the list", 4L) + "],'last_page':true}");
        try (ConnectorSession s = session(Map.of("workspace", "9001", "spaces", "901", "lists", "903"))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("task:abc1", "task:abc2", "task:abc4");
            // Once per listing, not once per session: a second listing lists them all again.
            assertThat(drain(s.list(ListRequest.full()))).hasSize(3);
        }
        assertThat(api.requests(TEAM)).hasSize(4);
    }

    @Test
    @DisplayName("a changes pass asks for tasks updated since the last pass, in milliseconds")
    void incrementalListing() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("GET", TEAM).json("{'tasks':[" + task("abc3", "Ship it", since.toEpochMilli() + 5000) + "],'last_page':true}");
        try (ConnectorSession s = session(Map.of("workspace", "Acme"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("task:abc3");
        }
        assertThat(api.requests(TEAM).get(0).param("date_updated_gt"))
                .isEqualTo(String.valueOf(since.minus(ClickUpSession.OVERLAP).toEpochMilli()));
    }

    @Test
    @DisplayName("a task is fetched as one markdown document: its fields, custom fields, description, checklists, comments and subtasks")
    void fetchTask() throws IOException {
        api.on("GET", "/task/abc1").json("{'id':'abc1','custom_id':'ENG-7','name':'Draft the plan','team_id':'9001',"
                + "'status':{'status':'in progress'},'priority':{'priority':'high'},'archived':false,"
                + "'assignees':[{'username':'Bo'},{'username':'Cy'}],'creator':{'username':'Ana'},'tags':[{'name':'q4'}],"
                + "'space':{'id':'901'},'folder':{'id':'902','name':'Planning','hidden':false},'list':{'id':'903','name':'Roadmap'},"
                + "'due_date':'1760918400000','start_date':null,'date_created':'1760000000000','date_updated':'1760000100000',"
                + "'date_closed':null,'url':'https://app.clickup.com/t/abc1','parent':null,"
                + "'markdown_description':'Plan **three** quarters.','text_content':'Plan three quarters.',"
                + "'custom_fields':[{'name':'Stage','type':'drop_down','value':1,'type_config':{'options':[{'id':'o1','name':'Idea','orderindex':0},"
                + "{'id':'o2','name':'Build','orderindex':1}]}},"
                + "{'name':'Areas','type':'labels','value':['l2'],'type_config':{'options':[{'id':'l1','label':'Web'},{'id':'l2','label':'Mobile'}]}},"
                + "{'name':'Budget','type':'currency','value':'1200'},{'name':'Signed','type':'checkbox','value':true},"
                + "{'name':'Owner','type':'users','value':[{'username':'Dee'}]},{'name':'Empty','type':'short_text'}],"
                + "'checklists':[{'name':'Before','items':[{'name':'Ask finance','resolved':true},{'name':'Ask legal','resolved':false}]}],"
                + "'attachments':[{'title':'plan.pdf','url':'https://clickup.example/plan.pdf'}],"
                + "'subtasks':[{'id':'abc9','name':'Collect numbers','status':{'status':'done'},'assignees':[{'username':'Bo'}]}]}");
        StringBuilder newest = new StringBuilder("{'comments':[");
        for (int i = 0; i < ClickUpSession.COMMENT_PAGE; i++) {
            if (i > 0) newest.append(',');
            long date = 1_760_000_500_000L - i * 1000L;
            newest.append("{'id':'c").append(100 - i).append("','comment_text':'Note ").append(100 - i)
                    .append("','user':{'username':'Bo'},'date':'").append(date).append("'}");
        }
        newest.append("]}");
        api.on("GET", "/task/abc1/comment").json(newest.toString());
        api.on("GET", "/task/abc1/comment").query("start_id", "c76").json("{'comments':[{'id':'c1','comment_text':'The first word.',"
                + "'user':{'username':'Ana'},'date':'1760000000000'}]}");

        String md = text(fetch("task:abc1", Map.of("workspace", "9001", "lists", "903")));

        assertThat(md).startsWith("# Draft the plan\n")
                .contains("- Task ID: ENG-7", "- Status: in progress", "- Priority: high", "- Assignees: Bo, Cy",
                        "- Creator: Ana", "- Tags: q4", "- Folder: Planning", "- List: Roadmap",
                        "- Due: 2025-10-20T00:00:00Z", "- Stage: Build", "- Areas: Mobile", "- Budget: 1200",
                        "- Signed: yes", "- Owner: Dee", "## Description\n\nPlan **three** quarters.",
                        "## Checklist: Before\n\n- [x] Ask finance\n- [ ] Ask legal", "## Attachments\n\n- plan.pdf",
                        "## Subtasks\n\n- Collect numbers (done; Bo)", "Note 100")
                .containsSubsequence("**Ana**, 2025-10-09T08:53:20Z:\n\nThe first word.", "Note 76", "Note 100")
                .doesNotContain("Empty");
        assertThat(api.requests("/task/abc1").get(0).param("include_markdown_description")).isEqualTo("true");
        assertThat(api.requests("/task/abc1/comment").get(1).param("start")).isEqualTo("1760000476000");
    }

    @Test
    @DisplayName("a deleted task, an archived one and one moved out of the chosen lists are gone")
    void goneTasks() {
        api.on("GET", "/task/gone1").status(404).json("{'err':'Task not found, deleted','ECODE':'ITEM_013'}");
        api.on("GET", "/task/arch1").json("{'id':'arch1','name':'Old','team_id':'9001','archived':true}");
        api.on("GET", "/task/moved1").json("{'id':'moved1','name':'Moved','team_id':'9001','archived':false,"
                + "'space':{'id':'999'},'folder':{'id':'998'},'list':{'id':'997'}}");
        Map<String, Object> source = Map.of("workspace", "9001", "lists", "903");
        assertThatThrownBy(() -> fetch("task:gone1", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("ClickUp no longer has task:gone1.");
        assertThatThrownBy(() -> fetch("task:arch1", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("ClickUp's task arch1 is archived.");
        assertThatThrownBy(() -> fetch("task:moved1", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("ClickUp's task moved1 is no longer in a space, folder or list this source reads.");
        assertThatThrownBy(() -> fetch("doc:1/2", source)).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a ClickUp source.");
    }

    @Test
    @DisplayName("registration finds the workspace and every chosen container, and says which it cannot")
    void accessProbe() {
        api.on("GET", "/space/901").json("{'id':'901','name':'Product'}");
        try (ConnectorSession s = session(Map.of("workspace", "Acme", "spaces", "901"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("workspace", "Nowhere"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("ClickUp has no workspace Nowhere, or the connection cannot see it.");
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
        }
        try (ConnectorSession s = session(Map.of("workspace", "Acme", "lists", "404"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("ClickUp has no list 404, or the connection cannot see it.");
        }
        api.reset();
        api.on("GET", "/team").status(401).json("{'err':'Token invalid','ECODE':'OAUTH_025'}");
        try (ConnectorSession s = session(Map.of("workspace", "Acme"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("ClickUp did not accept the token. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("ClickUp's 429 is waited out until its reset when that is soon, and reported when it is not")
    void rateLimits() {
        long soon = Instant.now().getEpochSecond() + 3;
        api.on("GET", TEAM).status(429).header("X-RateLimit-Remaining", "0").header("X-RateLimit-Reset", String.valueOf(soon))
                .json("{'err':'Rate limit reached','ECODE':'APP_002'}").then().json("{'tasks':[],'last_page':true}");
        try (ConnectorSession s = session(Map.of("workspace", "9001"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isLessThanOrEqualTo(Duration.ofSeconds(5));

        api.reset();
        teams();
        api.on("GET", TEAM).status(429).header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 3600)).json("{'err':'Rate limit reached'}");
        try (ConnectorSession s = session(Map.of("workspace", "9001"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("ClickUp is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is a bearer, refreshed once when ClickUp refuses it")
    void signIn() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.CLICKUP, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.reset();
        api.on("GET", "/team").status(401).json("{'err':'Token invalid'}").then().json("{'teams':[{'id':'9001','name':'Acme'}]}");
        try (ConnectorSession s = session(Map.of("workspace", "9001"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests("/team")).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test is whose token it is")
    void connectionTest() {
        api.on("GET", "/user").json("{'user':{'id':1,'username':'Ana Silva','email':'ana@example.com'}}");
        ClickUpConnectionPlugin connection = new ClickUpConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Silva");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().header("Authorization")).isEqualTo(TOKEN);

        api.reset();
        api.on("GET", "/user").status(401).json("{'err':'Token invalid','ECODE':'OAUTH_025'}");
        assertThat(connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct()).message())
                .isEqualTo("ClickUp did not accept the token.");
        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://app.clickup.com/api");
        assertThat(connection.oauth2().tokenUrl()).isEqualTo("https://api.clickup.com/api/v2/oauth/token");
    }

    @Test
    @DisplayName("the descriptor: identity is the workspace and its chosen containers, each kind sorted - or all")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(ClickUpConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("workspace", " Acme ", "spaces", "902, 901", "lists", "903")));
        assertThat(plugin.identity(config)).isEqualTo("acme/s:901,902;l:903");
        assertThat(plugin.displayName(config)).isEqualTo("ClickUp Acme (spaces 901,902; lists 903)");
        Map<String, String> all = ConfigValidator.validate(ClickUpConnectorPlugin.DESCRIPTOR.fields(), Map.of("workspace", "9001"));
        assertThat(plugin.identity(all)).isEqualTo("9001/all");
        assertThat(plugin.webUrl(all, null)).isEqualTo("https://app.clickup.com/9001");
        assertThatThrownBy(() -> ConfigValidator.validate(ClickUpConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("workspace", "Acme", "lists", "https://app.clickup.com/9001/v/li/903")))
                .hasMessageContaining("Lists is not in the form expected");
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
    @DisplayName("ClickUp's budget headers on a 200 slow the pass once ten requests are left, spread to the reset")
    void pacing() {
        java.time.Instant now = java.time.Instant.parse("2026-10-14T08:00:00Z");
        String reset = String.valueOf(now.getEpochSecond() + 44);
        assertThat(ClickUpApi.PACING.holdNext(budget("X-RateLimit-Remaining", "60", "X-RateLimit-Reset", reset), now)).isEmpty();
        assertThat(ClickUpApi.PACING.holdNext(budget("X-RateLimit-Remaining", "10", "X-RateLimit-Reset", reset), now))
                .contains(Duration.ofSeconds(4));
    }

    private static ai.proxyma.core.connector.spi.ApiResponse budget(String... headers) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        for (int i = 0; i + 1 < headers.length; i += 2) h.add(headers[i], headers[i + 1]);
        return new ai.proxyma.core.connector.spi.ApiResponse(null, 200, h, new byte[0]);
    }
}
