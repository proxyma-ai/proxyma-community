package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Bitbucket Cloud repository, opened for one pass or one batch of fetches. Everything it needs
 * is its {@link SourceContext}; nothing it holds is seen by any other source.
 *
 * <p>Items, by id:
 * <ul>
 *   <li>{@code pr:<id>} - a pull request in any state, listed from {@code /pullrequests} and rendered
 *       on fetch with its description, reviewers and comments;</li>
 *   <li>{@code file:<path>} - a documentation file on the branch, listed from its source tree at the
 *       head commit and fetched raw.</li>
 * </ul>
 */
final class BitbucketCloudSession implements ConnectorSession {

    // Clock skew between this host and Bitbucket: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final long MAX_DOC_BYTES = 1_000_000;
    static final Set<String> DOC_EXTENSIONS = Set.of("md", "mdx", "markdown", "rst", "txt", "adoc", "asciidoc");
    private static final List<String> STATES = List.of("OPEN", "MERGED", "DECLINED", "SUPERSEDED");
    private static final DateTimeFormatter BBQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
            .withZone(ZoneOffset.UTC);

    private final SourceContext context;
    private final String repository;
    private final String repoPath;
    private final ApiClient api;
    private JsonNode repoInfo;
    private String headHash;

    BitbucketCloudSession(SourceContext context) {
        this.context = context;
        String repo = context.get(BitbucketCloudConnectorPlugin.REPOSITORY);
        this.repository = repo == null ? "" : repo.strip();
        int slash = repository.indexOf('/');
        if (slash <= 0 || slash == repository.length() - 1) {
            throw new ConnectorException("A Bitbucket Cloud source needs its repository as workspace/repository.");
        }
        this.repoPath = "repositories/" + ApiClient.segment(repository.substring(0, slash)) + "/"
                + ApiClient.segment(repository.substring(slash + 1));
        this.api = client(context.http(), context.connection());
    }

    /**
     * The API at the connection's address (api.bitbucket.org's by default): an email and API token
     * as Basic, or an access token as a bearer.
     */
    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip().replaceAll("/+$", "")
                : BitbucketCloudConnectionPlugin.DEFAULT_BASE_URL;
        ApiAuth auth = connection.authKind() == AuthKind.BASIC
                ? ApiAuth.header(HttpHeaders.AUTHORIZATION, () -> {
                    String email = connection.username();
                    String token = connection.secret("token") != null ? connection.secret("token") : connection.soleSecret();
                    if (email == null || email.isBlank() || token == null || token.isBlank()) {
                        throw new ConnectorAuthException("This connection has no email address or API token. "
                                + "Add them in Settings > Connections.");
                    }
                    return "Basic " + Base64.getEncoder().encodeToString((email.strip() + ":" + token.strip())
                            .getBytes(StandardCharsets.UTF_8));
                })
                : ApiAuth.bearer(connection);
        return http.client(base).service("Bitbucket").auth(auth).rateLimit(RateLimitHint.STANDARD).build();
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            repoInfo();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 404) {
                return AccessCheck.failed("Bitbucket has no repository " + repository + ", or the credential cannot see it.");
            }
            if (e.status() == 401) {
                return AccessCheck.failed("Bitbucket did not accept the credential. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        boolean pulls = context.flag(BitbucketCloudConnectorPlugin.PULL_REQUESTS, true);
        boolean docs = context.flag(BitbucketCloudConnectorPlugin.DOCS, false);
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (pulls) parts.add(() -> pullItems(request));
        if (docs) parts.add(() -> fileItems(request));
        return Listings.withCursor(Listings.concat(parts), () -> docs && headHash != null ? "commit:" + headHash : null);
    }

    // Every pull request, in creation order - which an update cannot move, so a listing paged while
    // pull requests change neither skips one nor ends early. On a CHANGES pass, only those updated
    // since the last complete pass: a new comment updates a pull request.
    private Iterator<ItemMetadata> pullItems(ListRequest request) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("state", STATES);
        query.put("pagelen", 50);
        query.put("sort", "created_on");
        if (!request.isFull() && request.since() != null) {
            query.put("q", "updated_on > " + BBQL_TIME.format(request.since().minus(OVERLAP)));
        }
        Iterator<JsonNode> pages = Paginator.nextUrl(api, repoPath + "/pullrequests", query,
                page -> page.path("values"), page -> page.path("next").asText(null));
        return Listings.mapped(pages, json -> {
            int id = json.path("id").asInt(-1);
            if (id < 0) return null;
            ItemMetadata item = new ItemMetadata();
            item.setId("pr:" + id);
            item.setTitle("#" + id + " " + json.path("title").asText(""));
            item.setSourceType(ConnectorType.BITBUCKET_CLOUD);
            item.setLastModified(AtlassianApi.instant(json.path("updated_on").asText(null)));
            item.setContentType(AtlassianApi.MARKDOWN);
            putExtra(item, "url", json.path("links").path("html").path("href").asText(null));
            putExtra(item, ItemMetadata.AUTHOR, json.path("author").path("display_name").asText(null));
            return item;
        });
    }

    // The documentation files at the branch's head commit. On a CHANGES pass a head that is the
    // stored cursor has nothing new, and is not listed again.
    private Iterator<ItemMetadata> fileItems(ListRequest request) {
        String branch = branch();
        JsonNode head = api.getJson(repoPath + "/refs/branches/" + ApiClient.segment(branch)).path("target");
        headHash = head.path("hash").asText(null);
        if (headHash == null) throw new ConnectorException("Bitbucket did not say which commit the branch " + branch + " is at.");
        if (!request.isFull() && ("commit:" + headHash).equals(request.cursor())) return Collections.emptyIterator();
        Instant changed = AtlassianApi.instant(head.path("date").asText(null));
        String under = docsPath();
        String web = repoInfo().path("links").path("html").path("href").asText("");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("max_depth", 20);
        query.put("pagelen", 100);
        Iterator<JsonNode> entries = Paginator.nextUrl(api, repoPath + "/src/" + ApiClient.segment(headHash) + "/"
                + encodePath(under), query, page -> page.path("values"), page -> page.path("next").asText(null));
        return Listings.mapped(entries, entry -> {
            if (!"commit_file".equals(entry.path("type").asText())) return null;
            String path = entry.path("path").asText("");
            if (!under.isEmpty() && !path.startsWith(under)) return null;
            if (!isDocumentation(path) || entry.path("size").asLong(0) > MAX_DOC_BYTES) return null;
            ItemMetadata item = new ItemMetadata();
            item.setId("file:" + path);
            item.setTitle(path);
            item.setSourceType(ConnectorType.BITBUCKET_CLOUD);
            item.setLastModified(changed);
            item.setContentType(contentTypeOf(path));
            item.setSizeBytes(entry.path("size").asLong());
            if (!web.isEmpty()) putExtra(item, "url", web + "/src/" + branch + "/" + path);
            return item;
        });
    }

    static boolean isDocumentation(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.startsWith("readme") || name.startsWith("changelog")) return true;
        int dot = name.lastIndexOf('.');
        return dot > 0 && DOC_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String contentTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown")
                ? AtlassianApi.MARKDOWN : "text/plain";
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        try {
            if (id.startsWith("pr:")) return markdown(renderPull(number(id)));
            if (id.startsWith("file:")) {
                return api.stream(repoPath + "/src/" + ApiClient.segment(branch()) + "/"
                        + encodePath(id.substring("file:".length())), Map.of(), Map.of("Accept", "*/*"));
            }
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Bitbucket no longer has " + id + " in " + repository + ".");
            throw e;
        }
        throw new ConnectorException("That is not an item of a Bitbucket Cloud repository.");
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(id.indexOf(':') + 1));
        } catch (NumberFormatException e) {
            throw new ConnectorException("That is not an item of a Bitbucket Cloud repository.");
        }
    }

    String renderPull(int id) {
        JsonNode pr = api.getJson(repoPath + "/pullrequests/" + id);
        StringBuilder md = new StringBuilder();
        md.append("# #").append(id).append(' ').append(pr.path("title").asText("")).append("\n\n");
        md.append("- Repository: ").append(repository).append('\n');
        md.append("- Type: Pull request\n");
        md.append("- State: ").append(pr.path("state").asText("").toLowerCase(Locale.ROOT)).append('\n');
        md.append("- Author: ").append(pr.path("author").path("display_name").asText("")).append('\n');
        md.append("- Branch: ").append(pr.path("source").path("branch").path("name").asText(""))
                .append(" -> ").append(pr.path("destination").path("branch").path("name").asText("")).append('\n');
        List<String> reviewers = new ArrayList<>();
        pr.path("reviewers").forEach(r -> reviewers.add(r.path("display_name").asText()));
        if (!reviewers.isEmpty()) md.append("- Reviewers: ").append(String.join(", ", reviewers)).append('\n');
        md.append("- Created: ").append(pr.path("created_on").asText("")).append('\n');
        md.append("- Updated: ").append(pr.path("updated_on").asText("")).append('\n');
        md.append("- Link: ").append(pr.path("links").path("html").path("href").asText("")).append('\n');

        String body = pr.path("description").asText(pr.path("summary").path("raw").asText(""));
        md.append("\n## Description\n\n").append(body.isBlank() ? "(no description)" : body.strip()).append('\n');

        // A participant's verdict - approved, or asked for changes - is the review on Bitbucket.
        boolean any = false;
        for (JsonNode p : pr.path("participants")) {
            String verdict = p.path("approved").asBoolean(false) ? "approved"
                    : p.path("state").asText("").replace('_', ' ').toLowerCase(Locale.ROOT);
            if (verdict.isBlank() || "null".equals(verdict)) continue;
            if (!any) md.append("\n## Reviews\n\n");
            any = true;
            md.append("**").append(p.path("user").path("display_name").asText("")).append("**: ").append(verdict);
            if (p.hasNonNull("participated_on")) md.append(", ").append(p.path("participated_on").asText());
            md.append('\n');
        }

        Iterator<JsonNode> comments = Paginator.nextUrl(api, repoPath + "/pullrequests/" + id + "/comments",
                Map.of("pagelen", 100), page -> page.path("values"), page -> page.path("next").asText(null));
        boolean heading = false;
        while (comments.hasNext()) {
            JsonNode c = comments.next();
            if (c.path("deleted").asBoolean(false)) continue;
            String text = c.path("content").path("raw").asText("").strip();
            if (text.isEmpty()) continue;
            if (!heading) md.append("\n## Comments\n");
            heading = true;
            md.append("\n**").append(c.path("user").path("display_name").asText("")).append("**");
            if (c.path("inline").hasNonNull("path")) md.append(" on `").append(c.path("inline").path("path").asText()).append('`');
            if (c.hasNonNull("parent")) md.append(" (reply)");
            md.append(", ").append(c.path("created_on").asText("")).append(":\n\n").append(text).append('\n');
        }
        return md.toString();
    }

    // -- the repository ------------------------------------------------------------------------

    private JsonNode repoInfo() {
        if (repoInfo == null) repoInfo = api.getJson(repoPath);
        return repoInfo;
    }

    private String branch() {
        String configured = context.get(BitbucketCloudConnectorPlugin.BRANCH);
        if (configured != null && !configured.isBlank()) return configured.strip();
        String main = repoInfo().path("mainbranch").path("name").asText("");
        if (main.isEmpty()) throw new ConnectorException("Bitbucket did not say which branch is the repository's main one.");
        return main;
    }

    private String docsPath() {
        String raw = context.get(BitbucketCloudConnectorPlugin.DOCS_PATH);
        if (raw == null || raw.isBlank()) return "";
        String p = raw.strip().replace('\\', '/').replaceAll("^/+", "");
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static String encodePath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (!encoded.isEmpty()) encoded.append('/');
            encoded.append(ApiClient.segment(part));
        }
        if (path.endsWith("/") && !encoded.isEmpty()) encoded.append('/');
        return encoded.toString();
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
