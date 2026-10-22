package ai.proxyma.connectors.github;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
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
 * The reference connector against a loopback GitHub: the full listing with pagination, the
 * incremental one, what a complete pass lets the engine remove, each kind of fetch, the
 * credential on every request and nowhere else, and every failure as a sentence.
 */
class GitHubConnectorTest {

    private static final String TOKEN = "ghp_test-token";
    // The loopback server is not github.com, so it is spoken to as a GitHub Enterprise Server: under
    // /api/v3, which the connector puts after the address it is given.
    private static final String V3 = "/api/v3";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final GitHubConnectorPlugin plugin = new GitHubConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", V3 + "/repos/acme/web").json("{'default_branch':'main','html_url':'https://github.com/acme/web',"
                + "'pushed_at':'2026-10-10T08:00:00Z'}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(GitHubConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.GITHUB, config,
                ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private void issuesInTwoPages() {
        api.on("GET", V3 + "/repos/acme/web/issues").json("[{'number':1,'title':'Login fails','updated_at':'2026-10-01T10:00:00Z',"
                        + "'html_url':'https://github.com/acme/web/issues/1','user':{'login':'ana'}},"
                        + "{'number':2,'title':'Fix login','updated_at':'2026-10-02T10:00:00Z','pull_request':{},"
                        + "'html_url':'https://github.com/acme/web/pull/2','user':{'login':'bo'}}]",
                "Link", "<" + api.url(V3 + "/repos/acme/web/issues?state=all&page=2") + ">; rel=\"next\"");
        api.on("GET", V3 + "/repos/acme/web/issues").query("page", "2").json("[{'number':3,'title':'Dark mode',"
                + "'updated_at':'2026-10-03T10:00:00Z','html_url':'https://github.com/acme/web/issues/3','user':{'login':'cy'}}]");
    }

    private void docsTree(String sha) {
        api.on("GET", V3 + "/repos/acme/web/git/trees/main").query("recursive", "1").json("{'sha':'" + sha + "','truncated':false,'tree':["
                + "{'path':'README.md','type':'blob','sha':'b-readme','size':120},"
                + "{'path':'docs','type':'tree','sha':'t-docs'},"
                + "{'path':'docs/guide.md','type':'blob','sha':'b-guide','size':300},"
                + "{'path':'docs/notes.txt','type':'blob','sha':'b-notes','size':40},"
                + "{'path':'docs/huge.md','type':'blob','sha':'b-huge','size':2000000},"
                + "{'path':'src/app.ts','type':'blob','sha':'b-app','size':900}]}");
    }

    @Test
    @DisplayName("a full listing reads every page of issues and pull requests, and the docs files on the branch")
    void fullListing() {
        issuesInTwoPages();
        docsTree("t1");
        try (ConnectorSession s = session(Map.of("repository", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("issue:1", "pull:2", "issue:3", "file:README.md", "file:docs/guide.md", "file:docs/notes.txt");
            ItemMetadata pull = items.get(1);
            assertThat(pull.getTitle()).isEqualTo("#2 Fix login");
            assertThat(pull.getContentType()).isEqualTo("text/markdown");
            assertThat(pull.getLastModified()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(pull.getExtra("url")).isEqualTo("https://github.com/acme/web/pull/2");
            assertThat(pull.getExtra(ItemMetadata.AUTHOR)).isEqualTo("bo");
            ItemMetadata guide = items.get(4);
            assertThat(guide.getExtra(ItemMetadata.CONTENT_HASH)).isEqualTo("b-guide");
            assertThat(guide.getExtra("url")).isEqualTo("https://github.com/acme/web/blob/main/docs/guide.md");
            assertThat(items.get(5).getContentType()).isEqualTo("text/plain");
            // A complete pass: not errored, not partial - so the engine may remove what it did not list.
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
            assertThat(listing.nextCursor()).isEqualTo("tree:t1");
        }
        // Every request carried the token and the API version; the full pass asked for every state.
        assertThat(api.requests()).allSatisfy(r -> {
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(r.header("X-GitHub-Api-Version")).isEqualTo(GitHubApi.API_VERSION);
        });
        assertThat(api.requests(V3 + "/repos/acme/web/issues").get(0).param("state")).isEqualTo("all");
        assertThat(api.requests(V3 + "/repos/acme/web/issues").get(0).param("since")).isNull();
    }

    @Test
    @DisplayName("issues or pull requests can be left out, and the docs are off by default")
    void switches() {
        issuesInTwoPages();
        try (ConnectorSession s = session(Map.of("repository", "acme/web", "pullRequests", false))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId).containsExactly("issue:1", "issue:3");
        }
        assertThat(api.requests(V3 + "/repos/acme/web/git/trees/main")).isEmpty();
    }

    @Test
    @DisplayName("a changes pass asks for what changed since the last pass, and skips a tree it has already listed")
    void incrementalListing() {
        api.on("GET", V3 + "/repos/acme/web/issues").json("[{'number':3,'title':'Dark mode','updated_at':'2026-10-12T10:00:00Z',"
                + "'html_url':'https://github.com/acme/web/issues/3','user':{'login':'cy'}}]");
        docsTree("t1");
        Instant since = Instant.parse("2026-10-12T09:00:00Z");

        try (ConnectorSession s = session(Map.of("repository", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.changes(since, "tree:t1"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("issue:3");
            assertThat(listing.nextCursor()).isEqualTo("tree:t1");
        }
        assertThat(api.requests(V3 + "/repos/acme/web/issues").get(0).param("since"))
                .isEqualTo(since.minus(GitHubSession.OVERLAP).toString());

        // The branch moved on: its files are listed again, and the new tree is the next cursor.
        api.reset();
        api.on("GET", V3 + "/repos/acme/web").json("{'default_branch':'main','html_url':'https://github.com/acme/web'}");
        api.on("GET", V3 + "/repos/acme/web/issues").json("[]");
        docsTree("t2");
        try (ConnectorSession s = session(Map.of("repository", "acme/web", "docs", true))) {
            Listing listing = s.list(ListRequest.changes(since, "tree:t1"));
            assertThat(drain(listing)).extracting(ItemMetadata::getId)
                    .containsExactly("file:README.md", "file:docs/guide.md", "file:docs/notes.txt");
            assertThat(listing.nextCursor()).isEqualTo("tree:t2");
        }
    }

    @Test
    @DisplayName("only files under the chosen folder, and a truncated tree is a partial listing that purges nothing")
    void docsPathAndTruncatedTrees() {
        api.on("GET", V3 + "/repos/acme/web/git/trees/main").query("recursive", "1").json("{'sha':'t9','truncated':true,'tree':["
                + "{'path':'README.md','type':'blob','sha':'b1','size':10},"
                + "{'path':'docs/guide.md','type':'blob','sha':'b2','size':10}]}");
        try (ConnectorSession s = session(Map.of("repository", "acme/web", "issues", false, "pullRequests", false,
                "docs", true, "docsPath", "/docs"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("file:docs/guide.md");
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).contains("only part of the repository's files");
        }
    }

    @Test
    @DisplayName("a page that fails mid-listing is an errored listing - nothing is removed for what it did not reach")
    void failedPageIsNoDeletion() {
        api.on("GET", V3 + "/repos/acme/web/issues").json("[{'number':1,'title':'A','updated_at':'2026-10-01T10:00:00Z'}]",
                "Link", "<" + api.url(V3 + "/repos/acme/web/issues?page=2") + ">; rel=\"next\"");
        api.on("GET", V3 + "/repos/acme/web/issues").query("page", "2").status(500);
        try (ConnectorSession s = session(Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("issue:1");
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("GitHub answered with a server error (500). The next sync will try again.");
        }
    }

    @Test
    @DisplayName("an issue is fetched as one markdown document with every page of its comments")
    void fetchIssue() throws IOException {
        api.on("GET", V3 + "/repos/acme/web/issues/1").json("{'number':1,'title':'Login fails','state':'open',"
                + "'user':{'login':'ana'},'assignees':[{'login':'bo'}],'labels':[{'name':'bug'}],"
                + "'created_at':'2026-10-01T09:00:00Z','updated_at':'2026-10-01T10:00:00Z',"
                + "'html_url':'https://github.com/acme/web/issues/1','body':'The login page loops.'}");
        api.on("GET", V3 + "/repos/acme/web/issues/1/comments").json("[{'user':{'login':'bo'},'created_at':'2026-10-01T09:30:00Z',"
                + "'body':'Reproduced on Safari.'}]", "Link", "<" + api.url(V3 + "/repos/acme/web/issues/1/comments?page=2") + ">; rel=\"next\"");
        api.on("GET", V3 + "/repos/acme/web/issues/1/comments").query("page", "2")
                .json("[{'user':{'login':'ana'},'created_at':'2026-10-01T09:40:00Z','body':'Fixed by #2.'}]");

        String md = text(fetch("issue:1"));

        assertThat(md).startsWith("# #1 Login fails")
                .contains("- Repository: acme/web", "- Type: Issue", "- State: open", "- Author: ana",
                        "- Assignees: bo", "- Labels: bug", "The login page loops.",
                        "**bo**, 2026-10-01T09:30:00Z:", "Reproduced on Safari.", "Fixed by #2.");
    }

    @Test
    @DisplayName("a pull request is fetched with its branches, comments, reviews and review comments")
    void fetchPull() throws IOException {
        api.on("GET", V3 + "/repos/acme/web/pulls/2").json("{'number':2,'title':'Fix login','state':'closed',"
                + "'merged_at':'2026-10-02T11:00:00Z','user':{'login':'bo'},'head':{'ref':'fix-login'},'base':{'ref':'main'},"
                + "'created_at':'2026-10-02T09:00:00Z','updated_at':'2026-10-02T11:00:00Z','html_url':'https://github.com/acme/web/pull/2',"
                + "'body':'Stops the redirect loop.'}");
        api.on("GET", V3 + "/repos/acme/web/issues/2/comments").json("[]");
        api.on("GET", V3 + "/repos/acme/web/pulls/2/reviews").json("[{'user':{'login':'ana'},'state':'APPROVED',"
                + "'submitted_at':'2026-10-02T10:30:00Z','body':'Looks right.'}]");
        api.on("GET", V3 + "/repos/acme/web/pulls/2/comments").json("[{'user':{'login':'ana'},'path':'src/login.ts',"
                + "'created_at':'2026-10-02T10:00:00Z','body':'Nit: rename this.'}]");

        String md = text(fetch("pull:2"));

        assertThat(md).contains("- Type: Pull request", "- State: merged", "- Branch: fix-login -> main",
                "Stops the redirect loop.", "## Reviews", "**ana**: approved", "Looks right.",
                "## Review comments", "**ana** on `src/login.ts`", "Nit: rename this.");
    }

    @Test
    @DisplayName("a documentation file is fetched raw from the branch")
    void fetchFile() throws IOException {
        api.on("GET", V3 + "/repos/acme/web/contents/docs/guide.md").query("ref", "main").text("# Guide\n\nHello.");

        assertThat(text(fetch("file:docs/guide.md"))).isEqualTo("# Guide\n\nHello.");
        assertThat(api.last().header("Accept")).isEqualTo("application/vnd.github.raw");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("an item GitHub no longer has is gone - removed at once rather than retried")
    void goneItems() {
        api.on("GET", V3 + "/repos/acme/web/issues/9").status(404).json("{'message':'Not Found'}");
        api.on("GET", V3 + "/repos/acme/web/issues/8").status(410).json("{'message':'This issue was deleted'}");
        assertThatThrownBy(() -> fetch("issue:9")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("issue:8")).isInstanceOf(ItemGoneException.class);
    }

    @Test
    @DisplayName("registration probes the repository: a missing one and a refused token each say what to do")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("repository", "acme/web"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        api.on("GET", V3 + "/repos/acme/nope").status(404).json("{'message':'Not Found'}");
        try (ConnectorSession s = session(Map.of("repository", "acme/nope"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("GitHub has no repository acme/nope, or the token cannot see it.");
        }
        api.on("GET", V3 + "/repos/acme/locked").status(401).json("{'message':'Bad credentials'}");
        try (ConnectorSession s = session(Map.of("repository", "acme/locked"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("GitHub did not accept the token. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("GitHub's rate limit is waited out when it resets soon, and reported - not sat out - when it does not")
    void rateLimits() {
        long soon = Instant.now().getEpochSecond() + 3;
        api.on("GET", V3 + "/repos/acme/web/issues").status(403).header("x-ratelimit-remaining", "0")
                .header("x-ratelimit-reset", String.valueOf(soon)).then().json("[]");
        try (ConnectorSession s = session(Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).hasSize(1);

        api.reset();
        long later = Instant.now().getEpochSecond() + 3600;
        api.on("GET", V3 + "/repos/acme/web/issues").status(403).header("x-ratelimit-remaining", "0")
                .header("x-ratelimit-reset", String.valueOf(later));
        try (ConnectorSession s = session(Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("GitHub is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("the connection's Test is who the token belongs to")
    void connectionTest() {
        api.on("GET", V3 + "/user").json("{'login':'ana'}");
        GitHubConnectionPlugin connection = new GitHubConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as ana");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");

        api.reset();
        api.on("GET", V3 + "/user").status(401).json("{'message':'Bad credentials'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("GitHub did not accept the token.");

        // A proxy's sign-in page in front of the server: the Test says it is a page, and where to look.
        api.reset();
        api.on("GET", V3 + "/user").body("<html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8), "text/html");
        ConnectionTestResult page = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(page.ok()).isFalse();
        assertThat(page.message())
                .isEqualTo("GitHub answered with a web page instead of its API. Check the connection's address.");
    }

    @Test
    @DisplayName("a GitHub Enterprise Server is reached at /api/v3 whether the API URL was typed with it, without it, or as a repository page")
    void enterpriseServerBaseUrl() {
        Map<String, String> config = ConfigValidator.validate(GitHubConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("repository", "acme/web"));
        for (String typed : List.of(api.baseUrl() + "/api/v3", api.baseUrl() + "/api/v3/", api.baseUrl(),
                api.baseUrl() + "/acme/web/issues?q=is%3Aopen", api.baseUrl() + "/api/v3/repos/acme")) {
            try (ConnectorSession s = plugin.open(SourceContext.of(ConnectorType.GITHUB, config,
                    ResolvedCredentials.token(typed, TOKEN), ConnectorHttp.direct()))) {
                assertThat(s.verifyAccess()).as(typed).isEqualTo(AccessCheck.verified());
            }
            assertThat(api.last().path()).as(typed).isEqualTo("/api/v3/repos/acme/web");
        }

        // The connection's Test goes to the same place.
        api.on("GET", V3 + "/user").json("{'login':'ana'}");
        ConnectionTestResult ok = new GitHubConnectionPlugin().test(
                ResolvedCredentials.token(api.baseUrl() + "/acme", TOKEN), ConnectorHttp.direct());
        assertThat(ok.message()).isEqualTo("Signed in as ana");
        assertThat(api.last().path()).isEqualTo("/api/v3/user");
    }

    @Test
    @DisplayName("an API URL in any form becomes the API's address, and the repository's page follows it")
    void apiAddresses() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("https://api.github.com", "https://api.github.com");
        cases.put("https://api.github.com/", "https://api.github.com");
        cases.put("https://github.com/acme/web", "https://api.github.com");
        cases.put("https://www.github.com", "https://api.github.com");
        cases.put("https://ghe.corp.example/api/v3", "https://ghe.corp.example/api/v3");
        cases.put("https://ghe.corp.example/api/v3/repos/acme/web", "https://ghe.corp.example/api/v3");
        cases.put("https://ghe.corp.example", "https://ghe.corp.example/api/v3");
        cases.put("https://ghe.corp.example/acme/web/pulls#top", "https://ghe.corp.example/api/v3");
        cases.put("https://ghe.corp.example:8443/orgs/acme", "https://ghe.corp.example:8443/api/v3");
        cases.put("https://acme.ghe.com/acme/web", "https://api.acme.ghe.com");
        cases.put("https://api.acme.ghe.com", "https://api.acme.ghe.com");
        cases.forEach((typed, api) -> assertThat(GitHubApi.apiBase(typed)).as(typed).isEqualTo(api));
        assertThat(GitHubApi.apiBase(null)).isEqualTo(GitHubApi.DEFAULT_BASE_URL);

        Map<String, String> repo = Map.of("repository", "acme/web");
        assertThat(plugin.webUrl(repo, null)).isEqualTo("https://github.com/acme/web");
        assertThat(plugin.webUrl(repo, "https://github.com/acme")).isEqualTo("https://github.com/acme/web");
        assertThat(plugin.webUrl(repo, "https://ghe.corp.example/api/v3")).isEqualTo("https://ghe.corp.example/acme/web");
        assertThat(plugin.webUrl(repo, "https://ghe.corp.example/acme")).isEqualTo("https://ghe.corp.example/acme/web");
        assertThat(plugin.webUrl(repo, "https://api.acme.ghe.com")).isEqualTo("https://acme.ghe.com/acme/web");
    }

    @Test
    @DisplayName("the descriptor: identity is owner/repository without case, and the repository's form is checked")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(GitHubConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("repository", " Acme/Web ")));
        assertThat(plugin.identity(config)).isEqualTo("acme/web");
        assertThat(plugin.displayName(config)).isEqualTo("Acme/Web");
        assertThat(config).containsEntry("issues", "true").containsEntry("docs", "false");
        assertThatThrownBy(() -> ConfigValidator.validate(GitHubConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("repository", "https://github.com/acme/web")))
                .hasMessage("Repository is not in the form expected (for example owner/repository).");
        assertThat(GitHubSession.isDocumentation("docs/README")).isTrue();
        assertThat(GitHubSession.isDocumentation("src/app.ts")).isFalse();
    }

    private InputStream fetch(String id) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(Map.of("repository", "acme/web"))) {
            return s.fetch(item);
        }
    }

    private static String text(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
