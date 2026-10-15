package ai.proxyma.connectors.gitlab;

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
import java.util.Locale;
import java.util.Map;

/**
 * A GitLab project - or a whole group, with every project under it - as a source: its issues and
 * merge requests, each with its comments, and when asked for, the documentation files on each
 * project's default branch. Built on the connector framework v2 after the GitHub connector.
 *
 * <p><b>A project or a group.</b> GitLab nests projects in groups and subgroups, and a team's work is
 * often a group of small projects - the self-managed instances outsourcing firms run are laid out
 * that way. So the source is a PATH, and the session finds out which it names: a project is read
 * alone; a group is read through GitLab's group endpoints, which list the issues and merge requests
 * of every project under it (subgroups included, archived projects left out) in one paged list
 * each, so a group costs no more requests than a project for everything but its documentation files.
 *
 * <p><b>Identity</b> is the path, lower case: the same project added twice on one GitLab is one
 * source. A group and a project under it are two sources, as two GitHub repositories are.
 *
 * <p><b>Incremental</b>: issues and merge requests changed since the last complete pass (GitLab's
 * {@code updated_after}, which a new comment moves); the documentation files only when a project's
 * branch moved, which the cursor - a digest of every branch head - tells.
 *
 * <p><b>Deletion</b>: a full pass lists every issue, merge request and file, and the sync engine
 * removes what is absent - including everything of a project that left the group. An item deleted
 * between passes is gone at its next fetch (404 is ItemGoneException).
 */
@Component
public class GitLabConnectorPlugin implements ConnectorPlugin {

    static final String PATH = "path";
    static final String ISSUES = "issues";
    static final String MERGE_REQUESTS = "mergeRequests";
    static final String DOCS = "docs";
    static final String DOCS_PATH = "docsPath";
    static final String BRANCH = "branch";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.GITLAB)
            .protocol(ConnectionProtocol.GITLAB)
            .displayName("GitLab")
            .description("A GitLab project's or group's issues and merge requests, with their comments, and its "
                    + "documentation files if you choose.")
            .noun("GitLab project", "GitLab projects")
            .addTitle("Add a GitLab project or group")
            .order(56)
            .category(Category.CODE)
            .mark("gitlab")
            .fields(
                    FieldSpec.text(PATH, "Project or group").asRequired().placeholder("group/project")
                            .pattern("[A-Za-z0-9_.][A-Za-z0-9_.-]*(/[A-Za-z0-9_.][A-Za-z0-9_.-]*)*")
                            .help("A group's path reads every project under it, its subgroups included."),
                    FieldSpec.bool(ISSUES, "Issues").defaultValue("true"),
                    FieldSpec.bool(MERGE_REQUESTS, "Merge requests").defaultValue("true"),
                    FieldSpec.bool(DOCS, "Documentation files").defaultValue("false")
                            .help("Markdown, reStructuredText and text files on the default branch."),
                    FieldSpec.text(DOCS_PATH, "Only files under").placeholder("docs/").asAdvanced(),
                    FieldSpec.text(BRANCH, "Branch").asAdvanced().help("Each project's default branch when empty."))
            .capabilities(Capabilities.builder().incremental().cursor().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("gitlab")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new GitLabSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        return path(config).toLowerCase(Locale.ROOT);
    }

    /** Listed as typed - the registry compares names without case, so the identity still holds. */
    @Override
    public String displayName(Map<String, String> config) {
        return path(config);
    }

    /** The project's or group's page on its GitLab - the same address for both. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String path = path(config);
        if (path.isEmpty()) return null;
        return GitLabApi.webBase(serverUrl) + "/" + path;
    }

    static String path(Map<String, String> config) {
        String raw = config.get(PATH);
        return raw == null ? "" : raw.strip().replaceAll("^/+|/+$", "");
    }
}
