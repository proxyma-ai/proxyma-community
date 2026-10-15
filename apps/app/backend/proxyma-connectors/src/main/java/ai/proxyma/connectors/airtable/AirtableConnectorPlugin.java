package ai.proxyma.connectors.airtable;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Airtable base as a source: the records of one of its tables - or of every table - optionally
 * only those a view shows. One item per record, rendered as markdown: its fields by name, long text
 * kept whole, linked records by their names and attachments by name and link.
 *
 * <p><b>Identity</b> is the base, the table ({@code *} for every table) and the view when one is
 * chosen. A link to a table or a view, pasted as the base, names all three.
 *
 * <p><b>Incremental</b>: the records created or changed since a minute before the last complete pass,
 * by a {@code LAST_MODIFIED_TIME()} formula. That function does not see a change to a computed field
 * (a formula, a lookup, a rollup), so the daily full pass compares every record's content hash and
 * re-reads those whose fields changed.
 *
 * <p><b>Deletion</b>: a complete full listing leaves out the records deleted, or no longer in the
 * view, and the sync engine removes them.
 *
 * <p>Owner-only: a base's collaborators are not synced, so the source is not shareable.
 */
@Component
public class AirtableConnectorPlugin implements ConnectorPlugin {

    static final String BASE = "base";
    static final String TABLE = "table";
    static final String VIEW = "view";

    // Airtable's IDs: a three-letter kind and 14 letters and digits.
    static final Pattern BASE_ID = Pattern.compile("\\b(app[A-Za-z0-9]{14})\\b");
    static final Pattern TABLE_ID = Pattern.compile("\\b(tbl[A-Za-z0-9]{14})\\b");
    static final Pattern VIEW_ID = Pattern.compile("\\b(viw[A-Za-z0-9]{14})\\b");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.AIRTABLE)
            .protocol(ConnectionProtocol.AIRTABLE)
            .displayName("Airtable")
            .description("The records of an Airtable base, one document per record with every field, from one "
                    + "table or all of them.")
            .noun("Airtable base", "Airtable bases")
            .addTitle("Add an Airtable base")
            .order(64)
            .category(Category.TICKETS)
            .mark("airtable")
            .fields(
                    FieldSpec.text(BASE, "Base").asRequired().placeholder("appXXXXXXXXXXXXXX")
                            .pattern(".*app[A-Za-z0-9]{14}.*")
                            .help("The base's ID, or a link to the base, a table or a view - copied from the "
                                    + "address bar in Airtable."),
                    FieldSpec.text(TABLE, "Table").placeholder("Projects")
                            .help("Its name or ID. Leave empty for every table in the base."),
                    FieldSpec.text(VIEW, "View").placeholder("Grid view")
                            .help("Only the records this view shows, by its name or ID. Needs a table."))
            .capabilities(Capabilities.builder().incremental().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("airtable")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new AirtableSession(context);
    }

    /** {@code app.../Projects} or {@code app.../*}, with {@code /<view>} where one is chosen. */
    @Override
    public String identity(Map<String, String> config) {
        String base = baseId(config);
        if (base == null) return "";
        String table = table(config);
        String view = view(config);
        return base + "/" + (table == null ? "*" : table) + (view == null ? "" : "/" + view);
    }

    @Override
    public String displayName(Map<String, String> config) {
        String base = baseId(config);
        if (base == null) return "";
        String table = table(config);
        String view = view(config);
        if (table == null) return "Airtable base " + base;
        return table + (view == null ? "" : " (" + view + ")") + " in " + base;
    }

    /** The base in Airtable, or the table when it is named by its ID. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String base = baseId(config);
        if (base == null) return null;
        String table = table(config);
        return "https://airtable.com/" + base + (table != null && TABLE_ID.matcher(table).matches() ? "/" + table : "");
    }

    /** The base's ID, from the ID typed or the link pasted. */
    static String baseId(Map<String, String> config) {
        return find(BASE_ID, config.get(BASE));
    }

    /** The table typed - else the one a pasted link names - or null for every table. */
    static String table(Map<String, String> config) {
        String typed = config.get(TABLE);
        if (typed != null && !typed.isBlank()) return typed.strip();
        return find(TABLE_ID, config.get(BASE));
    }

    /** The view typed - else the one a pasted link names - or null for none. */
    static String view(Map<String, String> config) {
        String typed = config.get(VIEW);
        if (typed != null && !typed.isBlank()) return typed.strip();
        return find(VIEW_ID, config.get(BASE));
    }

    private static String find(Pattern pattern, String value) {
        if (value == null) return null;
        Matcher m = pattern.matcher(value);
        return m.find() ? m.group(1) : null;
    }
}
