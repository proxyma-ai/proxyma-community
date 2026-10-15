package ai.proxyma.connectors.airtable;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * One Airtable source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the base, the table, the view, the connection's token - and the base's
 * tables, read once from its schema, are kept for this session alone.
 *
 * <p>Items, by id: {@code record:<table id>:<record id>} - one record, listed page by page from the
 * table (or the view), fetched again with its values as Airtable writes them for a person: linked
 * records by their names, collaborators by theirs, attachments by name and link.
 *
 * <p><b>When a record changed.</b> The record list states only when a record was created. Unless the
 * table has a "Last modified time" field, a record is listed as observed now, with a hash of its
 * fields - the sync engine re-reads a record only when the hash differs from what it indexed.
 * Attachment links are left out of the hash: Airtable signs them afresh on every answer.
 */
final class AirtableSession implements ConnectorSession {

    // Clock skew: a changes pass asks for what changed from a minute before the last complete pass.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // A value longer than this, or on several lines, gets a section of its own.
    static final int LONG_VALUE = 200;
    static final String MARKDOWN = "text/markdown";
    static final String RECORD = "record:";
    static final String WEB = "https://airtable.com/";

    /** A table of the base, as its schema states it. */
    record Table(String id, String name, String primaryField, List<String> fields, String lastModifiedField,
                 String createdByField, Map<String, String> views) {}

    private final SourceContext context;
    private final ApiClient api;
    private final String base;
    private final String tableRef;
    private final String viewRef;
    private List<Table> tables;
    private String viewId;
    private String viewName;
    private String baseName;

    AirtableSession(SourceContext context) {
        this.context = context;
        this.base = AirtableConnectorPlugin.baseId(context.config());
        if (base == null) throw new ConnectorException("An Airtable source needs its base's ID, or a link to it.");
        this.tableRef = AirtableConnectorPlugin.table(context.config());
        this.viewRef = AirtableConnectorPlugin.view(context.config());
        this.api = AirtableApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            resolveTables();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Airtable did not accept the token. Replace it, or sign in again, in "
                        + "Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            // A refused sign-in (ConnectorAuthException), a base or table that is not there.
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Instant changedAfter = request.isFull() || request.since() == null ? null : request.since().minus(OVERLAP);
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        // The schema first, as a part of its own, so a base the token cannot read is an errored
        // listing rather than an exception.
        parts.add(() -> {
            resolveTables();
            return Collections.emptyIterator();
        });
        parts.add(() -> new Iterator<>() {
            private final Iterator<Table> remaining = tables.iterator();
            private Iterator<ItemMetadata> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    if (!remaining.hasNext()) return false;
                    current = records(remaining.next(), changedAfter);
                }
                return true;
            }

            @Override
            public ItemMetadata next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        });
        return Listings.concat(parts);
    }

    // One table's records, every page, as listed items.
    private Iterator<ItemMetadata> records(Table table, Instant changedAfter) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("pageSize", AirtableApi.PAGE);
        if (viewId != null) query.put("view", viewId);
        if (changedAfter != null) query.put("filterByFormula", changedSince(changedAfter));
        Iterator<JsonNode> pages = Paginator.cursor(api, base + "/" + ApiClient.segment(table.id()), query, "offset",
                page -> page.path("records"), page -> page.path("offset").asText(null));
        Instant observed = context.clock().instant().truncatedTo(ChronoUnit.SECONDS);
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return pages.hasNext();
            }

            @Override
            public ItemMetadata next() {
                return recordItem(table, pages.next(), observed);
            }
        };
    }

    /**
     * The formula for "created or changed since": LAST_MODIFIED_TIME() is empty for a record nobody
     * has changed since it was made, so its creation is asked about too.
     */
    static String changedSince(Instant after) {
        String at = "DATETIME_PARSE('" + after.truncatedTo(ChronoUnit.SECONDS) + "')";
        return "OR(IS_AFTER(LAST_MODIFIED_TIME(), " + at + "), IS_AFTER(CREATED_TIME(), " + at + "))";
    }

    private ItemMetadata recordItem(Table table, JsonNode record, Instant observed) {
        String id = record.path("id").asText("");
        JsonNode fields = record.path("fields");
        ItemMetadata item = new ItemMetadata();
        item.setId(RECORD + table.id() + ":" + id);
        item.setTitle(title(table, fields, id));
        item.setSourceType(ConnectorType.AIRTABLE);
        Instant modified = table.lastModifiedField() == null ? null
                : instant(fields.path(table.lastModifiedField()).asText(null));
        item.setLastModified(modified != null ? modified : observed);
        item.setContentType(MARKDOWN);
        putExtra(item, ItemMetadata.CONTENT_HASH, hash(fields));
        putExtra(item, "url", WEB + base + "/" + table.id() + "/" + id);
        if (table.createdByField() != null) {
            JsonNode by = fields.path(table.createdByField());
            putExtra(item, ItemMetadata.AUTHOR, by.path("name").asText(by.path("email").asText(null)));
        }
        return item;
    }

    private static String title(Table table, JsonNode fields, String id) {
        JsonNode primary = table.primaryField() == null ? null : fields.get(table.primaryField());
        String text = primary == null ? "" : primary.isValueNode() ? primary.asText("") : "";
        text = text.replaceAll("\\s+", " ").strip();
        if (text.length() > 120) text = text.substring(0, 117).strip() + "...";
        return text.isEmpty() ? "Record " + id : text;
    }

    // -- the base's tables ---------------------------------------------------------------------

    /**
     * The tables this source reads, from the base's schema: the one chosen (by ID, by name, then by
     * name in any case), or every table. A view is looked up in the chosen table the same way.
     */
    void resolveTables() {
        if (tables != null) return;
        JsonNode schema;
        try {
            schema = api.getJson("meta/bases/" + ApiClient.segment(base) + "/tables");
        } catch (ConnectorHttpException e) {
            if (e.isNotFound() || e.status() == 403) {
                throw new ConnectorException("Airtable has no base " + base + ", or the token cannot read it. Add the "
                        + "base to the token, with the schema.bases:read scope.");
            }
            throw e;
        }
        List<Table> all = new ArrayList<>();
        for (JsonNode t : schema.path("tables")) all.add(table(t));
        List<Table> out = new ArrayList<>();
        if (tableRef == null) {
            if (viewRef != null) throw new ConnectorException("Choose a table to read a view of.");
            out.addAll(all);
        } else {
            Table found = null;
            for (Table t : all) if (t.id().equals(tableRef) || t.name().equals(tableRef)) found = t;
            if (found == null) {
                for (Table t : all) if (t.name().equalsIgnoreCase(tableRef)) found = t;
            }
            if (found == null) throw new ConnectorException("The Airtable base has no table " + tableRef + ".");
            out.add(found);
            if (viewRef != null) {
                for (Map.Entry<String, String> v : found.views().entrySet()) {
                    if (v.getKey().equals(viewRef) || v.getValue().equals(viewRef)) {
                        viewId = v.getKey();
                        viewName = v.getValue();
                    }
                }
                if (viewId == null) {
                    for (Map.Entry<String, String> v : found.views().entrySet()) {
                        if (v.getValue().equalsIgnoreCase(viewRef)) {
                            viewId = v.getKey();
                            viewName = v.getValue();
                        }
                    }
                }
                if (viewId == null) throw new ConnectorException("The table " + found.name() + " has no view " + viewRef + ".");
            }
        }
        tables = out;
    }

    private static Table table(JsonNode t) {
        String primaryId = t.path("primaryFieldId").asText("");
        String primary = null;
        String lastModified = null;
        String createdBy = null;
        List<String> fields = new ArrayList<>();
        for (JsonNode f : t.path("fields")) {
            String name = f.path("name").asText("");
            fields.add(name);
            if (f.path("id").asText("").equals(primaryId)) primary = name;
            String type = f.path("type").asText("");
            // One that watches every field - a field watching some of them would miss the rest.
            if (type.equals("lastModifiedTime") && lastModified == null
                    && f.path("options").path("referencedFieldIds").isEmpty()) {
                lastModified = name;
            }
            if (type.equals("createdBy") && createdBy == null) createdBy = name;
        }
        Map<String, String> views = new LinkedHashMap<>();
        for (JsonNode v : t.path("views")) views.put(v.path("id").asText(""), v.path("name").asText(""));
        return new Table(t.path("id").asText(""), t.path("name").asText(""), primary, fields, lastModified, createdBy,
                views);
    }

    // -- fetching ------------------------------------------------------------------------------

    /**
     * One record, read again as Airtable writes its values for a person. Only from a table this
     * source reads: an id names its table, and the agent's readSourceItem passes any id it is given.
     */
    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || !id.startsWith(RECORD) || !parts[2].matches("rec[A-Za-z0-9]{14}")) {
            throw new ConnectorException("That is not an item of an Airtable source.");
        }
        resolveTables();
        Table table = null;
        for (Table t : tables) if (t.id().equals(parts[1])) table = t;
        if (table == null) throw new ConnectorException("That record is not in a table this Airtable source reads.");
        try {
            return markdown(render(table, parts[2]));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Airtable no longer has that record.");
            throw e;
        }
    }

    /**
     * The record as markdown. Read through the list endpoint, filtered to its ID, because that is
     * where Airtable documents the person-readable cell format - and it keeps the read inside the
     * table, where fetching a record by ID would find it anywhere in the base.
     */
    String render(Table table, String recordId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("filterByFormula", "RECORD_ID()='" + recordId + "'");
        query.put("maxRecords", 1);
        query.put("cellFormat", "string");
        query.put("timeZone", "UTC");
        query.put("userLocale", "en-us");
        JsonNode records = api.getJson(base + "/" + ApiClient.segment(table.id()), query).path("records");
        if (records.isEmpty()) throw new ItemGoneException("Airtable no longer has that record.");
        JsonNode record = records.get(0);
        JsonNode fields = record.path("fields");

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(table, fields, recordId)).append("\n\n");
        md.append("- Base: ").append(baseName()).append('\n');
        md.append("- Table: ").append(table.name()).append('\n');
        if (viewName != null) md.append("- View: ").append(viewName).append('\n');
        String created = record.path("createdTime").asText("");
        if (!created.isBlank()) md.append("- Created: ").append(created).append('\n');
        md.append("- Link: ").append(WEB).append(base).append('/').append(table.id()).append('/').append(recordId).append('\n');

        // The schema's order, then anything the schema did not name.
        List<String> order = new ArrayList<>(table.fields());
        fields.fieldNames().forEachRemaining(n -> {
            if (!order.contains(n)) order.add(n);
        });
        StringBuilder sections = new StringBuilder();
        boolean any = false;
        for (String name : order) {
            if (name.equals(table.primaryField())) continue;
            JsonNode value = fields.get(name);
            if (value == null || value.isNull()) continue;
            String text = value.isValueNode() ? value.asText("") : value.toString();
            if (text.isBlank()) continue;
            if (text.length() > LONG_VALUE || text.contains("\n")) {
                sections.append("\n## ").append(name).append("\n\n").append(text.strip()).append('\n');
            } else {
                if (!any) md.append('\n');
                any = true;
                md.append("- ").append(name).append(": ").append(text.strip()).append('\n');
            }
        }
        md.append(sections);
        return md.toString();
    }

    // The base's name, from the list of bases the token reaches - its ID where that cannot be had.
    private String baseName() {
        if (baseName != null) return baseName;
        baseName = base;
        try {
            Iterator<JsonNode> bases = Paginator.cursor(api, "meta/bases", Map.of(), "offset",
                    page -> page.path("bases"), page -> page.path("offset").asText(null));
            while (bases.hasNext()) {
                JsonNode b = bases.next();
                if (base.equals(b.path("id").asText(""))) {
                    String name = b.path("name").asText("");
                    if (!name.isBlank()) baseName = name;
                    break;
                }
            }
        } catch (ConnectorHttpException e) {
            // shown by its ID
        }
        return baseName;
    }

    // -- helpers -------------------------------------------------------------------------------

    /**
     * A hash of what a record says: its fields with their names sorted, and an attachment by its ID,
     * name, size and type - never its link or thumbnails, which Airtable signs afresh every time.
     */
    static String hash(JsonNode fields) {
        StringBuilder canonical = new StringBuilder();
        canonical(fields, canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void canonical(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            boolean attachment = node.has("filename") && node.has("url");
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            out.append('{');
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                String key = e.getKey();
                if (attachment && (key.equals("url") || key.equals("thumbnails"))) continue;
                out.append(quote(key)).append(':');
                canonical(e.getValue(), out);
                out.append(',');
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (JsonNode element : node) {
                canonical(element, out);
                out.append(',');
            }
            out.append(']');
        } else {
            out.append(node.toString());
        }
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
