package ai.proxyma.connectors.airtable;

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
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.testing.LoopbackApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Airtable connector against a loopback Airtable: the base's tables from its schema, records
 * paged by {@code offset}, a view, the incremental formula, what a pass may and may not remove, a
 * record rendered as markdown, the content hash that ignores Airtable's re-signed attachment links,
 * the token on every request, and the 429 that is a 30-second penalty rather than a hint.
 */
class AirtableConnectorTest {

    private static final String TOKEN = "pat-test-token";
    private static final String BASE = "appFakeCorpOps001";
    private static final String PROJECTS = "tblProjects000001";
    private static final String VENDORS = "tblVendors0000001";
    private static final String ACTIVE = "viwActive00000001";
    private static final String REC_1 = "recApollo00000001";
    private static final String REC_2 = "recBilling0000001";
    private static final String REC_3 = "recAcmePaper00001";
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");

    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final AirtableConnectorPlugin plugin = new AirtableConnectorPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        api.on("GET", "/meta/bases/" + BASE + "/tables").json("{'tables':["
                + "{'id':'" + PROJECTS + "','name':'Projects','primaryFieldId':'fldName','fields':["
                + "{'id':'fldName','name':'Name','type':'singleLineText'},"
                + "{'id':'fldStatus','name':'Status','type':'singleSelect'},"
                + "{'id':'fldNotes','name':'Notes','type':'multilineText'},"
                + "{'id':'fldFiles','name':'Files','type':'multipleAttachments'},"
                + "{'id':'fldOwner','name':'Owner','type':'createdBy'}],"
                + "'views':[{'id':'viwGrid0000000001','name':'Grid view','type':'grid'},"
                + "{'id':'" + ACTIVE + "','name':'Active','type':'grid'}]},"
                + "{'id':'" + VENDORS + "','name':'Vendors','primaryFieldId':'fldVendor','fields':["
                + "{'id':'fldVendor','name':'Vendor','type':'singleLineText'},"
                + "{'id':'fldChanged','name':'Changed','type':'lastModifiedTime','options':{}}],"
                + "'views':[]}]}");
        api.on("GET", "/meta/bases").json("{'bases':[{'id':'appOther0000000001','name':'Other'},"
                + "{'id':'" + BASE + "','name':'Fake Corp Ops','permissionLevel':'create'}]}");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(Map<String, Object> fields) {
        Map<String, String> config = ConfigValidator.validate(AirtableConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(new SourceContext(null, ConnectorType.AIRTABLE, null, null, config, null, null,
                ResolvedCredentials.token(api.baseUrl(), TOKEN), SourceScope.defaults(), ConnectorHttp.direct(waits::add),
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String record(String id, String name, String more) {
        return "{'id':'" + id + "','createdTime':'2026-09-01T09:00:00.000Z','fields':{'Name':'" + name + "'" + more + "}}";
    }

    // Projects over two pages; Vendors in one.
    private void records() {
        api.on("GET", "/" + BASE + "/" + PROJECTS).json("{'records':["
                + record(REC_1, "Apollo launch", ",'Status':'In progress','Owner':{'id':'usr1','email':'ana@fake.example','name':'Ana Lima'},"
                + "'Files':[{'id':'att1','filename':'plan.pdf','size':1200,'type':'application/pdf','url':'https://dl.example/a?sig=1'}]")
                + "],'offset':'itr2/rec2'}");
        api.on("GET", "/" + BASE + "/" + PROJECTS).query("offset", "itr2/rec2").json("{'records':["
                + record(REC_2, "Billing revamp", ",'Status':'Done'") + "]}");
        api.on("GET", "/" + BASE + "/" + VENDORS).json("{'records':[{'id':'" + REC_3 + "','createdTime':'2026-09-02T09:00:00.000Z',"
                + "'fields':{'Vendor':'Acme Paper','Changed':'2026-10-02T11:00:00.000Z'}}]}");
    }

    @Test
    @DisplayName("every table of the base, every page of records, each with its title, link, author and content hash")
    void fullListing() {
        records();
        try (ConnectorSession s = session(Map.of("base", BASE))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);

            assertThat(items).extracting(ItemMetadata::getId).containsExactly(
                    "record:" + PROJECTS + ":" + REC_1, "record:" + PROJECTS + ":" + REC_2, "record:" + VENDORS + ":" + REC_3);
            ItemMetadata apollo = items.get(0);
            assertThat(apollo.getTitle()).isEqualTo("Apollo launch");
            assertThat(apollo.getContentType()).isEqualTo("text/markdown");
            assertThat(apollo.getExtra("url")).isEqualTo("https://airtable.com/" + BASE + "/" + PROJECTS + "/" + REC_1);
            assertThat(apollo.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Lima");
            assertThat(apollo.getExtra(ItemMetadata.CONTENT_HASH)).hasSize(64);
            // No "Last modified time" field in Projects: observed now, and the hash decides.
            assertThat(apollo.getLastModified()).isEqualTo(NOW);
            // Vendors has one watching every field: its value is when the record changed.
            assertThat(items.get(2).getLastModified()).isEqualTo(Instant.parse("2026-10-02T11:00:00Z"));
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        assertThat(api.requests("/" + BASE + "/" + PROJECTS).get(0).param("pageSize")).isEqualTo("100");
        assertThat(api.requests("/" + BASE + "/" + PROJECTS).get(0).param("filterByFormula")).isNull();
    }

    @Test
    @DisplayName("the content hash ignores the attachment links Airtable signs afresh, and nothing else")
    void contentHash() throws IOException {
        ObjectMapper json = new ObjectMapper();
        String a = AirtableSession.hash(json.readTree("{\"Name\":\"A\",\"Files\":[{\"id\":\"att1\",\"filename\":\"p.pdf\","
                + "\"url\":\"https://dl.example/1\",\"thumbnails\":{\"small\":{\"url\":\"x\"}}}]}"));
        String b = AirtableSession.hash(json.readTree("{\"Files\":[{\"filename\":\"p.pdf\",\"id\":\"att1\","
                + "\"url\":\"https://dl.example/2\"}],\"Name\":\"A\"}"));
        String c = AirtableSession.hash(json.readTree("{\"Name\":\"B\",\"Files\":[{\"id\":\"att1\",\"filename\":\"p.pdf\","
                + "\"url\":\"https://dl.example/1\"}]}"));
        String d = AirtableSession.hash(json.readTree("{\"Name\":\"A\",\"Link\":\"https://dl.example/1\"}"));
        String e = AirtableSession.hash(json.readTree("{\"Name\":\"A\",\"Link\":\"https://dl.example/2\"}"));
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(d).isNotEqualTo(e);
    }

    @Test
    @DisplayName("a changes pass asks for what was created or changed since a minute before the last pass")
    void incrementalListing() {
        records();
        Instant since = Instant.parse("2026-10-12T09:00:00Z");
        try (ConnectorSession s = session(Map.of("base", BASE, "table", "Projects"))) {
            drain(s.list(ListRequest.changes(since, null)));
        }
        assertThat(api.requests("/" + BASE + "/" + VENDORS)).isEmpty();
        assertThat(api.requests("/" + BASE + "/" + PROJECTS).get(0).param("filterByFormula")).isEqualTo(
                "OR(IS_AFTER(LAST_MODIFIED_TIME(), DATETIME_PARSE('2026-10-12T08:59:00Z')), "
                        + "IS_AFTER(CREATED_TIME(), DATETIME_PARSE('2026-10-12T08:59:00Z')))");
    }

    @Test
    @DisplayName("a link to a view names the base, the table and the view, and the view bounds the listing")
    void viewFromLink() {
        api.on("GET", "/" + BASE + "/" + PROJECTS).query("view", ACTIVE).json("{'records':[" + record(REC_1, "Apollo launch", "") + "]}");
        try (ConnectorSession s = session(Map.of("base", "https://airtable.com/" + BASE + "/" + PROJECTS + "/" + ACTIVE + "?blocks=hide"))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getId)
                    .containsExactly("record:" + PROJECTS + ":" + REC_1);
        }
        // By name, in any case, as a person types it.
        try (ConnectorSession s = session(Map.of("base", BASE, "table", "projects", "view", "active"))) {
            assertThat(drain(s.list(ListRequest.full()))).hasSize(1);
        }
    }

    @Test
    @DisplayName("a page that fails mid-listing is an errored listing - nothing is removed for what it did not reach")
    void failedPageIsNoDeletion() {
        api.on("GET", "/" + BASE + "/" + PROJECTS).json("{'records':[" + record(REC_1, "Apollo launch", "") + "],'offset':'o2'}");
        api.on("GET", "/" + BASE + "/" + PROJECTS).query("offset", "o2").status(422)
                .json("{'error':{'type':'LIST_RECORDS_ITERATOR_NOT_AVAILABLE'}}");
        try (ConnectorSession s = session(Map.of("base", BASE, "table", PROJECTS))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).hasSize(1);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Airtable refused the request (422).");
        }
    }

    @Test
    @DisplayName("a record is fetched as markdown: its fields by name, long text as a section, attachments with their links")
    void fetchRecord() throws IOException {
        api.on("GET", "/" + BASE + "/" + PROJECTS).query("cellFormat", "string").json("{'records':[{'id':'" + REC_1 + "',"
                + "'createdTime':'2026-09-01T09:00:00.000Z','fields':{'Name':'Apollo launch','Status':'In progress',"
                + "'Notes':'Launch codeword is ZINNIA.\\nSecond line of the brief.',"
                + "'Files':'plan.pdf (https://dl.example/a?sig=1)','Owner':'Ana Lima','Extra':'kept'}}]}");

        String md = text(fetch("record:" + PROJECTS + ":" + REC_1, Map.of("base", BASE)));

        assertThat(md).startsWith("# Apollo launch\n\n").contains("- Base: Fake Corp Ops", "- Table: Projects",
                "- Created: 2026-09-01T09:00:00.000Z", "- Link: https://airtable.com/" + BASE + "/" + PROJECTS + "/" + REC_1,
                "- Status: In progress", "- Files: plan.pdf (https://dl.example/a?sig=1)", "- Owner: Ana Lima", "- Extra: kept",
                "## Notes\n\nLaunch codeword is ZINNIA.\nSecond line of the brief.");
        assertThat(md).doesNotContain("- Name:");
        var asked = api.requests("/" + BASE + "/" + PROJECTS).get(0);
        assertThat(asked.param("filterByFormula")).isEqualTo("RECORD_ID()='" + REC_1 + "'");
        assertThat(asked.param("timeZone")).isEqualTo("UTC");
        assertThat(asked.param("userLocale")).isEqualTo("en-us");
    }

    @Test
    @DisplayName("a record Airtable no longer has is gone; one in a table the source does not read is not its to read")
    void goneAndForeignRecords() {
        api.on("GET", "/" + BASE + "/" + PROJECTS).query("cellFormat", "string").json("{'records':[]}");
        assertThatThrownBy(() -> fetch("record:" + PROJECTS + ":" + REC_1, Map.of("base", BASE)))
                .isInstanceOf(ItemGoneException.class);
        assertThatThrownBy(() -> fetch("record:" + VENDORS + ":" + REC_3, Map.of("base", BASE, "table", "Projects")))
                .isInstanceOf(ConnectorException.class).isNotInstanceOf(ItemGoneException.class)
                .hasMessage("That record is not in a table this Airtable source reads.");
        assertThatThrownBy(() -> fetch("issue:1", Map.of("base", BASE))).hasMessage("That is not an item of an Airtable source.");
    }

    @Test
    @DisplayName("registration: a base the token cannot read, a table or view that is not there, a refused token")
    void accessProbe() {
        try (ConnectorSession s = session(Map.of("base", BASE, "table", "Projects", "view", "Active"))) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        try (ConnectorSession s = session(Map.of("base", BASE, "table", "Invoices"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The Airtable base has no table Invoices.");
        }
        try (ConnectorSession s = session(Map.of("base", BASE, "table", "Projects", "view", "Kanban"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("The table Projects has no view Kanban.");
        }
        try (ConnectorSession s = session(Map.of("base", BASE, "view", "Active"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Choose a table to read a view of.");
        }
        api.on("GET", "/meta/bases/appMissing0000001/tables").status(403)
                .json("{'error':{'type':'INVALID_PERMISSIONS_OR_MODEL_NOT_FOUND'}}");
        try (ConnectorSession s = session(Map.of("base", "appMissing0000001"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Airtable has no base appMissing0000001, or the token "
                    + "cannot read it. Add the base to the token, with the schema.bases:read scope.");
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isTrue();
        }
        api.reset();
        api.on("GET", "/meta/bases/" + BASE + "/tables").status(401).json("{'error':'AUTHENTICATION_REQUIRED'}");
        try (ConnectorSession s = session(Map.of("base", BASE))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Airtable did not accept the token. Replace it, or sign "
                    + "in again, in Settings > Connections.");
        }
    }

    @Test
    @DisplayName("a 429 is Airtable's 30-second penalty: waited out whole without a Retry-After, and the request repeated")
    void penaltyWindow() {
        api.on("GET", "/" + BASE + "/" + PROJECTS).status(429).json("{'errors':[{'error':'RATE_LIMIT_REACHED'}]}")
                .then().json("{'records':[]}");
        try (ConnectorSession s = session(Map.of("base", BASE, "table", PROJECTS))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(AirtableApi.PENALTY);
        assertThat(AirtableApi.PENALTY).isGreaterThanOrEqualTo(Duration.ofSeconds(30));

        // Penalty after penalty: reported as rate limited once the attempts are spent, for the next sync.
        api.reset();
        api.on("GET", "/meta/bases/" + BASE + "/tables").json("{'tables':[{'id':'" + PROJECTS + "','name':'Projects',"
                + "'primaryFieldId':'fldName','fields':[{'id':'fldName','name':'Name','type':'singleLineText'}],'views':[]}]}");
        api.on("GET", "/" + BASE + "/" + PROJECTS).status(429).json("{'errors':[{'error':'RATE_LIMIT_REACHED'}]}");
        try (ConnectorSession s = session(Map.of("base", BASE, "table", PROJECTS))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
            assertThat(listing.getErrorMessage()).isEqualTo("Airtable is limiting how often it is asked. The next sync will try again.");
        }
    }

    @Test
    @DisplayName("the connection's Test names the account, and a refused token says so")
    void connectionTest() {
        api.on("GET", "/meta/whoami").json("{'id':'usr1','email':'ana@fake.example'}");
        AirtableConnectionPlugin connection = new AirtableConnectionPlugin();
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as ana@fake.example");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer " + TOKEN);

        api.reset();
        api.on("GET", "/meta/whoami").status(401).json("{'error':{'type':'AUTHENTICATION_REQUIRED'}}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Airtable did not accept the token.");
    }

    @Test
    @DisplayName("Sign in with Airtable asks for exactly the read scopes, the client as HTTP Basic")
    void signIn() {
        OAuth2Spec spec = new AirtableConnectionPlugin().oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://airtable.com/oauth2/v1/authorize");
        assertThat(spec.tokenUrl()).isEqualTo("https://airtable.com/oauth2/v1/token");
        assertThat(spec.scopes()).containsExactly("data.records:read", "schema.bases:read", "user.email:read");
        assertThat(spec.scopes()).noneMatch(s -> s.contains("write"));
        assertThat(spec.clientAuth()).isEqualTo(OAuth2Spec.ClientAuth.BASIC);
        assertThat(AirtableConnectionPlugin.DESCRIPTOR.offers(AuthKind.TOKEN)).isTrue();
        assertThat(AirtableConnectionPlugin.DESCRIPTOR.offers(AuthKind.OAUTH2_CODE)).isTrue();
    }

    @Test
    @DisplayName("the descriptor: identity is base, table and view, a pasted link naming them as typed ones do")
    void descriptor() {
        Map<String, String> typed = ConfigValidator.validate(AirtableConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("base", BASE, "table", PROJECTS, "view", ACTIVE));
        Map<String, String> linked = ConfigValidator.validate(AirtableConnectorPlugin.DESCRIPTOR.fields(),
                Map.of("base", "https://airtable.com/" + BASE + "/" + PROJECTS + "/" + ACTIVE));
        assertThat(plugin.identity(typed)).isEqualTo(BASE + "/" + PROJECTS + "/" + ACTIVE);
        assertThat(plugin.identity(linked)).isEqualTo(plugin.identity(typed));
        Map<String, String> all = ConfigValidator.validate(AirtableConnectorPlugin.DESCRIPTOR.fields(), Map.of("base", BASE));
        assertThat(plugin.identity(all)).isEqualTo(BASE + "/*");
        assertThat(plugin.displayName(all)).isEqualTo("Airtable base " + BASE);
        assertThat(plugin.displayName(Map.of("base", BASE, "table", "Projects"))).isEqualTo("Projects in " + BASE);
        assertThat(plugin.webUrl(typed, null)).isEqualTo("https://airtable.com/" + BASE + "/" + PROJECTS);
        assertThatThrownBy(() -> ConfigValidator.validate(AirtableConnectorPlugin.DESCRIPTOR.fields(), Map.of("base", "Projects")))
                .hasMessage("Base is not in the form expected (for example appXXXXXXXXXXXXXX).");
        assertThat(AirtableConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(AirtableConnectorPlugin.DESCRIPTOR.capabilities().incremental()).isTrue();
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
