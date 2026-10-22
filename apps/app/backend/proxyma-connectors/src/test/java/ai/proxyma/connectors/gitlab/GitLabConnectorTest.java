package ai.proxyma.connectors.gitlab;

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
 * The GitLab connector against a loopback GitLab: a project and a group, the full listing with
 * pagination, the incremental one, what a complete pass lets the engine remove, each kind of fetch,
 * the credential on every request in the header its kind takes, and every failure as a sentence.
 */
class GitLabConnectorTest {

    private static final String TOKEN = "glpat-test-token";
    private static final String API = "/api/v4";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final GitLabConnectorPlugin plugin = new GitLabConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", API + "/projects/acme%2Fweb").json("{'id':7,'path_with_namespace':'acme/web','default_branch':'main',"
                + "'web_url':'https://gitlab.example/acme/web'}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(GitLabConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.GITLAB, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private void projectIssuesInTwoPages() {
        api.on("GET", API + "/projects/7/issues").json("[{'iid':1,'project_id':7,'title':'Login fails',"
                        + "'updated_at':'2026-10-01T10:00:00.000Z','web_url':'https://gitlab.example/acme/web/-/issues/1',"
                        + "'author':{'username':'ana'}}]",
                "Link", "<" + api.url(API + "/projects/7/issues?page=2") + ">; rel=\"next\"");
        api.on("GET", API + "/projects/7/issues").query("page", "2").json("[{'iid':3,'project_id':7,'title':'Dark mode',"
                + "'updated_at':'2026-10-03T10:00:00Z','web_url':'https://gitlab.example/acme/web/-/issues/3','author':{'username':'cy'}}]");
        api.on("GET", API + "/projects/7/merge_requests").json("[{'iid':2,'project_id':7,'title':'Fix login',"
                + "'updated_at':'2026-10-02T10:00:00Z','web_url':'https://gitlab.example/acme/web/-/merge_requests/2',"
                + "'author':{'username':'bo'}}]");
    }

    private void docsTree(String head) {
        api.on("GET", API + "/projects/7/repository/branches/main").json("{'name':'main','commit':{'id':'" + head + "',"
                + "'committed_date':'2026-10-10T08:00:00.000+00:00'}}");
        api.on("GET", API + "/projects/7/repository/tree").query("ref", "main").json("["
                        + "{'id':'b-readme','name':'README.md','type':'blob','path':'README.md'},"
                        + "{'id':'t-docs','name':'docs','type':'tree','path':'docs'},"
                        + "{'id':'b-guide','name':'guide.md','type':'blob','path':'docs/guide.md'}]",
                "Link", "<" + api.url(API + "/projects/7/repository/tree?ref=main&pagination=keyset&page_token=x") + ">; rel=\"next\"");
        api.on("GET", API + "/projects/7/repository/tree").query("ref", "main").query("page_token", "x").json("["
                + "{'id':'b-notes','name':'notes.txt','type':'blob','path':'docs/notes.txt'},"
                + "{'id':'b-app','name':'app.ts','type':'blob','path':'src/app.ts'}]");
    }

    @Test
    @DisplayName("a project's full listing reads every page of issues, its merge requests, and the docs files on the branch")
    void fullListing() {
        projectIssuesInTwoPages();
        docsTree("c1");
        try (ConnectorSession s = session(Map.of("path", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly("issue:7:1", "issue:7:3", "mr:7:2",
                    "file:7:README.md", "file:7:docs/guide.md", "file:7:docs/notes.txt");
            ItemMetadata mr = items.get(2);
            assertThat(mr.getTitle()).isEqualTo("!2 Fix login");
            assertThat(mr.getContentType()).isEqualTo("text/markdown");
            assertThat(mr.getLastModified()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(mr.getExtra("url")).isEqualTo("https://gitlab.example/acme/web/-/merge_requests/2");
            assertThat(mr.getExtra(ItemMetadata.AUTHOR)).isEqualTo("bo");
            assertThat(items.get(0).getTitle()).isEqualTo("#1 Login fails");
            ItemMetadata guide = items.get(4);
            assertThat(guide.getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo("b-guide");
            assertThat(guide.getExtra("url")).isEqualTo("https://gitlab.example/acme/web/-/blob/main/docs/guide.md");
            assertThat(guide.getLastModified()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
            assertThat(items.get(5).getContentType()).isEqualTo("text/plain");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            assertThat(listing.nextCursor()).startsWith("heads:");
        }
        // Every request carried the token in GitLab's own header, and nothing as a bearer.
        assertThat(api.requests()).allSatisfy(r -> {
            assertThat(r.header("PRIVATE-TOKEN")).isEqualTo(TOKEN);
            assertThat(r.header("Authorization")).isNull();
        });
        LoopbackApi.Recorded first = api.requests(API + "/projects/7/issues").get(0);
        assertThat(first.param("order_by")).isEqualTo("updated_at");
        assertThat(first.param("sort")).isEqualTo("asc");
        assertThat(first.param("updated_after")).isNull();
        assertThat(api.requests(API + "/projects/7/repository/tree").get(0).param("recursive")).isEqualTo("true");
    }

    @Test
    @DisplayName("a group reads the issues and merge requests of every project under it, and each project's docs")
    void groupListing() {
        api.on("GET", API + "/groups/acme").json("{'id':40,'full_path':'acme'}");
        api.on("GET", API + "/groups/40/issues").json("[{'iid':5,'project_id':7,'title':'Login fails',"
                + "'references':{'full':'acme/web#5'},'updated_at':'2026-10-01T10:00:00Z'},"
                + "{'iid':5,'project_id':8,'title':'Slow build','references':{'full':'acme/sub/api#5'},"
                + "'updated_at':'2026-10-02T10:00:00Z'}]");
        api.on("GET", API + "/groups/40/merge_requests").json("[]");
        api.on("GET", API + "/groups/40/projects").json("[{'id':7,'path_with_namespace':'acme/web','default_branch':'main',"
                + "'web_url':'https://gitlab.example/acme/web'},{'id':8,'path_with_namespace':'acme/sub/api',"
                + "'default_branch':'trunk','web_url':'https://gitlab.example/acme/sub/api'},"
                + "{'id':9,'path_with_namespace':'acme/empty','default_branch':null,'empty_repo':true}]");
        api.on("GET", API + "/projects/7/repository/branches/main").json("{'commit':{'id':'c7'}}");
        api.on("GET", API + "/projects/8/repository/branches/trunk").json("{'commit':{'id':'c8'}}");
        api.on("GET", API + "/projects/7/repository/tree").json("[{'id':'b1','type':'blob','path':'README.md'}]");
        api.on("GET", API + "/projects/8/repository/tree").json("[{'id':'b2','type':'blob','path':'docs/api.rst'}]");

        try (ConnectorSession s = session(Map.of("path", "acme", "docs", true))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("issue:7:5", "issue:8:5", "file:7:README.md", "file:8:docs/api.rst");
            assertThat(items).extracting(ItemMetadata::getTitle)
                    .containsExactly("acme/web#5 Login fails", "acme/sub/api#5 Slow build", "acme/web/README.md",
                            "acme/sub/api/docs/api.rst");
            assertThat(items.get(3).getExtra("url")).isEqualTo("https://gitlab.example/acme/sub/api/-/blob/trunk/docs/api.rst");
            assertThat(listing.hasErrored()).isFalse();
        }
        LoopbackApi.Recorded projects = api.requests(API + "/groups/40/projects").get(0);
        assertThat(projects.param("include_subgroups")).isEqualTo("true");
        assertThat(projects.param("archived")).isEqualTo("false");
        assertThat(projects.param("with_shared")).isEqualTo("false");
        assertThat(api.requests(API + "/projects/9/repository/branches/main")).isEmpty();
    }

    @Test
    @DisplayName("issues or merge requests can be left out, and the docs are off by default")
    void switches() {
        projectIssuesInTwoPages();
        try (ConnectorSession s = session(Map.of("path", "acme/web", "mergeRequests", false))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("issue:7:1", "issue:7:3");
        }
        assertThat(api.requests(API + "/projects/7/merge_requests")).isEmpty();
        assertThat(api.requests(API + "/projects/7/repository/tree")).isEmpty();
    }

    @Test
    @DisplayName("a changes pass asks for what changed since the last pass, and reads no tree while no branch moved")
    void incrementalListing() {
        api.on("GET", API + "/projects/7/issues").json("[{'iid':3,'project_id':7,'title':'Dark mode','updated_at':'2026-10-12T10:00:00Z'}]");
        api.on("GET", API + "/projects/7/merge_requests").json("[]");
        docsTree("c1");
        Instant since = Instant.parse("2026-10-12T09:00:00Z");

        String cursor;
        try (ConnectorSession s = session(Map.of("path", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            cursor = listing.nextCursor();
        }
        api.reset();
        api.on("GET", API + "/projects/acme%2Fweb").json("{'id':7,'default_branch':'main','web_url':'https://gitlab.example/acme/web'}");
        api.on("GET", API + "/projects/7/issues").json("[{'iid':3,'project_id':7,'title':'Dark mode','updated_at':'2026-10-12T10:00:00Z'}]");
        api.on("GET", API + "/projects/7/merge_requests").json("[]");
        docsTree("c1");
        try (ConnectorSession s = session(Map.of("path", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.changes(since, cursor));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("issue:7:3");
            assertThat(listing.nextCursor()).isEqualTo(cursor);
        }
        assertThat(api.requests(API + "/projects/7/issues").get(0).param("updated_after"))
                .isEqualTo(since.minus(GitLabSession.OVERLAP).toString());
        assertThat(api.requests(API + "/projects/7/merge_requests").get(0).param("updated_after"))
                .isEqualTo(since.minus(GitLabSession.OVERLAP).toString());
        assertThat(api.requests(API + "/projects/7/repository/tree")).isEmpty();

        // The branch moved on: its files are listed again, under a new cursor.
        api.reset();
        api.on("GET", API + "/projects/acme%2Fweb").json("{'id':7,'default_branch':'main','web_url':'https://gitlab.example/acme/web'}");
        api.on("GET", API + "/projects/7/issues").json("[]");
        api.on("GET", API + "/projects/7/merge_requests").json("[]");
        docsTree("c2");
        try (ConnectorSession s = session(Map.of("path", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.changes(since, cursor));
            assertThat(drain(listing)).extracting(ItemMetadata::getId)
                    .containsExactly("file:7:README.md", "file:7:docs/guide.md", "file:7:docs/notes.txt");
            assertThat(listing.nextCursor()).isNotEqualTo(cursor).startsWith("heads:");
        }
    }

    @Test
    @DisplayName("only files under the chosen folder, on the chosen branch")
    void docsPathAndBranch() {
        api.on("GET", API + "/projects/7/repository/branches/release").json("{'commit':{'id':'r1'}}");
        api.on("GET", API + "/projects/7/repository/tree").query("ref", "release").json("["
                + "{'id':'b1','type':'blob','path':'README.md'},{'id':'b2','type':'blob','path':'docs/guide.md'}]");
        try (ConnectorSession s = session(Map.of("path", "acme/web", "issues", false, "mergeRequests", false,
                "docs", true, "docsPath", "/docs", "branch", "release"))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("file:7:docs/guide.md");
        }
        // A project source asked for a branch it does not have says so, and purges nothing.
        try (ConnectorSession s = session(Map.of("path", "acme/web", "issues", false, "mergeRequests", false,
                "docs", true, "branch", "nope"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("GitLab has no branch nope in acme/web.");
        }
    }

    @Test
    @DisplayName("a page that fails mid-listing is an errored listing - nothing is removed for what it did not reach")
    void failedPageIsNoDeletion() {
        api.on("GET", API + "/projects/7/issues").json("[{'iid':1,'project_id':7,'title':'A','updated_at':'2026-10-01T10:00:00Z'}]",
                "Link", "<" + api.url(API + "/projects/7/issues?page=2") + ">; rel=\"next\"");
        api.on("GET", API + "/projects/7/issues").query("page", "2").status(500);
        try (ConnectorSession s = session(Map.of("path", "acme/web", "mergeRequests", false))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("issue:7:1");
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("GitLab answered with a server error (500). The next sync will try again.");
        }
    }

    @Test
    @DisplayName("an issue is fetched as one markdown document with every page of its comments, system notes left out")
    void fetchIssue() throws IOException {
        api.on("GET", API + "/projects/7/issues/1").json("{'iid':1,'title':'Login fails','state':'opened','issue_type':'issue',"
                + "'author':{'username':'ana'},'assignees':[{'username':'bo'}],'labels':['bug','auth'],"
                + "'milestone':{'title':'Q4'},'references':{'full':'acme/web#1'},'confidential':true,"
                + "'created_at':'2026-10-01T09:00:00Z','updated_at':'2026-10-01T10:00:00Z',"
                + "'web_url':'https://gitlab.example/acme/web/-/issues/1','description':'The login page loops.'}");
        api.on("GET", API + "/projects/7/issues/1/notes").json("[{'author':{'username':'bo'},'created_at':'2026-10-01T09:30:00Z',"
                + "'body':'Reproduced on Safari.','system':false},{'author':{'username':'bo'},'body':'added ~bug label','system':true}]",
                "Link", "<" + api.url(API + "/projects/7/issues/1/notes?page=2") + ">; rel=\"next\"");
        api.on("GET", API + "/projects/7/issues/1/notes").query("page", "2")
                .json("[{'author':{'username':'ana'},'created_at':'2026-10-01T09:40:00Z','body':'Fixed by !2.','system':false}]");

        String md = text(fetch("issue:7:1"));

        assertThat(md).startsWith("# #1 Login fails")
                .contains("- Project: acme/web", "- Type: Issue", "- State: open", "- Author: ana", "- Assignees: bo",
                        "- Labels: bug, auth", "- Milestone: Q4", "- Confidential: yes", "The login page loops.",
                        "**bo**, 2026-10-01T09:30:00Z:", "Reproduced on Safari.", "Fixed by !2.")
                .doesNotContain("added ~bug label");
        assertThat(api.requests(API + "/projects/7/issues/1/notes").get(0).param("sort")).isEqualTo("asc");
    }

    @Test
    @DisplayName("a merge request is fetched with its branches, reviewers and comments, a diff comment naming its file")
    void fetchMergeRequest() throws IOException {
        api.on("GET", API + "/projects/7/merge_requests/2").json("{'iid':2,'title':'Fix login','state':'merged','draft':false,"
                + "'author':{'username':'bo'},'reviewers':[{'username':'ana'}],'source_branch':'fix-login','target_branch':'main',"
                + "'references':{'full':'acme/web!2'},'merged_at':'2026-10-02T11:00:00Z',"
                + "'created_at':'2026-10-02T09:00:00Z','updated_at':'2026-10-02T11:00:00Z',"
                + "'web_url':'https://gitlab.example/acme/web/-/merge_requests/2','description':'Stops the redirect loop.'}");
        api.on("GET", API + "/projects/7/merge_requests/2/notes").json("[{'author':{'username':'ana'},'type':'DiffNote',"
                + "'position':{'new_path':'src/login.ts'},'created_at':'2026-10-02T10:00:00Z','body':'Nit: rename this.','system':false},"
                + "{'author':{'username':'ana'},'created_at':'2026-10-02T10:30:00Z','body':'approved this merge request','system':true}]");

        String md = text(fetch("mr:7:2"));

        assertThat(md).startsWith("# !2 Fix login")
                .contains("- Project: acme/web", "- Type: Merge request", "- State: merged", "- Branch: fix-login -> main",
                        "- Reviewers: ana", "- Merged: 2026-10-02T11:00:00Z", "Stops the redirect loop.",
                        "**ana** on `src/login.ts`", "Nit: rename this.")
                .doesNotContain("approved this merge request");
    }

    @Test
    @DisplayName("a documentation file is fetched raw from the branch, and one past the size cap is refused")
    void fetchFile() throws IOException {
        api.on("HEAD", API + "/projects/7/repository/files/docs%2Fguide.md").query("ref", "main").header("X-Gitlab-Size", "16");
        api.on("GET", API + "/projects/7/repository/files/docs%2Fguide.md/raw").query("ref", "main").text("# Guide\n\nHello.");
        api.on("GET", API + "/projects/7").json("{'id':7,'default_branch':'main'}");

        assertThat(text(fetch("file:7:docs/guide.md"))).isEqualTo("# Guide\n\nHello.");
        assertThat(api.last().header("PRIVATE-TOKEN")).isEqualTo(TOKEN);

        api.on("HEAD", API + "/projects/7/repository/files/big.md").query("ref", "main").header("X-Gitlab-Size", "5000000");
        assertThatThrownBy(() -> fetch("file:7:big.md")).isInstanceOf(ConnectorException.class)
                .hasMessage("big.md is larger than 1 MB, so it is not read as documentation.");
    }

    @Test
    @DisplayName("an item GitLab no longer has is gone - removed at once rather than retried")
    void goneItems() {
        api.on("GET", API + "/projects/7/issues/9").status(404).json("{'message':'404 Not found'}");
        api.on("GET", API + "/projects/7").json("{'id':7,'default_branch':'main'}");
        assertThatThrownBy(() -> fetch("issue:7:9")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("mr:7:9")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("file:7:docs/removed.md")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:x:1")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a GitLab project.");
    }

    @Test
    @DisplayName("registration probes the path: a project, a group, nothing, and a refused token each answer")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("path", "acme/web"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.on("GET", API + "/groups/acme").json("{'id':40}");
        try (ConnectorSession s = session(Map.of("path", "acme"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("path", "acme/nope"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("GitLab has no project or group acme/nope, or the token cannot see it.");
        }
        api.on("GET", API + "/projects/acme%2Flocked").status(401).json("{'message':'401 Unauthorized'}");
        try (ConnectorSession s = session(Map.of("path", "acme/locked"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("GitLab did not accept the token. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("GitLab's 429 is waited out when it resets soon, and reported - not sat out - when it does not")
    void rateLimits() {
        api.on("GET", API + "/projects/7/issues").status(429).header("Retry-After", "2").then().json("[]");
        try (ConnectorSession s = session(Map.of("path", "acme/web", "mergeRequests", false))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(2));

        api.reset();
        api.on("GET", API + "/projects/acme%2Fweb").json("{'id':7,'default_branch':'main'}");
        long later = Instant.now().getEpochSecond() + 3600;
        api.on("GET", API + "/projects/7/issues").status(429).header("RateLimit-Reset", String.valueOf(later));
        try (ConnectorSession s = session(Map.of("path", "acme/web", "mergeRequests", false))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("GitLab is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("a sign-in's token is sent as a bearer, and refreshed once when GitLab refuses it")
    void signInIsABearer() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.GITLAB, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.on("GET", API + "/projects/acme%2Fapp").status(401).json("{'message':'401 Unauthorized'}")
                .then().json("{'id':8,'default_branch':'main'}");
        try (ConnectorSession s = session(Map.of("path", "acme/app"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("PRIVATE-TOKEN")).isNull());
    }

    @Test
    @DisplayName("the connection's Test is who the token belongs to, and its sign-in asks gitlab.com for read_api")
    void connectionTest() {
        api.on("GET", API + "/user").json("{'username':'ana','name':'Ana'}");
        GitLabConnectionPlugin connection = new GitLabConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as ana");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().header("PRIVATE-TOKEN")).isEqualTo(TOKEN);

        api.reset();
        api.on("GET", API + "/user").status(401).json("{'message':'401 Unauthorized'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("GitLab did not accept the token.");

        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://gitlab.com/oauth/authorize");
        assertThat(connection.oauth2().scopes()).containsExactly("read_api");
        assertThat(connection.descriptor().offers(AuthKind.TOKEN)).isTrue();
        assertThat(connection.descriptor().offers(AuthKind.OAUTH2_CODE)).isTrue();
    }

    @Test
    @DisplayName("a self-managed GitLab under a subpath is the same connector, typed with or without /api/v4")
    void selfManagedBaseUrl() {
        api.on("GET", "/gitlab/api/v4/projects/acme%2Fweb").json("{'id':7}");
        for (String typed : List.of(api.baseUrl() + "/gitlab", api.baseUrl() + "/gitlab/", api.baseUrl() + "/gitlab/api/v4")) {
            try (ConnectorSession s = session(Map.of("path", "acme/web"), ResolvedCredentials.token(typed, TOKEN))) {
                assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
            }
            assertThat(api.last().path()).isEqualTo("/gitlab/api/v4/projects/acme%2Fweb");
        }
        assertThat(plugin.webUrl(Map.of("path", "acme/web"), "https://git.example/gitlab/api/v4"))
                .isEqualTo("https://git.example/gitlab/acme/web");
        assertThat(plugin.webUrl(Map.of("path", "acme/web"), null)).isEqualTo("https://gitlab.com/acme/web");
    }

    @Test
    @DisplayName("a page copied from the browser is cut back to the instance where GitLab's own path says where it is")
    void pastedPages() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("https://gitlab.com/acme/web/-/issues/4", "https://gitlab.com");
        cases.put("https://www.gitlab.com/acme", "https://gitlab.com");
        cases.put("https://gitlab.com/api/v4/", "https://gitlab.com");
        cases.put("https://git.example/acme/web/-/merge_requests?state=opened", "https://git.example/acme/web");
        cases.put("https://git.example/gitlab/acme/web/-/tree/main#readme", "https://git.example/gitlab/acme/web");
        cases.put("https://git.example/gitlab/api/v4/projects", "https://git.example/gitlab");
        cases.put("https://git.example/dashboard/projects", "https://git.example");
        cases.put("https://git.example/groups/acme/-/issues", "https://git.example");
        cases.put("https://git.example/-/profile", "https://git.example");
        cases.put("https://git.example:8443/gitlab", "https://git.example:8443/gitlab");
        cases.put("https://git.example/acme/dashboard", "https://git.example/acme/dashboard");
        cases.forEach((typed, web) -> assertThat(GitLabApi.webBase(typed)).as(typed).isEqualTo(web));
        assertThat(GitLabApi.apiBase("https://git.example/gitlab/acme/web/-/issues")).isEqualTo("https://git.example/gitlab/acme/web/api/v4");
    }

    @Test
    @DisplayName("the Test of a project page finds the instance above it and says so, changing nothing; a page where GitLab is not says so")
    void testFindsTheInstance() {
        // A project page under an instance at /gitlab: its own path answers with the web app, the one
        // above it with GitLab's 404 page, and /gitlab with the API.
        byte[] page = "<!DOCTYPE html><html><body>GitLab</body></html>".getBytes(StandardCharsets.UTF_8);
        api.on("GET", "/gitlab/acme/web/api/v4/user").body(page, "text/html; charset=utf-8");
        api.on("GET", "/gitlab/acme/api/v4/user").status(404).body(page, "text/html");
        api.on("GET", "/gitlab/api/v4/user").json("{'username':'ana'}");
        GitLabConnectionPlugin connection = new GitLabConnectionPlugin();
        ResolvedCredentials typed = ResolvedCredentials.token(api.baseUrl() + "/gitlab/acme/web", TOKEN);

        ConnectionTestResult found = connection.test(typed, ConnectorHttp.direct());

        assertThat(found.ok()).isFalse();
        assertThat(found.template()).isEqualTo("GitLab answers at {address}. Use that address as the GitLab URL.");
        assertThat(found.message()).isEqualTo("GitLab answers at " + api.baseUrl() + "/gitlab. Use that address as the GitLab URL.");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).containsExactly(
                "/gitlab/acme/web/api/v4/user", "/gitlab/acme/api/v4/user", "/gitlab/api/v4/user");
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("PRIVATE-TOKEN")).isEqualTo(TOKEN));

        // A refused token at the instance is still GitLab answering.
        api.reset();
        api.on("GET", "/acme/api/v4/user").body(page, "text/html");
        api.on("GET", "/api/v4/user").status(401).json("{'message':'401 Unauthorized'}");
        assertThat(connection.test(ResolvedCredentials.token(api.baseUrl() + "/acme", TOKEN), ConnectorHttp.direct()).message())
                .isEqualTo("GitLab answers at " + api.baseUrl() + ". Use that address as the GitLab URL.");

        // No GitLab anywhere on the host: the page is named for what it is.
        api.reset();
        api.on("GET", "/acme/api/v4/user").body(page, "text/html");
        api.on("GET", "/api/v4/user").body(page, "text/html");
        ConnectionTestResult none = connection.test(ResolvedCredentials.token(api.baseUrl() + "/acme", TOKEN), ConnectorHttp.direct());
        assertThat(none.ok()).isFalse();
        assertThat(none.message()).isEqualTo("GitLab answered with a web page instead of its API. Check the connection's address.");
    }

    @Test
    @DisplayName("the descriptor: identity is the path without case, and the path's form is checked")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(GitLabConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("path", " Acme/Sub/Web ")));
        assertThat(plugin.identity(config)).isEqualTo("acme/sub/web");
        assertThat(plugin.displayName(config)).isEqualTo("Acme/Sub/Web");
        assertThat(config).containsEntry("issues", "true").containsEntry("mergeRequests", "true").containsEntry("docs", "false");
        assertThatThrownBy(() -> ConfigValidator.validate(GitLabConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("path", "https://gitlab.com/acme/web")))
                .hasMessage("Project or group is not in the form expected (for example group/project).");
        assertThat(GitLabSession.isDocumentation("docs/README")).isTrue();
        assertThat(GitLabSession.isDocumentation("src/app.ts")).isFalse();
    }

    private InputStream fetch(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(Map.of("path", "acme/web"))) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
