package ai.proxyma.connectors.asana;

import ai.proxyma.connectors.workitems.WorkItems;
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
 * The Asana connector against a loopback Asana: the workspace and projects looked up by name, the
 * offset pages, the Events sync tokens as the cursor (a fresh one per project on a full pass, a
 * stale one replaced), deletions and removals from the Events feed listed so their fetch can say
 * gone, the rendered task, the token as a bearer on every request, and every failure as a sentence.
 */
class AsanaConnectorTest {

    private static final String TOKEN = "2/asana-test-token";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final AsanaConnectorPlugin plugin = new AsanaConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        workspaceAndProjects();
    }

    // The workspaces the token sees and the projects of Acme - asked for again after a reset.
    private void workspaceAndProjects() {
        api.on("GET", "/workspaces").json("{'data':[{'gid':'100','name':'Other'},{'gid':'101','name':'Acme'}],'next_page':null}");
        api.on("GET", "/projects").query("workspace", "101").json("{'data':[{'gid':'201','name':'Launch plan'},"
                + "{'gid':'202','name':'Hiring'}],'next_page':null}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(AsanaConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.ASANA, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String task(String gid, String name, String modified) {
        return "{'gid':'" + gid + "','name':'" + name + "','modified_at':'" + modified + "',"
                + "'permalink_url':'https://app.asana.com/0/201/" + gid + "','created_by':{'name':'Ana'}}";
    }

    @Test
    @DisplayName("a full listing pages through each project's tasks, a task in two projects once, with a fresh sync token per project")
    void fullListing() {
        api.on("GET", "/events").query("resource", "201").status(412).json("{'errors':[{'message':'Sync token invalid'}],'sync':'s201'}");
        api.on("GET", "/events").query("resource", "202").status(412).json("{'errors':[{'message':'Sync token invalid'}],'sync':'s202'}");
        api.on("GET", "/tasks").query("project", "201").json("{'data':[" + task("301", "Write the brief", "2026-10-01T10:00:00.000Z")
                + "],'next_page':{'offset':'o2','path':'/tasks?offset=o2','uri':'x'}}");
        api.on("GET", "/tasks").query("project", "201").query("offset", "o2").json("{'data':["
                + task("302", "Book the venue", "2026-10-02T10:00:00.000Z") + "],'next_page':null}");
        api.on("GET", "/tasks").query("project", "202").json("{'data':[" + task("302", "Book the venue", "2026-10-02T10:00:00.000Z")
                + "," + task("303", "Post the role", "2026-10-03T10:00:00.000Z") + "],'next_page':null}");

        try (ConnectorSession s = session(Map.of("workspace", "acme"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("task:301", "task:302", "task:303");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("Write the brief");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo("https://app.asana.com/0/201/301");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            assertThat(WorkItems.readCursor(listing.nextCursor())).containsExactly(Map.entry("201", "s201"), Map.entry("202", "s202"));
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        assertThat(api.requests("/tasks").get(0).param("limit")).isEqualTo("100");
        assertThat(api.requests("/tasks").get(0).param("modified_since")).isNull();
    }

    @Test
    @DisplayName("a changes pass asks for tasks modified since the last pass, and lists what the Events feed deleted or removed")
    void incrementalListing() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("GET", "/tasks").query("project", "201").json("{'data':[" + task("301", "Write the brief", "2026-10-12T10:00:00.000Z")
                + "],'next_page':null}");
        api.on("GET", "/events").query("resource", "201").query("sync", "s201").json("{'data':["
                + "{'action':'changed','resource':{'gid':'301','resource_type':'task','name':'Write the brief'},'created_at':'2026-10-12T09:59:00Z'},"
                + "{'action':'deleted','resource':{'gid':'304','resource_type':'task','name':'Old draft'},'created_at':'2026-10-12T09:30:00Z'},"
                + "{'action':'removed','resource':{'gid':'305','resource_type':'task','name':'Moved away'},"
                + "'parent':{'gid':'201','resource_type':'project'},'created_at':'2026-10-12T09:40:00Z'},"
                + "{'action':'removed','resource':{'gid':'306','resource_type':'task','name':'Elsewhere'},"
                + "'parent':{'gid':'999','resource_type':'project'},'created_at':'2026-10-12T09:40:00Z'},"
                + "{'action':'deleted','resource':{'gid':'401','resource_type':'story'},'created_at':'2026-10-12T09:41:00Z'}"
                + "],'sync':'s201b','has_more':true}");
        api.on("GET", "/events").query("resource", "201").query("sync", "s201b").json("{'data':[],'sync':'s201c','has_more':false}");

        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "Launch plan"))) {
            Listing listing = s.list(ListRequest.changes(since, WorkItems.writeCursor(Map.of("201", "s201"))));
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("task:301", "task:304", "task:305");
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-12T09:30:00Z"));
            assertThat(WorkItems.readCursor(listing.nextCursor())).containsExactly(Map.entry("201", "s201c"));
        }
        assertThat(api.requests("/tasks").get(0).param("modified_since")).isEqualTo(since.minus(AsanaSession.OVERLAP).toString());
        assertThat(api.requests("/workspaces")).isEmpty();
    }

    @Test
    @DisplayName("a sync token Asana no longer knows is replaced, and a feed the connection may not read is done without")
    void staleAndRefusedFeeds() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("GET", "/tasks").query("project", "201").json("{'data':[],'next_page':null}");
        api.on("GET", "/events").query("resource", "201").query("sync", "old").status(412)
                .json("{'errors':[{'message':'Sync token invalid or too old'}],'sync':'new201'}");
        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "201"))) {
            Listing listing = s.list(ListRequest.changes(since, WorkItems.writeCursor(Map.of("201", "old"))));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
            assertThat(WorkItems.readCursor(listing.nextCursor())).containsExactly(Map.entry("201", "new201"));
        }

        api.reset();
        workspaceAndProjects();
        api.on("GET", "/events").query("resource", "201").status(403).json("{'errors':[{'message':'Forbidden'}]}");
        api.on("GET", "/tasks").query("project", "201").json("{'data':[" + task("301", "Write the brief", "2026-10-01T10:00:00.000Z")
                + "],'next_page':null}");
        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "201"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("task:301");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isNull();
        }
    }

    @Test
    @DisplayName("a task is fetched as one markdown document: its details, custom fields, notes, comments and subtasks")
    void fetchTask() throws IOException {
        api.on("GET", "/tasks/301").json("{'data':{'gid':'301','name':'Write the brief','resource_subtype':'milestone',"
                + "'notes':'Two pages at most.','assignee':{'name':'Bo'},'created_by':{'name':'Ana'},'start_on':null,"
                + "'due_on':'2026-10-20','due_at':null,'completed':true,'completed_at':'2026-10-12T10:00:00.000Z',"
                + "'created_at':'2026-10-01T09:00:00.000Z','modified_at':'2026-10-12T10:00:00.000Z',"
                + "'permalink_url':'https://app.asana.com/0/201/301','workspace':{'gid':'101'},'parent':null,"
                + "'tags':[{'name':'press'}],'memberships':[{'project':{'gid':'201','name':'Launch plan'},'section':{'name':'Doing'}},"
                + "{'project':{'gid':'202','name':'Hiring'},'section':{'name':'Backlog'}}],"
                + "'custom_fields':[{'name':'Priority','display_value':'High'},{'name':'Budget','display_value':null}]}}");
        api.on("GET", "/tasks/301/stories").json("{'data':[{'type':'system','resource_subtype':'assigned','text':'assigned to Bo',"
                + "'created_at':'2026-10-01T09:05:00.000Z','created_by':{'name':'Ana'}},"
                + "{'type':'comment','resource_subtype':'comment_added','text':'Draft is in the drive.',"
                + "'created_at':'2026-10-02T09:00:00.000Z','created_by':{'name':'Bo'}}],'next_page':{'offset':'p2'}}");
        api.on("GET", "/tasks/301/stories").query("offset", "p2").json("{'data':[{'type':'comment','resource_subtype':'comment_added',"
                + "'text':'Approved.','created_at':'2026-10-03T09:00:00.000Z','created_by':{'name':'Ana'}}],'next_page':null}");
        api.on("GET", "/tasks/301/subtasks").json("{'data':[{'gid':'311','name':'Outline','completed':true,'assignee':{'name':'Bo'},"
                + "'due_on':'2026-10-05'},{'gid':'312','name':'Proofread','completed':false,'assignee':null,'due_on':null}],'next_page':null}");

        String md = text(fetch("task:301", Map.of("workspace", "101", "projects", "201")));

        assertThat(md).startsWith("# Write the brief\n")
                .contains("- Projects: Launch plan, Hiring", "- Section: Doing", "- Type: milestone", "- Assignee: Bo",
                        "- Created by: Ana", "- Due: 2026-10-20", "- Completed: yes, 2026-10-12T10:00:00.000Z",
                        "- Tags: press", "- Priority: High", "- Link: https://app.asana.com/0/201/301",
                        "## Notes\n\nTwo pages at most.", "## Comments", "**Bo**, 2026-10-02T09:00:00.000Z:\n\nDraft is in the drive.",
                        "**Ana**, 2026-10-03T09:00:00.000Z:\n\nApproved.", "## Subtasks", "- [x] Outline (Bo, due 2026-10-05)",
                        "- [ ] Proofread")
                .doesNotContain("assigned to Bo", "Budget", "Backlog");
        assertThat(api.requests("/tasks/301").get(0).param("opt_fields")).contains("custom_fields.display_value");
    }

    @Test
    @DisplayName("a deleted task, one no longer in a chosen project, and one of another workspace are gone")
    void goneTasks() {
        api.on("GET", "/tasks/304").status(404).json("{'errors':[{'message':'task: Unknown object: 304'}]}");
        api.on("GET", "/tasks/305").json("{'data':{'gid':'305','name':'Moved away','workspace':{'gid':'101'},"
                + "'memberships':[{'project':{'gid':'999','name':'Elsewhere'},'section':null}]}}");
        api.on("GET", "/tasks/307").json("{'data':{'gid':'307','name':'Foreign','workspace':{'gid':'555'},'memberships':[]}}");
        Map<String, Object> source = Map.of("workspace", "101", "projects", "201");
        assertThatThrownBy(() -> fetch("task:304", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("Asana no longer has task:304.");
        assertThatThrownBy(() -> fetch("task:305", source)).isInstanceOf(ItemGoneException.class)
                .hasMessage("Asana's task 305 is no longer in a project this source reads.");
        assertThatThrownBy(() -> fetch("task:307", source)).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("story:1", source)).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of an Asana source.");
    }

    @Test
    @DisplayName("registration finds the workspace and every chosen project, and says which one it cannot")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("workspace", "Acme", "projects", "launch plan, 202"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("workspace", "Nowhere"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Asana has no workspace Nowhere, or the connection cannot see it.");
        }
        try (ConnectorSession s = session(Map.of("workspace", "Acme", "projects", "Launch plan, Roadmap"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Asana has no project Roadmap in the workspace Acme, "
                    + "or it is archived, or the connection cannot see it.");
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
        }
        api.on("GET", "/workspaces/777").status(404).json("{'errors':[{'message':'Not found'}]}");
        try (ConnectorSession s = session(Map.of("workspace", "777"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Asana has no workspace 777, or the connection cannot see it.");
        }
        api.reset();
        api.on("GET", "/workspaces").status(401).json("{'errors':[{'message':'Not Authorized'}]}");
        try (ConnectorSession s = session(Map.of("workspace", "Acme"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Asana did not accept the token. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("Asana's 429 is waited out for its Retry-After, and reported when it is too long to sit out")
    void rateLimits() {
        api.on("GET", "/events").query("resource", "201").status(412).json("{'sync':'s'}");
        api.on("GET", "/tasks").query("project", "201").status(429).header("Retry-After", "2")
                .json("{'errors':[{'message':'Rate limit enforced'}]}").then().json("{'data':[],'next_page':null}");
        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "201"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));

        api.reset();
        workspaceAndProjects();
        api.on("GET", "/events").query("resource", "201").status(412).json("{'sync':'s'}");
        api.on("GET", "/tasks").query("project", "201").status(429).header("Retry-After", "3600")
                .json("{'errors':[{'message':'Rate limit enforced'}]}");
        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "201"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Asana is limiting how often it is asked. The next sync will try again.");
        }
        api.reset();
        workspaceAndProjects();
        api.on("GET", "/events").query("resource", "201").status(412).json("{'sync':'s'}");
        api.on("GET", "/tasks").query("project", "201").status(401).json("{'errors':[{'message':'Not Authorized'}]}");
        try (ConnectorSession s = session(Map.of("workspace", "101", "projects", "201"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Asana refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is a bearer, refreshed once when Asana refuses it")
    void signIn() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.ASANA, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.on("GET", "/workspaces/101").status(401).json("{'errors':[{'message':'expired'}]}").then().json("{'data':{'gid':'101','name':'Acme'}}");
        try (ConnectorSession s = session(Map.of("workspace", "101"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests("/workspaces/101")).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test is who the token belongs to, and the sign-in asks for read scopes only")
    void connectionTest() {
        api.on("GET", "/users/me").json("{'data':{'gid':'1','name':'Ana Silva','email':'ana@example.com'}}");
        AsanaConnectionPlugin connection = new AsanaConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Silva");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + TOKEN);

        api.reset();
        api.on("GET", "/users/me").status(401).json("{'errors':[{'message':'Not Authorized'}]}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Asana did not accept the token.");

        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://app.asana.com/-/oauth_authorize");
        assertThat(connection.oauth2().tokenUrl()).isEqualTo("https://app.asana.com/-/oauth_token");
        assertThat(connection.oauth2().scopes()).allSatisfy(scope -> assertThat(scope).endsWith(":read"));
    }

    @Test
    @DisplayName("the descriptor: identity is the workspace and the projects without case or order, or the workspace and all")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(AsanaConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("workspace", " Acme ", "projects", "Launch plan, 1201,launch plan")));
        assertThat(plugin.identity(config)).isEqualTo("acme/1201,launch plan");
        assertThat(plugin.displayName(config)).isEqualTo("Asana Acme: Launch plan, 1201, launch plan");
        Map<String, String> all = ConfigValidator.validate(AsanaConnectorPlugin.DESCRIPTOR.fields(), Map.of("workspace", "Acme"));
        assertThat(plugin.identity(all)).isEqualTo("acme/all");
        assertThat(plugin.webUrl(Map.of("workspace", "Acme", "projects", "1201"), null)).isEqualTo("https://app.asana.com/0/1201");
        assertThat(plugin.webUrl(all, null)).isNull();
        assertThatThrownBy(() -> ConfigValidator.validate(AsanaConnectorPlugin.DESCRIPTOR.fields(), Map.of()))
                .isInstanceOf(RuntimeException.class);
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
