package ai.proxyma.connectors.hubspot;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.Capabilities;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.FieldType;
import ai.proxyma.core.connector.spi.SourceContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * HubSpot CRM records as a source: the companies, contacts, deals and tickets of the connection's
 * account - the object types chosen, companies, deals and tickets by default - each one document with
 * its properties, the records it is associated with by name, and its activity (notes, calls,
 * meetings, emails, tasks).
 *
 * <p><b>Identity</b> is the set of object types, sorted ({@code companies,deals,tickets}): one
 * connection is one account, so the same types added twice through it are one source.
 *
 * <p><b>Incremental</b>: records whose last-modified date is at or after the last complete pass (less
 * a minute), through CRM search - which answers at most 10,000 results for one query, so a longer
 * change list is read in date windows (HubSpotSession). Logging a note or a call moves a record's
 * last-modified date.
 *
 * <p><b>Deletion</b>: a full pass lists every record that is not archived, and the sync engine
 * removes what is absent. A CHANGES pass also lists the records archived since the last pass; their
 * fetch answers ItemGoneException, so they leave at once.
 *
 * <p><b>Not shareable</b>: a Service Key, a private app token and a sign-in all read the whole
 * account at their scopes, whatever HubSpot's own record permissions would show a given user.
 */
@Component
public class HubSpotConnectorPlugin implements ConnectorPlugin {

    static final String OBJECTS = "objects";
    static final String API_VERSION = "apiVersion";
    static final String DEFAULT_OBJECTS = "companies,deals,tickets";
    static final List<String> ALL_OBJECTS = List.of("companies", "contacts", "deals", "tickets");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.HUBSPOT)
            .protocol(ConnectionProtocol.HUBSPOT)
            .displayName("HubSpot")
            .description("HubSpot companies, contacts, deals and tickets, each with its notes, activity and "
                    + "associated records.")
            .noun("HubSpot source", "HubSpot sources")
            .addTitle("Add HubSpot records")
            .order(70)
            .category(Category.CRM)
            .mark("hubspot")
            .fields(
                    FieldSpec.of(OBJECTS, "Records", FieldType.MULTISELECT).asRequired()
                            .withOptions(List.of(
                                    new FieldSpec.Option("companies", "Companies"),
                                    new FieldSpec.Option("contacts", "Contacts"),
                                    new FieldSpec.Option("deals", "Deals"),
                                    new FieldSpec.Option("tickets", "Tickets")))
                            .defaultValue(DEFAULT_OBJECTS)
                            .help("The connection reads the whole HubSpot account, so this source cannot be shared."),
                    FieldSpec.text(API_VERSION, "API version").asAdvanced().placeholder(HubSpotApi.DEFAULT_VERSION)
                            .pattern("\\s*(20[0-9]{2}-(0[1-9]|1[0-2])|v[34])\\s*")
                            .help("HubSpot's dated API version. Leave empty for the one this version of Proxyma was built on."))
            .capabilities(Capabilities.builder().incremental().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("hubspot")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new HubSpotSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        return String.join(",", objects(config));
    }

    @Override
    public String displayName(Map<String, String> config) {
        return "HubSpot " + String.join(", ", objects(config));
    }

    /** The object types a configuration names, known ones only, sorted, each once; the default when none. */
    static List<String> objects(Map<String, String> config) {
        String raw = config.get(OBJECTS);
        if (raw == null || raw.isBlank()) raw = DEFAULT_OBJECTS;
        TreeSet<String> types = new TreeSet<>();
        for (String part : raw.split(",")) {
            String type = part.strip().toLowerCase(Locale.ROOT);
            if (ALL_OBJECTS.contains(type)) types.add(type);
        }
        return new ArrayList<>(types);
    }

    /** The API version a configuration pins, or the default. */
    static String version(Map<String, String> config) {
        String raw = config.get(API_VERSION);
        return raw == null || raw.isBlank() ? HubSpotApi.DEFAULT_VERSION : raw.strip();
    }
}
