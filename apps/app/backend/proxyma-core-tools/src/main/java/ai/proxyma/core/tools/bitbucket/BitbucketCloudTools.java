package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.SensitivePaths;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bitbucket Cloud, read live: a pull request, or a file at a branch - the Cloud counterparts of
 * getBitbucketPr and browseBitbucketPath's file read, which speak Data Center's API and name a
 * PROJECT; Cloud names a WORKSPACE, so these take {@code workspace/repository} as its sources do.
 *
 * <p>NOT a second client. Each call opens the Bitbucket Cloud connector's own session over the
 * caller's connection - the one ToolConnectorResolver picks (useServer, else the repository's own
 * source, else the default) and checks for Read - and fetches one item, so what the agent reads is
 * rendered exactly as it was indexed, and the credential goes where the session pins it and nowhere
 * else. Reads only: Bitbucket Cloud has no write tools, so its connection offers no Write switch.
 */
@Component
public class BitbucketCloudTools {

    private static final String LABEL = "Bitbucket Cloud";
    // As much text as readSourceItem hands back - a page the model can read, not a repository dump.
    static final int MAX_CHARS = 30_000;
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
    private static final Pattern BRANCH = Pattern.compile("[^\\s~^:?*\\[\\\\]{1,200}");

    private final ToolConnectorResolver resolver;
    private final ConnectorConnectionService connections;
    private final SourceContextFactory contexts;
    private final ConnectorCatalog catalog;
    private final ConnectorHttp http;

    public BitbucketCloudTools(ToolConnectorResolver resolver, ConnectorConnectionService connections,
                               SourceContextFactory contexts, ConnectorCatalog catalog, ConnectorHttp http) {
        this.resolver = resolver;
        this.connections = connections;
        this.contexts = contexts;
        this.catalog = catalog;
        this.http = http;
    }

    @Tool(description = "A Bitbucket Cloud pull request, live: title, state, branches, description, reviewers and "
            + "comments as Markdown. For Bitbucket Data Center use getBitbucketPr.")
    @ToolResult({
            @ToolResultField(name = "content", type = ToolFieldType.STRING, always = true,
                    description = "The pull request as Markdown."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when the text was cut to fit.")
    })
    public Map<String, Object> getBitbucketCloudPr(
            @ToolParam(description = "workspace/repository, e.g. acme/website") String repository,
            @ToolParam(description = "The pull request's number") int prId) {
        try {
            if (prId <= 0) return failure("A pull request number is a positive whole number.");
            return read(repository, null, "pr:" + prId);
        } catch (Exception e) {
            return failure(Failures.describe(e));
        }
    }

    @Tool(description = "One file of a Bitbucket Cloud repository as text, live, at a branch (the main branch when "
            + "omitted). For Bitbucket Data Center use browseBitbucketPath.")
    @ToolResult({
            @ToolResultField(name = "content", type = ToolFieldType.STRING, always = true,
                    description = "The file's text."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when the text was cut to fit.")
    })
    public Map<String, Object> readBitbucketCloudFile(
            @ToolParam(description = "workspace/repository, e.g. acme/website") String repository,
            @ToolParam(description = "The file's path in the repository, e.g. docs/setup.md") String path,
            @ToolParam(required = false, description = "A branch; the repository's main branch when omitted") String branch) {
        try {
            String p = path == null ? "" : path.strip().replaceAll("^/+", "");
            if (p.isEmpty() || p.endsWith("/")) return failure("Name a file, such as docs/setup.md.");
            if (branch != null && !branch.isBlank() && !BRANCH.matcher(branch.strip()).matches()) {
                return failure("That is not a branch name.");
            }
            return read(repository, branch, "file:" + p);
        } catch (Exception e) {
            return failure(Failures.describe(e));
        }
    }

    // One item through the connector's session, over the caller's own connection, checked for Read.
    private Map<String, Object> read(String repository, String branch, String itemId) throws Exception {
        String repo = repository == null ? "" : repository.strip();
        if (!REPOSITORY.matcher(repo).matches()) {
            return failure("Name the repository as workspace/repository, e.g. acme/website.");
        }
        ConnectorPlugin plugin = catalog.plugin(ConnectorType.BITBUCKET_CLOUD).orElse(null);
        if (plugin == null) return failure("Bitbucket Cloud is not available on this installation.");
        String userId = SearchContext.getUserId();
        // The repository is what a Bitbucket Cloud source is named, so a registered one picks its own
        // connection - as a Jira project key does for the Jira tools.
        Endpoint endpoint = resolver.resolve(ConnectorType.BITBUCKET_CLOUD, LABEL, repo, Access.READ);
        ResolvedConnection connection = connections.resolveOwned(userId, endpoint.connectionId())
                .orElseThrow(() -> new IllegalStateException("That Bitbucket Cloud connection is not yours."));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("repository", repo);
        if (branch != null && !branch.isBlank()) config.put("branch", branch.strip());
        ItemMetadata item = new ItemMetadata();
        item.setId(itemId);
        String text;
        try (ConnectorSession session = plugin.open(SourceContext.of(ConnectorType.BITBUCKET_CLOUD, config,
                contexts.credentials(connection), http));
             InputStream in = session.fetch(item)) {
            text = new String(in.readNBytes(MAX_CHARS * 4 + 1), StandardCharsets.UTF_8);
        }
        boolean truncated = text.length() > MAX_CHARS;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("content", SensitivePaths.scrub(truncated ? text.substring(0, MAX_CHARS) : text));
        out.put("truncated", truncated);
        return out;
    }

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message);
    }
}
