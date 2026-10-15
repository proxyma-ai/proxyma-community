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
 * The Bitbucket Cloud connector against a loopback api.bitbucket.org: pull requests paged by their
 * next URLs, the incremental query, the documentation files at the branch head with the head as the
 * cursor, a pull request rendered with its reviews and comments, both credentials, and every failure
 * as a sentence.
 */
class BitbucketCloudConnectorTest {

    private static final String EMAIL = "ana@example.com";
    private static final String API_TOKEN = "bb-api-token";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final BitbucketCloudConnectorPlugin plugin = new BitbucketCloudConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/repositories/acme/web").json("{'mainbranch':{'name':'main'},"
                + "'links':{'html':{'href':'https://bitbucket.org/acme/web'}}}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials apiToken() {
        return new ResolvedCredentials(null, ConnectionProtocol.BITBUCKET_CLOUD, AuthKind.BASIC, api.baseUrl(), EMAIL,
                Map.of("token", API_TOKEN), Map.of(), null);
    }

    private ResolvedCredentials accessToken() {
        return new ResolvedCredentials(null, ConnectionProtocol.BITBUCKET_CLOUD, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("token", "repo-access-token"), Map.of(), null);
    }

    private ConnectorSession session(ResolvedCredentials connection, Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(BitbucketCloudConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.BITBUCKET_CLOUD, config, connection, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private String fetch(String id) throws IOException {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web")); InputStream in = s.fetch(item)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String pull(int id, String title, String updated) {
        return "{'id':" + id + ",'title':'" + title + "','updated_on':'" + updated + "','author':{'display_name':'Ana'},"
                + "'links':{'html':{'href':'https://bitbucket.org/acme/web/pull-requests/" + id + "'}}}";
    }

    @Test
    @DisplayName("the descriptor: its own type and service, identity workspace/repository without case")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(BitbucketCloudConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("repository", " Acme/Web ")));
        assertThat(plugin.identity(config)).isEqualTo("acme/web");
        assertThat(plugin.displayName(config)).isEqualTo("Acme/Web");
        assertThat(config).containsEntry("pullRequests", "true").containsEntry("docs", "false");
        assertThat(plugin.webUrl(config, null)).isEqualTo("https://bitbucket.org/Acme/Web");
        assertThat(plugin.descriptor().protocol()).isEqualTo(ConnectionProtocol.BITBUCKET_CLOUD);
        assertThat(BitbucketCloudConnectionPlugin.DESCRIPTOR.authMethods()).extracting(m -> m.kind())
                .containsExactly(AuthKind.BASIC, AuthKind.TOKEN);
        assertThatThrownBy(() -> ConfigValidator.validate(BitbucketCloudConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("repository", "https://bitbucket.org/acme/web")))
                .hasMessage("Repository is not in the form expected (for example workspace/repository).");
    }

    @Test
    @DisplayName("a full listing reads every page of pull requests in every state, in creation order, as Basic")
    void fullListing() {
        api.on("GET", "/repositories/acme/web/pullrequests").json("{'values':[" + pull(1, "Login fails", "2026-10-01T10:00:00.000000+00:00")
                + "," + pull(2, "Fix login", "2026-10-02T10:00:00.000000+00:00") + "],'next':'"
                + api.url("/repositories/acme/web/pullrequests?page=2") + "'}");
        api.on("GET", "/repositories/acme/web/pullrequests").query("page", "2").json("{'values':["
                + pull(3, "Dark mode", "2026-10-03T10:00:00.000000+00:00") + "]}");
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("pr:1", "pr:2", "pr:3");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.nextCursor()).isNull();
            ItemMetadata second = items.get(1);
            assertThat(second.getTitle()).isEqualTo("#2 Fix login");
            assertThat(second.getLastModified()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(second.getExtra("url")).isEqualTo("https://bitbucket.org/acme/web/pull-requests/2");
            assertThat(second.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
        }
        LoopbackApi.Recorded first = api.requests("/repositories/acme/web/pullrequests").get(0);
        assertThat(first.param("sort")).isEqualTo("created_on");
        assertThat(first.param("q")).isNull();
        // Every state is asked for - the loopback keeps the last of a repeated parameter.
        assertThat(first.param("state")).isEqualTo("SUPERSEDED");
        String basic = "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(basic));
    }

    @Test
    @DisplayName("a changes pass asks for pull requests updated since the last pass, a minute early")
    void incremental() {
        api.on("GET", "/repositories/acme/web/pullrequests").json("{'values':[]}");
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web"))) {
            drain(s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), null)));
        }
        assertThat(api.requests("/repositories/acme/web/pullrequests").get(0).param("q"))
                .isEqualTo("updated_on > 2026-10-12T08:59:00+00:00");
    }

    @Test
    @DisplayName("the docs files at the branch head are listed, and the head is the cursor a later pass compares")
    void docs() {
        api.on("GET", "/repositories/acme/web/refs/branches/main").json("{'name':'main','target':{'hash':'h1',"
                + "'date':'2026-10-10T08:00:00+00:00'}}");
        api.on("GET", "/repositories/acme/web/src/h1/").json("{'values':["
                + "{'type':'commit_file','path':'README.md','size':120},"
                + "{'type':'commit_directory','path':'docs'},"
                + "{'type':'commit_file','path':'docs/guide.md','size':300},"
                + "{'type':'commit_file','path':'docs/huge.md','size':2000000},"
                + "{'type':'commit_file','path':'src/app.ts','size':90}]}");
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web", "pullRequests", false, "docs", true))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("file:README.md", "file:docs/guide.md");
            assertThat(items.get(1).getExtra("url")).isEqualTo("https://bitbucket.org/acme/web/src/main/docs/guide.md");
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
            assertThat(listing.nextCursor()).isEqualTo("commit:h1");
        }
        assertThat(api.requests("/repositories/acme/web/src/h1/").get(0).param("max_depth")).isEqualTo("20");

        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web", "pullRequests", false, "docs", true))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), "commit:h1"));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.nextCursor()).isEqualTo("commit:h1");
        }
        assertThat(api.requests("/repositories/acme/web/src/h1/")).hasSize(1);
    }

    @Test
    @DisplayName("a pull request is rendered with its branches, reviewers, verdicts and comments, deleted ones left out")
    void fetchPull() throws IOException {
        api.on("GET", "/repositories/acme/web/pullrequests/2").json("{'id':2,'title':'Fix login','state':'MERGED',"
                + "'author':{'display_name':'Bo'},'source':{'branch':{'name':'fix-login'}},'destination':{'branch':{'name':'main'}},"
                + "'reviewers':[{'display_name':'Ana'}],'participants':[{'user':{'display_name':'Ana'},'role':'REVIEWER',"
                + "'approved':true,'participated_on':'2026-10-02T10:30:00+00:00'},{'user':{'display_name':'Cy'},'role':'PARTICIPANT',"
                + "'approved':false,'state':null}],'created_on':'2026-10-02T09:00:00+00:00','updated_on':'2026-10-02T11:00:00+00:00',"
                + "'links':{'html':{'href':'https://bitbucket.org/acme/web/pull-requests/2'}},'description':'Stops the redirect loop.'}");
        api.on("GET", "/repositories/acme/web/pullrequests/2/comments").json("{'values':["
                + "{'id':1,'user':{'display_name':'Ana'},'created_on':'2026-10-02T10:00:00+00:00','content':{'raw':'Nit: rename this.'},"
                + "'inline':{'path':'src/login.ts'}},"
                + "{'id':2,'user':{'display_name':'Bo'},'created_on':'2026-10-02T10:05:00+00:00','content':{'raw':'Done.'},'parent':{'id':1}},"
                + "{'id':3,'user':{'display_name':'Cy'},'created_on':'2026-10-02T10:06:00+00:00','content':{'raw':'spam'},'deleted':true}]}");

        String md = fetch("pr:2");

        assertThat(md).startsWith("# #2 Fix login").contains("- Repository: acme/web", "- Type: Pull request",
                "- State: merged", "- Author: Bo", "- Branch: fix-login -> main", "- Reviewers: Ana",
                "Stops the redirect loop.", "## Reviews", "**Ana**: approved, 2026-10-02T10:30:00+00:00",
                "## Comments", "**Ana** on `src/login.ts`", "Nit: rename this.", "**Bo** (reply)", "Done.");
        assertThat(md).doesNotContain("spam", "**Cy**:");
    }

    @Test
    @DisplayName("a documentation file is fetched raw from the branch; a gone item is gone")
    void fetchFileAndGone() throws IOException {
        api.on("GET", "/repositories/acme/web/src/main/docs/guide.md").text("# Guide\n\nThe codeword is JUNIPER.");
        assertThat(fetch("file:docs/guide.md")).isEqualTo("# Guide\n\nThe codeword is JUNIPER.");
        api.on("GET", "/repositories/acme/web/pullrequests/9").status(404).json("{'type':'error'}");
        assertThatThrownBy(() -> fetch("pr:9")).isInstanceOf(ItemGoneException.class)
                .hasMessage("Bitbucket no longer has pr:9 in acme/web.");
    }

    @Test
    @DisplayName("an access token goes as a bearer, and the probe says what is wrong with a repository")
    void accessTokenAndProbe() {
        try (ConnectorSession s = session(accessToken(), Map.of("repository", "acme/web"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer repo-access-token");
        try (ConnectorSession s = session(accessToken(), Map.of("repository", "acme/nope"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Bitbucket has no repository acme/nope, or the credential cannot see it.");
        }
        api.on("GET", "/repositories/acme/locked").status(401).json("{}");
        try (ConnectorSession s = session(accessToken(), Map.of("repository", "acme/locked"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Bitbucket did not accept the credential. Replace it in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("a rate limit that ends soon is waited out, and one that does not is reported")
    void rateLimits() {
        api.on("GET", "/repositories/acme/web/pullrequests").status(429).header("Retry-After", "1").then().json("{'values':[]}");
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).containsExactly(Duration.ofSeconds(1));
        api.reset();
        api.on("GET", "/repositories/acme/web/pullrequests").status(429).header("Retry-After", "7200");
        try (ConnectorSession s = session(apiToken(), Map.of("repository", "acme/web"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Bitbucket is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("the connection's Test names the account; an access token, which /user refuses, is let through")
    void connectionTest() {
        BitbucketCloudConnectionPlugin connection = new BitbucketCloudConnectionPlugin();
        api.on("GET", "/user").json("{'display_name':'Ana Lima','username':'ana'}");
        ConnectionTestResult ok = connection.test(apiToken(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as Ana Lima");

        api.reset();
        api.on("GET", "/user").status(403).json("{}");
        assertThat(connection.test(accessToken(), ConnectorHttp.direct()).ok()).isTrue();
        assertThat(connection.test(apiToken(), ConnectorHttp.direct()).ok()).isFalse();

        api.reset();
        api.on("GET", "/user").status(401).json("{}");
        assertThat(connection.test(apiToken(), ConnectorHttp.direct()).message()).isEqualTo("Bitbucket did not accept the credential.");
    }
}
