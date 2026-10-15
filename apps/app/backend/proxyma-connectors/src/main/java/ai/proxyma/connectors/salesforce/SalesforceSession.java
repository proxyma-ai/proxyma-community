package ai.proxyma.connectors.salesforce;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One Salesforce source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the objects, the API version, the connection - and nothing it holds is
 * seen by any other source. The org is reached lazily, on the first request, so a JWT sign-in that
 * fails is a failed listing or a failed probe rather than a session that cannot open.
 *
 * <p>Items are {@code <Object>:<record id>} ({@code Account:001...}). Each is rendered on fetch as
 * one markdown document.
 *
 * <p><b>The field allow-list.</b> Each object is read through a fixed list of fields that say what
 * the record is (an account's industry, an opportunity's stage and amount, a case's status), never
 * {@code FIELDS(ALL)}: a custom field can hold anything, and what is indexed should be what the
 * documentation says is indexed. The list is intersected with the object's describe, so a field the
 * user's field-level security hides, or an org without it, narrows the document rather than failing
 * it. Related records (contacts, comments, notes, activity) are read the same way, each part left out
 * when the org will not answer it.
 */
final class SalesforceSession implements ConnectorSession {

    // Clock skew between this host and Salesforce: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // getDeleted answers for the last 30 days at most; older than this, the full pass decides.
    static final Duration DELETED_WINDOW = Duration.ofDays(29);
    static final int MAX_TEXT = 2_000;
    static final String MARKDOWN = "text/markdown";
    static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9]{15}([A-Za-z0-9]{3})?");
    static final DateTimeFormatter SOQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    static final DateTimeFormatter DELETED_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SALESFORCE_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX");

    /**
     * One object as the connector reads it: what titles a record, the allow-listed fields shown
     * under it, its long text, and which activity field (WhatId or WhoId) points at it.
     */
    record Kind(String object, String label, List<String> title, List<String> fields, String body, String activityField) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        add(new Kind("Account", "Account", List.of("Name"),
                List.of("Type", "Industry", "Website", "Phone", "BillingCity", "BillingState", "BillingCountry",
                        "AnnualRevenue", "NumberOfEmployees", "Rating", "Parent.Name", "Owner.Name", "CreatedDate",
                        "LastModifiedDate"), "Description", "WhatId"));
        add(new Kind("Contact", "Contact", List.of("Name"),
                List.of("Title", "Department", "Account.Name", "Email", "Phone", "MobilePhone", "MailingCity",
                        "MailingCountry", "LeadSource", "Owner.Name", "CreatedDate", "LastModifiedDate"), "Description", "WhoId"));
        add(new Kind("Opportunity", "Opportunity", List.of("Name"),
                List.of("Account.Name", "StageName", "Amount", "CloseDate", "Probability", "Type", "LeadSource",
                        "NextStep", "ForecastCategoryName", "IsClosed", "IsWon", "Owner.Name", "CreatedDate",
                        "LastModifiedDate"), "Description", "WhatId"));
        add(new Kind("Lead", "Lead", List.of("Name"),
                List.of("Company", "Title", "Status", "Email", "Phone", "LeadSource", "Industry", "Rating", "City",
                        "Country", "Owner.Name", "CreatedDate", "LastModifiedDate"), "Description", "WhoId"));
        add(new Kind("Case", "Case", List.of("CaseNumber", "Subject"),
                List.of("Status", "Priority", "Origin", "Type", "Reason", "Account.Name", "Contact.Name", "Owner.Name",
                        "CreatedDate", "ClosedDate", "LastModifiedDate"), "Description", "WhatId"));
    }

    private static void add(Kind kind) {
        KINDS.put(kind.object(), kind);
    }

    // The relationship fields' labels; every other field is labelled as the org's describe says.
    static final Map<String, String> RELATIONSHIP_LABELS = Map.of("Owner.Name", "Owner", "Account.Name", "Account",
            "Contact.Name", "Contact", "Parent.Name", "Parent account");

    /** An object's describe, as the allow-list needs it. */
    record Describe(Map<String, String> labels, Set<String> relationships) {
        boolean has(String field) {
            int dot = field.indexOf('.');
            return dot > 0 ? relationships.contains(field.substring(0, dot)) : labels.containsKey(field);
        }

        String label(String field) {
            String label = RELATIONSHIP_LABELS.get(field);
            return label != null ? label : labels.getOrDefault(field, field);
        }
    }

    private final ResolvedCredentials connection;
    private final SourceContext context;
    private final List<String> objects;
    private final String version;
    private final Clock clock;
    private SalesforceApi.Org org;
    private final Map<String, Describe> describes = new HashMap<>();

    SalesforceSession(SourceContext context) {
        this.context = context;
        this.connection = context.connection();
        this.objects = SalesforceConnectorPlugin.objects(context.config());
        this.version = SalesforceConnectorPlugin.version(context.config());
        this.clock = context.clock();
    }

    private SalesforceApi.Org org() {
        if (org == null) org = SalesforceApi.connect(context.http(), connection, clock);
        return org;
    }

    private String data() {
        return org().data(version);
    }

    // -- the probe -----------------------------------------------------------------------------

    /** The org answers, and the connection's user can see every chosen object. */
    @Override
    public AccessCheck verifyAccess() {
        try {
            for (String object : objects) {
                try {
                    describe(object);
                } catch (ConnectorHttpException e) {
                    if (e.isNotFound()) {
                        return AccessCheck.failed("Salesforce has no object " + object
                                + ", or the connection's user cannot see it.");
                    }
                    throw e;
                }
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Salesforce did not accept the connection's sign-in. Sign in again, or "
                        + "check its keys, in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        boolean changes = !request.isFull() && request.since() != null;
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String object : objects) {
            Kind kind = KINDS.get(object);
            Instant from = changes ? request.since().minus(OVERLAP).truncatedTo(ChronoUnit.SECONDS) : null;
            parts.add(() -> records(kind, from));
            if (changes) parts.add(() -> deleted(kind, from));
        }
        return Listings.concat(parts);
    }

    // The object's records, oldest change first: all of them, or those changed after {@code from}.
    private Iterator<ItemMetadata> records(Kind kind, Instant from) {
        Describe d = describe(kind.object());
        List<String> fields = new ArrayList<>(List.of("Id", "SystemModstamp"));
        for (String f : kind.title()) if (d.has(f)) fields.add(f);
        if (d.has("Owner.Name")) fields.add("Owner.Name");
        StringBuilder soql = new StringBuilder("SELECT ").append(String.join(", ", fields))
                .append(" FROM ").append(kind.object());
        if (from != null) soql.append(" WHERE SystemModstamp > ").append(SOQL_DATETIME.format(from));
        soql.append(" ORDER BY SystemModstamp, Id");
        return Listings.mapped(query(soql.toString()), json -> item(kind, json));
    }

    // Records deleted since {@code from}, from the getDeleted resource: listed so their fetch can say
    // they are gone. A window it no longer covers is left to the next full pass.
    private Iterator<ItemMetadata> deleted(Kind kind, Instant from) {
        Instant now = clock.instant();
        if (from.isBefore(now.minus(DELETED_WINDOW)) || Duration.between(from, now).compareTo(Duration.ofMinutes(1)) < 0) {
            return Collections.emptyIterator();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("start", DELETED_DATETIME.format(from));
        query.put("end", DELETED_DATETIME.format(now));
        JsonNode answer;
        try {
            answer = org().api().getJson(data() + "sobjects/" + kind.object() + "/deleted/", query);
        } catch (ConnectorHttpException e) {
            if (e.status() == 400) return Collections.emptyIterator(); // a window Salesforce will not answer
            throw e;
        }
        List<ItemMetadata> out = new ArrayList<>();
        for (JsonNode r : answer.path("deletedRecords")) {
            String id = r.path("id").asText("");
            if (id.isEmpty()) continue;
            ItemMetadata item = new ItemMetadata();
            item.setId(kind.object() + ":" + id);
            item.setTitle(kind.label() + " " + id);
            item.setSourceType(ConnectorType.SALESFORCE);
            item.setLastModified(instant(r.path("deletedDate").asText(null)));
            item.setContentType(MARKDOWN);
            out.add(item);
        }
        return out.iterator();
    }

    private ItemMetadata item(Kind kind, JsonNode json) {
        String id = json.path("Id").asText("");
        if (id.isEmpty()) return null;
        ItemMetadata item = new ItemMetadata();
        item.setId(kind.object() + ":" + id);
        item.setTitle(title(kind, json, id));
        item.setSourceType(ConnectorType.SALESFORCE);
        item.setLastModified(instant(json.path("SystemModstamp").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", org().instanceUrl() + "/" + id);
        putExtra(item, ItemMetadata.AUTHOR, value(json, "Owner.Name"));
        return item;
    }

    /** Every record a SOQL query answers, following its nextRecordsUrl. */
    private Iterator<JsonNode> query(String soql) {
        return Paginator.iterate(next -> {
            JsonNode page = next == null
                    ? org().api().getJson(data() + "query", Map.of("q", soql))
                    : org().api().getJson(next);
            String more = page.path("done").asBoolean(true) ? null : page.path("nextRecordsUrl").asText(null);
            // nextRecordsUrl is a path from the server's root (/services/data/...), whatever the base's own path.
            if (more != null && more.startsWith("/")) more = org().api().base().resolve(more).toString();
            return new Paginator.Page<>(Paginator.elements(page.path("records")), more);
        });
    }

    private Describe describe(String object) {
        Describe d = describes.get(object);
        if (d != null) return d;
        JsonNode answer = org().api().getJson(data() + "sobjects/" + object + "/describe");
        Map<String, String> labels = new HashMap<>();
        Set<String> relationships = new HashSet<>();
        for (JsonNode f : answer.path("fields")) {
            labels.put(f.path("name").asText(), f.path("label").asText(f.path("name").asText()));
            String rel = f.path("relationshipName").asText("");
            if (!rel.isEmpty() && !"null".equals(rel)) relationships.add(rel);
        }
        d = new Describe(labels, relationships);
        describes.put(object, d);
        return d;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        int colon = id.indexOf(':');
        Kind kind = colon > 0 ? KINDS.get(id.substring(0, colon)) : null;
        String recordId = colon > 0 ? id.substring(colon + 1) : "";
        if (kind == null || !RECORD_ID.matcher(recordId).matches()) {
            throw new ConnectorException("That is not an item of a Salesforce source.");
        }
        // Gone only when the query answers and finds nothing: a 404 is an address this connector got
        // wrong (an API version the org does not serve), and must not read as every record deleted.
        return new ByteArrayInputStream(render(kind, recordId).getBytes(StandardCharsets.UTF_8));
    }

    String render(Kind kind, String id) {
        Describe d = describe(kind.object());
        List<String> fields = new ArrayList<>(List.of("Id"));
        for (String f : kind.title()) if (d.has(f)) fields.add(f);
        for (String f : kind.fields()) if (d.has(f)) fields.add(f);
        if (kind.body() != null && d.has(kind.body())) fields.add(kind.body());
        // The id is checked against Salesforce's id shape before it is ever put into a query.
        List<JsonNode> found = Paginator.elements(org().api().getJson(data() + "query", Map.of("q",
                "SELECT " + String.join(", ", fields) + " FROM " + kind.object() + " WHERE Id = '" + id + "'")).path("records"));
        if (found.isEmpty()) throw new ItemGoneException("Salesforce no longer has " + kind.object() + ":" + id + ".");
        JsonNode record = found.get(0);

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(kind, record, id)).append("\n\n");
        md.append("- Object: ").append(kind.label()).append('\n');
        for (String f : kind.fields()) {
            if (!d.has(f)) continue;
            line(md, d.label(f), value(record, f));
        }
        line(md, "Link", org().instanceUrl() + "/" + id);
        String body = kind.body() == null ? null : value(record, kind.body());
        if (body != null && !body.isBlank()) md.append("\n## Description\n\n").append(body.strip()).append('\n');

        switch (kind.object()) {
            case "Account" -> {
                section(md, "Contacts", "SELECT Name, Title, Email, Phone FROM Contact WHERE AccountId = '" + id
                        + "' ORDER BY Name LIMIT 50", r -> joinNonBlank(" - ", value(r, "Name"),
                        joinNonBlank(", ", value(r, "Title"), value(r, "Email"), value(r, "Phone"))));
                section(md, "Opportunities", "SELECT Name, StageName, Amount, CloseDate FROM Opportunity WHERE AccountId = '"
                        + id + "' ORDER BY CloseDate DESC LIMIT 25", r -> joinNonBlank(" - ", value(r, "Name"),
                        joinNonBlank(", ", value(r, "StageName"), value(r, "Amount"), value(r, "CloseDate"))));
                section(md, "Cases", "SELECT CaseNumber, Subject, Status FROM Case WHERE AccountId = '" + id
                        + "' ORDER BY CreatedDate DESC LIMIT 25", r -> joinNonBlank(" - ",
                        joinNonBlank(" ", value(r, "CaseNumber"), value(r, "Subject")), value(r, "Status")));
            }
            case "Opportunity" -> section(md, "Contact roles", "SELECT Contact.Name, Role, IsPrimary FROM "
                    + "OpportunityContactRole WHERE OpportunityId = '" + id + "' LIMIT 50", r -> joinNonBlank(" - ",
                    value(r, "Contact.Name"), joinNonBlank(", ", value(r, "Role"),
                            "true".equals(value(r, "IsPrimary")) ? "primary" : null)));
            case "Case" -> comments(md, id);
            default -> { }
        }
        notes(md, id);
        activity(md, kind, id);
        return md.toString();
    }

    private void comments(StringBuilder md, String id) {
        List<JsonNode> rows = optional("SELECT CommentBody, CreatedDate, CreatedBy.Name FROM CaseComment WHERE ParentId = '"
                + id + "' ORDER BY CreatedDate LIMIT 100");
        if (rows.isEmpty()) return;
        md.append("\n## Comments\n");
        for (JsonNode r : rows) {
            md.append("\n**").append(nonNull(value(r, "CreatedBy.Name"))).append("**, ")
                    .append(nonNull(value(r, "CreatedDate"))).append(":\n\n")
                    .append(truncate(nonNull(value(r, "CommentBody")).strip())).append('\n');
        }
    }

    // Classic notes, and the enhanced notes (ContentNote) linked to the record.
    private void notes(StringBuilder md, String id) {
        List<String[]> notes = new ArrayList<>();
        for (JsonNode r : optional("SELECT Title, Body, CreatedDate, CreatedBy.Name FROM Note WHERE ParentId = '" + id
                + "' ORDER BY CreatedDate DESC LIMIT 20")) {
            notes.add(new String[]{value(r, "Title"), joinNonBlank(", ", value(r, "CreatedDate"),
                    value(r, "CreatedBy.Name") == null ? null : "by " + value(r, "CreatedBy.Name")), value(r, "Body")});
        }
        List<String> linked = new ArrayList<>();
        for (JsonNode r : optional("SELECT ContentDocumentId FROM ContentDocumentLink WHERE LinkedEntityId = '" + id
                + "' AND ContentDocument.FileType = 'SNOTE' LIMIT 20")) {
            String doc = value(r, "ContentDocumentId");
            if (doc != null && RECORD_ID.matcher(doc).matches()) linked.add("'" + doc + "'");
        }
        if (!linked.isEmpty()) {
            for (JsonNode r : optional("SELECT Title, TextPreview, LastModifiedDate FROM ContentNote WHERE Id IN ("
                    + String.join(", ", linked) + ") ORDER BY LastModifiedDate DESC")) {
                notes.add(new String[]{value(r, "Title"), value(r, "LastModifiedDate"), value(r, "TextPreview")});
            }
        }
        if (notes.isEmpty()) return;
        md.append("\n## Notes\n");
        for (String[] n : notes) {
            md.append("\n### ").append(n[0] == null || n[0].isBlank() ? "Note" : n[0].strip()).append("\n\n");
            if (n[1] != null) md.append(n[1]).append("\n\n");
            if (n[2] != null && !n[2].isBlank()) md.append(truncate(n[2].strip())).append('\n');
        }
    }

    // Recent tasks and events whose WhatId (or WhoId, for a contact or a lead) is the record.
    private void activity(StringBuilder md, Kind kind, String id) {
        List<String[]> entries = new ArrayList<>();
        for (JsonNode r : optional("SELECT Subject, Status, ActivityDate, Description, Owner.Name FROM Task WHERE "
                + kind.activityField() + " = '" + id + "' ORDER BY ActivityDate DESC NULLS LAST LIMIT 25")) {
            entries.add(new String[]{nonNull(value(r, "ActivityDate")), "Task", value(r, "Subject"),
                    joinNonBlank(", ", value(r, "ActivityDate"), value(r, "Status"),
                            value(r, "Owner.Name") == null ? null : "by " + value(r, "Owner.Name")), value(r, "Description")});
        }
        for (JsonNode r : optional("SELECT Subject, StartDateTime, Description, Owner.Name FROM Event WHERE "
                + kind.activityField() + " = '" + id + "' ORDER BY StartDateTime DESC LIMIT 25")) {
            entries.add(new String[]{nonNull(value(r, "StartDateTime")), "Event", value(r, "Subject"),
                    joinNonBlank(", ", value(r, "StartDateTime"),
                            value(r, "Owner.Name") == null ? null : "by " + value(r, "Owner.Name")), value(r, "Description")});
        }
        if (entries.isEmpty()) return;
        entries.sort((a, b) -> b[0].compareTo(a[0]));
        md.append("\n## Activity\n");
        for (String[] e : entries) {
            md.append("\n### ").append(e[1]);
            if (e[2] != null && !e[2].isBlank()) md.append(": ").append(e[2].strip());
            md.append("\n\n");
            if (e[3] != null) md.append(e[3]).append("\n\n");
            if (e[4] != null && !e[4].isBlank()) md.append(truncate(e[4].strip())).append('\n');
        }
    }

    private void section(StringBuilder md, String heading, String soql, Function<JsonNode, String> line) {
        List<JsonNode> rows = optional(soql);
        if (rows.isEmpty()) return;
        md.append("\n## ").append(heading).append("\n\n");
        for (JsonNode r : rows) {
            String text = line.apply(r);
            if (text != null && !text.isBlank()) md.append("- ").append(text).append('\n');
        }
    }

    // A related query's records - none when the org will not answer it (an object or field the
    // user cannot see, a feature the org does not have). A refused sign-in or a spent allowance still fails.
    private List<JsonNode> optional(String soql) {
        try {
            List<JsonNode> out = new ArrayList<>();
            query(soql).forEachRemaining(out::add);
            return out;
        } catch (ConnectorHttpException e) {
            if (e.status() == 400 || e.isNotFound()) return List.of();
            throw e;
        }
    }

    // -- helpers -------------------------------------------------------------------------------

    private static String title(Kind kind, JsonNode json, String id) {
        List<String> parts = new ArrayList<>();
        for (String f : kind.title()) {
            String v = value(json, f);
            if (v != null && !v.isBlank()) parts.add(v.strip());
        }
        return parts.isEmpty() ? kind.label() + " " + id : String.join(" ", parts);
    }

    /** A field's value as text - {@code Owner.Name} read through its relationship - or null. */
    static String value(JsonNode record, String field) {
        JsonNode node = record;
        for (String part : field.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode() || node.isNull()) return null;
        }
        return node.isValueNode() ? node.asText() : null;
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) kept.add(p.strip());
        return kept.isEmpty() ? null : String.join(sep, kept);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + " ...";
    }

    private static void line(StringBuilder md, String label, String value) {
        if (value != null && !value.isBlank()) md.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    /** A Salesforce date-time ({@code 2026-10-12T09:30:00.000+0000}) or an ISO one. */
    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(value.trim(), SALESFORCE_DATETIME).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
