package ai.proxyma.connectors.hubspot;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One HubSpot source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the object types, the API version, the connection's token - and nothing
 * it holds is seen by any other source.
 *
 * <p>Items are {@code company:<id>}, {@code contact:<id>}, {@code deal:<id>} and {@code ticket:<id>},
 * by HubSpot's record id. Each is rendered on fetch as one markdown document: its properties, the
 * companies, contacts, deals and tickets it is associated with, by name, and its activity - notes,
 * calls, meetings, emails and tasks, newest first.
 *
 * <p><b>Listing.</b> A full pass pages through each type's list endpoint, which has no ceiling. A
 * CHANGES pass asks CRM search for records modified at or after the last pass, oldest first. Search
 * answers at most {@value HubSpotApi#SEARCH_CAP} results for one query however it is paged, so when
 * the next page would cross that line the session starts a NEW query from the last-modified date it
 * has reached - a date window - and the records on the boundary, which both windows return, are
 * listed once.
 */
final class HubSpotSession implements ConnectorSession {

    // Clock skew between this host and HubSpot: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int LIST_PAGE = 100;
    static final int SEARCH_PAGE = 200;
    // One batch read at most, per associated type: a company with more contacts names the first 100.
    static final int MAX_ASSOCIATED = 100;
    static final int MAX_ACTIVITY_TEXT = 2_000;
    static final String MARKDOWN = "text/markdown";
    static final String OBJECT_NOT_FOUND = "OBJECT_NOT_FOUND";

    /** One CRM object type as the connector reads it. */
    record Kind(String type, String item, String label, String typeId, String modified, List<String> titleProps,
                List<String> properties, String body) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        add(new Kind("companies", "company", "Company", "0-2", "hs_lastmodifieddate", List.of("name", "domain"),
                List.of("name", "domain", "industry", "type", "lifecyclestage", "phone", "city", "state", "country",
                        "numberofemployees", "annualrevenue", "hubspot_owner_id", "createdate", "hs_lastmodifieddate",
                        "description"), "description"));
        add(new Kind("contacts", "contact", "Contact", "0-1", "lastmodifieddate", List.of("firstname", "lastname", "email"),
                List.of("firstname", "lastname", "email", "jobtitle", "company", "phone", "lifecyclestage",
                        "hs_lead_status", "city", "country", "hubspot_owner_id", "createdate", "lastmodifieddate"), null));
        add(new Kind("deals", "deal", "Deal", "0-3", "hs_lastmodifieddate", List.of("dealname"),
                List.of("dealname", "pipeline", "dealstage", "amount", "closedate", "dealtype", "hubspot_owner_id",
                        "createdate", "hs_lastmodifieddate", "description"), "description"));
        add(new Kind("tickets", "ticket", "Ticket", "0-5", "hs_lastmodifieddate", List.of("subject"),
                List.of("subject", "hs_pipeline", "hs_pipeline_stage", "hs_ticket_priority", "hs_ticket_category",
                        "source_type", "hubspot_owner_id", "createdate", "hs_lastmodifieddate", "content"), "content"));
    }

    private static void add(Kind kind) {
        KINDS.put(kind.type(), kind);
    }

    // The labels a record's properties are shown under. A property not named here is not shown.
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("domain", "Domain"), Map.entry("industry", "Industry"), Map.entry("type", "Company type"),
            Map.entry("lifecyclestage", "Lifecycle stage"), Map.entry("phone", "Phone"), Map.entry("city", "City"),
            Map.entry("state", "State"), Map.entry("country", "Country"), Map.entry("numberofemployees", "Employees"),
            Map.entry("annualrevenue", "Annual revenue"), Map.entry("email", "Email"), Map.entry("jobtitle", "Job title"),
            Map.entry("company", "Company"), Map.entry("hs_lead_status", "Lead status"), Map.entry("pipeline", "Pipeline"),
            Map.entry("dealstage", "Stage"), Map.entry("amount", "Amount"), Map.entry("closedate", "Close date"),
            Map.entry("dealtype", "Deal type"), Map.entry("hs_pipeline", "Pipeline"), Map.entry("hs_pipeline_stage", "Stage"),
            Map.entry("hs_ticket_priority", "Priority"), Map.entry("hs_ticket_category", "Category"),
            Map.entry("source_type", "Source"));

    /** An activity type: its properties, its title and body, and the time it happened. */
    record Activity(String type, String label, String title, String body, List<String> extra) {}

    static final List<Activity> ACTIVITIES = List.of(
            new Activity("notes", "Note", null, "hs_note_body", List.of()),
            new Activity("calls", "Call", "hs_call_title", "hs_call_body", List.of("hs_call_direction")),
            new Activity("meetings", "Meeting", "hs_meeting_title", "hs_meeting_body", List.of()),
            new Activity("emails", "Email", "hs_email_subject", "hs_email_text", List.of("hs_email_direction")),
            new Activity("tasks", "Task", "hs_task_subject", "hs_task_body", List.of("hs_task_status")));

    // What an associated record is named by, per type.
    static final Map<String, List<String>> ASSOCIATED = new LinkedHashMap<>();
    static {
        ASSOCIATED.put("companies", List.of("name", "domain"));
        ASSOCIATED.put("contacts", List.of("firstname", "lastname", "email", "jobtitle"));
        ASSOCIATED.put("deals", List.of("dealname", "dealstage", "pipeline", "amount"));
        ASSOCIATED.put("tickets", List.of("subject", "hs_pipeline_stage", "hs_pipeline"));
    }

    private final ApiClient api;
    private final ApiClient search;
    private final String version;
    private final List<String> types;
    private JsonNode account;
    private boolean accountTried;
    private Map<String, String> owners;
    private final Map<String, Map<String, String>> stages = new HashMap<>();
    private boolean windowStuck;

    HubSpotSession(SourceContext context) {
        this.types = HubSpotConnectorPlugin.objects(context.config());
        this.version = HubSpotConnectorPlugin.version(context.config());
        this.api = HubSpotApi.client(context.http(), context.connection());
        this.search = HubSpotApi.searchClient(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    /** The account answers, and every chosen type can be read with the connection's scopes. */
    @Override
    public AccessCheck verifyAccess() {
        try {
            api.getJson(HubSpotApi.accountDetails(version));
            for (String type : types) {
                try {
                    api.getJson(HubSpotApi.objects(version, type), Map.of("limit", 1));
                } catch (ConnectorHttpException e) {
                    if (e.status() == 403) {
                        return AccessCheck.failed("HubSpot will not let this connection read " + type
                                + ". Give its key the read scope for " + type + ".");
                    }
                    throw e;
                }
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("HubSpot did not accept the token. Replace it in Settings > Connections.");
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
        for (String type : types) {
            Kind kind = KINDS.get(type);
            if (changes) {
                Instant from = request.since().minus(OVERLAP);
                parts.add(() -> changed(kind, from));
                parts.add(() -> archived(kind, from));
            } else {
                parts.add(() -> all(kind));
            }
        }
        return Listings.partialIf(Listings.concat(parts), () -> windowStuck
                ? "More than 10,000 HubSpot records changed at the same moment, so some are left for the next full sync."
                : null);
    }

    // Every record of one type that is not archived - the list endpoint, which has no ceiling.
    private Iterator<ItemMetadata> all(Kind kind) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("limit", LIST_PAGE);
        query.put("archived", "false");
        query.put("properties", String.join(",", listProperties(kind)));
        Iterator<JsonNode> records = Paginator.iterate(after -> {
            Map<String, Object> q = new LinkedHashMap<>(query);
            if (after != null) q.put("after", after);
            JsonNode page = read(api.get(HubSpotApi.objects(version, kind.type()), q));
            return new Paginator.Page<>(Paginator.elements(page.path("results")), nextAfter(page));
        });
        return Listings.mapped(records, json -> item(kind, json));
    }

    /**
     * Records of one type modified at or after {@code from}, oldest first, through CRM search - in
     * date windows of at most {@value HubSpotApi#SEARCH_CAP} results. The paging cursor carries the
     * window: {@code <from millis>|<after>}.
     */
    private Iterator<ItemMetadata> changed(Kind kind, Instant from) {
        Set<String> seen = new HashSet<>();
        Iterator<JsonNode> records = Paginator.iterate(next -> {
            long windowStart = from.toEpochMilli();
            String after = null;
            if (next != null) {
                int bar = next.indexOf('|');
                windowStart = Long.parseLong(next.substring(0, bar));
                after = bar + 1 < next.length() ? next.substring(bar + 1) : null;
            }
            JsonNode page = read(search.send(HttpMethod.POST, HubSpotApi.objects(version, kind.type()) + "/search",
                    Map.of(), searchBody(kind, windowStart, after), Map.of()));
            List<JsonNode> results = Paginator.elements(page.path("results"));
            String more = nextAfter(page);
            String cursor = null;
            if (more != null) {
                int offset = parseInt(more);
                if (offset >= 0 && offset + SEARCH_PAGE <= HubSpotApi.SEARCH_CAP) {
                    cursor = windowStart + "|" + more;
                } else {
                    // The next page would pass the cap: a new query from the newest date reached.
                    long reached = results.isEmpty() ? windowStart
                            : modified(kind, results.get(results.size() - 1)).toEpochMilli();
                    if (reached > windowStart) cursor = reached + "|";
                    else windowStuck = true;
                }
            }
            return new Paginator.Page<>(results, cursor);
        });
        return Listings.mapped(records, json -> seen.add(json.path("id").asText()) ? item(kind, json) : null);
    }

    private Map<String, Object> searchBody(Kind kind, long fromMillis, String after) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("propertyName", kind.modified());
        filter.put("operator", "GTE");
        filter.put("value", String.valueOf(fromMillis));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filterGroups", List.of(Map.of("filters", List.of(filter))));
        body.put("sorts", List.of(Map.of("propertyName", kind.modified(), "direction", "ASCENDING")));
        body.put("properties", listProperties(kind));
        body.put("limit", SEARCH_PAGE);
        if (after != null) body.put("after", after);
        return body;
    }

    // Records archived since the last pass: listed so their fetch can say they are gone. Search never
    // returns archived records, so this is the list endpoint with archived=true, read whole - HubSpot
    // keeps an archived record for 90 days, which bounds it.
    private Iterator<ItemMetadata> archived(Kind kind, Instant from) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("limit", LIST_PAGE);
        query.put("archived", "true");
        query.put("properties", String.join(",", listProperties(kind)));
        Iterator<JsonNode> records = Paginator.iterate(after -> {
            Map<String, Object> q = new LinkedHashMap<>(query);
            if (after != null) q.put("after", after);
            JsonNode page = read(api.get(HubSpotApi.objects(version, kind.type()), q));
            return new Paginator.Page<>(Paginator.elements(page.path("results")), nextAfter(page));
        });
        return Listings.mapped(records, json -> {
            Instant at = instant(json.path("archivedAt").asText(null));
            if (at == null || at.isBefore(from)) return null;
            return item(kind, json);
        });
    }

    private ItemMetadata item(Kind kind, JsonNode json) {
        String id = json.path("id").asText("");
        if (id.isEmpty()) return null;
        JsonNode props = json.path("properties");
        ItemMetadata item = new ItemMetadata();
        item.setId(kind.item() + ":" + id);
        item.setTitle(title(kind, props, id));
        item.setSourceType(ConnectorType.HUBSPOT);
        item.setLastModified(modified(kind, json));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", recordUrl(kind, id));
        putExtra(item, ItemMetadata.AUTHOR, owner(props.path("hubspot_owner_id").asText(null)));
        return item;
    }

    private static List<String> listProperties(Kind kind) {
        List<String> props = new ArrayList<>(kind.titleProps());
        props.add(kind.modified());
        props.add("hubspot_owner_id");
        return props;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        int colon = id.indexOf(':');
        Kind kind = null;
        if (colon > 0) {
            String prefix = id.substring(0, colon);
            for (Kind k : KINDS.values()) if (k.item().equals(prefix)) kind = k;
        }
        String recordId = colon > 0 ? id.substring(colon + 1) : "";
        if (kind == null || !recordId.matches("[0-9]{1,20}")) {
            throw new ConnectorException("That is not an item of a HubSpot source.");
        }
        return new ByteArrayInputStream(render(kind, recordId).getBytes(StandardCharsets.UTF_8));
    }

    String render(Kind kind, String recordId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("properties", String.join(",", kind.properties()));
        List<String> assoc = new ArrayList<>(ASSOCIATED.keySet());
        ACTIVITIES.forEach(a -> assoc.add(a.type()));
        query.put("associations", String.join(",", assoc));
        ApiResponse answer = api.get(HubSpotApi.objects(version, kind.type()) + "/" + recordId, query);
        // Gone only when HubSpot says the RECORD is not found: a 404 for an address this connector got
        // wrong (an API version HubSpot does not serve) must not read as every record deleted.
        if (answer.status() == 404 && answer.text().contains(OBJECT_NOT_FOUND)) {
            throw new ItemGoneException("HubSpot no longer has " + kind.item() + ":" + recordId + ".");
        }
        JsonNode record = read(answer);
        if (record.path("archived").asBoolean(false)) {
            throw new ItemGoneException("HubSpot has archived " + kind.item() + ":" + recordId + ".");
        }
        JsonNode props = record.path("properties");

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(kind, props, recordId)).append("\n\n");
        md.append("- Type: ").append(kind.label()).append('\n');
        if (kind.type().equals("contacts")) {
            line(md, "Name", fullName(props));
        }
        for (String p : kind.properties()) {
            String label = LABELS.get(p);
            if (label == null) continue;
            String value = text(props, p);
            if (p.equals("dealstage") || p.equals("hs_pipeline_stage")) {
                value = stage(kind.type(), value);
            }
            if (p.equals("pipeline") || p.equals("hs_pipeline")) value = pipeline(kind.type(), value);
            line(md, label, value);
        }
        line(md, "Owner", owner(text(props, "hubspot_owner_id")));
        line(md, "Created", text(props, "createdate"));
        line(md, "Updated", text(props, kind.modified()));
        line(md, "Link", recordUrl(kind, recordId));

        if (kind.body() != null) {
            String body = text(props, kind.body());
            if (body != null && !body.isBlank()) {
                md.append("\n## ").append(kind.type().equals("tickets") ? "Description" : "About").append("\n\n")
                        .append(plain(body)).append('\n');
            }
        }

        JsonNode associations = record.path("associations");
        boolean anyAssociated = false;
        for (Map.Entry<String, List<String>> e : ASSOCIATED.entrySet()) {
            List<String> ids = associatedIds(associations, e.getKey(), kind.type().equals(e.getKey()) ? recordId : null);
            if (ids.isEmpty()) continue;
            List<JsonNode> records = batchRead(e.getKey(), ids, e.getValue());
            if (records.isEmpty()) continue;
            if (!anyAssociated) md.append("\n## Associated records\n");
            anyAssociated = true;
            md.append("\n### ").append(KINDS.get(e.getKey()).label()).append(records.size() == 1 ? "" : "s").append("\n\n");
            for (JsonNode r : records) md.append("- ").append(associatedLine(e.getKey(), r)).append('\n');
        }

        List<String[]> timeline = new ArrayList<>();
        for (Activity a : ACTIVITIES) {
            List<String> ids = associatedIds(associations, a.type(), null);
            if (ids.isEmpty()) continue;
            List<String> props2 = new ArrayList<>();
            if (a.title() != null) props2.add(a.title());
            props2.add(a.body());
            props2.addAll(a.extra());
            props2.add("hs_timestamp");
            props2.add("hubspot_owner_id");
            for (JsonNode r : batchRead(a.type(), ids, props2)) {
                JsonNode p = r.path("properties");
                String when = text(p, "hs_timestamp");
                StringBuilder entry = new StringBuilder();
                entry.append("\n### ").append(a.label());
                String title = a.title() == null ? null : text(p, a.title());
                if (title != null && !title.isBlank()) entry.append(": ").append(title.strip());
                entry.append("\n\n");
                List<String> meta = new ArrayList<>();
                if (when != null) meta.add(when);
                String who = owner(text(p, "hubspot_owner_id"));
                if (who != null) meta.add("by " + who);
                for (String x : a.extra()) {
                    String v = text(p, x);
                    if (v != null && !v.isBlank()) meta.add(v.toLowerCase(Locale.ROOT).replace('_', ' '));
                }
                if (!meta.isEmpty()) entry.append(String.join(", ", meta)).append("\n\n");
                String body = text(p, a.body());
                if (body != null && !body.isBlank()) entry.append(truncate(plain(body))).append('\n');
                timeline.add(new String[]{when == null ? "" : when, entry.toString()});
            }
        }
        if (!timeline.isEmpty()) {
            timeline.sort(Comparator.comparing((String[] t) -> t[0]).reversed());
            md.append("\n## Activity\n");
            timeline.forEach(t -> md.append(t[1]));
        }
        return md.toString();
    }

    // The ids one type of association lists, each once, without the record itself.
    private static List<String> associatedIds(JsonNode associations, String type, String self) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode node = associations.path(type);
        for (JsonNode r : node.path("results")) {
            String id = r.path("id").asText(r.path("toObjectId").asText(""));
            if (!id.isEmpty() && !id.equals(self)) ids.add(id);
            if (ids.size() >= MAX_ASSOCIATED) break;
        }
        return new ArrayList<>(ids);
    }

    // One batch read; a type the connection may not read (a 403 for a scope it lacks) is left out of
    // the document rather than failing it.
    private List<JsonNode> batchRead(String type, List<String> ids, List<String> properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("properties", properties);
        List<Map<String, String>> inputs = new ArrayList<>();
        ids.forEach(id -> inputs.add(Map.of("id", id)));
        body.put("inputs", inputs);
        try {
            JsonNode answer = read(api.send(HttpMethod.POST, HubSpotApi.objects(version, type) + "/batch/read",
                    Map.of(), body, Map.of()));
            return Paginator.elements(answer.path("results"));
        } catch (ConnectorHttpException e) {
            if (e.status() == 403 || e.isNotFound()) return List.of();
            throw e;
        }
    }

    private String associatedLine(String type, JsonNode record) {
        JsonNode p = record.path("properties");
        String id = record.path("id").asText("");
        return switch (type) {
            case "companies" -> join(text(p, "name"), paren(text(p, "domain")), id);
            case "contacts" -> join(fullName(p), paren(joinNonBlank(", ", text(p, "jobtitle"), text(p, "email"))), id);
            case "deals" -> join(text(p, "dealname"), paren(joinNonBlank(", ", stage("deals", text(p, "dealstage")),
                    text(p, "amount"))), id);
            default -> join(text(p, "subject"), paren(stage("tickets", text(p, "hs_pipeline_stage"))), id);
        };
    }

    private static String join(String name, String detail, String id) {
        String n = name == null || name.isBlank() ? "#" + id : name.strip();
        return detail == null ? n : n + " " + detail;
    }

    private static String paren(String s) {
        return s == null || s.isBlank() ? null : "(" + s + ")";
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) kept.add(p.strip());
        return kept.isEmpty() ? null : String.join(sep, kept);
    }

    // -- names HubSpot keeps elsewhere -----------------------------------------------------------

    /** An owner's name by id, from the account's owners, read once; null when it cannot be told. */
    private String owner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return null;
        if (owners == null) {
            owners = new HashMap<>();
            try {
                Iterator<JsonNode> all = Paginator.iterate(after -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("limit", 100);
                    if (after != null) q.put("after", after);
                    JsonNode page = read(api.get(HubSpotApi.owners(version), q));
                    return new Paginator.Page<>(Paginator.elements(page.path("results")), nextAfter(page));
                });
                all.forEachRemaining(o -> {
                    String name = joinNonBlank(" ", o.path("firstName").asText(null), o.path("lastName").asText(null));
                    if (name == null) name = o.path("email").asText(null);
                    if (name != null) owners.put(o.path("id").asText(), name);
                });
            } catch (ConnectorHttpException e) {
                // Owners need a scope of their own; without it a record is shown without its owner.
                if (e.status() == 429 || e.status() == 401 || e.status() >= 500) throw e;
            }
        }
        return owners.get(ownerId.strip());
    }

    private String stage(String type, String stageId) {
        if (stageId == null || stageId.isBlank()) return stageId;
        return pipelineLabels(type).getOrDefault("stage:" + stageId, stageId);
    }

    private String pipeline(String type, String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) return pipelineId;
        return pipelineLabels(type).getOrDefault("pipeline:" + pipelineId, pipelineId);
    }

    // The labels of a type's pipelines and their stages, read once - a deal's stage is an id.
    private Map<String, String> pipelineLabels(String type) {
        return stages.computeIfAbsent(type, t -> {
            Map<String, String> labels = new HashMap<>();
            try {
                JsonNode answer = read(api.get(HubSpotApi.pipelines(version, t)));
                for (JsonNode p : answer.path("results")) {
                    labels.put("pipeline:" + p.path("id").asText(), p.path("label").asText(p.path("id").asText()));
                    for (JsonNode s : p.path("stages")) {
                        labels.put("stage:" + s.path("id").asText(), s.path("label").asText(s.path("id").asText()));
                    }
                }
            } catch (ConnectorHttpException e) {
                if (e.status() == 429 || e.status() == 401 || e.status() >= 500) throw e;
            }
            return labels;
        });
    }

    private String recordUrl(Kind kind, String id) {
        JsonNode a = account();
        if (a == null) return null;
        String portal = a.path("portalId").asText("");
        String domain = a.path("uiDomain").asText("app.hubspot.com");
        if (portal.isEmpty()) return null;
        return "https://" + domain + "/contacts/" + portal + "/record/" + kind.typeId() + "/" + id;
    }

    // The account's details, for links - read once, and left out when they cannot be read.
    private JsonNode account() {
        if (!accountTried) {
            accountTried = true;
            try {
                account = read(api.get(HubSpotApi.accountDetails(version)));
            } catch (ConnectorHttpException e) {
                if (e.status() == 429 || e.status() == 401 || e.status() >= 500) throw e;
            }
        }
        return account;
    }

    // -- helpers -------------------------------------------------------------------------------

    // A 2xx answer as JSON, its daily allowance checked; anything else as the framework's sentence.
    private JsonNode read(ApiResponse response) {
        JsonNode json = api.requireOk(response).json();
        HubSpotApi.checkDailyBudget(response);
        return json;
    }

    private static String nextAfter(JsonNode page) {
        String after = page.path("paging").path("next").path("after").asText(null);
        return after == null || after.isBlank() ? null : after;
    }

    private static String title(Kind kind, JsonNode props, String id) {
        String title = switch (kind.type()) {
            case "contacts" -> {
                String name = fullName(props);
                yield name != null ? name : text(props, "email");
            }
            case "companies" -> {
                String name = text(props, "name");
                yield name != null && !name.isBlank() ? name : text(props, "domain");
            }
            default -> text(props, kind.titleProps().get(0));
        };
        return title == null || title.isBlank() ? kind.label() + " " + id : title.strip();
    }

    private static String fullName(JsonNode props) {
        return joinNonBlank(" ", text(props, "firstname"), text(props, "lastname"));
    }

    private static Instant modified(Kind kind, JsonNode json) {
        Instant at = instant(text(json.path("properties"), kind.modified()));
        return at != null ? at : instant(json.path("updatedAt").asText(null));
    }

    private static String text(JsonNode props, String key) {
        JsonNode v = props.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    // HTML from HubSpot's rich text (a note's body) as plain text: tags dropped, the usual entities read.
    static String plain(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("<[^>]*>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        return s.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").strip();
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ACTIVITY_TEXT ? s : s.substring(0, MAX_ACTIVITY_TEXT) + " ...";
    }

    private static void line(StringBuilder md, String label, String value) {
        if (value != null && !value.isBlank()) md.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // A HubSpot date: ISO 8601, or epoch milliseconds as some properties still hold them.
    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
