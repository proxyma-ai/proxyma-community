package ai.proxyma.connectors.atlassian;

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
 * A Bitbucket Cloud repository as a source: its pull requests, each with its comments and reviews,
 * and - when asked for - the documentation files on its main branch.
 *
 * <p>A TYPE OF ITS OWN, not a variant of the Bitbucket Data Center type: the two share a name and
 * little else - another API (2.0 against REST 1.0), another identity (workspace/repository against
 * a project key and a slug), other credentials, and twenty agent tools written for Data Center's
 * API. One type with both behind a switch would hand those tools a Cloud connection they cannot
 * use; two types each say exactly what they read.
 *
 * <p><b>Identity</b> is {@code workspace/repository}, lower case.
 *
 * <p><b>Incremental</b>: pull requests updated since the last complete pass ({@code q=updated_on>}),
 * and the documentation files only when the branch's head commit moved, which the cursor tells.
 *
 * <p><b>Deletion</b>: a full pass lists every pull request and file, and the sync engine removes
 * what is absent; one deleted between passes is gone at its next fetch (a 404).
 */
@Component
public class BitbucketCloudConnectorPlugin implements ConnectorPlugin {

    static final String REPOSITORY = "repository";
    static final String PULL_REQUESTS = "pullRequests";
    static final String DOCS = "docs";
    static final String DOCS_PATH = "docsPath";
    static final String BRANCH = "branch";

    static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder(ConnectorType.BITBUCKET_CLOUD)
            .protocol(ConnectionProtocol.BITBUCKET_CLOUD)
            .displayName("Bitbucket Cloud")
            .description("A Bitbucket Cloud repository's pull requests, with their comments and reviews, and its "
                    + "documentation files if you choose.")
            .noun("Bitbucket Cloud repository", "Bitbucket Cloud repositories")
            .addTitle("Add a Bitbucket Cloud repository")
            .order(52)
            .category(Category.CODE)
            .mark("bitbucket")
            .fields(
                    FieldSpec.text(REPOSITORY, "Repository").asRequired().placeholder("workspace/repository")
                            .pattern("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"),
                    FieldSpec.bool(PULL_REQUESTS, "Pull requests").defaultValue("true"),
                    FieldSpec.bool(DOCS, "Documentation files").defaultValue("false")
                            .help("Markdown, reStructuredText and text files on the main branch."),
                    FieldSpec.text(DOCS_PATH, "Only files under").placeholder("docs/").asAdvanced(),
                    FieldSpec.text(BRANCH, "Branch").asAdvanced().help("The repository's main branch when empty."))
            .capabilities(Capabilities.builder().incremental().cursor().shareable().liveRead().build())
            .syncEvery(Duration.ofHours(1))
            .docsAnchor("bitbucket-cloud")
            .build();

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new BitbucketCloudSession(context);
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

    /** The repository's page on bitbucket.org, for the public API; another API address has none here. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String repo = config.get(REPOSITORY);
        if (repo == null || repo.isBlank()) return null;
        String base = serverUrl == null || serverUrl.isBlank() ? BitbucketCloudConnectionPlugin.DEFAULT_BASE_URL
                : serverUrl.strip().replaceAll("/+$", "");
        if (!base.equalsIgnoreCase(BitbucketCloudConnectionPlugin.DEFAULT_BASE_URL)) return null;
        return "https://bitbucket.org/" + repo.strip();
    }
}
