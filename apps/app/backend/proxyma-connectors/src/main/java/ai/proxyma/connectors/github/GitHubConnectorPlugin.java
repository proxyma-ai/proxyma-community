package ai.proxyma.connectors.github;

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
 * A GitHub repository as a source: its issues and pull requests, each with its conversation, and -
 * when asked for - the documentation files on its default branch. The reference connector of the
 * connector framework v2: built on the SPI alone, it is what a new connector copies.
 *
 * <p><b>Identity</b> is {@code owner/repository}, lower case: the same repository added twice on one
 * GitHub is one source, and the same name on a GitHub Enterprise Server is another.
 *
 * <p><b>Incremental</b>: issues and pull requests changed since the last complete pass (GitHub's
 * {@code since} on the issues list, which covers pull requests); the documentation files only when
 * the branch's tree changed, which the cursor - the tree's hash - tells.
 *
 * <p><b>Deletion</b>: a full pass lists every issue, pull request and file, and the sync engine
 * removes what is absent - the "slim listing", since nothing but the list endpoints is read for it.
 * An issue deleted between passes is gone at its next fetch (404 is ItemGoneException).
 */
@Component
public class GitHubConnectorPlugin implements ConnectorPlugin {

    static final String REPOSITORY = "repository";
    static final String ISSUES = "issues";
    static final String PULL_REQUESTS = "pullRequests";
    static final String DOCS = "docs";
    static final String DOCS_PATH = "docsPath";
    static final String BRANCH = "branch";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.GITHUB)
            .protocol(ConnectionProtocol.GITHUB)
            .displayName("GitHub")
            .description("A GitHub repository's issues and pull requests, with their comments, and its "
                    + "documentation files if you choose.")
            .noun("GitHub repository", "GitHub repositories")
            .addTitle("Add a GitHub repository")
            .order(55)
            .category(Category.CODE)
            .mark("github")
            .fields(
                    FieldSpec.text(REPOSITORY, "Repository").asRequired().placeholder("owner/repository")
                            .pattern("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+"),
                    FieldSpec.bool(ISSUES, "Issues").defaultValue("true"),
                    FieldSpec.bool(PULL_REQUESTS, "Pull requests").defaultValue("true"),
                    FieldSpec.bool(DOCS, "Documentation files").defaultValue("false")
                            .help("Markdown, reStructuredText and text files on the default branch."),
                    FieldSpec.text(DOCS_PATH, "Only files under").placeholder("docs/").asAdvanced(),
                    FieldSpec.text(BRANCH, "Branch").asAdvanced().help("The repository's default branch when empty."))
            .capabilities(Capabilities.builder().incremental().cursor().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("github")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new GitHubSession(context);
    }

    @Override
    public String identity(Map<String, String> config) {
        String repo = config.get(REPOSITORY);
        return repo == null ? "" : repo.strip().toLowerCase(Locale.ROOT);
    }

    /** Listed as typed - the registry compares names without case, so the identity still holds. */
    @Override
    public String displayName(Map<String, String> config) {
        String repo = config.get(REPOSITORY);
        return repo == null ? "" : repo.strip();
    }

    /**
     * The repository's page: github.com for the public API, and for GitHub Enterprise Server the
     * host its API lives under ({@code https://host/api/v3} serves {@code https://host}).
     */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String repo = config.get(REPOSITORY);
        if (repo == null || repo.isBlank()) return null;
        return webBase(serverUrl) + "/" + repo.strip();
    }

    static String webBase(String apiBase) {
        String base = apiBase == null || apiBase.isBlank() ? GitHubApi.DEFAULT_BASE_URL : apiBase.strip();
        base = base.replaceAll("/+$", "");
        if (base.equalsIgnoreCase(GitHubApi.DEFAULT_BASE_URL)) return "https://github.com";
        return base.replaceFirst("(?i)/api/v3$", "");
    }
}
