package ai.proxyma.core.connector.bitbucket;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bitbucket source indexes: its pull requests and its commits, and nothing else.
 *
 * <p>Added 2026-09-14, when branch, tag and repository items were indexed too; narrowed on
 * 2026-09-18 to the specification's item set (03 sections 1 and 4.1), which is why several
 * assertions here are that something is NOT emitted. The live tools still read branches, tags and
 * diffs - that is a tool question, not an index one.
 *
 * <p>Runs against a real HTTP server answering canned Bitbucket Server responses, so the request
 * log is real too - which is how the "never the code" half is asserted rather than assumed: no
 * request may reach an endpoint that returns file content.
 */
class BitbucketIndexScopeTest {

    private static final String REPO = "/rest/api/1.0/projects/PROJ/repos/demo";
    private static final long T_OLD = 1_700_000_000_000L;
    private static final long T_MID = 1_710_000_000_000L;
    private static final long T_NEW = 1_720_000_000_000L;

    private HttpServer server;
    private final Map<String, String> routes = new HashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private BitbucketConnector connector;
    // The source PROJ/demo on this server, as a sync opens it.
    private ConnectorSession session;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : "");
            requests.add(key);
            String body = routes.get(key);
            byte[] bytes = (body != null ? body : "{\"errors\":[{\"message\":\"not found\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        connector = new BitbucketConnector(RestClient.builder());
        session = authed(baseUrl, "test-token", "PROJ", "demo");
        seedRoutes();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a full sync lists pull requests and commits - each with a link - and nothing else")
    void fullSyncItems() {
        Map<String, ItemMetadata> items = drain(session.list(ListRequest.full()));

        assertEquals(List.of("PR-1", "COMMIT-aaa", "COMMIT-bbb"), new ArrayList<>(items.keySet()));
        for (ItemMetadata item : items.values()) {
            assertNotNull(item.getExtra("url"), item.getId() + " has no citation link");
        }
        // Straight from Bitbucket's epoch milliseconds on both kinds, so neither depends on the
        // machine's time zone.
        assertEquals(Instant.ofEpochMilli(T_NEW), items.get("PR-1").getLastModified());
        assertEquals(Instant.ofEpochMilli(T_OLD), items.get("COMMIT-aaa").getLastModified());
        assertNoCodeRead();
    }

    @Test
    @DisplayName("a full sync emits no branch, tag or repository item, and does not list tags at all")
    void fullSyncEmitsNoRefs() {
        Map<String, ItemMetadata> items = drain(session.list(ListRequest.full()));

        for (String id : items.keySet()) {
            assertFalse(id.startsWith("BRANCH-") || id.startsWith("TAG-") || id.equals("REPO"),
                    "indexed a ref: " + id);
        }
        // The branch list is still read - it is what the commit walk iterates - but nothing asks for
        // the per-branch commit metadata a ref item needed, and nothing lists tags.
        assertTrue(requests.contains(REPO + "/branches?start=0&limit=100"));
        for (String r : requests) {
            assertFalse(r.startsWith(REPO + "/tags"), "listed tags for indexing: " + r);
            assertFalse(r.contains("details=true"), "read branch metadata for indexing: " + r);
        }
    }

    @Test
    @DisplayName("an incremental sync widens its cutoff by 15 minutes, so an item changed while the last sync ran is still seen")
    void incrementalCutoffIsWidened() {
        // Five minutes AFTER the newest PR and commit: without the overlap this returns nothing,
        // which is exactly how an edit made while the previous sync was running went missing until
        // the next daily full sync.
        Map<String, ItemMetadata> items = drain(session.list(ListRequest.changes(Instant.ofEpochMilli(T_NEW + minutes(5)), null)));

        assertEquals(List.of("PR-1", "COMMIT-bbb"), new ArrayList<>(items.keySet()));
    }

    @Test
    @DisplayName("an incremental sync stops at the first item older than the widened cutoff")
    void incrementalCutoffStops() {
        // Twenty minutes after, so even widened the cutoff is past everything in the repository.
        assertTrue(drain(session.list(ListRequest.changes(Instant.ofEpochMilli(T_NEW + minutes(20)), null))).isEmpty());

        // And the older commit is never emitted while the newer one is: the walk stops at it rather
        // than reading the whole branch.
        Map<String, ItemMetadata> items = drain(session.list(ListRequest.changes(Instant.ofEpochMilli(T_MID), null)));
        assertTrue(items.containsKey("COMMIT-bbb"));
        assertFalse(items.containsKey("COMMIT-aaa"), "a commit older than the cutoff was emitted");
    }

    @Test
    @DisplayName("the cutoff is an instant, so the same sync sees the same items in any time zone")
    void incrementalCutoffIsZoneIndependent() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            List<String> utc = new ArrayList<>(drain(session.list(ListRequest.changes(Instant.ofEpochMilli(T_NEW), null))).keySet());

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));
            List<String> chatham = new ArrayList<>(drain(session.list(ListRequest.changes(Instant.ofEpochMilli(T_NEW), null))).keySet());

            assertEquals(utc, chatham);
            assertEquals(List.of("PR-1", "COMMIT-bbb"), utc);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("a pull request or commit that no longer exists says so, rather than failing as an API error or reading as empty")
    void vanishedItemsSaySo() {
        ConnectorException pr = assertThrows(ConnectorException.class, () -> content("PR-99"));
        assertEquals("Pull request PR-99 no longer exists", pr.getMessage());

        ConnectorException commit = assertThrows(ConnectorException.class, () -> content("COMMIT-zzz"));
        assertEquals("Commit zzz no longer exists", commit.getMessage());
    }

    @Test
    @DisplayName("verifyAccess reads the repository itself, and names what to fix when it cannot")
    void verifyAccessProbesTheRepository() {
        assertTrue(session.verifyAccess().ok());
        assertTrue(requests.contains(REPO));

        assertEquals("The repository must be given as <projectKey>/<repoSlug>.",
                authed(baseUrl, "test-token", "PROJ", null).verifyAccess().detail());
        assertEquals("No Bitbucket access token is configured. Add one in Settings > Connections > "
                        + "Bitbucket, then register the repository again.",
                authed(baseUrl, null, "PROJ", "demo").verifyAccess().detail());
        assertEquals("Repository PROJ/gone was not found on " + baseUrl
                        + " - check the name, and that it has not been renamed or deleted.",
                authed(baseUrl, "test-token", "PROJ", "gone").verifyAccess().detail());
    }

    @Test
    @DisplayName("a pull request carries every page of activity, threaded replies, reviewer verdicts, commits and changed paths")
    void pullRequestContent() throws Exception {
        String md = content("PR-1");

        assertTrue(md.contains("| Reviewers | Bob Reviewer (approved) |"), md);
        assertTrue(md.contains("Alice Author opened the pull request"), md);
        // Second page of activities - the old code read one page of 100 and stopped.
        assertTrue(md.contains("Bob Reviewer approved"), md);
        assertTrue(md.contains("Carol Merger merged the pull request as 9f8e7d6"), md);
        assertTrue(md.contains("### Bob Reviewer") && md.contains("on `src/Retry.java` line 42"), md);
        assertTrue(md.contains("> **Alice Author**") && md.contains("> Fixed in the next commit."), md);
        assertTrue(md.contains("> > **Bob Reviewer**"), md);
        assertTrue(md.contains("- bbb Add retry with backoff (Alice Author)"), md);
        assertTrue(md.contains("- MODIFY `src/Retry.java`"), md);
        assertTrue(md.contains("- MOVE `docs/old.md` -> `docs/retry.md`"), md);
        assertTrue(md.contains("Detected in the title and source branch: ABC-12"), md);
        assertNoCodeRead();
    }

    @Test
    @DisplayName("a commit carries its parents, changed paths and the pull requests that brought it in")
    void commitContent() throws Exception {
        String md = content("COMMIT-bbb");

        assertTrue(md.contains("| Parent | aaa |"), md);
        assertTrue(md.contains("- PR-1 ABC-12 Retry failed uploads (MERGED)"), md);
        assertTrue(md.contains("- ADD `src/Retry.java`"), md);
        assertTrue(md.contains("Detected in the commit message: ABC-12"), md);
        assertNoCodeRead();
    }

    // LEGACY IDS. sync() stopped emitting these on 2026-09-18, but installs that ran 0.9.x still
    // have the rows, and the drain loop can ask for one before the next full sync purges it - so
    // rendering them has to keep working, and a slash in a branch name still has to be encoded once.
    @Test
    @DisplayName("a branch, tag or repository row left over from an older version still renders")
    void refContent() throws Exception {
        String branch = content("BRANCH-feature/x");
        assertTrue(branch.contains("# Branch feature/x"), branch);
        assertTrue(branch.contains("| Default branch | No |"), branch);
        assertTrue(branch.contains("## Latest Commit") && branch.contains("Add retry with backoff"), branch);
        // "feature/x" must match exactly - "feature/x2" came back from the substring filter too.
        assertTrue(branch.contains("| Hash | bbb |"), branch);
        assertTrue(requests.contains(REPO + "/branches?details=true&filterText=feature%2Fx&start=0&limit=100"));

        String tag = content("TAG-v1.0");
        assertTrue(tag.contains("# Tag v1.0") && tag.contains("## Tagged Commit") && tag.contains("| Hash | aaa |"), tag);

        String repo = content("REPO");
        assertTrue(repo.contains("# Repository PROJ/demo - Demo Service"), repo);
        assertTrue(repo.contains("| Project | Project One (PROJ) |"), repo);
        // default-branch answered 404 here; the older endpoint is the fallback.
        assertTrue(repo.contains("| Default branch | main |"), repo);
        assertTrue(repo.contains("Handles uploads."), repo);
        assertTrue(repo.contains("- ssh: ssh://git@host/proj/demo.git"), repo);
        assertNoCodeRead();
    }

    @Test
    @DisplayName("a ref that no longer exists reads as empty rather than failing the item")
    void vanishedRef() throws Exception {
        assertEquals("", content("BRANCH-deleted"));
    }

    // - the tools' file tree, a deleted branch, and a climbing id -------------------------------

    @Test
    @DisplayName("a slashed ref and a path with '#' and a space reach Bitbucket encoded once")
    void fileTreeEncodesRefAndPath() {
        route("/files/src/C%23/My%20Dir?start=0&limit=1000&at=feature%2Fx", page("'Program.cs'", true, 0));
        route("/browse/src/C%23?limit=1000&at=release%2F2.1", "{'children':{'values':[],'isLastPage':true}}");

        Map<String, Object> files = connector.listFiles(baseUrl, "test-token", "PROJ", "demo", "src/C#/My Dir", "feature/x", 10);
        assertEquals(List.of("Program.cs"), files.get("files"));
        assertEquals("DIRECTORY", connector.browsePath(baseUrl, "test-token", "PROJ", "demo", "./src/C#", "release/2.1", 10).get("type"));
    }

    @Test
    @DisplayName("a branch deleted while the commits are walked is skipped, and the listing still completes")
    void deletedBranchIsSkipped() {
        route("/branches?start=0&limit=100", page("{'displayId':'gone'},{'displayId':'main'},{'displayId':'feature/x'}", true, 0));

        Map<String, ItemMetadata> items = drain(session.list(ListRequest.full()));

        assertTrue(items.containsKey("COMMIT-aaa") && items.containsKey("COMMIT-bbb"), items.keySet().toString());
    }

    @Test
    @DisplayName("a path or id that climbs with '..' is never sent")
    void climbingPathIsRefused() {
        assertThrows(RuntimeException.class,
                () -> connector.browsePath(baseUrl, "test-token", "PROJ", "demo", "src/../../../../admin", null, 10));
        for (String r : requests) {
            assertFalse(r.contains(".."), "sent a climbing path: " + r);
        }
    }

    // - helpers ------------------------------------------------------------------------------

    @Test
    @DisplayName("two sources' sessions are their own: one with no token does not touch another's")
    void sessionsShareNothing() {
        ConnectorSession withoutToken = authed(baseUrl, null, "PROJ", "demo");
        assertTrue(withoutToken.verifyAccess().rejected());
        assertTrue(session.verifyAccess().ok());
        assertFalse(drain(session.list(ListRequest.full())).isEmpty());
    }

    @Test
    @DisplayName("a row stored before its fields were JSON reads its repository from its name")
    void anOldRowReadsItsName() {
        ConnectorSession old = connector.open(new SourceContext(null, ConnectorType.BITBUCKET, null, "PROJ/demo",
                Map.of(), baseUrl, null, ResolvedCredentials.token(baseUrl, "test-token"),
                ai.proxyma.core.connector.spi.SourceScope.defaults(), null, null));
        assertTrue(old.verifyAccess().ok());
    }

    @Test
    @DisplayName("a source links to its repository's page on its server")
    void webUrl() {
        assertEquals(baseUrl + "/projects/PROJ/repos/demo",
                connector.webUrl(Map.of("projectKey", "PROJ", "repoSlug", "demo"), baseUrl + "/"));
    }

    // The bean has a second, private constructor (forSource), and Spring only chooses one itself
    // when a class declares exactly one - the app did not start until the public one said so.
    @Test
    @DisplayName("Spring builds the plugin bean, whose per-source copies come from a constructor of their own")
    void springBuildsTheBean() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("externalRestClientBuilder", RestClient.Builder.class,
                    (java.util.function.Supplier<RestClient.Builder>) RestClient::builder);
            context.register(BitbucketConnector.class);
            context.refresh();
            assertNotNull(context.getBean(BitbucketConnector.class).descriptor());
        }
    }

    private static long minutes(int count) {
        return count * 60_000L;
    }

    // A session of one source against this server, as SourceContextFactory builds it: the source's
    // fields and its connection's server and token - so every case verifyAccess() reports is a
    // source's configuration rather than an argument.
    private ConnectorSession authed(String url, String accessToken, String projectKey, String repoSlug) {
        Map<String, String> fields = new HashMap<>();
        fields.put("projectKey", projectKey);
        if (repoSlug != null) fields.put("repoSlug", repoSlug);
        return connector.open(SourceContext.of(ConnectorType.BITBUCKET, fields,
                ResolvedCredentials.token(url, accessToken), null));
    }

    private Map<String, ItemMetadata> drain(Iterator<ItemMetadata> it) {
        Map<String, ItemMetadata> items = new LinkedHashMap<>();
        while (it.hasNext()) {
            ItemMetadata m = it.next();
            items.put(m.getId(), m);
        }
        assertFalse(((SyncErrorAware) it).hasErrored(), () -> ((SyncErrorAware) it).getErrorMessage());
        return items;
    }

    // As the drain loop calls it: an id and nothing else, the extras gone.
    private String content(String id) throws Exception {
        ItemMetadata meta = new ItemMetadata();
        meta.setId(id);
        try (InputStream in = session.fetch(meta)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertNoCodeRead() {
        for (String r : requests) {
            assertFalse(r.contains("/browse") || r.contains("/files") || r.contains("/raw")
                    || r.contains("/archive") || r.contains("/diff"), "read repository content: " + r);
        }
    }

    private void route(String pathAndQuery, String json) {
        routes.put(REPO + pathAndQuery, json.replace('\'', '"'));
    }

    private static String page(String values, boolean last, int next) {
        return "{'values':[" + values + "],'isLastPage':" + last + (last ? "" : ",'nextPageStart':" + next) + "}";
    }

    private static String user(String name) {
        return "{'name':'" + name.toLowerCase().replace(' ', '.') + "','displayName':'" + name + "'}";
    }

    private static String commit(String id, String message, long time, String parent) {
        return "{'id':'" + id + "','displayId':'" + id + "','message':'" + message + "','author':"
                + "{'name':'Alice Author','emailAddress':'alice@example.com'},'authorTimestamp':" + time
                + ",'committer':{'name':'Alice Author'},'committerTimestamp':" + time
                + ",'parents':[" + (parent == null ? "" : "{'id':'" + parent + "','displayId':'" + parent + "'}") + "]}";
    }

    private void seedRoutes() {
        String pr = "{'id':1,'title':'ABC-12 Retry failed uploads','state':'MERGED','description':'Adds retries.',"
                + "'author':{'user':" + user("Alice Author") + "},"
                + "'reviewers':[{'user':" + user("Bob Reviewer") + ",'status':'APPROVED'}],"
                + "'fromRef':{'displayId':'feature/x'},'toRef':{'displayId':'main'},"
                + "'createdDate':" + T_NEW + ",'updatedDate':" + T_NEW + ","
                + "'links':{'self':[{'href':'" + "http://bitbucket/projects/PROJ/repos/demo/pull-requests/1'}]}}";
        route("/pull-requests?state=ALL&order=NEWEST&start=0&limit=100", page(pr, true, 0));
        route("/pull-requests/1", pr);

        String thread = "{'action':'COMMENTED','commentAction':'ADDED','createdDate':" + (T_NEW + 2) + ",'user':" + user("Bob Reviewer")
                + ",'commentAnchor':{'path':'src/Retry.java','line':42},'comment':{'text':'Cap the backoff?','author':" + user("Bob Reviewer")
                + ",'createdDate':" + (T_NEW + 2) + ",'comments':[{'text':'Fixed in the next commit.','author':" + user("Alice Author")
                + ",'createdDate':" + (T_NEW + 3) + ",'comments':[{'text':'Thanks','author':" + user("Bob Reviewer")
                + ",'createdDate':" + (T_NEW + 4) + ",'comments':[]}]}]}}";
        String reply = "{'action':'COMMENTED','commentAction':'REPLIED','createdDate':" + (T_NEW + 3) + ",'user':" + user("Alice Author")
                + ",'comment':{'text':'Fixed in the next commit.','author':" + user("Alice Author") + ",'comments':[]}}";
        String merged = "{'action':'MERGED','createdDate':" + (T_NEW + 9) + ",'user':" + user("Carol Merger") + ",'commit':{'displayId':'9f8e7d6'}}";
        route("/pull-requests/1/activities?start=0&limit=500", page(merged + "," + reply + "," + thread, false, 3));
        String approved = "{'action':'APPROVED','createdDate':" + (T_NEW + 1) + ",'user':" + user("Bob Reviewer") + "}";
        String opened = "{'action':'OPENED','createdDate':" + T_NEW + ",'user':" + user("Alice Author") + "}";
        route("/pull-requests/1/activities?start=3&limit=500", page(approved + "," + opened, true, 0));
        route("/pull-requests/1/changes?start=0&limit=500", page(
                "{'path':{'toString':'src/Retry.java'},'type':'MODIFY'},"
                        + "{'path':{'toString':'docs/retry.md'},'srcPath':{'toString':'docs/old.md'},'type':'MOVE'}", true, 0));
        route("/pull-requests/1/commits?start=0&limit=500", page(commit("bbb", "Add retry with backoff", T_NEW, "aaa"), true, 0));

        route("/branches?start=0&limit=100", page("{'displayId':'main'},{'displayId':'feature/x'}", true, 0));
        route("/commits?until=main&start=0&limit=100", page(commit("aaa", "Initial import", T_OLD, null), true, 0));
        route("/commits?until=feature%2Fx&start=0&limit=100", page(
                commit("bbb", "ABC-12 Add retry with backoff", T_NEW, "aaa") + "," + commit("aaa", "Initial import", T_OLD, null), true, 0));
        route("/branches?details=true&start=0&limit=100", page(
                "{'displayId':'main','latestCommit':'aaa','isDefault':true,'metadata':{"
                        + "'com.atlassian.bitbucket.server.bitbucket-branch:latest-commit-metadata':{'authorTimestamp':" + T_MID + "}}},"
                        + "{'displayId':'feature/x','latestCommit':'bbb','isDefault':false}", true, 0));
        route("/tags?start=0&limit=100", page("{'displayId':'v1.0','latestCommit':'aaa'},{'displayId':'v0.9','latestCommit':'ccc'}", true, 0));

        route("/commits/aaa", commit("aaa", "Initial import", T_OLD, null));
        route("/commits/bbb", commit("bbb", "ABC-12 Add retry with backoff", T_NEW, "aaa"));
        route("/commits/ccc", commit("ccc", "Old release", T_OLD, null));
        route("/commits/bbb/changes?start=0&limit=500", page("{'path':{'toString':'src/Retry.java'},'type':'ADD'}", true, 0));
        route("/commits/bbb/pull-requests?start=0&limit=50", page("{'id':1,'title':'ABC-12 Retry failed uploads','state':'MERGED'}", true, 0));

        route("/branches?details=true&filterText=feature%2Fx&start=0&limit=100", page(
                "{'displayId':'feature/x2','latestCommit':'aaa','isDefault':false},"
                        + "{'displayId':'feature/x','latestCommit':'bbb','isDefault':false}", true, 0));
        route("/branches?details=true&filterText=deleted&start=0&limit=100", page("", true, 0));
        route("/tags?filterText=v1.0&start=0&limit=100", page("{'displayId':'v1.0','latestCommit':'aaa'}", true, 0));

        route("", "{'slug':'demo','name':'Demo Service','description':'Handles uploads.','public':false,'forkable':true,"
                + "'state':'AVAILABLE','scmId':'git','project':{'key':'PROJ','name':'Project One'},"
                + "'links':{'self':[{'href':'http://bitbucket/projects/PROJ/repos/demo/browse'}],"
                + "'clone':[{'name':'http','href':'http://bitbucket/scm/proj/demo.git'},{'name':'ssh','href':'ssh://git@host/proj/demo.git'}]}}");
        route("/branches/default", "{'displayId':'main','id':'refs/heads/main'}");
    }
}
