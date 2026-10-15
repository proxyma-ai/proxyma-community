package ai.proxyma.connectors.notion;

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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notion as a source: every page shared with the integration, or the pages and databases chosen -
 * a page with every page under it, a database with all its rows. Each page is one markdown document
 * (Notion's own page-as-markdown rendering), and a database row is a page whose properties head
 * the document.
 *
 * <p><b>Identity</b> is the selection: the chosen pages and databases by their Notion IDs, sorted,
 * or {@code *} for everything shared with the integration. A link and the bare ID of one page are
 * the same source.
 *
 * <p><b>Incremental</b>: Notion's search, sorted by {@code last_edited_time} newest first, read
 * until it reaches a minute before the last complete pass; with pages chosen, a changed page is kept
 * when its parents lead to one of them.
 *
 * <p><b>Deletion</b>: a complete full listing - the search, or the walk of every chosen page and
 * database - leaves out what was deleted, moved to the trash, or unshared, and the sync engine
 * removes it. A chosen page Notion no longer lets the integration read makes the listing partial,
 * so nothing is removed for it. A page in the trash is gone at its next fetch.
 *
 * <p>Owner-only: Notion's API states no sharing lists, so who in the workspace may read a page
 * cannot be carried over, and the source is not shareable.
 */
@Component
public class NotionConnectorPlugin implements ConnectorPlugin {

    static final String PAGES = "pages";

    // A Notion ID as it appears in a link or an API answer: 32 hex digits, with or without dashes.
    static final Pattern ID = Pattern.compile("([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})"
            + "-?([0-9a-fA-F]{12})");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.NOTION)
            .protocol(ConnectionProtocol.NOTION)
            .displayName("Notion")
            .description("Notion pages and database rows shared with your integration, each page one document "
                    + "with its properties.")
            .noun("Notion pages", "Notion pages")
            .addTitle("Add Notion pages")
            .order(52)
            .category(Category.DOCS)
            .mark("notion")
            .fields(
                    FieldSpec.list(PAGES, "Pages and databases").placeholder("https://www.notion.so/...")
                            .help("One per line, as a link or an ID. A page brings every page under it, a "
                                    + "database all its rows. Leave empty for everything shared with the "
                                    + "integration."))
            .capabilities(Capabilities.builder().incremental().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("notion")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new NotionSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> ids = new ArrayList<>(new TreeSet<>(roots(config.get(PAGES)).keySet()));
        return ids.isEmpty() ? "*" : String.join(",", ids);
    }

    /**
     * "Team Handbook, Roadmap": the titles a Notion link carries before its ID, or the ID where an
     * entry has none. Everything shared with the integration when nothing is chosen.
     */
    @Override
    public String displayName(Map<String, String> config) {
        Map<String, String> roots = roots(config.get(PAGES));
        if (roots.isEmpty()) return "Everything shared with the integration";
        return String.join(", ", roots.values());
    }

    /** The one page chosen, when exactly one is and it was given as a link. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String value = config.get(PAGES);
        if (value == null) return null;
        List<String> links = new ArrayList<>();
        for (String line : value.split("[\\r\\n]+")) {
            if (!line.isBlank()) links.add(line.strip());
        }
        if (links.size() != 1 || !links.get(0).matches("(?i)https?://.*")) return null;
        return links.get(0);
    }

    /**
     * The chosen pages and databases, in the order typed: each one's ID (dashed, lower case) and the
     * name it is shown under. An entry with no Notion ID in it is left out here, and named by
     * {@link #unrecognized}.
     */
    static Map<String, String> roots(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null) return out;
        for (String line : value.split("[\\r\\n]+")) {
            String entry = line.strip();
            if (entry.isEmpty()) continue;
            String id = id(entry);
            if (id != null && !out.containsKey(id)) out.put(id, nameOf(entry, id));
        }
        return out;
    }

    /** The entries that name no Notion page or database. */
    static List<String> unrecognized(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String line : value.split("[\\r\\n]+")) {
            String entry = line.strip();
            if (!entry.isEmpty() && id(entry) == null) out.add(entry);
        }
        return out;
    }

    /**
     * The Notion ID an entry names: a bare ID, or the last ID in a link's path - the query is left
     * out, since a database link's {@code ?v=} names its view, not the database.
     */
    static String id(String entry) {
        String e = entry == null ? "" : entry.strip();
        if (e.matches("(?i)https?://.*")) {
            try {
                String path = URI.create(e.replace(" ", "%20")).getPath();
                e = path == null ? "" : path;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        Matcher m = ID.matcher(e);
        String found = null;
        while (m.find()) found = m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "-" + m.group(4) + "-" + m.group(5);
        return found == null ? null : found.toLowerCase(Locale.ROOT);
    }

    /** {@code Team-Handbook-0123...} in a link is "Team Handbook"; anything else is shown as its ID. */
    private static String nameOf(String entry, String id) {
        if (entry.matches("(?i)https?://.*")) {
            try {
                String path = URI.create(entry.replace(" ", "%20")).getPath();
                String last = path == null ? "" : path.replaceAll("/+$", "");
                last = last.substring(last.lastIndexOf('/') + 1);
                String slug = ID.matcher(last).replaceAll("").replaceAll("-+$", "").replace('-', ' ').strip();
                if (!slug.isEmpty()) return slug;
            } catch (IllegalArgumentException ignored) {
                // shown as its ID
            }
        }
        return id;
    }
}
