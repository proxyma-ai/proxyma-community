package ai.proxyma.connectors.asana;

import ai.proxyma.connectors.workitems.WorkItems;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asana tasks as a source - of a workspace's chosen projects, or of every project the connection
 * can see in it: each task with its assignee, dates, section, custom fields, notes, comments and
 * subtasks. Built on the connector framework v2 after the GitHub connector.
 *
 * <p><b>Identity</b> is the workspace and the projects, without case and sorted
 * ({@code acme/1201,launch plan}), or the workspace and {@code all}.
 *
 * <p><b>Incremental</b>: tasks modified since the last complete pass ({@code modified_since} on each
 * project's task list; a new comment modifies a task). Each project's Events sync token is the
 * cursor, so a CHANGES pass also learns which tasks were deleted or taken out of a project.
 *
 * <p><b>Deletion</b>: a full pass lists every task of the projects and the sync engine removes what
 * it no longer sees. Between full passes, a task the Events feed reports deleted or removed is
 * listed, and its fetch answers ItemGoneException (a 404, or a task no longer in any project the
 * source reads), so it leaves at once.
 */
@Component
public class AsanaConnectorPlugin implements ConnectorPlugin {

    static final String WORKSPACE = "workspace";
    static final String PROJECTS = "projects";
    static final String ALL = "all";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.ASANA)
            .protocol(ConnectionProtocol.ASANA)
            .displayName("Asana")
            .description("Asana tasks with their notes, comments and subtasks, from the projects you choose.")
            .noun("Asana source", "Asana sources")
            .addTitle("Add Asana tasks")
            .order(59)
            .category(Category.TICKETS)
            .mark("asana")
            .fields(FieldSpec.text(WORKSPACE, "Workspace").asRequired().placeholder("Acme")
                            .help("The workspace's name as Asana shows it, or its ID."),
                    FieldSpec.text(PROJECTS, "Projects").placeholder("1201234567890123, Launch plan")
                            .help("Project IDs or names, separated by commas. Every project the connection can see "
                                    + "in the workspace when empty."))
            .capabilities(Capabilities.builder().incremental().cursor().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("asana")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new AsanaSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        List<String> projects = projects(config);
        String workspace = workspace(config).toLowerCase(Locale.ROOT);
        return workspace + "/" + (projects.isEmpty() ? ALL
                : String.join(",", projects.stream().map(p -> p.toLowerCase(Locale.ROOT)).distinct().sorted().toList()));
    }

    @Override
    public String displayName(Map<String, String> config) {
        List<String> projects = projects(config);
        String name = "Asana " + workspace(config);
        return projects.isEmpty() ? name : name + ": " + String.join(", ", projects);
    }

    /** One project chosen by its ID links to it; anything else has no single page. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        List<String> projects = projects(config);
        if (projects.size() != 1 || !projects.get(0).matches("\\d+")) return null;
        return "https://app.asana.com/0/" + projects.get(0);
    }

    static String workspace(Map<String, String> config) {
        String raw = config.get(WORKSPACE);
        return raw == null ? "" : raw.strip();
    }

    /** The projects a configuration names, as typed, each once; empty for every project. */
    static List<String> projects(Map<String, String> config) {
        return WorkItems.list(config.get(PROJECTS));
    }
}
