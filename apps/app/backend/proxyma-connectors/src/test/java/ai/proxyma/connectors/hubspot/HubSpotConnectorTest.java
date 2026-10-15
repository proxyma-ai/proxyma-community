package ai.proxyma.connectors.hubspot;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HubSpot connector against a loopback HubSpot: the full listing through each type's list
 * endpoint, the CHANGES pass through CRM search - in date windows past its 10,000-result cap - with
 * the archived records it lists as gone, the rendered record with its associations and activity,
 * the token as a bearer everywhere, both of HubSpot's limits, and the connection's Test.
 */
class HubSpotConnectorTest {

    private static final String TOKEN = "pat-na1-test-token";
    private static final String V = HubSpotApi.DEFAULT_VERSION;
    private static final String COMPANIES = "/crm/objects/" + V + "/companies";
    private static final String DEALS = "/crm/objects/" + V + "/deals";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final HubSpotConnectorPlugin plugin = new HubSpotConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/account-info/" + V + "/details").json("{'portalId':4455,'uiDomain':'app-eu1.hubspot.com'}");
        api.on("GET", "/crm/owners/" + V).json("{'results':[{'id':'77','firstName':'Ana','lastName':'Silva','email':'ana@example.com'}]}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(HubSpotConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.HUBSPOT, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private JsonNode body(String path, int index) {
        try {
            return JSON.readTree(api.requests(path).get(index).body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String company(String id, String name, String modified) {
        return "{'id':'" + id + "','properties':{'name':'" + name + "','domain':null,'hs_lastmodifieddate':'" + modified
                + "','hubspot_owner_id':'77'},'createdAt':'2026-01-01T00:00:00Z','updatedAt':'" + modified + "','archived':false}";
    }

    @Test
    @DisplayName("a full listing pages through every chosen type's list endpoint, archived records left out")
    void fullListing() {
        api.on("GET", COMPANIES).json("{'results':[" + company("1", "Acme", "2026-10-01T10:00:00Z") + ","
                + company("2", "Globex", "2026-10-02T10:00:00Z") + "],'paging':{'next':{'after':'2','link':'x'}}}");
        api.on("GET", COMPANIES).query("after", "2").json("{'results':[" + company("3", "Initech", "2026-10-03T10:00:00Z") + "]}");
        api.on("GET", DEALS).json("{'results':[{'id':'9','properties':{'dealname':'Acme renewal',"
                + "'hs_lastmodifieddate':'2026-10-04T10:00:00Z'},'updatedAt':'2026-10-04T10:00:00Z'}]}");

        try (ConnectorSession s = session(Map.of("objects", "deals,companies"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("company:1", "company:2", "company:3", "deal:9");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("Acme");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo("https://app-eu1.hubspot.com/contacts/4455/record/0-2/1");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Silva");
            assertThat(items.get(3).getTitle()).isEqualTo("Acme renewal");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        LoopbackApi.Recorded firstPage = api.requests(COMPANIES).get(0);
        assertThat(firstPage.param("archived")).isEqualTo("false");
        assertThat(firstPage.param("limit")).isEqualTo("100");
        assertThat(firstPage.param("properties")).contains("name", "hs_lastmodifieddate", "hubspot_owner_id");
        assertThat(api.requests(COMPANIES).get(1).param("after")).isEqualTo("2");
        assertThat(api.requests(COMPANIES + "/search")).isEmpty();
    }

    @Test
    @DisplayName("companies, deals and tickets by default; a contact is listed by its name and modified by lastmodifieddate")
    void defaultsAndContacts() {
        Map<String, String> config = ConfigValidator.validate(HubSpotConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(HubSpotConnectorPlugin.objects(config)).containsExactly("companies", "deals", "tickets");

        api.on("GET", "/crm/objects/" + V + "/contacts").json("{'results':[{'id':'5','properties':{'firstname':'Bo',"
                + "'lastname':'Tran','email':'bo@example.com','lastmodifieddate':'2026-10-05T10:00:00Z'}}]}");
        try (ConnectorSession s = session(Map.of("objects", "contacts"))) {
            List<ItemMetadata> items = drain(s.list(ListRequest.full()));
            assertThat(items).extracting(ItemMetadata::getTitle).containsExactly("Bo Tran");
            assertThat(items.get(0).getLastModified()).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));
        }
        assertThat(api.requests("/crm/objects/" + V + "/contacts").get(0).param("properties")).contains("lastmodifieddate");
    }

    @Test
    @DisplayName("a changes pass searches by last-modified date from a minute before the last pass, and lists what was archived since")
    void incrementalListing() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("POST", COMPANIES + "/search").json("{'total':2,'results':[" + company("1", "Acme", "2026-10-12T09:30:00Z") + "],"
                        + "'paging':{'next':{'after':'1'}}}")
                .then().json("{'total':2,'results':[" + company("4", "Umbrella", "2026-10-12T09:40:00Z") + "]}");
        api.on("GET", COMPANIES).query("archived", "true").json("{'results':["
                + "{'id':'8','properties':{'name':'Old Co'},'archived':true,'archivedAt':'2026-10-12T09:10:00Z'},"
                + "{'id':'6','properties':{'name':'Older Co'},'archived':true,'archivedAt':'2026-09-01T00:00:00Z'}]}");

        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("company:1", "company:4", "company:8");
            assertThat(listing.isPartial()).isFalse();
        }
        JsonNode first = body(COMPANIES + "/search", 0);
        JsonNode filter = first.path("filterGroups").path(0).path("filters").path(0);
        assertThat(filter.path("propertyName").asText()).isEqualTo("hs_lastmodifieddate");
        assertThat(filter.path("operator").asText()).isEqualTo("GTE");
        assertThat(filter.path("value").asText()).isEqualTo(String.valueOf(since.minus(HubSpotSession.OVERLAP).toEpochMilli()));
        assertThat(first.path("sorts").path(0).path("direction").asText()).isEqualTo("ASCENDING");
        assertThat(first.path("limit").asInt()).isEqualTo(HubSpotSession.SEARCH_PAGE);
        assertThat(first.has("after")).isFalse();
        assertThat(body(COMPANIES + "/search", 1).path("after").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("past search's 10,000-result cap the pass starts a new date window, listing the boundary records once")
    void dateWindows() {
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        api.on("POST", COMPANIES + "/search")
                .json("{'results':[" + company("1", "Acme", "2026-10-12T09:30:00Z") + ","
                        + company("2", "Globex", "2026-10-12T09:45:00Z") + "],'paging':{'next':{'after':'9900'}}}")
                .then().json("{'results':[" + company("2", "Globex", "2026-10-12T09:45:00Z") + ","
                        + company("3", "Initech", "2026-10-12T09:50:00Z") + "]}");
        api.on("GET", COMPANIES).query("archived", "true").json("{'results':[]}");

        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("company:1", "company:2", "company:3");
            assertThat(listing.isPartial()).isFalse();
        }
        JsonNode second = body(COMPANIES + "/search", 1);
        assertThat(second.has("after")).isFalse();
        assertThat(second.path("filterGroups").path(0).path("filters").path(0).path("value").asText())
                .isEqualTo(String.valueOf(Instant.parse("2026-10-12T09:45:00Z").toEpochMilli()));
    }

    @Test
    @DisplayName("a window that cannot move - 10,000 records changed in the same millisecond - ends the pass as partial")
    void stuckWindow() {
        api.on("POST", COMPANIES + "/search").json("{'results':[" + company("1", "Acme", "2026-10-12T08:59:00Z")
                + "],'paging':{'next':{'after':'10000'}}}");
        api.on("GET", COMPANIES).query("archived", "true").json("{'results':[]}");
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.changes(Instant.parse("2026-10-12T09:00:00Z"), null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId).containsExactly("company:1");
            assertThat(listing.isPartial()).isTrue();
            assertThat(listing.getPartialReason()).startsWith("More than 10,000 HubSpot records");
        }
        assertThat(api.requests(COMPANIES + "/search")).hasSize(1);
    }

    @Test
    @DisplayName("a record is fetched as one markdown document with its properties, associated records by name and activity")
    void fetchRecord() throws IOException {
        api.on("GET", DEALS + "/9").json("{'id':'9','properties':{'dealname':'Acme renewal','pipeline':'default',"
                + "'dealstage':'contractsent','amount':'12000','closedate':'2026-11-30T00:00:00Z','hubspot_owner_id':'77',"
                + "'createdate':'2026-09-01T00:00:00Z','hs_lastmodifieddate':'2026-10-04T10:00:00Z',"
                + "'description':'<p>Three-year renewal with &amp; upsell.</p>'},'archived':false,"
                + "'associations':{'companies':{'results':[{'id':'1','type':'deal_to_company'},{'id':'1','type':'deal_to_company_unlabeled'}]},"
                + "'contacts':{'results':[{'id':'5','type':'deal_to_contact'}]},"
                + "'notes':{'results':[{'id':'301','type':'deal_to_note'}]},"
                + "'calls':{'results':[{'id':'401','type':'deal_to_call'}]}}}");
        api.on("GET", "/crm/pipelines/" + V + "/deals").json("{'results':[{'id':'default','label':'Sales pipeline',"
                + "'stages':[{'id':'contractsent','label':'Contract sent'}]}]}");
        api.on("POST", COMPANIES + "/batch/read").json("{'results':[{'id':'1','properties':{'name':'Acme','domain':'acme.example'}}]}");
        api.on("POST", "/crm/objects/" + V + "/contacts/batch/read").json("{'results':[{'id':'5','properties':{'firstname':'Bo',"
                + "'lastname':'Tran','email':'bo@example.com','jobtitle':'CFO'}}]}");
        api.on("POST", "/crm/objects/" + V + "/notes/batch/read").json("{'results':[{'id':'301','properties':{"
                + "'hs_note_body':'<div>Budget approved<br>by the board.</div>','hs_timestamp':'2026-10-02T08:00:00Z','hubspot_owner_id':'77'}}]}");
        api.on("POST", "/crm/objects/" + V + "/calls/batch/read").json("{'results':[{'id':'401','properties':{"
                + "'hs_call_title':'Kickoff call','hs_call_body':'Discussed the timeline.','hs_call_direction':'OUTBOUND',"
                + "'hs_timestamp':'2026-10-03T08:00:00Z'}}]}");

        String md = text(fetch("deal:9"));

        assertThat(md).startsWith("# Acme renewal")
                .contains("- Type: Deal", "- Pipeline: Sales pipeline", "- Stage: Contract sent", "- Amount: 12000",
                        "- Close date: 2026-11-30T00:00:00Z", "- Owner: Ana Silva", "- Updated: 2026-10-04T10:00:00Z",
                        "- Link: https://app-eu1.hubspot.com/contacts/4455/record/0-3/9",
                        "## About", "Three-year renewal with & upsell.",
                        "## Associated records", "### Company", "- Acme (acme.example)", "### Contact", "- Bo Tran (CFO, bo@example.com)",
                        "## Activity", "### Call: Kickoff call", "2026-10-03T08:00:00Z, outbound", "Discussed the timeline.",
                        "### Note", "2026-10-02T08:00:00Z, by Ana Silva", "Budget approved\nby the board.");
        // Newest activity first.
        assertThat(md.indexOf("Kickoff call")).isLessThan(md.indexOf("Budget approved"));
        LoopbackApi.Recorded get = api.requests(DEALS + "/9").get(0);
        assertThat(get.param("associations")).contains("companies", "contacts", "notes", "calls", "meetings", "emails", "tasks");
        assertThat(get.param("properties")).contains("dealname", "dealstage", "description");
        JsonNode batch = body(COMPANIES + "/batch/read", 0);
        assertThat(batch.path("inputs")).hasSize(1);
        assertThat(batch.path("inputs").path(0).path("id").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("a type the connection may not read is left out of the document rather than failing it")
    void missingScopeIsLeftOut() throws IOException {
        api.on("GET", "/crm/objects/" + V + "/tickets/7").json("{'id':'7','properties':{'subject':'Printer jam',"
                + "'content':'Tray two.'},'associations':{'contacts':{'results':[{'id':'5'}]}}}");
        api.on("POST", "/crm/objects/" + V + "/contacts/batch/read").status(403).json("{'status':'error','category':'MISSING_SCOPES'}");
        String md = text(fetch("ticket:7"));
        assertThat(md).startsWith("# Printer jam").contains("## Description", "Tray two.").doesNotContain("Associated records");
    }

    @Test
    @DisplayName("a deleted or archived record is gone, any other 404 is not, and an id of another shape is refused")
    void goneItems() {
        api.on("GET", COMPANIES + "/2").status(404).json("{'status':'error','category':'OBJECT_NOT_FOUND'}");
        api.on("GET", COMPANIES + "/3").json("{'id':'3','properties':{'name':'Initech'},'archived':true}");
        assertThatThrownBy(() -> fetch("company:2")).isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("company:3")).isInstanceOf(ItemGoneException.class);
        // A 404 that is not about the record - an address HubSpot does not serve - is a failure, not a deletion.
        api.on("GET", COMPANIES + "/4").status(404).text("Not Found");
        assertThatThrownBy(() -> fetch("company:4")).isNotInstanceOf(ItemGoneException.class)
                .hasMessage("HubSpot has nothing at that address (404).");
        assertThatThrownBy(() -> fetch("company:../2")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a HubSpot source.");
        assertThatThrownBy(() -> fetch("lead:2")).isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("the burst limit is waited out; the daily limit is reported, and a pass stops before spending the day's reserve")
    void rateLimits() {
        api.on("GET", COMPANIES).status(429).header("X-HubSpot-RateLimit-Interval-Milliseconds", "10000")
                .header("X-HubSpot-RateLimit-Remaining", "0")
                .json("{'status':'error','message':'You have reached your ten_secondly_rolling limit.',"
                        + "'errorType':'RATE_LIMIT','policyName':'TEN_SECONDLY_ROLLING'}")
                .then().json("{'results':[" + company("1", "Acme", "2026-10-01T10:00:00Z") + "]}");
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).hasSize(1);
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(10));

        api.reset();
        waits.clear();
        api.on("GET", COMPANIES).status(429).header("X-HubSpot-RateLimit-Daily", "250000")
                .header("X-HubSpot-RateLimit-Daily-Remaining", "0")
                .json("{'status':'error','message':'You have reached your daily limit.',"
                        + "'errorType':'RATE_LIMIT','policyName':'DAILY'}");
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("HubSpot is limiting how often it is asked. The next sync will try again.");
        }
        // Not sat out: the day's allowance comes back at midnight, so the next sync asks again.
        assertThat(api.requests(COMPANIES)).hasSize(1);

        api.reset();
        api.on("GET", COMPANIES).json("{'results':[" + company("1", "Acme", "2026-10-01T10:00:00Z") + "]}",
                "X-HubSpot-RateLimit-Daily", "250000", "X-HubSpot-RateLimit-Daily-Remaining", "1200");
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).startsWith("HubSpot's daily request allowance is nearly used up");
        }
    }

    @Test
    @DisplayName("a refused token says what to do, and registration checks every chosen type can be read")
    void refusalsAndProbe() {
        api.on("GET", COMPANIES).status(401).json("{'status':'error','category':'INVALID_AUTHENTICATION'}");
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("HubSpot refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("HubSpot did not accept the token. Replace it in Settings > Connections.");
        }
        api.reset();
        api.on("GET", "/account-info/" + V + "/details").json("{'portalId':4455}");
        api.on("GET", COMPANIES).json("{'results':[]}");
        api.on("GET", "/crm/objects/" + V + "/tickets").status(403).json("{'status':'error','category':'MISSING_SCOPES'}");
        try (ConnectorSession s = session(Map.of("objects", "companies,tickets"))) {
            assertThat(s.verifyAccess().detail())
                    .isEqualTo("HubSpot will not let this connection read tickets. Give its key the read scope for tickets.");
        }
        try (ConnectorSession s = session(Map.of("objects", "companies"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
    }

    @Test
    @DisplayName("a sign-in's token is a bearer too, refreshed once when HubSpot refuses it")
    void signIn() {
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.HUBSPOT, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.reset();
        api.on("GET", "/account-info/" + V + "/details").status(401).json("{'status':'error'}")
                .then().json("{'portalId':4455}");
        api.on("GET", COMPANIES).json("{'results':[]}");
        try (ConnectorSession s = session(Map.of("objects", "companies"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests("/account-info/" + V + "/details")).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test names the portal, and its sign-in asks HubSpot for the CRM read scopes")
    void connectionTest() {
        HubSpotConnectionPlugin connection = new HubSpotConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in to portal 4455");
        assertThat(ok.template()).isEqualTo("Signed in to portal {portal}");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + TOKEN);

        api.reset();
        api.on("GET", "/account-info/" + V + "/details").status(401).json("{'status':'error'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("HubSpot did not accept the token.");

        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://app.hubspot.com/oauth/authorize");
        assertThat(connection.oauth2().tokenUrl()).isEqualTo("https://api.hubapi.com/oauth/v1/token");
        assertThat(connection.oauth2().scopes()).contains("crm.objects.companies.read", "crm.objects.deals.read", "tickets");
    }

    @Test
    @DisplayName("an older API version is spoken on the old paths")
    void legacyVersion() {
        api.on("GET", "/crm/v3/objects/companies").json("{'results':[" + company("1", "Acme", "2026-10-01T10:00:00Z") + "]}");
        api.on("GET", "/account-info/v3/details").json("{'portalId':4455}");
        try (ConnectorSession s = session(Map.of("objects", "companies", "apiVersion", "v3"))) {
            assertThat(drain(s.list(ListRequest.full()))).hasSize(1);
        }
        assertThat(HubSpotApi.objects("2026-09", "deals")).isEqualTo("crm/objects/2026-09/deals");
        assertThatThrownBy(() -> ConfigValidator.validate(HubSpotConnectorPlugin.DESCRIPTOR.fields(), Map.of("apiVersion", "latest")))
                .hasMessageStartingWith("API version is not in the form expected");
    }

    @Test
    @DisplayName("the descriptor: identity is the object types sorted; not shareable; incremental")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(HubSpotConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("objects", "tickets,companies")));
        assertThat(plugin.identity(config)).isEqualTo("companies,tickets");
        assertThat(plugin.displayName(config)).isEqualTo("HubSpot companies, tickets");
        assertThat(HubSpotConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(HubSpotConnectorPlugin.DESCRIPTOR.capabilities().incremental()).isTrue();
        assertThatThrownBy(() -> ConfigValidator.validate(HubSpotConnectorPlugin.DESCRIPTOR.fields(), Map.of("objects", "leads")))
                .hasMessage("Records must be one of the choices offered.");
    }

    @Test
    @DisplayName("rich text reads as plain text")
    void plainText() {
        assertThat(HubSpotSession.plain("<p>One &lt;two&gt;</p><p>Three&nbsp;four</p>")).isEqualTo("One <two>\nThree four");
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
}
