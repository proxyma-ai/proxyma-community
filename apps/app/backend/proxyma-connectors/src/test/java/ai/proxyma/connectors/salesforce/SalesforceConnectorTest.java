package ai.proxyma.connectors.salesforce;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Salesforce connector against a loopback org: SOQL listings followed through nextRecordsUrl,
 * the SystemModstamp filter and the getDeleted resource on a CHANGES pass, the allow-listed fields
 * cut to what the describe shows, the rendered record with its related records, the JWT bearer
 * exchange - the assertion's claims and signature, the instance_url it answers used as the API's
 * address and refused when it is not Salesforce's - the org's spent allowance, and the Test.
 */
class SalesforceConnectorTest {

    private static final String TOKEN = "00D-session-token";
    private static final String DATA = "/services/data/v" + SalesforceApi.DEFAULT_VERSION + "/";
    private static final String QUERY = DATA + "query";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT_LIST = "SELECT Id, SystemModstamp, Name, Owner.Name FROM Account ORDER BY SystemModstamp, Id";
    private LoopbackApi api;
    private final List<Duration> waits = new ArrayList<>();
    private final SalesforceConnectorPlugin plugin = new SalesforceConnectorPlugin();

    @BeforeEach
    void start() {
        SalesforceJwt.forgetAll();
        api = LoopbackApi.start();
        describe("", "Account", "Name", "Type", "Phone", "Description", "CreatedDate", "LastModifiedDate",
                "OwnerId:Owner");
        describe("", "Case", "CaseNumber", "Subject", "Status", "Priority", "Description", "AccountId:Account",
                "OwnerId:Owner");
        describe("", "Opportunity", "Name", "StageName", "Amount", "CloseDate", "OwnerId:Owner");
    }

    @AfterEach
    void stop() {
        api.close();
    }

    // A describe answering the fields named - "Name", or "OwnerId:Owner" for a lookup and its relationship.
    private void describe(String prefix, String object, String... fields) {
        StringBuilder json = new StringBuilder("{'name':'" + object + "','fields':[");
        for (int i = 0; i < fields.length; i++) {
            String[] parts = fields[i].split(":");
            if (i > 0) json.append(',');
            json.append("{'name':'").append(parts[0]).append("','label':'").append(label(parts[0])).append("','relationshipName':")
                    .append(parts.length > 1 ? "'" + parts[1] + "'" : "null").append('}');
        }
        api.on("GET", prefix + DATA + "sobjects/" + object + "/describe").json(json.append("]}").toString());
    }

    private static String label(String name) {
        return switch (name) {
            case "StageName" -> "Stage";
            case "CloseDate" -> "Close Date";
            case "LastModifiedDate" -> "Last Modified Date";
            case "CreatedDate" -> "Created Date";
            default -> name;
        };
    }

    private ConnectorSession session(Map<String, Object> fields) {
        return session(fields, ResolvedCredentials.token(api.baseUrl(), TOKEN));
    }

    private ConnectorSession session(Map<String, Object> fields, ResolvedCredentials credentials) {
        Map<String, String> config = ConfigValidator.validate(SalesforceConnectorPlugin.DESCRIPTOR.fields(), fields);
        return plugin.open(SourceContext.of(ConnectorType.SALESFORCE, config, credentials, ConnectorHttp.direct(waits::add)));
    }

    private static List<ItemMetadata> drain(Listing listing) {
        List<ItemMetadata> out = new ArrayList<>();
        listing.forEachRemaining(out::add);
        return out;
    }

    private static String account(String id, String name, String stamp) {
        return "{'attributes':{'type':'Account'},'Id':'" + id + "','SystemModstamp':'" + stamp + "','Name':'" + name
                + "','Owner':{'attributes':{'type':'User'},'Name':'Ana Silva'}}";
    }

    @Test
    @DisplayName("a full listing pages through the SOQL answer by its nextRecordsUrl, one item per record")
    void fullListing() {
        api.on("GET", QUERY).query("q", ACCOUNT_LIST).json("{'totalSize':3,'done':false,"
                + "'nextRecordsUrl':'" + DATA + "query/01gD0000002HU6KIAW-2000','records':["
                + account("001000000000001AAA", "Acme", "2026-10-01T10:00:00.000+0000") + ","
                + account("001000000000002AAA", "Globex", "2026-10-02T10:00:00.000+0000") + "]}");
        api.on("GET", DATA + "query/01gD0000002HU6KIAW-2000").json("{'totalSize':3,'done':true,'records':["
                + account("001000000000003AAA", "Initech", "2026-10-03T10:00:00.000+0000") + "]}");

        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.full());
            List<ItemMetadata> items = drain(listing);
            assertThat(items).extracting(ItemMetadata::getId)
                    .containsExactly("Account:001000000000001AAA", "Account:001000000000002AAA", "Account:001000000000003AAA");
            ItemMetadata first = items.get(0);
            assertThat(first.getTitle()).isEqualTo("Acme");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getLastModified()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(first.getExtra("url")).isEqualTo(api.baseUrl() + "/001000000000001AAA");
            assertThat(first.getExtra(ItemMetadata.AUTHOR)).isEqualTo("Ana Silva");
            assertThat(listing.hasErrored()).isFalse();
            assertThat(listing.isPartial()).isFalse();
        }
        assertThat(api.requests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
        assertThat(api.requests(DATA + "sobjects/Account/deleted/")).isEmpty();
    }

    @Test
    @DisplayName("Account, Opportunity and Case by default; a case is titled by its number and subject")
    void defaults() {
        Map<String, String> config = ConfigValidator.validate(SalesforceConnectorPlugin.DESCRIPTOR.fields(), Map.of());
        assertThat(SalesforceConnectorPlugin.objects(config)).containsExactly("Account", "Case", "Opportunity");
        api.on("GET", QUERY).query("q", "SELECT Id, SystemModstamp, CaseNumber, Subject, Owner.Name FROM Case ORDER BY SystemModstamp, Id")
                .json("{'done':true,'records':[{'Id':'500000000000001AAA','SystemModstamp':'2026-10-01T10:00:00.000+0000',"
                        + "'CaseNumber':'00001026','Subject':'Login loop','Owner':null}]}");
        try (ConnectorSession s = session(Map.of("objects", "Case"))) {
            assertThat(drain(s.list(ListRequest.full()))).extracting(ItemMetadata::getTitle).containsExactly("00001026 Login loop");
        }
    }

    @Test
    @DisplayName("a changes pass asks for SystemModstamp after the last pass less a minute, and lists what getDeleted names")
    void incrementalListing() {
        Instant since = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        String from = SalesforceSession.SOQL_DATETIME.format(since.minus(SalesforceSession.OVERLAP));
        api.on("GET", QUERY).query("q", "SELECT Id, SystemModstamp, Name, Owner.Name FROM Account WHERE SystemModstamp > "
                + from + " ORDER BY SystemModstamp, Id").json("{'done':true,'records':["
                + account("001000000000004AAA", "Umbrella", "2026-10-12T09:30:00.000+0000") + "]}");
        api.on("GET", DATA + "sobjects/Account/deleted/").json("{'deletedRecords':[{'id':'001000000000009AAA',"
                + "'deletedDate':'2026-10-12T09:20:00.000+0000'}],'earliestDateAvailable':'2026-09-20T00:00:00.000+0000'}");

        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).extracting(ItemMetadata::getId)
                    .containsExactly("Account:001000000000004AAA", "Account:001000000000009AAA");
            assertThat(listing.hasErrored()).isFalse();
        }
        LoopbackApi.Recorded deleted = api.requests(DATA + "sobjects/Account/deleted/").get(0);
        assertThat(deleted.param("start")).isEqualTo(SalesforceSession.DELETED_DATETIME.format(since.minus(SalesforceSession.OVERLAP)));
        assertThat(deleted.param("end")).endsWith("+00:00");

        // The deleted record's fetch: SOQL no longer finds it.
        api.on("GET", QUERY).query("q", "SELECT Id, Name, Type, Phone, Owner.Name, CreatedDate, LastModifiedDate, Description "
                + "FROM Account WHERE Id = '001000000000009AAA'").json("{'done':true,'records':[]}");
        assertThatThrownBy(() -> fetch("Account:001000000000009AAA")).isInstanceOf(ItemGoneException.class);
    }

    @Test
    @DisplayName("a changes pass older than getDeleted's window leaves deletions to the full pass")
    void oldWindowSkipsDeleted() {
        Instant since = Instant.now().minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.SECONDS);
        api.on("GET", QUERY).json("{'done':true,'records':[]}");
        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.changes(since, null));
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(api.requests(DATA + "sobjects/Account/deleted/")).isEmpty();
    }

    @Test
    @DisplayName("a record is one markdown document: the allow-listed fields the describe shows, related records, notes and activity")
    void fetchAccount() throws IOException {
        String id = "001000000000001AAA";
        // Industry is on the allow-list but not in this org's describe (field-level security): it is not asked for.
        api.on("GET", QUERY).query("q", "SELECT Id, Name, Type, Phone, Owner.Name, CreatedDate, LastModifiedDate, Description "
                + "FROM Account WHERE Id = '" + id + "'").json("{'done':true,'records':[{'Id':'" + id + "','Name':'Acme',"
                + "'Type':'Customer','Phone':'+1 555 0100','Owner':{'Name':'Ana Silva'},'CreatedDate':'2026-01-05T09:00:00.000+0000',"
                + "'LastModifiedDate':'2026-10-01T10:00:00.000+0000','Description':'Our oldest customer.'}]}");
        api.on("GET", QUERY).query("q", "SELECT Name, Title, Email, Phone FROM Contact WHERE AccountId = '" + id
                + "' ORDER BY Name LIMIT 50").json("{'done':true,'records':[{'Name':'Bo Tran','Title':'CFO','Email':'bo@acme.example','Phone':null}]}");
        api.on("GET", QUERY).query("q", "SELECT Name, StageName, Amount, CloseDate FROM Opportunity WHERE AccountId = '" + id
                + "' ORDER BY CloseDate DESC LIMIT 25").json("{'done':true,'records':[{'Name':'Acme renewal','StageName':'Negotiation',"
                + "'Amount':12000.0,'CloseDate':'2026-11-30'}]}");
        // Cases: the org refuses the query (an object the user cannot see) - the section is left out.
        api.on("GET", QUERY).query("q", "SELECT CaseNumber, Subject, Status FROM Case WHERE AccountId = '" + id
                + "' ORDER BY CreatedDate DESC LIMIT 25").status(400).rawJson("[{\"errorCode\":\"INVALID_TYPE\",\"message\":\"sObject type 'Case' is not supported.\"}]");
        api.on("GET", QUERY).query("q", "SELECT Title, Body, CreatedDate, CreatedBy.Name FROM Note WHERE ParentId = '" + id
                + "' ORDER BY CreatedDate DESC LIMIT 20").json("{'done':true,'records':[{'Title':'Kickoff','Body':'Budget approved.',"
                + "'CreatedDate':'2026-10-02T08:00:00.000+0000','CreatedBy':{'Name':'Ana Silva'}}]}");
        api.on("GET", QUERY).query("q", "SELECT ContentDocumentId FROM ContentDocumentLink WHERE LinkedEntityId = '" + id
                + "' AND ContentDocument.FileType = 'SNOTE' LIMIT 20").json("{'done':true,'records':[{'ContentDocumentId':'069000000000001AAA'}]}");
        api.on("GET", QUERY).query("q", "SELECT Title, TextPreview, LastModifiedDate FROM ContentNote WHERE Id IN "
                + "('069000000000001AAA') ORDER BY LastModifiedDate DESC").json("{'done':true,'records':[{'Title':'Pricing call',"
                + "'TextPreview':'They want a three-year term.','LastModifiedDate':'2026-10-03T08:00:00.000+0000'}]}");
        api.on("GET", QUERY).query("q", "SELECT Subject, Status, ActivityDate, Description, Owner.Name FROM Task WHERE WhatId = '"
                + id + "' ORDER BY ActivityDate DESC NULLS LAST LIMIT 25").json("{'done':true,'records':[{'Subject':'Send the quote',"
                + "'Status':'Completed','ActivityDate':'2026-10-04','Description':'Sent by email.','Owner':{'Name':'Ana Silva'}}]}");

        String md = text(fetch("Account:" + id));

        assertThat(md).startsWith("# Acme")
                .contains("- Object: Account", "- Type: Customer", "- Phone: +1 555 0100", "- Owner: Ana Silva",
                        "- Created Date: 2026-01-05T09:00:00.000+0000", "- Link: " + api.baseUrl() + "/" + id,
                        "## Description", "Our oldest customer.",
                        "## Contacts", "- Bo Tran - CFO, bo@acme.example",
                        "## Opportunities", "- Acme renewal - Negotiation, 12000.0, 2026-11-30",
                        "## Notes", "### Kickoff", "2026-10-02T08:00:00.000+0000, by Ana Silva", "Budget approved.",
                        "### Pricing call", "They want a three-year term.",
                        "## Activity", "### Task: Send the quote", "2026-10-04, Completed, by Ana Silva", "Sent by email.")
                .doesNotContain("Industry", "## Cases");
    }

    @Test
    @DisplayName("a case carries its comments, an opportunity its contact roles")
    void caseAndOpportunity() throws IOException {
        String caseId = "500000000000001AAA";
        api.on("GET", QUERY).query("q", "SELECT Id, CaseNumber, Subject, Status, Priority, Account.Name, Owner.Name, Description "
                + "FROM Case WHERE Id = '" + caseId + "'").json("{'done':true,'records':[{'Id':'" + caseId + "','CaseNumber':'00001026',"
                + "'Subject':'Login loop','Status':'New','Priority':'High','Account':{'Name':'Acme'},'Owner':null,'Description':'Loops.'}]}");
        api.on("GET", QUERY).query("q", "SELECT CommentBody, CreatedDate, CreatedBy.Name FROM CaseComment WHERE ParentId = '"
                + caseId + "' ORDER BY CreatedDate LIMIT 100").json("{'done':true,'records':[{'CommentBody':'Cleared the cookie.',"
                + "'CreatedDate':'2026-10-05T08:00:00.000+0000','CreatedBy':{'Name':'Bo Tran'}}]}");
        assertThat(text(fetch("Case:" + caseId))).startsWith("# 00001026 Login loop")
                .contains("- Account: Acme", "- Priority: High", "## Comments", "**Bo Tran**, 2026-10-05T08:00:00.000+0000:",
                        "Cleared the cookie.");

        String oppId = "006000000000001AAA";
        api.on("GET", QUERY).query("q", "SELECT Id, Name, StageName, Amount, CloseDate, Owner.Name FROM Opportunity WHERE Id = '"
                + oppId + "'").json("{'done':true,'records':[{'Id':'" + oppId + "','Name':'Acme renewal','StageName':'Negotiation'}]}");
        api.on("GET", QUERY).query("q", "SELECT Contact.Name, Role, IsPrimary FROM OpportunityContactRole WHERE OpportunityId = '"
                + oppId + "' LIMIT 50").json("{'done':true,'records':[{'Contact':{'Name':'Bo Tran'},'Role':'Decision Maker','IsPrimary':true}]}");
        assertThat(text(fetch("Opportunity:" + oppId))).contains("- Stage: Negotiation", "## Contact roles",
                "- Bo Tran - Decision Maker, primary");
    }

    @Test
    @DisplayName("an id of another shape is refused before anything is asked, and a 404 is not a deletion")
    void badIds() {
        assertThatThrownBy(() -> fetch("Account:001' OR Name != '")).isInstanceOf(ConnectorException.class)
                .hasMessage("That is not an item of a Salesforce source.");
        assertThatThrownBy(() -> fetch("User:005000000000001AAA")).isInstanceOf(ConnectorException.class);
        assertThat(api.requests(QUERY)).isEmpty();
        // A 404 is an address the org does not serve - an API version, say - never a deleted record.
        assertThatThrownBy(() -> fetch("Account:001000000000001AAA")).isNotInstanceOf(ItemGoneException.class)
                .hasMessage("Salesforce has nothing at that address (404).");
    }

    @Test
    @DisplayName("the JWT bearer flow: a signed assertion for the integration user, the instance_url as the API's address")
    void jwtBearer() throws Exception {
        KeyPair keys = rsa();
        api.on("POST", "/services/oauth2/token").json("{'access_token':'jwt-1','instance_url':'" + api.baseUrl() + "/org',"
                        + "'id':'https://login.salesforce.com/id/00D/005','token_type':'Bearer','scope':'api'}")
                .then().json("{'access_token':'jwt-2','instance_url':'" + api.baseUrl() + "/org','token_type':'Bearer'}");
        describe("/org", "Account", "Name", "OwnerId:Owner");
        api.on("GET", "/org" + QUERY).status(401).rawJson("[{\"errorCode\":\"INVALID_SESSION_ID\",\"message\":\"Session expired\"}]")
                .then().json("{'done':true,'records':[" + account("001000000000001AAA", "Acme", "2026-10-01T10:00:00.000+0000") + "]}");

        try (ConnectorSession s = session(Map.of("objects", "Account"), jwt(api.baseUrl(), pem(keys, false)))) {
            List<ItemMetadata> items = drain(s.list(ListRequest.full()));
            assertThat(items).extracting(ItemMetadata::getId).containsExactly("Account:001000000000001AAA");
            assertThat(items.get(0).getExtra("url")).isEqualTo(api.baseUrl() + "/org/001000000000001AAA");
        }

        LoopbackApi.Recorded exchange = api.requests("/services/oauth2/token").get(0);
        Map<String, String> form = form(exchange.body());
        assertThat(form.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
        String[] jwt = form.get("assertion").split("\\.");
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(jwt[0]));
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(claims.path("iss").asText()).isEqualTo("3MVG9-consumer-key");
        assertThat(claims.path("sub").asText()).isEqualTo("integration@acme.com");
        assertThat(claims.path("aud").asText()).isEqualTo("https://login.salesforce.com");
        assertThat(claims.path("exp").asLong() - Instant.now().getEpochSecond()).isBetween(60L, 180L);
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();

        // The token goes to the instance, and a refused one is exchanged for a new one, once.
        assertThat(api.requests("/org" + QUERY)).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer jwt-1", "Bearer jwt-2");
        assertThat(api.requests("/services/oauth2/token")).hasSize(2);
        assertThat(exchange.header("Authorization")).isNull();
    }

    @Test
    @DisplayName("a PKCS#1 key reads like a PKCS#8 one, and a sandbox address signs in at test.salesforce.com")
    void keysAndAudiences() throws Exception {
        KeyPair keys = rsa();
        assertThat(SalesforceJwt.privateKey(pem(keys, true)).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        // Pasted into a one-line field, the line breaks are gone.
        assertThat(SalesforceJwt.privateKey(pem(keys, false).replace("\n", "")).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());

        assertThat(SalesforceJwt.audienceFor("https://acme.my.salesforce.com")).isEqualTo("https://login.salesforce.com");
        assertThat(SalesforceJwt.audienceFor("https://acme--uat.sandbox.my.salesforce.com")).isEqualTo("https://test.salesforce.com");
        assertThat(SalesforceJwt.audienceFor("https://test.salesforce.com")).isEqualTo("https://test.salesforce.com");
    }

    @Test
    @DisplayName("a My Domain URL copied from the browser is the org's address: its path dropped, a Lightning or Setup host its My Domain")
    void myDomainAddresses() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("https://acme.lightning.force.com/lightning/page/home", "https://acme.my.salesforce.com");
        cases.put("https://acme--uat.sandbox.lightning.force.com/lightning/o/Account/list?filterName=Recent",
                "https://acme--uat.sandbox.my.salesforce.com");
        cases.put("https://acme-dev-ed.develop.lightning.force.com/", "https://acme-dev-ed.develop.my.salesforce.com");
        cases.put("https://acme.my.salesforce-setup.com/lightning/setup/SetupOneHome/home", "https://acme.my.salesforce.com");
        cases.put("https://acme.my.salesforce.com/", "https://acme.my.salesforce.com");
        cases.put("https://ACME.my.salesforce.com/services/oauth2/token", "https://acme.my.salesforce.com");
        cases.put("https://login.salesforce.com/?locale=uk", "https://login.salesforce.com");
        // Not Salesforce's host: kept up to Salesforce's own paths, so a relay under a path still works.
        cases.put("https://relay.example/salesforce/lightning/page/home", "https://relay.example/salesforce");
        cases.put("https://relay.example/salesforce/", "https://relay.example/salesforce");
        cases.forEach((typed, org) -> assertThat(SalesforceApi.myDomain(typed)).as(typed).isEqualTo(org));

        // The browser sign-in goes to the org's My Domain, not to the Lightning host typed.
        assertThat(new SalesforceConnectionPlugin().oauth2(Map.of(), "https://acme.lightning.force.com/lightning/page/home")
                .tokenUrl()).isEqualTo("https://acme.my.salesforce.com/services/oauth2/token");
    }

    @Test
    @DisplayName("the JWT sign-in at an address with a page's path asks the token endpoint at the org's root; a page or an error in its place says so")
    void jwtAtAPastedAddress() throws Exception {
        KeyPair keys = rsa();
        api.on("POST", "/services/oauth2/token").json("{'access_token':'jwt-1','instance_url':'" + api.baseUrl() + "'}");
        api.on("GET", "/services/oauth2/userinfo").json("{'preferred_username':'integration@acme.com'}");
        SalesforceConnectionPlugin connection = new SalesforceConnectionPlugin();
        ConnectionTestResult ok = connection.test(jwt(api.baseUrl() + "/lightning/page/home", pem(keys, false)),
                ConnectorHttp.direct());
        assertThat(ok.message()).isEqualTo("Signed in as integration@acme.com on 127.0.0.1");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path)
                .containsExactly("/services/oauth2/token", "/services/oauth2/userinfo");

        // A sign-in page where the token endpoint should be: named for what it is.
        api.reset();
        SalesforceJwt.forgetAll();
        api.on("POST", "/services/oauth2/token").body("<html><body>Login</body></html>".getBytes(StandardCharsets.UTF_8),
                "text/html;charset=UTF-8");
        ConnectionTestResult page = connection.test(jwt(api.baseUrl(), pem(keys, false)), ConnectorHttp.direct());
        assertThat(page.ok()).isFalse();
        assertThat(page.message()).isEqualTo("Salesforce answered with a web page instead of its API. Check the connection's address.");

        // An answer with no token but a reason keeps the reason.
        api.reset();
        SalesforceJwt.forgetAll();
        api.on("POST", "/services/oauth2/token").rawJson("{\"error\":\"invalid_client\",\"error_description\":\"invalid client credentials\"}");
        ConnectionTestResult reason = connection.test(jwt(api.baseUrl(), pem(keys, false)), ConnectorHttp.direct());
        assertThat(reason.message()).isEqualTo("Salesforce answered the sign-in without a token (invalid client credentials).");
    }

    @Test
    @DisplayName("an instance_url that is not Salesforce's is refused, and a refused assertion says what to check")
    void jwtRefusals() throws Exception {
        KeyPair keys = rsa();
        api.on("POST", "/services/oauth2/token").json("{'access_token':'jwt-1','instance_url':'https://collector.example.net'}");
        try (ConnectorSession s = session(Map.of("objects", "Account"), jwt(api.baseUrl(), pem(keys, false)))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Salesforce named collector.example.net as the org's address, "
                    + "which is not a Salesforce server, so the token was not sent there.");
        }
        api.reset();
        SalesforceJwt.forgetAll();
        api.on("POST", "/services/oauth2/token").status(400)
                .rawJson("{\"error\":\"invalid_grant\",\"error_description\":\"user hasn't approved this consumer\"}");
        try (ConnectorSession s = session(Map.of("objects", "Account"), jwt(api.baseUrl(), pem(keys, false)))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Salesforce refused the JWT sign-in (user hasn't approved this "
                    + "consumer). Check the consumer key, the user name, the certificate uploaded to the app, and that the user "
                    + "is pre-authorized for it.");
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.hasErrored()).isTrue();
        }
        try (ConnectorSession s = session(Map.of("objects", "Account"), jwt(api.baseUrl(), "not a key"))) {
            assertThat(s.verifyAccess().detail()).startsWith("The private key could not be read.");
        }
    }

    @Test
    @DisplayName("the org's spent allowance is a rate limit, not a refusal; a refused token says what to do")
    void limitsAndRefusals() {
        api.on("GET", QUERY).status(403).rawJson("[{\"errorCode\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"TotalRequests Limit exceeded.\"}]");
        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Salesforce is limiting how often it is asked. The next sync will try again.");
        }
        assertThat(api.requests(QUERY)).hasSize(1);

        api.reset();
        api.on("GET", DATA + "sobjects/Account/describe").status(401).rawJson("[{\"errorCode\":\"INVALID_SESSION_ID\"}]");
        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.full());
            drain(listing);
            assertThat(listing.getErrorMessage()).isEqualTo("Salesforce refused the connection's credential. "
                    + "Replace it, or sign in again, in Settings > Connections.");
            assertThat(s.verifyAccess().detail()).isEqualTo("Salesforce did not accept the connection's sign-in. Sign in "
                    + "again, or check its keys, in Settings > Connections.");
        }

        api.reset();
        api.on("GET", QUERY).status(429).header("Retry-After", "2").json("{}")
                .then().json("{'done':true,'records':[]}");
        describe("", "Account", "Name");
        try (ConnectorSession s = session(Map.of("objects", "Account"))) {
            Listing listing = s.list(ListRequest.full());
            assertThat(drain(listing)).isEmpty();
            assertThat(listing.hasErrored()).isFalse();
        }
        assertThat(waits).contains(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("registration checks the user can see every chosen object; a sign-in's token is refreshed once")
    void probeAndSignIn() {
        api.on("GET", DATA + "sobjects/Lead/describe").status(404).rawJson("[{\"errorCode\":\"NOT_FOUND\"}]");
        try (ConnectorSession s = session(Map.of("objects", "Account,Lead"))) {
            assertThat(s.verifyAccess().detail()).isEqualTo("Salesforce has no object Lead, or the connection's user cannot see it.");
        }
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "oauth-1";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "oauth-2"; }
        };
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.SALESFORCE, AuthKind.OAUTH2_CODE,
                api.baseUrl(), null, Map.of(), Map.of(), tokens);
        api.reset();
        api.on("GET", DATA + "sobjects/Account/describe").status(401).rawJson("[{\"errorCode\":\"INVALID_SESSION_ID\"}]")
                .then().json("{'fields':[{'name':'Name','label':'Name','relationshipName':null}]}");
        try (ConnectorSession s = session(Map.of("objects", "Account"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer oauth-1", "Bearer oauth-2");
    }

    @Test
    @DisplayName("the connection's Test names the user and the org; a sign-in needs the org's own address")
    void connectionTest() throws Exception {
        SalesforceConnectionPlugin connection = new SalesforceConnectionPlugin();
        api.on("GET", "/services/oauth2/userinfo").json("{'preferred_username':'ana@acme.com','name':'Ana Silva'}");
        ConnectionTestResult ok = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as ana@acme.com on 127.0.0.1");
        assertThat(ok.template()).isEqualTo("Signed in as {user} on {instance}");

        ConnectionTestResult login = connection.test(new ResolvedCredentials(null, ConnectionProtocol.SALESFORCE,
                AuthKind.OAUTH2_CODE, "https://login.salesforce.com", null, Map.of(), Map.of(), () -> "t"), ConnectorHttp.direct());
        assertThat(login.ok()).isFalse();
        assertThat(login.message()).startsWith("A sign-in needs the org's own My Domain URL");

        // The JWT flow without the id scope: the token works, and its user is the one configured.
        api.reset();
        KeyPair keys = rsa();
        api.on("POST", "/services/oauth2/token").json("{'access_token':'jwt-1','instance_url':'" + api.baseUrl() + "'}");
        api.on("GET", "/services/oauth2/userinfo").status(403).json("{'error':'insufficient_scope'}");
        ConnectionTestResult jwt = connection.test(jwt(api.baseUrl(), pem(keys, false)), ConnectorHttp.direct());
        assertThat(jwt.ok()).isTrue();
        assertThat(jwt.message()).isEqualTo("Signed in as integration@acme.com on 127.0.0.1");

        api.reset();
        api.on("GET", "/services/oauth2/userinfo").status(401).json("{'error':'invalid_token'}");
        ConnectionTestResult refused = connection.test(ResolvedCredentials.token(api.baseUrl(), TOKEN), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Salesforce did not accept the token.");

        assertThat(connection.oauth2().authorizeUrl()).isEqualTo("https://login.salesforce.com/services/oauth2/authorize");
        assertThat(connection.oauth2().scopes()).containsExactly("api", "id", "refresh_token");
    }

    @Test
    @DisplayName("a browser sign-in goes to the org's own Salesforce host - a sandbox's included - and anywhere else to the production login server")
    void signInAddressesPerConnection() {
        SalesforceConnectionPlugin connection = new SalesforceConnectionPlugin();
        assertThat(connection.oauth2(Map.of(), "https://acme--uat.sandbox.my.salesforce.com/").authorizeUrl())
                .isEqualTo("https://acme--uat.sandbox.my.salesforce.com/services/oauth2/authorize");
        assertThat(connection.oauth2(Map.of(), "https://test.salesforce.com").tokenUrl())
                .isEqualTo("https://test.salesforce.com/services/oauth2/token");
        assertThat(connection.oauth2(Map.of(), "https://acme.my.salesforce.com").scopes()).containsExactly("api", "id", "refresh_token");
        // The client's secret goes to a Salesforce host over https and nowhere else.
        for (String other : List.of("https://salesforce.com.evil.example", "http://acme.my.salesforce.com",
                "https://acme.my.salesforce.com:8443", api.baseUrl(), "not a url")) {
            assertThat(connection.oauth2(Map.of(), other).authorizeUrl()).as(other)
                    .isEqualTo("https://login.salesforce.com/services/oauth2/authorize");
        }
        assertThat(connection.oauth2().grantFields()).containsExactly("instance_url");
    }

    @Test
    @DisplayName("a sign-in's kept instance_url is where the API is spoken, and is refused when it is not Salesforce's")
    void signInSpeaksAtTheKeptInstance() {
        api.on("GET", "/org" + DATA + "sobjects/Account/describe")
                .json("{'fields':[{'name':'Name','label':'Name','relationshipName':null}]}");
        ResolvedCredentials signIn = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.SALESFORCE,
                AuthKind.OAUTH2_CODE, api.baseUrl(), null, Map.of(), Map.of(), () -> "oauth-1",
                Map.of("instance_url", api.url("/org")));
        try (ConnectorSession s = session(Map.of("objects", "Account"), signIn)) {
            assertThat(s.verifyAccess()).isEqualTo(AccessCheck.verified());
        }
        assertThat(api.last().path()).isEqualTo("/org" + DATA + "sobjects/Account/describe");

        ResolvedCredentials elsewhere = new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.SALESFORCE,
                AuthKind.OAUTH2_CODE, api.baseUrl(), null, Map.of(), Map.of(), () -> "oauth-1",
                Map.of("instance_url", "https://collector.example.net"));
        assertThatThrownBy(() -> SalesforceApi.connect(ConnectorHttp.direct(), elsewhere))
                .hasMessageContaining("which is not a Salesforce server");
    }

    @Test
    @DisplayName("the descriptor: identity is the objects sorted in their own case; not shareable; the version pinned")
    void descriptor() {
        Map<String, String> config = ConfigValidator.validate(SalesforceConnectorPlugin.DESCRIPTOR.fields(),
                new LinkedHashMap<>(Map.of("objects", "Case,Account")));
        assertThat(plugin.identity(config)).isEqualTo("Account,Case");
        assertThat(plugin.displayName(config)).isEqualTo("Salesforce Account, Case");
        assertThat(plugin.webUrl(config, "https://acme.my.salesforce.com/")).isEqualTo("https://acme.my.salesforce.com");
        assertThat(SalesforceConnectorPlugin.DESCRIPTOR.capabilities().shareable()).isFalse();
        assertThat(SalesforceConnectorPlugin.version(config)).isEqualTo(SalesforceApi.DEFAULT_VERSION);
        assertThatThrownBy(() -> ConfigValidator.validate(SalesforceConnectorPlugin.DESCRIPTOR.fields(), Map.of("apiVersion", "v64")))
                .hasMessageStartingWith("API version is not in the form expected");
        assertThat(SalesforceSession.instant("2026-10-12T09:30:00.000+0000")).isEqualTo(Instant.parse("2026-10-12T09:30:00Z"));
    }

    // -- helpers -------------------------------------------------------------------------------

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    // The private key as PEM: PKCS#8, or PKCS#1 cut out of it (its last element, after a 26-byte header).
    private static String pem(KeyPair keys, boolean pkcs1) {
        byte[] der = keys.getPrivate().getEncoded();
        String kind = "PRIVATE KEY";
        if (pkcs1) {
            der = Arrays.copyOfRange(der, 26, der.length);
            kind = "RSA PRIVATE KEY";
        }
        return "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + kind + "-----\n";
    }

    private static ResolvedCredentials jwt(String base, String pem) {
        return new ResolvedCredentials(UUID.randomUUID(), ConnectionProtocol.SALESFORCE, AuthKind.KEY_PAIR, base,
                "integration@acme.com", Map.of(SalesforceJwt.PRIVATE_KEY, pem),
                Map.of(SalesforceJwt.CONSUMER_KEY, "3MVG9-consumer-key"), null);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
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
