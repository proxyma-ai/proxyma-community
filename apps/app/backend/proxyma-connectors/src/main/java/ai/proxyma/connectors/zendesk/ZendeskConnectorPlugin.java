package ai.proxyma.connectors.zendesk;

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
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A Zendesk account as a source: its tickets, each with its whole comment thread, and/or its Help
 * Center's published articles, in the languages and sections chosen. Built on the connector
 * framework v2 after the GitHub connector, over Zendesk's REST API.
 *
 * <p><b>Identity</b> is what the source reads - {@code tickets}, {@code articles(en-us;all)} or both,
 * the languages and section ids sorted - through one connection, which is one Zendesk account. The
 * same choice added twice is one source; whether internal notes are read is a setting of it, not
 * another source.
 *
 * <p><b>Incremental</b>, through Zendesk's incremental exports: tickets from the cursor-based ticket
 * export, resumed from the cursor the last complete pass stored (a new comment moves a ticket);
 * articles from the Help Center's incremental article export, from the {@code end_time} it last
 * answered.
 *
 * <p><b>Deletion</b>: a deleted ticket stays in the ticket export with the status {@code deleted}, so
 * a CHANGES pass lists it and its fetch answers ItemGoneException - it leaves at once. A full pass
 * lists neither deleted tickets nor draft or archived articles, and the sync engine removes what it
 * no longer sees; that also covers a ticket Zendesk has deleted for good, which leaves the export
 * entirely after 90 days.
 */
@Component
public class ZendeskConnectorPlugin implements ConnectorPlugin {

    static final String TICKETS = "tickets";
    static final String INTERNAL_NOTES = "internalNotes";
    static final String ARTICLES = "articles";
    static final String LOCALES = "locales";
    static final String SECTIONS = "sections";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.ZENDESK)
            .protocol(ConnectionProtocol.ZENDESK)
            .displayName("Zendesk")
            .description("Zendesk tickets with their comments, and Help Center articles if you choose.")
            .noun("Zendesk source", "Zendesk sources")
            .addTitle("Add Zendesk tickets or articles")
            .order(60)
            .category(Category.SUPPORT)
            .mark("zendesk")
            .fields(
                    FieldSpec.bool(TICKETS, "Tickets").defaultValue("true")
                            .help("Every ticket the agent can see, with its comments."),
                    FieldSpec.bool(INTERNAL_NOTES, "Internal notes").defaultValue("false")
                            .help("Agents' private comments on tickets. Off: only the public conversation is read."),
                    FieldSpec.bool(ARTICLES, "Help Center articles").defaultValue("false")
                            .help("Published articles. Drafts are left out."),
                    FieldSpec.text(LOCALES, "Article languages").placeholder("en-us, de")
                            .pattern("\\s*[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*(\\s*,\\s*[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*)*\\s*")
                            .help("Language codes, separated by commas. The Help Center's default language when empty."),
                    FieldSpec.text(SECTIONS, "Article sections").placeholder("360001234567")
                            .pattern("\\s*[0-9]+(\\s*,\\s*[0-9]+)*\\s*").asAdvanced()
                            .help("Section numbers, separated by commas - the number in a section's address. "
                                    + "Every section when empty."))
            .capabilities(Capabilities.builder().incremental().cursor().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("zendesk")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new ZendeskSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> parts = new ArrayList<>();
        if (flag(config, TICKETS, true)) parts.add("tickets");
        if (flag(config, ARTICLES, false)) {
            List<String> locales = locales(config);
            List<String> sections = sections(config);
            parts.add("articles(" + (locales.isEmpty() ? "default" : String.join(",", locales)) + ";"
                    + (sections.isEmpty() ? "all" : String.join(",", sections)) + ")");
        }
        return String.join("+", parts);
    }

    @Override
    public String displayName(Map<String, String> config) {
        boolean tickets = flag(config, TICKETS, true);
        boolean articles = flag(config, ARTICLES, false);
        if (tickets && articles) return "Zendesk tickets and articles";
        return articles ? "Zendesk articles" : "Zendesk tickets";
    }

    /**
     * The account's agent view for tickets, its Help Center for articles - known when the connection
     * names its Zendesk address; a connection known by its subdomain alone has none to link to here.
     */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) return null;
        String base = ZendeskApi.trimAddress(serverUrl);
        return base + (flag(config, TICKETS, true) ? "/agent" : "/hc");
    }

    static boolean flag(Map<String, String> config, String key, boolean fallback) {
        String v = config.get(key);
        return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v.strip());
    }

    /** The languages a configuration names, lower case, sorted, each once; empty for the default one. */
    static List<String> locales(Map<String, String> config) {
        return listOf(config.get(LOCALES), true);
    }

    /** The section ids a configuration names, sorted, each once; empty for every section. */
    static List<String> sections(Map<String, String> config) {
        return listOf(config.get(SECTIONS), false);
    }

    private static List<String> listOf(String raw, boolean lower) {
        TreeSet<String> out = new TreeSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.strip();
                if (lower) v = v.toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }
}
