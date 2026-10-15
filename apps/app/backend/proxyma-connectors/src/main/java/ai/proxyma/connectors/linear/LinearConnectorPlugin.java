package ai.proxyma.connectors.linear;

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
 * Linear issues as a source - of the teams chosen by key, or of every team the connection can see:
 * each issue with its description, state, assignee, project, cycle, labels and comments. Built on the
 * connector framework v2 after the GitHub connector, over Linear's GraphQL API.
 *
 * <p><b>Identity</b> is the set of team keys, upper case and sorted ({@code ENG,OPS}), or {@code all}:
 * the same teams added twice through one connection are one source, in whatever order they were
 * typed.
 *
 * <p><b>Incremental</b>: issues updated since the last complete pass (an {@code updatedAt} filter; a
 * new comment moves it).
 *
 * <p><b>Deletion</b>: archived and trashed issues are gone. A full pass lists neither, so the sync
 * engine removes what it no longer sees; a CHANGES pass lists the ones archived or trashed since the
 * last pass, and their fetch answers ItemGoneException, so they leave at once rather than at the next
 * full pass.
 */
@Component
public class LinearConnectorPlugin implements ConnectorPlugin {

    static final String TEAMS = "teams";
    static final String ALL = "all";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.LINEAR)
            .protocol(ConnectionProtocol.LINEAR)
            .displayName("Linear")
            .description("Linear issues with their descriptions and comments, from the teams you choose.")
            .noun("Linear source", "Linear sources")
            .addTitle("Add Linear issues")
            .order(57)
            .category(Category.TICKETS)
            .mark("linear")
            .fields(FieldSpec.text(TEAMS, "Team keys").placeholder("ENG, OPS")
                    .pattern("\\s*[A-Za-z0-9_]+(\\s*,\\s*[A-Za-z0-9_]+)*\\s*")
                    .help("Separated by commas. Every team the connection can see when empty."))
            .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("linear")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new LinearSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> keys = teams(config);
        return keys.isEmpty() ? ALL : String.join(",", keys);
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> keys = teams(config);
        return keys.isEmpty() ? "Linear" : "Linear " + String.join(", ", keys);
    }

    /** The team keys a configuration names, upper case, sorted, each once; empty for every team. */
    static List<String> teams(Map<String, String> config) {
        String raw = config.get(TEAMS);
        TreeSet<String> keys = new TreeSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String key = part.strip().toUpperCase(Locale.ROOT);
                if (!key.isEmpty()) keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }
}
