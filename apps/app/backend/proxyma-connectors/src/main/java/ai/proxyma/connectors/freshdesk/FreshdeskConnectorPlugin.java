package ai.proxyma.connectors.freshdesk;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A Freshdesk account as a source: its tickets, each with its conversation - replies and public
 * notes, private notes only when chosen - and/or its knowledge base's published solution articles,
 * of chosen categories. Built on the connector framework v2 after the GitHub connector, over
 * Freshdesk's REST API v2.
 *
 * <p><b>Identity</b> is what the source reads - {@code tickets}, {@code articles(all)} or
 * {@code articles(<category ids>)}, or both - through one connection, which is one Freshdesk account.
 * Whether private notes are read is a setting of the source, not another source.
 *
 * <p><b>Incremental</b>: tickets updated since the last complete pass ({@code updated_since}; a reply
 * or a note moves a ticket), and the articles whose {@code updated_at} is after it. Freshdesk lists at
 * most 300 pages of a ticket query, so a long listing moves its window on to the last change it has
 * seen and carries on.
 *
 * <p><b>Deletion</b>: a full pass lists every ticket but those in the trash or marked spam, and every
 * published article; the sync engine removes what it no longer sees. A ticket fetched after it was
 * deleted, or an article after it went back to draft, is gone at once.
 */
@Component
public class FreshdeskConnectorPlugin implements ConnectorPlugin {

    static final String TICKETS = "tickets";
    static final String PRIVATE_NOTES = "privateNotes";
    static final String ARTICLES = "articles";
    static final String CATEGORIES = "categories";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.FRESHDESK)
            .protocol(ConnectionProtocol.FRESHDESK)
            .displayName("Freshdesk")
            .description("Freshdesk tickets with their conversations, and knowledge base articles if you choose.")
            .noun("Freshdesk source", "Freshdesk sources")
            .addTitle("Add Freshdesk tickets or articles")
            .order(61)
            .category(Category.SUPPORT)
            .mark("freshdesk")
            .fields(
                    FieldSpec.bool(TICKETS, "Tickets").defaultValue("true")
                            .help("Every ticket the agent can see, with its replies and notes."),
                    FieldSpec.bool(PRIVATE_NOTES, "Private notes").defaultValue("false")
                            .help("Notes only agents can see. Off: replies and public notes are read."),
                    FieldSpec.bool(ARTICLES, "Knowledge base articles").defaultValue("false")
                            .help("Published solution articles. Drafts are left out."),
                    FieldSpec.text(CATEGORIES, "Article categories").placeholder("43000123456")
                            .pattern("\\s*[0-9]+(\\s*,\\s*[0-9]+)*\\s*")
                            .help("Category numbers, separated by commas - the number in a category's address. "
                                    + "Every category when empty."))
            .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("freshdesk")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new FreshdeskSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> parts = new ArrayList<>();
        if (flag(config, TICKETS, true)) parts.add("tickets");
        if (flag(config, ARTICLES, false)) {
            List<String> categories = categories(config);
            parts.add("articles(" + (categories.isEmpty() ? "all" : String.join(",", categories)) + ")");
        }
        return String.join("+", parts);
    }

    @Override
    public String displayName(Map<String, String> config) {
        boolean tickets = flag(config, TICKETS, true);
        boolean articles = flag(config, ARTICLES, false);
        if (tickets && articles) return "Freshdesk tickets and articles";
        return articles ? "Freshdesk articles" : "Freshdesk tickets";
    }

    /**
     * The account's ticket list, or its knowledge base - known when the connection names its
     * Freshdesk address; a connection known by its domain alone has none to link to here.
     */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) return null;
        String base = FreshdeskApi.trimAddress(serverUrl);
        return base + (flag(config, TICKETS, true) ? "/a/tickets" : "/support/solutions");
    }

    static boolean flag(Map<String, String> config, String key, boolean fallback) {
        String v = config.get(key);
        return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v.strip());
    }

    /** The category ids a configuration names, sorted, each once; empty for every category. */
    static List<String> categories(Map<String, String> config) {
        TreeSet<String> out = new TreeSet<>();
        String raw = config.get(CATEGORIES);
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.strip();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }
}
