package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.confluence.ConfluenceConnector.CommentDto;
import ai.proxyma.core.connector.confluence.ConfluenceConnector.ConfluencePageDto;
import ai.proxyma.core.connector.confluence.ConfluencePageMarkdown;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Confluence space, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext}; nothing it holds is seen by any other source.
 *
 * <p>Items are the space's current pages, by page id - the id every index already holds - rendered
 * on fetch with their comments by {@link ConfluencePageMarkdown}, the renderer the agent's
 * getConfluencePage uses too.
 *
 * <p><b>Data Center and Server</b> are read through {@code /rest/api}: the space's pages by offset,
 * a page with {@code body.view}, its comments by {@code child/comment?depth=all}.
 *
 * <p><b>Cloud</b> is read through the v2 API under {@code /wiki/api/v2} - the space by its key, its
 * pages by cursor, a page's body as rendered HTML, its footer comments and their replies and its
 * inline comments, and the people they name in one bulk lookup - except the incremental pass, which
 * needs CQL and so asks v1's {@code /wiki/rest/api/search}, the one v1 search Cloud still serves.
 */
final class ConfluenceSession implements ConnectorSession {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSession.class);

    static final int PAGE_SIZE = 100;
    private static final int CLOUD_PAGE_SIZE = 250;
    // Confluence's own ceiling for the comment endpoints; a page with more comments than this keeps
    // the first MAX_COMMENTS.
    private static final int COMMENT_PAGE_SIZE = 50;
    private static final int MAX_COMMENTS = 2000;
    private static final int MAX_ANCESTORS = 8;
    private static final DateTimeFormatter CQL_CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // A day for the unknown zone of the Confluence server, plus the framework's 15-minute minimum
    // for clock skew - see cqlCutoff().
    private static final Duration CUTOFF_OVERLAP = Duration.ofDays(1).plusMinutes(15);

    private final SourceContext context;
    private final ResolvedCredentials connection;
    private final boolean cloud;
    private final String spaceKey;
    private String base;
    private ApiClient api;
    private JsonNode cloudSpace;
    private boolean signedIn;
    private final Map<String, String> people = new HashMap<>();

    ConfluenceSession(SourceContext context) {
        this.context = context;
        this.connection = context.connection();
        this.cloud = AtlassianApi.isCloud(connection);
        String key = context.get(ConfluenceConnectorPlugin.SPACE_KEY);
        if ((key == null || key.isBlank()) && context.name() != null) key = context.name();
        this.spaceKey = key == null ? "" : key.strip();
    }

    private ApiClient api() {
        if (api == null) {
            base = AtlassianApi.confluenceBase(connection);
            api = AtlassianApi.client(context.http(), connection, "Confluence", base);
        }
        return api;
    }

    private String base() {
        api();
        return base;
    }

    // -- the probe -----------------------------------------------------------------------------

    /**
     * One authenticated read of the space itself - the resource being registered, not just the
     * server - so a wrong token or a misspelled key is refused while the person who typed it is still
     * on the form.
     */
    @Override
    public AccessCheck verifyAccess() {
        if (spaceKey.isEmpty()) return AccessCheck.failed("No Confluence space key was given for this source.");
        try {
            if (cloud) {
                requireSignedIn();
                cloudSpace();
            } else {
                api().getJson("rest/api/space/" + ApiClient.segment(spaceKey));
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            return AccessCheck.failed(described(e).getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    /**
     * Cloud only: that Confluence signed the credential in as an ACCOUNT. A wrong, expired or revoked
     * API token is not a 401 on Cloud - it is answered as the anonymous user, so a space anonymous
     * visitors may open registered with any token, and a private one was "not found" rather than a
     * refused token (telemetry aced67d0, on Jira). Asked once a session.
     */
    private void requireSignedIn() {
        if (signedIn) return;
        JsonNode me = getJson("wiki/rest/api/user/current", Map.of());
        if (me.path("accountId").asText("").isBlank() || "anonymous".equalsIgnoreCase(me.path("type").asText(""))) {
            throw described(new ConnectorHttpException("Not signed in", 401, "wiki/rest/api/user/current"));
        }
        signedIn = true;
    }

    /**
     * The registration wording for a 401, 403 or 404 - shared with the listings, so a failed sync
     * says as much as a refused registration did. A 401 is the credential itself, a 403 a working
     * credential whose account cannot open this space.
     */
    private ConnectorHttpException described(ConnectorHttpException e) {
        String message = switch (e.status()) {
            case 401 -> "Confluence at " + base + (cloud ? " rejected the email address and API token" : " rejected the access token")
                    + " - check it in Settings > Connections > Confluence, and that it has not expired.";
            case 403 -> "Confluence accepted the access token, but its account has no permission to view space "
                    + spaceKey + " - ask a Confluence administrator to grant it access.";
            case 404 -> "Space " + spaceKey + " was not found on " + base
                    + " - check the space key, and that your Confluence account can open it.";
            default -> null;
        };
        return message == null ? e : new ConnectorHttpException(message, e.status(), e.path(), e);
    }

    private JsonNode getJson(String path, Map<String, ?> query) {
        try {
            return api().getJson(path, query);
        } catch (ConnectorHttpException e) {
            throw described(e);
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        if (spaceKey.isEmpty()) return Listings.failed("This Confluence source has no space key, so there is nothing to list.");
        boolean full = request.isFull() || request.since() == null;
        Supplier<Iterator<ItemMetadata>> pages = () -> {
            if (cloud) requireSignedIn();
            if (full) {
                return cloud ? Listings.mapped(cloudPages(), this::cloudPageItem)
                        : Listings.mapped(dataCenterPages(), this::dataCenterPageItem);
            }
            return changedPages(cqlCutoff(request.since(), ZoneId.systemDefault()));
        };
        return Listings.concat(List.of(pages));
    }

    /**
     * The CQL cutoff for an incremental listing, as the wall-clock string CQL expects. CQL has no time
     * zone in its syntax - Confluence reads the string in the SERVER's zone - so the window is
     * widened by a day plus the framework's 15-minute minimum and rendered in {@code zone}. Re-listing
     * a day of unchanged pages is free: reconcile compares lastModified and skips them without a write.
     */
    static String cqlCutoff(Instant since, ZoneId zone) {
        Instant widened = since.minus(CUTOFF_OVERLAP);
        return LocalDateTime.ofInstant(widened, zone).format(CQL_CUTOFF_FORMAT);
    }

    // Data Center: /rest/api/content by offset, while the answer names a next page.
    private Iterator<JsonNode> dataCenterPages() {
        return Paginator.iterate(next -> {
            int start = next == null ? 0 : Integer.parseInt(next);
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("spaceKey", spaceKey);
            query.put("type", "page");
            query.put("status", "current");
            query.put("start", start);
            query.put("limit", PAGE_SIZE);
            query.put("expand", "version");
            JsonNode page = getJson("rest/api/content", query);
            List<JsonNode> results = Paginator.elements(page.path("results"));
            boolean more = page.path("_links").has("next") && !results.isEmpty();
            return new Paginator.Page<>(results, more ? String.valueOf(start + results.size()) : null);
        });
    }

    private ItemMetadata dataCenterPageItem(JsonNode page) {
        ConfluencePageDto dto = ConfluencePageMarkdown.parsePage(page, base());
        ItemMetadata item = pageItem(dto.getId(), dto.getTitle(), dto.getWebUrl(),
                ConfluencePageMarkdown.parseConfluenceTimestamp(dto.getLastModified()));
        String by = page.path("version").path("by").path("displayName").asText("");
        if (!by.isBlank()) item.putExtra(ItemMetadata.AUTHOR, by);
        return item;
    }

    // Cloud: the space's pages by cursor, from the v2 API.
    private Iterator<JsonNode> cloudPages() {
        String spaceId = cloudSpace().path("id").asText();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("limit", CLOUD_PAGE_SIZE);
        query.put("status", "current");
        return Paginator.iterate(next -> {
            JsonNode page = next == null ? getJson("wiki/api/v2/spaces/" + ApiClient.segment(spaceId) + "/pages", query)
                    : getJson(next, Map.of());
            return new Paginator.Page<>(Paginator.elements(page.path("results")), cloudNext(page));
        });
    }

    private ItemMetadata cloudPageItem(JsonNode page) {
        String id = page.path("id").asText("");
        if (id.isEmpty()) return null;
        return pageItem(id, page.path("title").asText(id), cloudWebUrl(page, id),
                AtlassianApi.instant(page.path("version").path("createdAt").asText(null)));
    }

    // The next page a Cloud answer names. v2 writes it from the site root (/wiki/api/v2/...), v1's
    // search from its own base, which is the site's /wiki (/rest/api/search?...).
    private static String cloudNext(JsonNode page) {
        String next = page.path("_links").path("next").asText("");
        if (next.isBlank()) return null;
        if (next.startsWith("http://") || next.startsWith("https://")) return next;
        String path = next.startsWith("/") ? next.substring(1) : next;
        return path.startsWith("wiki/") ? path : "wiki/" + path;
    }

    private String cloudWebUrl(JsonNode node, String id) {
        String webui = node.path("_links").path("webui").asText("");
        return webui.isBlank() ? base() + "/wiki/pages/viewpage.action?pageId=" + id
                : ConfluencePageMarkdown.joinUrl(base() + "/wiki", webui);
    }

    private JsonNode cloudSpace() {
        if (cloudSpace == null) {
            JsonNode found = getJson("wiki/api/v2/spaces", Map.of("keys", spaceKey));
            JsonNode first = found.path("results").path(0);
            if (first.isMissingNode() || !first.has("id")) {
                throw described(new ConnectorHttpException("Not found", 404, "wiki/api/v2/spaces"));
            }
            cloudSpace = first;
        }
        return cloudSpace;
    }

    /**
     * Pages changed since the cutoff, and the pages of COMMENTS changed since it: a comment is
     * rendered into its page's document but makes no page version, so a page whose only change was a
     * new comment would otherwise reach the index only when its body was next edited. A comment
     * stands for its page (its container), dated by the comment; a page reached several ways is
     * listed once per pass.
     */
    private Iterator<ItemMetadata> changedPages(String cutoff) {
        String cql = "space=\"" + spaceKey + "\" AND lastModified>\"" + cutoff + "\" AND (type=page OR type=comment)";
        Set<String> listed = new HashSet<>();
        Iterator<JsonNode> results = cloud ? cloudSearch(cql) : dataCenterSearch(cql);
        return Listings.mapped(results, node -> {
            // Cloud's search wraps each content in a result; Data Center's content search does not.
            JsonNode content = node.has("content") ? node.path("content") : node;
            Instant when = AtlassianApi.instant(content.path("version").path("when").asText(null));
            if (when == null) when = AtlassianApi.instant(node.path("lastModified").asText(null));
            JsonNode page = content;
            if ("comment".equals(content.path("type").asText())) {
                page = content.path("container");
                if (!page.has("id") || !"page".equals(page.path("type").asText("page"))) return null;
            } else if (!"page".equals(content.path("type").asText("page"))) {
                return null;
            }
            String id = page.path("id").asText("");
            if (id.isEmpty() || !listed.add(id)) return null;
            String title = page.path("title").asText(id);
            String url = cloud ? cloudWebUrl(page, id) : ConfluencePageMarkdown.parsePage(page, base()).getWebUrl();
            return pageItem(id, title, url, when);
        });
    }

    // Data Center: /rest/api/content/search, paged by what the search READ - a comment on a blog post
    // is read and dropped, and paging by what was kept would ask for it again.
    private Iterator<JsonNode> dataCenterSearch(String cql) {
        return Paginator.iterate(next -> {
            int start = next == null ? 0 : Integer.parseInt(next);
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("cql", cql);
            query.put("start", start);
            query.put("limit", PAGE_SIZE);
            query.put("expand", "version,container");
            JsonNode page = getJson("rest/api/content/search", query);
            List<JsonNode> results = Paginator.elements(page.path("results"));
            boolean more = page.path("_links").has("next") && !results.isEmpty();
            return new Paginator.Page<>(results, more ? String.valueOf(start + results.size()) : null);
        });
    }

    // Cloud: v1's search - the CQL endpoint Cloud still serves - by its cursor links.
    private Iterator<JsonNode> cloudSearch(String cql) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("cql", cql);
        query.put("limit", PAGE_SIZE);
        query.put("expand", "content.version,content.container");
        return Paginator.iterate(next -> {
            JsonNode page = next == null ? getJson("wiki/rest/api/search", query) : getJson(next, Map.of());
            return new Paginator.Page<>(Paginator.elements(page.path("results")), cloudNext(page));
        });
    }

    private static ItemMetadata pageItem(String id, String title, String url, Instant lastModified) {
        ItemMetadata item = new ItemMetadata();
        item.setId(id);
        item.setTitle(title);
        item.setSourceType(ConnectorType.CONFLUENCE);
        item.setContentType(AtlassianApi.MARKDOWN);
        item.setLastModified(lastModified != null ? lastModified : Instant.now());
        if (url != null && !url.isBlank()) item.putExtra("url", url);
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId().strip();
        if (id.isEmpty()) throw new ConnectorException("That is not a page of a Confluence space.");
        String markdown;
        try {
            markdown = cloud ? cloudPage(id) : dataCenterPage(id);
        } catch (ConnectorHttpException e) {
            // A gone page is a distinct outcome, not an empty document: the drain removes it at once.
            if (e.isNotFound()) throw new ItemGoneException("Page " + id + " no longer exists");
            throw e;
        }
        return new ByteArrayInputStream((markdown == null ? "" : markdown).getBytes(StandardCharsets.UTF_8));
    }

    String dataCenterPage(String id) {
        JsonNode page = api().getJson("rest/api/content/" + ApiClient.segment(id),
                Map.of("expand", "body.view,version,history,ancestors,metadata.labels,space"));
        return ConfluencePageMarkdown.parsePageWithDetails(page, dataCenterComments(id), base()).getContent();
    }

    // Every page of the page's comments, replies included (depth=all). Best effort: a comment that
    // will not load costs the comments, never the page.
    private List<CommentDto> dataCenterComments(String id) {
        List<CommentDto> comments = new ArrayList<>();
        int start = 0;
        try {
            while (comments.size() < MAX_COMMENTS) {
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("expand", "body.view,version");
                query.put("depth", "all");
                query.put("start", start);
                query.put("limit", COMMENT_PAGE_SIZE);
                JsonNode page = api().getJson("rest/api/content/" + ApiClient.segment(id) + "/child/comment", query);
                List<JsonNode> results = Paginator.elements(page.path("results"));
                for (JsonNode c : results) {
                    CommentDto dto = ConfluencePageMarkdown.parseComment(c);
                    if (dto != null) comments.add(dto);
                }
                // Advance by what came back, never by the limit: a comment the server filtered out
                // of this batch would otherwise take a real one's place in the next one.
                start += results.size();
                if (!page.path("_links").has("next") || results.isEmpty()) break;
            }
        } catch (RuntimeException e) {
            log.warn("[Confluence] Could not read the comments of page {}: {}", id, e.getMessage());
        }
        return comments;
    }

    String cloudPage(String id) {
        String path = "wiki/api/v2/pages/" + ApiClient.segment(id);
        JsonNode page;
        try {
            page = api().getJson(path, Map.of("body-format", "view", "include-labels", "true", "include-version", "true"));
        } catch (ConnectorHttpException e) {
            // A site that will not render the page as HTML still has its storage format.
            if (e.status() != 400) throw e;
            page = api().getJson(path, Map.of("body-format", "storage", "include-labels", "true", "include-version", "true"));
        }
        JsonNode body = page.path("body");
        String html = body.path("view").path("value").asText(body.path("storage").path("value").asText(""));

        List<JsonNode> rawComments = cloudComments(id);
        Set<String> accounts = new LinkedHashSet<>();
        accounts.add(page.path("authorId").asText(""));
        accounts.add(page.path("version").path("authorId").asText(""));
        for (JsonNode c : rawComments) accounts.add(c.path("version").path("authorId").asText(""));
        resolvePeople(accounts);

        List<CommentDto> comments = new ArrayList<>();
        for (JsonNode c : rawComments) {
            CommentDto dto = new CommentDto();
            String author = people.get(c.path("version").path("authorId").asText(""));
            dto.setAuthor(author == null ? "Unknown" : author);
            dto.setDate(ConfluencePageMarkdown.formatDate(c.path("version").path("createdAt").asText("")));
            JsonNode cb = c.path("body");
            dto.setContent(ConfluencePageMarkdown.htmlToMarkdown(
                    cb.path("storage").path("value").asText(cb.path("view").path("value").asText(""))));
            if (!dto.getContent().isBlank()) comments.add(dto);
        }

        List<String> labels = new ArrayList<>();
        for (JsonNode l : Paginator.elements(page.path("labels").path("results"))) labels.add(l.path("name").asText());

        ConfluencePageDto dto = new ConfluencePageDto();
        dto.setId(id);
        dto.setTitle(page.path("title").asText(id));
        dto.setWebUrl(cloudWebUrl(page, id));
        JsonNode space = cloudSpaceQuietly();
        String createdAt = page.path("createdAt").asText("");
        String updatedAt = page.path("version").path("createdAt").asText("");
        return ConfluencePageMarkdown.pageToMarkdown(dto,
                space == null ? spaceKey : space.path("key").asText(spaceKey),
                space == null ? null : space.path("name").asText(null),
                people.get(page.path("authorId").asText("")),
                createdAt.isEmpty() ? null : ConfluencePageMarkdown.formatDate(createdAt),
                people.get(page.path("version").path("authorId").asText("")),
                updatedAt.isEmpty() ? null : ConfluencePageMarkdown.formatDate(updatedAt),
                labels, cloudAncestors(id), html, comments);
    }

    private JsonNode cloudSpaceQuietly() {
        try {
            return cloudSpace();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The footer comments with their replies, then the inline ones. Best effort, as on Data Center.
    private List<JsonNode> cloudComments(String id) {
        List<JsonNode> out = new ArrayList<>();
        try {
            String page = "wiki/api/v2/pages/" + ApiClient.segment(id);
            for (JsonNode footer : cloudList(page + "/footer-comments")) {
                if (out.size() >= MAX_COMMENTS) break;
                out.add(footer);
                String cid = footer.path("id").asText("");
                if (!cid.isEmpty()) {
                    for (JsonNode reply : cloudList("wiki/api/v2/footer-comments/" + ApiClient.segment(cid) + "/children")) {
                        if (out.size() >= MAX_COMMENTS) break;
                        out.add(reply);
                    }
                }
            }
            for (JsonNode inline : cloudList(page + "/inline-comments")) {
                if (out.size() >= MAX_COMMENTS) break;
                out.add(inline);
            }
        } catch (RuntimeException e) {
            log.warn("[Confluence] Could not read the comments of page {}: {}", id, e.getMessage());
        }
        return out;
    }

    private List<JsonNode> cloudList(String path) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("body-format", "storage");
        query.put("limit", COMMENT_PAGE_SIZE);
        List<JsonNode> out = new ArrayList<>();
        Iterator<JsonNode> all = Paginator.iterate(next -> {
            JsonNode page = next == null ? api().getJson(path, query) : api().getJson(next);
            return new Paginator.Page<>(Paginator.elements(page.path("results")), cloudNext(page));
        });
        while (all.hasNext() && out.size() < MAX_COMMENTS) out.add(all.next());
        return out;
    }

    // The titles of the pages above this one, the space's top first. Best effort.
    private List<String> cloudAncestors(String id) {
        List<String> titles = new ArrayList<>();
        try {
            JsonNode answer = api().getJson("wiki/api/v2/pages/" + ApiClient.segment(id) + "/ancestors",
                    Map.of("limit", MAX_ANCESTORS));
            for (JsonNode a : Paginator.elements(answer.path("results"))) {
                if (!"page".equals(a.path("type").asText("page"))) continue;
                String title = a.path("title").asText("");
                if (title.isBlank()) {
                    title = api().getJson("wiki/api/v2/pages/" + ApiClient.segment(a.path("id").asText()))
                            .path("title").asText("");
                }
                if (!title.isBlank()) titles.add(title);
            }
        } catch (RuntimeException e) {
            log.debug("[Confluence] Could not read the ancestors of page {}: {}", id, e.getMessage());
        }
        return titles;
    }

    // Account ids to names, in one request for everyone a page names. Cloud states people by id only.
    private void resolvePeople(Set<String> accountIds) {
        List<String> missing = accountIds.stream().filter(a -> !a.isBlank() && !people.containsKey(a)).toList();
        if (missing.isEmpty()) return;
        try {
            JsonNode answer = api().requireOk(api().send(HttpMethod.POST, "wiki/api/v2/users-bulk", Map.of(),
                    Map.of("accountIds", missing), Map.of())).json();
            for (JsonNode u : Paginator.elements(answer.path("results"))) {
                String name = u.path("displayName").asText(u.path("publicName").asText(""));
                if (!name.isBlank()) people.put(u.path("accountId").asText(), name);
            }
        } catch (RuntimeException e) {
            log.debug("[Confluence] Could not look up {} people: {}", missing.size(), e.getMessage());
        }
    }
}
