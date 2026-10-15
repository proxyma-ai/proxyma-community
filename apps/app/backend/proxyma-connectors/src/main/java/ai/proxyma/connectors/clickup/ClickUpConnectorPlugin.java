package ai.proxyma.connectors.clickup;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ClickUp tasks as a source - of a whole workspace, or of its spaces, folders and lists chosen by ID:
 * each task with its status, assignees, priority, dates, tags, custom fields, description,
 * checklists, comments and subtasks. Built on the connector framework v2 after the GitHub connector.
 *
 * <p><b>Identity</b> is the workspace (without case) and the chosen spaces, folders and lists, each
 * sorted ({@code acme/s:90101;l:90301}), or the workspace and {@code all}.
 *
 * <p><b>Incremental</b>: tasks updated since the last complete pass ({@code date_updated_gt} on the
 * workspace's task list). Closed tasks are read too; subtasks are items of their own.
 *
 * <p><b>Deletion</b>: a full pass lists every task in scope and the sync engine removes what it no
 * longer sees - deleted and archived tasks alike, since the list leaves archived ones out. A task
 * fetched after it was deleted, archived or moved out of scope is ItemGoneException.
 */
@Component
public class ClickUpConnectorPlugin implements ConnectorPlugin {

    static final String WORKSPACE = "workspace";
    static final String SPACES = "spaces";
    static final String FOLDERS = "folders";
    static final String LISTS = "lists";
    static final String ALL = "all";
    private static final String IDS = "\\s*[A-Za-z0-9_-]+(\\s*,\\s*[A-Za-z0-9_-]+)*\\s*";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.CLICKUP)
            .protocol(ConnectionProtocol.CLICKUP)
            .displayName("ClickUp")
            .description("ClickUp tasks with their descriptions, comments and subtasks, from a workspace or the "
                    + "spaces, folders and lists you choose.")
            .noun("ClickUp source", "ClickUp sources")
            .addTitle("Add ClickUp tasks")
            .order(62)
            .category(Category.TICKETS)
            .mark("clickup")
            .fields(FieldSpec.text(WORKSPACE, "Workspace").asRequired().placeholder("Acme")
                            .help("The workspace's name, or its ID - the number after app.clickup.com/ in its address."),
                    FieldSpec.text(SPACES, "Spaces").placeholder("90101").pattern(IDS)
                            .help("Space IDs, separated by commas. Every task of the workspace when no space, folder "
                                    + "or list is chosen."),
                    FieldSpec.text(FOLDERS, "Folders").placeholder("90201").pattern(IDS)
                            .help("Folder IDs, separated by commas."),
                    FieldSpec.text(LISTS, "Lists").placeholder("90301").pattern(IDS)
                            .help("List IDs, separated by commas."))
            .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("clickup")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new ClickUpSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        String scope = scope(config);
        return workspace(config).toLowerCase(Locale.ROOT) + "/" + (scope.isEmpty() ? ALL : scope);
    }

    @Override
    public String displayName(Map<String, String> config) {
        String scope = scope(config);
        String name = "ClickUp " + workspace(config);
        if (scope.isEmpty()) return name;
        return name + " (" + scope.replace("s:", "spaces ").replace("f:", "folders ").replace("l:", "lists ")
                .replace(";", "; ") + ")";
    }

    /** A workspace chosen by its ID links to it. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String workspace = workspace(config);
        return workspace.matches("\\d+") ? "https://app.clickup.com/" + workspace : null;
    }

    static String workspace(Map<String, String> config) {
        String raw = config.get(WORKSPACE);
        return raw == null ? "" : raw.strip();
    }

    // The chosen spaces, folders and lists, each kind sorted: "s:1,2;f:3;l:4", empty for all.
    static String scope(Map<String, String> config) {
        List<String> parts = new ArrayList<>();
        part(parts, "s", config.get(SPACES));
        part(parts, "f", config.get(FOLDERS));
        part(parts, "l", config.get(LISTS));
        return String.join(";", parts);
    }

    private static void part(List<String> parts, String prefix, String raw) {
        List<String> ids = WorkItems.sortedList(raw);
        if (!ids.isEmpty()) parts.add(prefix + ":" + String.join(",", ids));
    }
}
