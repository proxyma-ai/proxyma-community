package ai.proxyma.connectors.github;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One GitHub repository, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the repository, its switches, the connection's API address and token - and
 * nothing it holds is seen by any other source.
 *
 * <p>Items, by id:
 * <ul>
 *   <li>{@code issue:<number>} and {@code pull:<number>} - listed from {@code /issues} (which carries
 *       pull requests too, told apart by their {@code pull_request} key), rendered on fetch as one
 *       markdown document with the conversation;</li>
 *   <li>{@code file:<path>} - a documentation file on the branch, listed from the branch's tree with
 *       its blob hash as the content hash, fetched raw.</li>
 * </ul>
 */
final class GitHubSession implements ConnectorSession {

    // Clock skew between this host and GitHub: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // GitHub's contents API serves files up to 1 MB raw; larger ones are not documentation anyway.
    static final long MAX_DOC_BYTES = 1_000_000;
    static final Set<String> DOC_EXTENSIONS = Set.of("md", "mdx", "markdown", "rst", "txt", "adoc", "asciidoc");
    static final String MARKDOWN = "text/markdown";

    private final SourceContext context;
    private final ApiClient api;
    private final String repository;
    private final String repoPath;
    private JsonNode repoInfo;
    private String treeSha;
    private boolean treeTruncated;

    GitHubSession(SourceContext context) {
        this.context = context;
        this.repository = context.get(GitHubConnectorPlugin.REPOSITORY) == null ? ""
                : context.get(GitHubConnectorPlugin.REPOSITORY).strip();
        int slash = repository.indexOf('/');
        if (slash <= 0 || slash == repository.length() - 1) {
            throw new ConnectorException("A GitHub source needs its repository as owner/repository.");
        }
        this.repoPath = "repos/" + ApiClient.segment(repository.substring(0, slash)) + "/"
                + ApiClient.segment(repository.substring(slash + 1));
        this.api = GitHubApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            repoInfo();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 404) {
                return AccessCheck.failed("GitHub has no repository " + repository + ", or the token cannot see it.");
            }
            if (e.status() == 401) {
                return AccessCheck.failed("GitHub did not accept the token. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        boolean issues = context.flag(GitHubConnectorPlugin.ISSUES, true);
        boolean pulls = context.flag(GitHubConnectorPlugin.PULL_REQUESTS, true);
        boolean docs = context.flag(GitHubConnectorPlugin.DOCS, false);
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (issues || pulls) parts.add(() -> issueItems(request, issues, pulls));
        if (docs) parts.add(() -> fileItems(request));
        Listing listing = Listings.withCursor(Listings.concat(parts),
                () -> docs && treeSha != null ? "tree:" + treeSha : null);
        return Listings.partialIf(listing, () -> treeTruncated
                ? "GitHub listed only part of the repository's files, so some documentation files were left out."
                : null);
    }

    // Issues and pull requests in one list, oldest change first. On a CHANGES pass, only those
    // changed since the last complete pass - a new comment changes an issue's updated_at.
    private Iterator<ItemMetadata> issueItems(ListRequest request, boolean issues, boolean pulls) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("state", "all");
        query.put("sort", "updated");
        query.put("direction", "asc");
        query.put("per_page", 100);
        if (!request.isFull() && request.since() != null) query.put("since", request.since().minus(OVERLAP).toString());
        Iterator<JsonNode> pages = Paginator.linkHeader(api, repoPath + "/issues", query, page -> page);
        return Listings.mapped(pages, json -> {
            boolean pull = json.has("pull_request");
            if (pull ? !pulls : !issues) return null;
            ItemMetadata item = new ItemMetadata();
            int number = json.path("number").asInt();
            item.setId((pull ? "pull:" : "issue:") + number);
            item.setTitle("#" + number + " " + json.path("title").asText(""));
            item.setSourceType(ConnectorType.GITHUB);
            item.setLastModified(instant(json.path("updated_at").asText(null)));
            item.setContentType(MARKDOWN);
            putExtra(item, "url", json.path("html_url").asText(null));
            putExtra(item, ItemMetadata.AUTHOR, json.path("user").path("login").asText(null));
            return item;
        });
    }

    // The documentation files on the branch, from its tree - one request for the whole branch. On a
    // CHANGES pass a tree whose hash is the stored cursor has nothing new, and is not listed again.
    private Iterator<ItemMetadata> fileItems(ListRequest request) {
        String branch = branch();
        JsonNode tree = api.getJson(repoPath + "/git/trees/" + ApiClient.segment(branch), Map.of("recursive", "1"));
        treeSha = tree.path("sha").asText(null);
        treeTruncated = tree.path("truncated").asBoolean(false);
        if (!request.isFull() && treeSha != null && ("tree:" + treeSha).equals(request.cursor())) {
            return Collections.emptyIterator();
        }
        String under = docsPath();
        String web = repoInfo().path("html_url").asText("");
        Instant pushed = instant(repoInfo().path("pushed_at").asText(null));
        List<ItemMetadata> out = new ArrayList<>();
        for (JsonNode entry : Paginator.elements(tree.path("tree"))) {
            if (!"blob".equals(entry.path("type").asText())) continue;
            String path = entry.path("path").asText("");
            if (!under.isEmpty() && !path.startsWith(under)) continue;
            if (!isDocumentation(path) || entry.path("size").asLong(0) > MAX_DOC_BYTES) continue;
            ItemMetadata item = new ItemMetadata();
            item.setId("file:" + path);
            item.setTitle(path);
            item.setSourceType(ConnectorType.GITHUB);
            item.setLastModified(pushed);
            item.setContentType(contentTypeOf(path));
            item.setSizeBytes(entry.path("size").asLong());
            putExtra(item, ItemMetadata.CONTENT_HASH, entry.path("sha").asText(null));
            if (!web.isEmpty()) putExtra(item, "url", web + "/blob/" + branch + "/" + path);
            out.add(item);
        }
        return out.iterator();
    }

    static boolean isDocumentation(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.startsWith("readme") || name.startsWith("changelog")) return true;
        int dot = name.lastIndexOf('.');
        return dot > 0 && DOC_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String contentTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown") ? MARKDOWN : "text/plain";
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        try {
            if (id.startsWith("issue:")) return markdown(renderIssue(number(id)));
            if (id.startsWith("pull:")) return markdown(renderPull(number(id)));
            if (id.startsWith("file:")) {
                String path = id.substring("file:".length());
                StringBuilder encoded = new StringBuilder();
                for (String part : path.split("/")) {
                    if (!encoded.isEmpty()) encoded.append('/');
                    encoded.append(ApiClient.segment(part));
                }
                return api.stream(repoPath + "/contents/" + encoded, Map.of("ref", branch()),
                        Map.of("Accept", "application/vnd.github.raw"));
            }
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("GitHub no longer has " + id + " in " + repository + ".");
            throw e;
        }
        throw new ConnectorException("That is not an item of a GitHub repository.");
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(id.indexOf(':') + 1));
        } catch (NumberFormatException e) {
            throw new ConnectorException("That is not an item of a GitHub repository.");
        }
    }

    String renderIssue(int number) {
        JsonNode issue = api.getJson(repoPath + "/issues/" + number);
        StringBuilder md = header(issue, "Issue", issue.path("state").asText(""));
        body(md, issue);
        comments(md, "Comments", repoPath + "/issues/" + number + "/comments", null);
        return md.toString();
    }

    String renderPull(int number) {
        JsonNode pull = api.getJson(repoPath + "/pulls/" + number);
        String state = pull.hasNonNull("merged_at") ? "merged" : pull.path("state").asText("");
        if (pull.path("draft").asBoolean(false)) state = state + " (draft)";
        StringBuilder md = header(pull, "Pull request", state);
        md.append("- Branch: ").append(pull.path("head").path("ref").asText("")).append(" -> ")
                .append(pull.path("base").path("ref").asText("")).append('\n');
        body(md, pull);
        comments(md, "Comments", repoPath + "/issues/" + number + "/comments", null);
        reviews(md, number);
        comments(md, "Review comments", repoPath + "/pulls/" + number + "/comments", "path");
        return md.toString();
    }

    private StringBuilder header(JsonNode json, String kind, String state) {
        StringBuilder md = new StringBuilder();
        md.append("# #").append(json.path("number").asInt()).append(' ').append(json.path("title").asText("")).append("\n\n");
        md.append("- Repository: ").append(repository).append('\n');
        md.append("- Type: ").append(kind).append('\n');
        md.append("- State: ").append(state).append('\n');
        md.append("- Author: ").append(json.path("user").path("login").asText("")).append('\n');
        List<String> assignees = new ArrayList<>();
        json.path("assignees").forEach(a -> assignees.add(a.path("login").asText()));
        if (!assignees.isEmpty()) md.append("- Assignees: ").append(String.join(", ", assignees)).append('\n');
        List<String> labels = new ArrayList<>();
        json.path("labels").forEach(l -> labels.add(l.path("name").asText()));
        if (!labels.isEmpty()) md.append("- Labels: ").append(String.join(", ", labels)).append('\n');
        if (json.hasNonNull("milestone")) md.append("- Milestone: ").append(json.path("milestone").path("title").asText()).append('\n');
        md.append("- Created: ").append(json.path("created_at").asText("")).append('\n');
        md.append("- Updated: ").append(json.path("updated_at").asText("")).append('\n');
        if (json.hasNonNull("closed_at")) md.append("- Closed: ").append(json.path("closed_at").asText()).append('\n');
        md.append("- Link: ").append(json.path("html_url").asText("")).append('\n');
        return md;
    }

    private static void body(StringBuilder md, JsonNode json) {
        String body = json.path("body").asText("");
        md.append("\n## Description\n\n").append(body.isBlank() ? "(no description)" : body.strip()).append('\n');
    }

    // A paged list of comments under a heading - issue comments, or a pull request's review comments
    // with the file each is on.
    private void comments(StringBuilder md, String heading, String path, String fileField) {
        Iterator<JsonNode> all = Paginator.linkHeader(api, path, Map.of("per_page", 100), page -> page);
        boolean any = false;
        while (all.hasNext()) {
            JsonNode c = all.next();
            if (!any) md.append("\n## ").append(heading).append("\n");
            any = true;
            md.append("\n**").append(c.path("user").path("login").asText("")).append("**");
            if (fileField != null && c.hasNonNull(fileField)) md.append(" on `").append(c.path(fileField).asText()).append('`');
            md.append(", ").append(c.path("created_at").asText("")).append(":\n\n")
                    .append(c.path("body").asText("").strip()).append('\n');
        }
    }

    private void reviews(StringBuilder md, int number) {
        Iterator<JsonNode> all = Paginator.linkHeader(api, repoPath + "/pulls/" + number + "/reviews",
                Map.of("per_page", 100), page -> page);
        boolean any = false;
        while (all.hasNext()) {
            JsonNode r = all.next();
            String body = r.path("body").asText("").strip();
            String state = r.path("state").asText("").toLowerCase(Locale.ROOT).replace('_', ' ');
            if (!any) md.append("\n## Reviews\n");
            any = true;
            md.append("\n**").append(r.path("user").path("login").asText("")).append("**: ").append(state);
            md.append(r.hasNonNull("submitted_at") ? ", " + r.path("submitted_at").asText() : "").append('\n');
            if (!body.isEmpty()) md.append('\n').append(body).append('\n');
        }
    }

    // -- the repository ------------------------------------------------------------------------

    private JsonNode repoInfo() {
        if (repoInfo == null) repoInfo = api.getJson(repoPath);
        return repoInfo;
    }

    private String branch() {
        String configured = context.get(GitHubConnectorPlugin.BRANCH);
        if (configured != null && !configured.isBlank()) return configured.strip();
        String fromRepo = repoInfo().path("default_branch").asText("");
        if (fromRepo.isEmpty()) throw new ConnectorException("GitHub did not say which branch is the repository's default.");
        return fromRepo;
    }

    private String docsPath() {
        String raw = context.get(GitHubConnectorPlugin.DOCS_PATH);
        if (raw == null || raw.isBlank()) return "";
        String p = raw.strip().replace('\\', '/').replaceAll("^/+", "");
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
