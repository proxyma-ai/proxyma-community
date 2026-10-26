package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigValidator;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Jira connector against a loopback Jira, on both product lines: Data Center's REST v2 search by
 * offset and Cloud's v3 {@code /search/jql} by token, the incremental pass in the owner's zone, the
 * issue rendered (Cloud's Atlassian Document Format included), the credential each line sends, and
 * every failure as a sentence.
 */
class JiraConnectorTest {

    private static final String PAT = "dc-personal-token";
    private static final String EMAIL = "ana@example.com";
    private static final String API_TOKEN = "cloud-api-token";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials dataCenter() {
        return new ResolvedCredentials(null, ConnectionProtocol.JIRA, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("token", PAT), Map.of(), null);
    }

    private ResolvedCredentials cloud() {
        return new ResolvedCredentials(null, ConnectionProtocol.JIRA, AuthKind.BASIC, api.baseUrl() + "/", EMAIL,
                Map.of("token", API_TOKEN), Map.of(), null);
    }

    private ConnectorSession session(ResolvedCredentials connection, String skipCustomFields) {
        Map<String, String> config = ConfigValidator.validate(CoreDescriptors.of(ConnectorType.JIRA).fields(),
                Map.of("projectKey", "proj"));
        return new JiraSession(SourceContext.of(ConnectorType.JIRA, config, connection, ConnectorHttp.direct(waits::add)),
                skipCustomFields);
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String issue(String key, String summary, String updated) {
        return "{'key':'" + key + "','fields':{'summary':'" + summary + "','updated':'" + updated
                + "','issuetype':{'name':'Task'},'reporter':{'displayName':'Ana'}}}";
    }

    @Test
    @DisplayName("the descriptor is the shared one, native now, and signs in either way")
    void descriptor() {
        JiraConnectorPlugin plugin = new JiraConnectorPlugin();
        assertThat(plugin.descriptor().syncIntervalSetting()).isEqualTo("jira.sync-interval-seconds");
        assertThat(new JiraConnectionPlugin().descriptor().authMethods()).extracting(m -> m.kind())
                .containsExactly(AuthKind.TOKEN, AuthKind.BASIC);
        assertThat(plugin.identity(Map.of("projectKey", " proj "))).isEqualTo("PROJ");
        assertThat(plugin.webUrl(Map.of("projectKey", "PROJ"), "https://acme.atlassian.net/"))
                .isEqualTo("https://acme.atlassian.net/projects/PROJ");
        assertThat(plugin.webUrl(Map.of("projectKey", "PROJ"), "https://acme.atlassian.net/jira"))
                .isEqualTo("https://acme.atlassian.net/projects/PROJ");
        assertThat(plugin.webUrl(Map.of("projectKey", "PROJ"), "https://corp.example/jira"))
                .isEqualTo("https://corp.example/jira/projects/PROJ");
    }

    @Nested
    @DisplayName("Data Center and Server")
    class DataCenter {

        @Test
        @DisplayName("a full listing pages the search by offset, in key order, with the token as a bearer")
        void fullListing() {
            api.on("GET", "/rest/api/2/search").json("{'total':3,'issues':[" + issue("PROJ-1", "Login fails", "2026-10-01T10:00:00.000+0000")
                    + "," + issue("PROJ-2", "Fix login", "2026-10-02T10:00:00.000+0700") + "]}");
            api.on("GET", "/rest/api/2/search").query("startAt", "2").json("{'total':3,'issues':["
                    + issue("PROJ-3", "Dark mode", "2026-10-03T10:00:00.000+0000") + "]}");
            try (ConnectorSession s = session(dataCenter(), null)) {
                Listing listing = s.list(ListRequest.full());
                List<ItemMetadata> items = drain(listing);
                assertThat(items).extracting(ItemMetadata::getId).containsExactly("PROJ-1", "PROJ-2", "PROJ-3");
                assertThat(listing.hasErrored()).isFalse();
                ItemMetadata second = items.get(1);
                assertThat(second.getTitle()).isEqualTo("Fix login");
                assertThat(second.getLastModified()).isEqualTo(Instant.parse("2026-10-02T03:00:00Z"));
                assertThat(second.getExtra("url")).isEqualTo(api.baseUrl() + "/browse/PROJ-2");
                assertThat(second.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
                assertThat(second.getContentType()).isEqualTo("text/markdown");
            }
            LoopbackApi.Recorded first = api.requests("/rest/api/2/search").get(0);
            assertThat(first.param("jql")).isEqualTo("project = \"PROJ\" ORDER BY key ASC");
            assertThat(first.param("startAt")).isEqualTo("0");
            assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + PAT));
        }

        @Test
        @DisplayName("a changes pass asks for updates since the last pass, in the token owner's zone, 15 minutes wide")
        void incremental() {
            api.on("GET", "/rest/api/2/myself").json("{'name':'ana','timeZone':'Asia/Ho_Chi_Minh'}");
            api.on("GET", "/rest/api/2/search").json("{'total':1,'issues':[" + issue("PROJ-3", "Dark mode", "2026-09-18T09:00:00.000+0700") + "]}");
            try (ConnectorSession s = session(dataCenter(), null)) {
                assertThat(drain(s.list(ListRequest.changes(Instant.parse("2026-09-18T02:00:00Z"), null))))
                        .extracting(ItemMetadata::getId).containsExactly("PROJ-3");
            }
            assertThat(api.requests("/rest/api/2/search").get(0).param("jql"))
                    .isEqualTo("project = \"PROJ\" AND updated >= \"2026-09-18 08:45\" ORDER BY key ASC");
        }

        @Test
        @DisplayName("an issue is fetched whole and rendered, leaving out the custom fields its owner named")
        void fetchIssue() throws IOException {
            api.on("GET", "/rest/api/2/issue/PROJ-1").json("{'key':'PROJ-1','names':{'customfield_1':'Story Points',"
                    + "'customfield_2':'Rank','customfield_3':'Team'},'fields':{'summary':'Login fails',"
                    + "'status':{'name':'Open'},'description':'The login page loops.','customfield_1':5,"
                    + "'customfield_2':'0|i0001','customfield_3':{'value':'Platform'},"
                    + "'comment':{'comments':[{'author':{'displayName':'Bo'},'created':'2026-10-01T09:30:00.000+0000',"
                    + "'body':'Reproduced on Safari.'}]}},'changelog':{'histories':[]}}");
            ItemMetadata item = new ItemMetadata();
            item.setId("PROJ-1");
            String md;
            try (ConnectorSession s = session(dataCenter(), "Story Points")) {
                md = text(s.fetch(item));
            }
            assertThat(md).startsWith("# [PROJ-1] Login fails").contains("| Status | Open |", "The login page loops.",
                    "| Team | Platform |", "### Bo - 2026-10-01 09:30", "Reproduced on Safari.");
            assertThat(md).doesNotContain("Story Points", "Rank");
            assertThat(api.last().param("fields")).isEqualTo("*all");
            assertThat(api.last().param("expand")).isEqualTo("names,changelog");
        }

        @Test
        @DisplayName("an issue Jira no longer has is gone - removed at once rather than retried")
        void goneIssue() {
            api.on("GET", "/rest/api/2/issue/PROJ-9").status(404).json("{'errorMessages':['Issue does not exist']}");
            ItemMetadata item = new ItemMetadata();
            item.setId("PROJ-9");
            try (ConnectorSession s = session(dataCenter(), null)) {
                assertThatThrownBy(() -> s.fetch(item)).isInstanceOf(ItemGoneException.class)
                        .hasMessage("Issue PROJ-9 no longer exists");
            }
        }

        @Test
        @DisplayName("registration probes the project, and a refused token, a closed project and a missing one each say what to do")
        void accessProbe() {
            api.on("GET", "/rest/api/2/search").json("{'total':0,'issues':[]}");
            try (ConnectorSession s = session(dataCenter(), null)) {
                assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
            }
            assertThat(api.last().param("maxResults")).isEqualTo("1");

            for (int status : new int[] {401, 403, 400}) {
                api.reset();
                api.on("GET", "/rest/api/2/search").status(status).json("{'errorMessages':['no']}");
                try (ConnectorSession s = session(dataCenter(), null)) {
                    String detail = s.verifyAccess().detail();
                    switch (status) {
                        case 401 -> assertThat(detail).isEqualTo("Jira at " + api.baseUrl() + " did not accept the access token"
                                + " - it may be wrong, expired or revoked. Check it in Settings > Connections > Jira.");
                        case 403 -> assertThat(detail).isEqualTo("Jira accepted the access token, but its account has no "
                                + "permission to browse project PROJ - ask a Jira administrator to grant it access.");
                        default -> assertThat(detail).isEqualTo("Project PROJ was not found on " + api.baseUrl()
                                + ", or this account cannot browse it.");
                    }
                }
            }
        }

        @Test
        @DisplayName("a refused token mid-listing is an errored listing, and a rate limit is waited out or reported")
        void failuresWhileListing() {
            api.on("GET", "/rest/api/2/search").status(401).json("{}");
            try (ConnectorSession s = session(dataCenter(), null)) {
                Listing listing = s.list(ListRequest.full());
                assertThat(drain(listing)).isEmpty();
                assertThat(listing.hasErrored()).isTrue();
                assertThat(listing.getErrorMessage()).contains("did not accept the access token");
            }

            api.reset();
            api.on("GET", "/rest/api/2/search").status(429).header("Retry-After", "2").then().json("{'total':0,'issues':[]}");
            try (ConnectorSession s = session(dataCenter(), null)) {
                Listing listing = s.list(ListRequest.full());
                drain(listing);
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(waits).containsExactly(Duration.ofSeconds(2));

            api.reset();
            api.on("GET", "/rest/api/2/search").status(429).header("Retry-After", "3600");
            try (ConnectorSession s = session(dataCenter(), null)) {
                Listing listing = s.list(ListRequest.full());
                drain(listing);
                assertThat(listing.getErrorMessage()).isEqualTo("Jira is limiting how often it is asked. The next sync will try again.");
            }
        }

        @Test
        @DisplayName("the connection's Test names the account, and a refused token says so")
        void connectionTest() {
            api.on("GET", "/rest/api/2/myself").json("{'name':'ana','displayName':'Ana Lima'}");
            ConnectionTestResult ok = new JiraConnectionPlugin().test(dataCenter(), ConnectorHttp.direct());
            assertThat(ok.ok()).isTrue();
            assertThat(ok.message()).isEqualTo("Signed in as Ana Lima");

            api.reset();
            api.on("GET", "/rest/api/2/myself").status(401).json("{}");
            assertThat(new JiraConnectionPlugin().test(dataCenter(), ConnectorHttp.direct()).message())
                    .isEqualTo("Jira did not accept the token.");
        }

        @Test
        @DisplayName("an address pasted from an issue page is spoken to at the server's root, its context path kept")
        void pastedFromAPage() {
            // Registration said "Project PROJ was not found on https://host/jira/browse/PROJ-1": every
            // API path was joined to the issue page. The server lives under /jira, which must stay.
            ResolvedCredentials pasted = new ResolvedCredentials(null, ConnectionProtocol.JIRA, AuthKind.TOKEN,
                    api.baseUrl() + "/jira/browse/PROJ-1?filter=1#comment", null, Map.of("token", PAT), Map.of(), null);
            api.on("GET", "/jira/rest/api/2/myself").json("{'name':'ana','displayName':'Ana Lima'}");
            api.on("GET", "/jira/rest/api/2/search").json("{'total':1,'issues':["
                    + issue("PROJ-1", "Login fails", "2026-10-01T10:00:00.000+0000") + "]}");

            assertThat(new JiraConnectionPlugin().test(pasted, ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lima");
            try (ConnectorSession s = session(pasted, null)) {
                assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
                List<ItemMetadata> items = drain(s.list(ListRequest.full()));
                assertThat(items.get(0).getExtra("url")).isEqualTo(api.baseUrl() + "/jira/browse/PROJ-1");
            }
            assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).allMatch(p -> p.startsWith("/jira/rest/"));
            assertThat(new JiraConnectorPlugin().webUrl(Map.of("projectKey", "PROJ"), "https://corp.example/jira/secure/Dashboard.jspa"))
                    .isEqualTo("https://corp.example/jira/projects/PROJ");
        }
    }

    @Nested
    @DisplayName("Cloud")
    class Cloud {

        private void signedIn() {
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','displayName':'Ana Lima','timeZone':'UTC'}");
        }

        @Test
        @DisplayName("a full listing posts to /search/jql page by token, with the email and API token as Basic")
        void fullListing() {
            signedIn();
            api.on("POST", "/rest/api/3/search/jql")
                    .json("{'issues':[" + issue("PROJ-1", "Login fails", "2026-10-01T10:00:00.000+0000") + "],'nextPageToken':'t2'}")
                    .then().json("{'issues':[" + issue("PROJ-2", "Fix login", "2026-10-02T10:00:00.000+0000") + "],'isLast':true}");
            try (ConnectorSession s = session(cloud(), null)) {
                Listing listing = s.list(ListRequest.full());
                assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("PROJ-1", "PROJ-2");
                assertThat(listing.hasErrored()).isFalse();
            }
            List<LoopbackApi.Recorded> posts = api.requests("/rest/api/3/search/jql");
            assertThat(posts).hasSize(2);
            assertThat(posts.get(0).body()).contains("\"jql\":\"project = \\\"PROJ\\\" ORDER BY key ASC\"")
                    .contains("\"fields\":[\"summary\",\"issuetype\",\"updated\",\"reporter\"]").doesNotContain("nextPageToken");
            assertThat(posts.get(1).body()).contains("\"nextPageToken\":\"t2\"");
            String basic = "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));
            assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(basic));
        }

        @Test
        @DisplayName("a token that leads back to issues already listed ends the listing instead of looping")
        void repeatingTokenEnds() {
            signedIn();
            api.on("POST", "/rest/api/3/search/jql")
                    .json("{'issues':[" + issue("PROJ-1", "A", "2026-10-01T10:00:00.000+0000") + "],'nextPageToken':'t2','isLast':false}")
                    .then().json("{'issues':[" + issue("PROJ-1", "A", "2026-10-01T10:00:00.000+0000") + "],'nextPageToken':'t3','isLast':false}");
            try (ConnectorSession s = session(cloud(), null)) {
                Listing listing = s.list(ListRequest.full());
                assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("PROJ-1");
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(api.requests("/rest/api/3/search/jql")).hasSize(2);
        }

        @Test
        @DisplayName("a changes pass reads the owner's zone from v3 and asks with updated >=")
        void incremental() {
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','timeZone':'America/New_York'}");
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            try (ConnectorSession s = session(cloud(), null)) {
                drain(s.list(ListRequest.changes(Instant.parse("2026-09-18T02:00:00Z"), null)));
            }
            assertThat(api.requests("/rest/api/3/search/jql").get(0).body())
                    .contains("updated >= \\\"2026-09-17 21:45\\\"");
        }

        @Test
        @DisplayName("an issue's Atlassian Document Format - description, comments, a rich custom field - becomes markdown")
        void adfRendering() throws IOException {
            api.on("GET", "/rest/api/3/issue/PROJ-1").rawJson("""
                    {"key":"PROJ-1","names":{"customfield_9":"Acceptance"},"fields":{"summary":"Login fails",
                     "status":{"name":"In Progress"},"assignee":{"displayName":"Bo","accountId":"b1"},
                     "description":{"type":"doc","version":1,"content":[
                       {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Steps"}]},
                       {"type":"orderedList","content":[
                         {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Open the page"}]}]},
                         {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Sign in"}]}]}]},
                       {"type":"paragraph","content":[{"type":"text","text":"The codeword is "},
                         {"type":"text","text":"TANGERINE","marks":[{"type":"strong"}]},
                         {"type":"text","text":" - see "},
                         {"type":"text","text":"the guide","marks":[{"type":"link","attrs":{"href":"https://docs.example.com"}}]}]},
                       {"type":"codeBlock","attrs":{"language":"sh"},"content":[{"type":"text","text":"curl -i /login"}]}]},
                     "customfield_9":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"No loop on Safari"}]}]},
                     "comment":{"comments":[{"author":{"displayName":"Cy"},"created":"2026-10-01T09:30:00.000+0000",
                       "body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[
                         {"type":"mention","attrs":{"id":"b1","text":"@Bo"}},{"type":"text","text":" can you check?"}]}]}}]}},
                     "changelog":{"histories":[{"author":{"displayName":"Bo"},"created":"2026-10-01T11:00:00.000+0000",
                       "items":[{"field":"status","fromString":"Open","toString":"In Progress"}]}]}}""");
            ItemMetadata item = new ItemMetadata();
            item.setId("PROJ-1");
            String md;
            try (ConnectorSession s = session(cloud(), null)) {
                md = text(s.fetch(item));
            }
            assertThat(md).contains("# [PROJ-1] Login fails", "| Assignee | Bo |", "## Steps", "1. Open the page", "2. Sign in",
                    "The codeword is **TANGERINE** - see [the guide](https://docs.example.com)",
                    "```sh\ncurl -i /login```", "| Acceptance | No loop on Safari |", "@Bo can you check?",
                    "| 2026-10-01 11:00 | Bo | status | Open | In Progress |");
            assertThat(md).doesNotContain("\"type\"");
            assertThat(api.last().path()).isEqualTo("/rest/api/3/issue/PROJ-1");
        }

        @Test
        @DisplayName("a refused email and token say so, on the listing and on the connection's Test")
        void refusedCredential() {
            api.on("POST", "/rest/api/3/search/jql").status(401).json("{}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess().detail()).isEqualTo("Jira at " + api.baseUrl() + " did not accept the email "
                        + "address and API token - the token may be wrong, expired or revoked. Check it in Settings > "
                        + "Connections > Jira.");
            }
            api.on("GET", "/rest/api/3/myself").status(401).json("{}");
            assertThat(new JiraConnectionPlugin().test(cloud(), ConnectorHttp.direct()).message())
                    .isEqualTo("Jira did not accept the email address and API token.");
            api.reset();
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','displayName':'Ana Lima'}");
            assertThat(new JiraConnectionPlugin().test(cloud(), ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lima");
        }

        @Test
        @DisplayName("a token Jira Cloud answers as nobody is refused at registration, not listed as an empty project")
        void anonymousAnswerIsRefused() {
            // Jira Cloud signs a wrong, expired or revoked API token in as NOBODY, and an anonymous
            // search is a 200 with no issues: the source registered and every sync listed nothing
            // without an error (telemetry aced67d0).
            String refused = "Jira at " + api.baseUrl() + " did not accept the email address and API token - the "
                    + "token may be wrong, expired or revoked. Check it in Settings > Connections > Jira.";
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").status(401).json("{'errorMessages':['Client must be authenticated']}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess().detail()).isEqualTo(refused);
            }

            // Answered, but as no account.
            api.reset();
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").json("{}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess().detail()).isEqualTo(refused);
            }

            // Even a project anonymous visitors may browse: its issues come back, the token is still wrong.
            api.reset();
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[" + issue("PROJ-1", "One", "2026-03-26T14:08:31.000+0000")
                    + "],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").status(401).json("{}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess().detail()).isEqualTo(refused);
            }

            // A source registered while its token worked: every pass is an error that says why, full or
            // changes - not an empty project.
            api.reset();
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").status(401).json("{}");
            try (ConnectorSession s = session(cloud(), null)) {
                Listing full = s.list(ListRequest.full());
                assertThat(drain(full)).isEmpty();
                assertThat(full.getErrorMessage()).isEqualTo(refused);
                Listing changes = s.list(ListRequest.changes(Instant.parse("2026-09-18T02:00:00Z"), null));
                assertThat(drain(changes)).isEmpty();
                assertThat(changes.getErrorMessage()).isEqualTo(refused);
            }
            assertThat(api.requests("/rest/api/3/search/jql")).isEmpty();

            // And the connection's Test says so, whichever way Jira answers.
            api.reset();
            api.on("GET", "/rest/api/3/myself").json("{}");
            assertThat(new JiraConnectionPlugin().test(cloud(), ConnectorHttp.direct()).message())
                    .isEqualTo("Jira did not accept the email address and API token.");
        }

        @Test
        @DisplayName("an empty search by a signed-in account asks for the project: one it cannot see is refused, an empty one is fine")
        void emptySearchAsksForTheProject() {
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','displayName':'Ana Lima'}");
            api.on("GET", "/rest/api/3/project/PROJ").status(404)
                    .json("{'errorMessages':['No project could be found with key \\'PROJ\\'.']}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess().detail()).isEqualTo("Project PROJ was not found on " + api.baseUrl()
                        + ", or this account cannot browse it.");
            }

            api.reset();
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[],'isLast':true}");
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','displayName':'Ana Lima'}");
            api.on("GET", "/rest/api/3/project/PROJ").json("{'id':'10000','key':'PROJ'}");
            try (ConnectorSession s = session(cloud(), null)) {
                assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
            }
        }

        @Test
        @DisplayName("a site typed with /jira, as the browser shows it, is spoken to at the site (0.36.3 feedback)")
        void typedWithJira() {
            // Jira Cloud's pages live under /jira, and that path answers every API call with the web
            // app - so the Test said "a page instead of its API" and every project was "not found".
            ResolvedCredentials typed = new ResolvedCredentials(null, ConnectionProtocol.JIRA, AuthKind.BASIC,
                    api.baseUrl() + "/jira/", EMAIL, Map.of("token", API_TOKEN), Map.of(), null);
            api.on("GET", "/rest/api/3/myself").json("{'accountId':'a1','displayName':'Ana Lima'}");
            api.on("POST", "/rest/api/3/search/jql").json("{'issues':[" + issue("PROJ-1", "One", "2026-03-26T14:08:31.000+0000")
                    + "],'isLast':true}");

            assertThat(new JiraConnectionPlugin().test(typed, ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lima");
            try (ConnectorSession s = session(typed, null)) {
                assertThat(s.verifyAccess().rejected()).isFalse();
            }
            assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).noneMatch(p -> p.startsWith("/jira"));
        }

        @Test
        @DisplayName("a Cloud connection with no token fails with what to add, before anything is sent")
        void missingToken() {
            ResolvedCredentials empty = new ResolvedCredentials(null, ConnectionProtocol.JIRA, AuthKind.BASIC, api.baseUrl(),
                    EMAIL, Map.of(), Map.of(), null);
            Map<String, String> config = Map.of("projectKey", "PROJ");
            try (ConnectorSession s = new JiraSession(SourceContext.of(ConnectorType.JIRA, config, empty, ConnectorHttp.direct()), null)) {
                Listing listing = s.list(ListRequest.full());
                drain(listing);
                assertThat(listing.getErrorMessage()).isEqualTo("This connection has no email address or API token. "
                        + "Add them in Settings > Connections.");
            }
            assertThat(api.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("a source with no project key is a failed listing - never a healthy empty project")
    void noProjectKey() {
        try (ConnectorSession s = new JiraSession(SourceContext.of(ConnectorType.JIRA, Map.of(), dataCenter(),
                ConnectorHttp.direct()), null)) {
            Listing listing = s.list(ListRequest.full());
            assertThat(listing.hasNext()).isFalse();
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("This Jira source has no project key.");
        }
    }

    @Nested
    @DisplayName("the incremental cutoff")
    class IncrementalCutoff {

        private static final Instant SINCE = Instant.parse("2026-09-18T02:00:00Z");

        @Test
        @DisplayName("is widened by 15 minutes and rendered in the token owner's zone")
        void widenedAndConvertedToServerZone() {
            // +07:00: 01:45Z is 08:45 local. A UTC cutoff sat two hours in the future for JQL.
            assertThat(JiraSession.incrementalCutoff(SINCE, ZoneId.of("Asia/Ho_Chi_Minh")))
                    .isEqualTo(LocalDateTime.of(2026, 9, 18, 8, 45));
        }

        @Test
        @DisplayName("a zone behind UTC moves the other way, still 15 minutes wide")
        void zoneBehindUtc() {
            assertThat(JiraSession.incrementalCutoff(SINCE, ZoneId.of("America/New_York")))
                    .isEqualTo(LocalDateTime.of(2026, 9, 17, 21, 45));
        }

        @Test
        @DisplayName("an unreadable zone falls a whole day back instead of guessing one")
        void unknownZoneFallsBackADay() {
            assertThat(JiraSession.incrementalCutoff(SINCE, null)).isEqualTo(LocalDateTime.of(2026, 9, 17, 2, 0));
        }
    }
}
