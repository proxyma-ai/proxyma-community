package ai.proxyma.connectors.salesforce;

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

/**
 * Salesforce records as a source: the accounts, contacts, opportunities, leads and cases of the
 * connection's org - the objects chosen, Account, Opportunity and Case by default - each one
 * document with an allow-listed set of its fields and what hangs off it: an account's contacts,
 * opportunities and cases, an opportunity's contact roles, a case's comments, and every record's
 * notes and recent tasks and events.
 *
 * <p><b>Identity</b> is the set of objects, sorted ({@code Account,Case,Opportunity}): one connection
 * is one org.
 *
 * <p><b>Incremental</b>: records whose {@code SystemModstamp} is after the last complete pass less a
 * minute, by SOQL, oldest first.
 *
 * <p><b>Deletion</b>: a full pass lists every record that is not deleted, and the sync engine
 * removes what is absent. A CHANGES pass also asks the getDeleted resource which records were
 * deleted since; they are listed, and their fetch answers ItemGoneException, so they leave at once.
 *
 * <p><b>Not shareable</b>: what the source holds is what the connection's user may see, under the
 * org's sharing rules and field-level security - not what another Proxyma user could.
 */
@Component
public class SalesforceConnectorPlugin implements ConnectorPlugin {

    static final String OBJECTS = "objects";
    static final String API_VERSION = "apiVersion";
    static final String DEFAULT_OBJECTS = "Account,Opportunity,Case";
    static final List<String> ALL_OBJECTS = List.of("Account", "Contact", "Opportunity", "Lead", "Case");

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.SALESFORCE)
            .protocol(ConnectionProtocol.SALESFORCE)
            .displayName("Salesforce")
            .description("Salesforce accounts, contacts, opportunities, leads and cases, each with its notes, "
                    + "activity and related records.")
            .noun("Salesforce source", "Salesforce sources")
            .addTitle("Add Salesforce records")
            .order(71)
            .category(Category.CRM)
            .mark("salesforce")
            .fields(
                    FieldSpec.of(OBJECTS, "Objects", FieldType.MULTISELECT).asRequired()
                            .withOptions(List.of(
                                    new FieldSpec.Option("Account", "Accounts"),
                                    new FieldSpec.Option("Contact", "Contacts"),
                                    new FieldSpec.Option("Opportunity", "Opportunities"),
                                    new FieldSpec.Option("Lead", "Leads"),
                                    new FieldSpec.Option("Case", "Cases")))
                            .defaultValue(DEFAULT_OBJECTS)
                            .help("Read as the connection's Salesforce user, so this source cannot be shared."),
                    FieldSpec.text(API_VERSION, "API version").asAdvanced().placeholder(SalesforceApi.DEFAULT_VERSION)
                            .pattern("\\s*[0-9]{2,3}\\.0\\s*")
                            .help("Salesforce's REST API version. Leave empty for the one this version of Proxyma was built on."))
            .capabilities(Capabilities.builder().incremental().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("salesforce")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new SalesforceSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        return String.join(",", objects(config));
    }

    @Override
    public String displayName(Map<String, String> config) {
        return "Salesforce " + String.join(", ", objects(config));
    }

    /** The source's org: its My Domain URL. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return serverUrl == null || serverUrl.isBlank() ? null : serverUrl.strip().replaceAll("/+$", "");
    }

    /** The objects a configuration names, known ones only, in their canonical case, sorted; the default when none. */
    static List<String> objects(Map<String, String> config) {
        String raw = config.get(OBJECTS);
        if (raw == null || raw.isBlank()) raw = DEFAULT_OBJECTS;
        List<String> picked = new ArrayList<>();
        for (String part : raw.split(",")) {
            String wanted = part.strip().toLowerCase(Locale.ROOT);
            for (String known : ALL_OBJECTS) {
                if (known.toLowerCase(Locale.ROOT).equals(wanted) && !picked.contains(known)) picked.add(known);
            }
        }
        picked.sort(String::compareTo);
        return picked;
    }

    /** The API version a configuration pins, or the default. */
    static String version(Map<String, String> config) {
        String raw = config.get(API_VERSION);
        return raw == null || raw.isBlank() ? SalesforceApi.DEFAULT_VERSION : raw.strip();
    }
}
