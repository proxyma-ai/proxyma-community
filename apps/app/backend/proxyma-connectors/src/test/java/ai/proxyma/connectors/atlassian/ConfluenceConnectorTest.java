package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.AuthKind;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Confluence connector against a loopback Confluence, on both product lines: Data Center's
 * {@code /rest/api} and Cloud's v2 API under {@code /wiki}, the incremental CQL that lists a page
 * for its new comment, the page rendered with its comments, and every failure as a sentence.
 */
class ConfluenceConnectorTest {

    private static final String PAT = "dc-personal-token";
    private static final String EMAIL = "ana@example.com";
    private static final String API_TOKEN = "cloud-api-token";
    private LoopbackApi api;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials dataCenter() {
        return new ResolvedCredentials(null, ConnectionProtocol.CONFLUENCE, AuthKind.TOKEN, api.baseUrl(), null,
                Map.of("token", PAT), Map.of(), null);
    }

    // Typed with /wiki, as people copy it from the browser - the site is what is kept.
    private ResolvedCredentials cloud() {
        return new ResolvedCredentials(null, ConnectionProtocol.CONFLUENCE, AuthKind.BASIC, api.baseUrl() + "/wiki/", EMAIL,
                Map.of("token", API_TOKEN), Map.of(), null);
    }

    private ConnectorSession session(ResolvedCredentials connection) {
        return new ConfluenceSession(SourceContext.of(ConnectorType.CONFLUENCE, Map.of("spaceKey", "OPS"), connection,
                ConnectorHttp.direct()));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private String fetch(ResolvedCredentials connection, String id) throws IOException {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        try (ConnectorSession s = session(connection); InputStream in = s.fetch(item)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the descriptor is the shared one, native now; the space's page is told apart by the server's address")
    void descriptor() {
        ConfluenceConnectorPlugin plugin = new ConfluenceConnectorPlugin();
        assertThat(plugin.descriptor().description()).isEqualTo("A Confluence space's pages and their comments.");
        assertThat(plugin.identity(Map.of("spaceKey", " OPS "))).isEqualTo("OPS");
        assertThat(plugin.webUrl(Map.of("spaceKey", "OPS"), "https://wiki.example.com/confluence"))
                .isEqualTo("https://wiki.example.com/confluence/display/OPS");
        assertThat(plugin.webUrl(Map.of("spaceKey", "OPS"), "https://acme.atlassian.net/wiki"))
                .isEqualTo("https://acme.atlassian.net/wiki/spaces/OPS");
    }

    @Nested
    @DisplayName("Data Center and Server")
    class DataCenter {

        @Test
        @DisplayName("a full listing pages the space's current pages while a next page is named")
        void fullListing() {
            api.on("GET", "/rest/api/content").json("{'results':[{'id':'10','title':'Runbook','_links':{'webui':'/display/OPS/Runbook'},"
                    + "'version':{'when':'2026-10-10T09:00:00.000Z','by':{'displayName':'Ana'}}},"
                    + "{'id':'11','title':'On call','version':{'when':'2026-10-10T10:00:00.000Z'}}],'_links':{'next':'/rest/api/content?start=2'}}");
            api.on("GET", "/rest/api/content").query("start", "2").json("{'results':[{'id':'12','title':'Escalation',"
                    + "'version':{'when':'2026-10-11T09:00:00.000Z'}}],'_links':{}}");
            List<ItemMetadata> items;
            try (ConnectorSession s = session(dataCenter())) {
                Listing listing = s.list(ListRequest.full());
                items = drain(listing);
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("10", "11", "12");
            assertThat(items.get(0).getExtra("url")).isEqualTo(api.baseUrl() + "/display/OPS/Runbook");
            assertThat(items.get(0).getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana");
            assertThat(items.get(1).getExtra("url")).isEqualTo(api.baseUrl() + "/pages/viewpage.action?pageId=11");
            assertThat(items.get(2).getLastModified()).isEqualTo(Instant.parse("2026-10-11T09:00:00Z"));
            LoopbackApi.Recorded first = api.requests("/rest/api/content").get(0);
            assertThat(first.param("spaceKey")).isEqualTo("OPS");
            assertThat(first.param("type")).isEqualTo("page");
            assertThat(first.param("status")).isEqualTo("current");
            assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + PAT));
        }

        @Test
        @DisplayName("an incremental pass lists the page a new comment was added to, dated by the comment, once")
        void newCommentListsItsPage() {
            api.on("GET", "/rest/api/content/search").rawJson("""
                    {"results":[
                      {"id":"10","type":"page","title":"Runbook","version":{"when":"2026-10-10T09:00:00.000Z"}},
                      {"id":"91","type":"comment","title":"Re: Release notes","version":{"when":"2026-10-11T08:30:00.000Z"},
                       "container":{"id":"20","type":"page","title":"Release notes"}},
                      {"id":"92","type":"comment","title":"Re: Release notes","version":{"when":"2026-10-11T07:00:00.000Z"},
                       "container":{"id":"20","type":"page","title":"Release notes"}},
                      {"id":"93","type":"comment","title":"Re: Team news","version":{"when":"2026-10-11T07:00:00.000Z"},
                       "container":{"id":"30","type":"blogpost","title":"Team news"}}
                    ],"_links":{}}""");
            List<ItemMetadata> items;
            try (ConnectorSession s = session(dataCenter())) {
                Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-09T00:00:00Z"), null));
                items = drain(listing);
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("10", "20");
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-11T08:30:00Z"));
            assertThat(items.get(1).getTitle()).isEqualTo("Release notes");
            String cql = api.requests("/rest/api/content/search").get(0).param("cql");
            assertThat(cql).startsWith("space=\"OPS\" AND lastModified>\"").endsWith("\" AND (type=page OR type=comment)");
            assertThat(api.requests("/rest/api/content/search")).hasSize(1);
        }

        @Test
        @DisplayName("a page is fetched with every page of its comments and rendered for the index")
        void fetchPage() throws IOException {
            api.on("GET", "/rest/api/content/10").json("{'id':'10','title':'Runbook','space':{'key':'OPS','name':'Operations'},"
                    + "'history':{'createdBy':{'displayName':'Ana'},'createdDate':'2026-10-01T09:00:00.000Z'},"
                    + "'version':{'by':{'displayName':'Bo'},'when':'2026-10-10T09:00:00.000Z'},"
                    + "'ancestors':[{'title':'Handbook'}],'metadata':{'labels':{'results':[{'name':'ops'}]}},"
                    + "'body':{'view':{'value':'<p>Restart the <strong>queue</strong> first.</p>'}},'_links':{'webui':'/display/OPS/Runbook'}}");
            api.on("GET", "/rest/api/content/10/child/comment").json("{'results':[{'version':{'by':{'displayName':'Cy'},"
                    + "'when':'2026-10-10T10:00:00.000Z'},'body':{'view':{'value':'<p>Done twice.</p>'}}}],'_links':{'next':'x'}}");
            api.on("GET", "/rest/api/content/10/child/comment").query("start", "1").json("{'results':[{'version':{'by':"
                    + "{'displayName':'Di'},'when':'2026-10-10T11:00:00.000Z'},'body':{'view':{'value':'<p>Also the cache.</p>'}}}],'_links':{}}");

            String md = fetch(dataCenter(), "10");

            assertThat(md).startsWith("# Runbook").contains("| Space | OPS - Operations |", "| Created | 2026-10-01 09:00 by Ana |",
                    "| Last Updated | 2026-10-10 09:00 by Bo |", "| Labels | ops |", "## Location\nHandbook > Runbook",
                    "Restart the **queue** first.", "### Cy - 2026-10-10 10:00", "Done twice.", "### Di", "Also the cache.");
            assertThat(api.requests("/rest/api/content/10").get(0).param("expand"))
                    .isEqualTo("body.view,version,history,ancestors,metadata.labels,space");
        }

        @Test
        @DisplayName("a page Confluence no longer has is gone - removed at once rather than retried")
        void gonePage() {
            api.on("GET", "/rest/api/content/99").status(404).json("{'message':'No content found'}");
            assertThatThrownBy(() -> fetch(dataCenter(), "99")).isInstanceOf(ItemGoneException.class)
                    .hasMessage("Page 99 no longer exists");
        }

        @Test
        @DisplayName("registration probes the space; a refused token, a closed space and a missing one each say what to do")
        void accessProbe() {
            api.on("GET", "/rest/api/space/OPS").json("{'key':'OPS'}");
            try (ConnectorSession s = session(dataCenter())) {
                assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
            }
            api.reset();
            api.on("GET", "/rest/api/space/OPS").status(403).json("{}");
            try (ConnectorSession s = session(dataCenter())) {
                assertThat(s.verifyAccess().detail()).isEqualTo("Confluence accepted the access token, but its account has no "
                        + "permission to view space OPS - ask a Confluence administrator to grant it access.");
            }
            api.reset();
            try (ConnectorSession s = session(dataCenter())) {
                assertThat(s.verifyAccess().detail()).isEqualTo("Space OPS was not found on " + api.baseUrl()
                        + " - check the space key, and that your Confluence account can open it.");
            }
            api.on("GET", "/rest/api/content").status(401).json("{}");
            try (ConnectorSession s = session(dataCenter())) {
                Listing listing = s.list(ListRequest.full());
                drain(listing);
                assertThat(listing.getErrorMessage()).isEqualTo("Confluence at " + api.baseUrl() + " rejected the access token"
                        + " - check it in Settings > Connections > Confluence, and that it has not expired.");
            }
        }

        @Test
        @DisplayName("the connection's Test names the account behind the token")
        void connectionTest() {
            api.on("GET", "/rest/api/user/current").json("{'username':'ana','displayName':'Ana Lima'}");
            ConnectionTestResult ok = new ConfluenceConnectionPlugin().test(dataCenter(), ConnectorHttp.direct());
            assertThat(ok.message()).isEqualTo("Signed in as Ana Lima");
            api.reset();
            api.on("GET", "/rest/api/user/current").status(401).json("{}");
            assertThat(new ConfluenceConnectionPlugin().test(dataCenter(), ConnectorHttp.direct()).message())
                    .isEqualTo("Confluence did not accept the token.");
        }
    }

    @Nested
    @DisplayName("Cloud")
    class Cloud {

        private void space() {
            api.on("GET", "/wiki/api/v2/spaces").query("keys", "OPS").json("{'results':[{'id':'77','key':'OPS','name':'Operations'}]}");
        }

        @Test
        @DisplayName("a full listing finds the space by key and pages its pages by cursor, as Basic")
        void fullListing() {
            space();
            api.on("GET", "/wiki/api/v2/spaces/77/pages").json("{'results':[{'id':'10','title':'Runbook',"
                    + "'version':{'createdAt':'2026-10-10T09:00:00.000Z'},'_links':{'webui':'/spaces/OPS/pages/10/Runbook'}}],"
                    + "'_links':{'next':'/wiki/api/v2/spaces/77/pages?cursor=c2','base':'" + api.baseUrl() + "/wiki'}}");
            api.on("GET", "/wiki/api/v2/spaces/77/pages").query("cursor", "c2").json("{'results':[{'id':'11','title':'On call',"
                    + "'version':{'createdAt':'2026-10-11T09:00:00.000Z'}}],'_links':{}}");
            List<ItemMetadata> items;
            try (ConnectorSession s = session(cloud())) {
                Listing listing = s.list(ListRequest.full());
                items = drain(listing);
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("10", "11");
            assertThat(items.get(0).getExtra("url")).isEqualTo(api.baseUrl() + "/wiki/spaces/OPS/pages/10/Runbook");
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-11T09:00:00Z"));
            String basic = "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));
            assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo(basic));
        }

        @Test
        @DisplayName("an incremental pass searches with CQL on v1's search, and a comment stands for its page")
        void incremental() {
            api.on("GET", "/wiki/rest/api/search").rawJson("""
                    {"results":[
                      {"content":{"id":"10","type":"page","title":"Runbook","version":{"when":"2026-10-10T09:00:00.000Z"},
                       "_links":{"webui":"/spaces/OPS/pages/10/Runbook"}}},
                      {"content":{"id":"91","type":"comment","title":"Re: Release notes","version":{"when":"2026-10-11T08:30:00.000Z"},
                       "container":{"id":"20","type":"page","title":"Release notes"}}}
                    ],"_links":{"next":"/rest/api/search?cursor=n2"}}""");
            api.on("GET", "/wiki/rest/api/search").query("cursor", "n2").rawJson("""
                    {"results":[{"content":{"id":"92","type":"comment","title":"Re: Runbook","version":{"when":"2026-10-12T08:30:00.000Z"},
                      "container":{"id":"10","type":"page","title":"Runbook"}}}],"_links":{}}""");
            List<ItemMetadata> items;
            try (ConnectorSession s = session(cloud())) {
                Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-09T00:00:00Z"), null));
                items = drain(listing);
                assertThat(listing.hasErrored()).isFalse();
            }
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("10", "20");
            assertThat(items.get(1).getLastModified()).isEqualTo(Instant.parse("2026-10-11T08:30:00Z"));
            assertThat(api.requests("/wiki/rest/api/search")).hasSize(2);
            assertThat(api.requests("/wiki/rest/api/search").get(0).param("expand")).isEqualTo("content.version,content.container");
        }

        @Test
        @DisplayName("a page is rendered from v2 - its body, labels, place, people by name and comments with replies")
        void fetchPage() throws IOException {
            space();
            api.on("GET", "/wiki/api/v2/pages/10").json("{'id':'10','title':'Runbook','spaceId':'77','authorId':'a1',"
                    + "'createdAt':'2026-10-01T09:00:00.000Z','version':{'createdAt':'2026-10-10T09:00:00.000Z','authorId':'b2'},"
                    + "'labels':{'results':[{'name':'ops'}]},'body':{'view':{'value':'<p>Restart the <strong>queue</strong> first.</p>'}},"
                    + "'_links':{'webui':'/spaces/OPS/pages/10/Runbook'}}");
            api.on("GET", "/wiki/api/v2/pages/10/ancestors").json("{'results':[{'id':'9','type':'page'}]}");
            api.on("GET", "/wiki/api/v2/pages/9").json("{'id':'9','title':'Handbook'}");
            api.on("GET", "/wiki/api/v2/pages/10/footer-comments").json("{'results':[{'id':'501','version':{'authorId':'c3',"
                    + "'createdAt':'2026-10-10T10:00:00.000Z'},'body':{'storage':{'value':'<p>Done twice.</p>'}}}],'_links':{}}");
            api.on("GET", "/wiki/api/v2/footer-comments/501/children").json("{'results':[{'id':'502','version':{'authorId':'a1',"
                    + "'createdAt':'2026-10-10T11:00:00.000Z'},'body':{'storage':{'value':'<p>Thanks.</p>'}}}],'_links':{}}");
            api.on("GET", "/wiki/api/v2/pages/10/inline-comments").json("{'results':[],'_links':{}}");
            api.on("POST", "/wiki/api/v2/users-bulk").json("{'results':[{'accountId':'a1','displayName':'Ana'},"
                    + "{'accountId':'b2','displayName':'Bo'},{'accountId':'c3','displayName':'Cy'}]}");

            String md = fetch(cloud(), "10");

            assertThat(md).startsWith("# Runbook").contains("| Space | OPS - Operations |", "| Created | 2026-10-01 09:00 by Ana |",
                    "| Last Updated | 2026-10-10 09:00 by Bo |", "| Labels | ops |",
                    "| URL | " + api.baseUrl() + "/wiki/spaces/OPS/pages/10/Runbook |", "## Location\nHandbook > Runbook",
                    "Restart the **queue** first.", "### Cy - 2026-10-10 10:00", "Done twice.", "### Ana - 2026-10-10 11:00", "Thanks.");
            assertThat(api.requests("/wiki/api/v2/pages/10").get(0).param("body-format")).isEqualTo("view");
            assertThat(api.requests("/wiki/api/v2/users-bulk").get(0).body()).contains("a1", "b2", "c3");
        }

        @Test
        @DisplayName("a site that will not render a page as HTML gives its storage format, and a deleted page is gone")
        void storageFallbackAndGone() throws IOException {
            space();
            api.on("GET", "/wiki/api/v2/pages/10").query("body-format", "view").status(400).json("{}");
            api.on("GET", "/wiki/api/v2/pages/10").query("body-format", "storage").json("{'id':'10','title':'Runbook',"
                    + "'body':{'storage':{'value':'<p>Stored text.</p>'}}}");
            assertThat(fetch(cloud(), "10")).contains("Stored text.");

            api.on("GET", "/wiki/api/v2/pages/99").status(404).json("{}");
            assertThatThrownBy(() -> fetch(cloud(), "99")).isInstanceOf(ItemGoneException.class);
        }

        @Test
        @DisplayName("a space key the site does not have is refused at registration, as a missing space")
        void missingSpace() {
            api.on("GET", "/wiki/api/v2/spaces").json("{'results':[]}");
            try (ConnectorSession s = session(cloud())) {
                assertThat(s.verifyAccess().detail()).isEqualTo("Space OPS was not found on " + api.baseUrl()
                        + " - check the space key, and that your Confluence account can open it.");
            }
        }

        @Test
        @DisplayName("the connection's Test reads the site's current user, and the anonymous user is a refusal")
        void connectionTest() {
            api.on("GET", "/wiki/rest/api/user/current").json("{'type':'known','displayName':'Ana Lima'}");
            assertThat(new ConfluenceConnectionPlugin().test(cloud(), ConnectorHttp.direct()).message()).isEqualTo("Signed in as Ana Lima");
            api.reset();
            api.on("GET", "/wiki/rest/api/user/current").json("{'type':'anonymous','displayName':'Anonymous'}");
            ConnectionTestResult anonymous = new ConfluenceConnectionPlugin().test(cloud(), ConnectorHttp.direct());
            assertThat(anonymous.ok()).isFalse();
            assertThat(anonymous.message()).isEqualTo("Confluence did not accept the email address and API token.");
        }
    }

    @Nested
    @DisplayName("the CQL cutoff")
    class Cutoff {

        private final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        private final Instant since = Instant.parse("2026-09-18T00:05:00Z");

        @Test
        @DisplayName("is widened a day and 15 minutes, not the bare since")
        void widened() {
            assertThat(ConfluenceSession.cqlCutoff(since, ZoneId.of("UTC"))).isEqualTo("2026-09-16 23:50");
        }

        @Test
        @DisplayName("is wall-clock in the given zone, since CQL has no zone of its own")
        void renderedInZone() {
            assertThat(ConfluenceSession.cqlCutoff(since, ZoneId.of("Asia/Ho_Chi_Minh"))).isEqualTo("2026-09-17 06:50");
        }

        @Test
        @DisplayName("never lands after since - 15 minutes, in any zone")
        void alwaysOverlaps() {
            for (String zone : new String[] {"UTC", "Asia/Ho_Chi_Minh", "America/Los_Angeles", "Pacific/Kiritimati"}) {
                ZoneId id = ZoneId.of(zone);
                Instant effective = LocalDateTime.parse(ConfluenceSession.cqlCutoff(since, id), format).atZone(id).toInstant();
                assertThat(effective).as(zone).isBeforeOrEqualTo(since.minusSeconds(900));
            }
        }
    }
}
