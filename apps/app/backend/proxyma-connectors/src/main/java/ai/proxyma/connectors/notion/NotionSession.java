package ai.proxyma.connectors.notion;

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
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * One Notion source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the chosen pages and databases, the connection's token - and every name it
 * looks up (a person, a database, a page's parents) is kept for this session alone.
 *
 * <p>Items, by id: {@code page:<id>} - a page, or a database row, which Notion keeps as a page.
 * Listed from the search (everything shared), or from a walk of each chosen page's blocks and each
 * chosen database's rows; fetched as Notion's own markdown rendering of the page, under a header of
 * its properties.
 *
 * <p><b>What a walk reaches.</b> Under a chosen page: its child pages, at any depth and inside
 * toggles, columns and other blocks that hold blocks, and the rows of the databases among them. Not
 * the pages inside a database row's own body, which would cost a request per row of every database;
 * with nothing chosen, the search finds those too.
 */
final class NotionSession implements ConnectorSession {

    // Clock skew, and Notion's last_edited_time being rounded down to the minute: a changes pass
    // keeps anything edited from a minute before the last complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // How deep a walk goes in pages under a chosen one, and in blocks holding blocks within a page.
    static final int MAX_PAGE_DEPTH = 30;
    static final int MAX_BLOCK_DEPTH = 8;
    // How far up a changed page's parents are followed to find whether it is under a chosen page.
    static final int MAX_ANCESTRY = 60;
    // A page Notion truncates names the blocks it left out; this many are asked for on their own.
    static final int MAX_UNKNOWN_BLOCKS = 10;
    // The fallback renderer's bound, in blocks, for a page the markdown endpoint will not render.
    static final int MAX_RENDERED_BLOCKS = 5000;
    static final String MARKDOWN = "text/markdown";
    static final String PAGE = "page:";

    /** A chosen page, database or data source, as found. */
    record Root(String id, Kind kind) {}

    enum Kind { PAGE, DATABASE, DATA_SOURCE }

    private final SourceContext context;
    private final ApiClient api;
    private final Map<String, String> chosen;
    private final Map<String, String> userNames = new HashMap<>();
    private final Map<String, String> dataSourceTitles = new HashMap<>();
    private final Map<String, Boolean> within = new HashMap<>();
    private final Map<String, JsonNode> rootPages = new HashMap<>();
    private List<Root> roots;
    // The chosen ids, and the data sources of the chosen databases.
    private final Set<String> rootIds = new HashSet<>();
    private final List<String> unreadable = new ArrayList<>();
    private boolean incomplete;

    NotionSession(SourceContext context) {
        this.context = context;
        this.api = NotionApi.client(context.http(), context.connection());
        this.chosen = NotionConnectorPlugin.roots(context.get(NotionConnectorPlugin.PAGES));
    }

    private boolean everything() {
        return chosen.isEmpty();
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            List<String> wrong = NotionConnectorPlugin.unrecognized(context.get(NotionConnectorPlugin.PAGES));
            if (!wrong.isEmpty()) {
                return AccessCheck.failed(String.join(", ", wrong) + " is not a link to a Notion page or database, "
                        + "or its ID.");
            }
            if (everything()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("filter", Map.of("property", "object", "value", "page"));
                body.put("page_size", 1);
                if (NotionApi.post(api, "search", body).path("results").isEmpty()) {
                    return AccessCheck.failed("No page is shared with the Notion integration yet. In Notion, open a "
                            + "page's ... menu, choose Connections, and add the integration.");
                }
                return AccessCheck.verified();
            }
            resolveRoots();
            if (!unreadable.isEmpty()) {
                return AccessCheck.failed("The Notion integration cannot read " + String.join(", ", unreadable)
                        + ". Share each page or database with it under ... > Connections, or check the links.");
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Notion did not accept the token. Replace it, or sign in again, in "
                        + "Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Instant changedAfter = request.isFull() || request.since() == null ? null : request.since().minus(OVERLAP);
        Listing listing;
        if (everything()) {
            listing = Listings.of(searchItems(changedAfter, null));
        } else if (changedAfter != null) {
            listing = Listings.of(searchItems(changedAfter, this::isWithinRoots));
        } else {
            listing = Listings.of(new Walk());
        }
        // A chosen page the integration can no longer read is not a page whose children are gone,
        // and a search Notion cut short did not see everything: either way, nothing is removed.
        return Listings.partialIf(listing, () -> {
            if (!unreadable.isEmpty()) {
                return "Notion did not let the integration read " + String.join(", ", unreadable)
                        + ", so nothing under it was removed. Share it with the integration again, or leave it out.";
            }
            return incomplete ? "Notion listed only part of the pages, so nothing was removed." : null;
        });
    }

    /**
     * The pages the search finds, newest edit first - all of them, or on a changes pass those edited
     * after {@code changedAfter}, the search stopping at the first older one. {@code keep}, when
     * given, decides which of them belong to the source.
     */
    private Iterator<ItemMetadata> searchItems(Instant changedAfter, java.util.function.Predicate<JsonNode> keep) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", Map.of("property", "object", "value", "page"));
        body.put("sort", Map.of("timestamp", "last_edited_time", "direction", "descending"));
        Iterator<JsonNode> pages = NotionApi.postPages(api, "search", body, this::noteStatus);
        Set<String> seen = new HashSet<>();
        return new Iterator<>() {
            private ItemMetadata next;
            private boolean done;

            @Override
            public boolean hasNext() {
                while (next == null && !done) {
                    if (!pages.hasNext()) {
                        done = true;
                        break;
                    }
                    JsonNode page = pages.next();
                    Instant edited = instant(page.path("last_edited_time").asText(null));
                    if (changedAfter != null && edited != null && edited.isBefore(changedAfter)) {
                        done = true; // newest first: everything after this is older still
                        break;
                    }
                    if (isTrashed(page) || !seen.add(page.path("id").asText(""))) continue;
                    if (keep != null && !keep.test(page)) continue;
                    next = pageItem(page);
                }
                return next != null;
            }

            @Override
            public ItemMetadata next() {
                if (!hasNext()) throw new NoSuchElementException();
                ItemMetadata out = next;
                next = null;
                return out;
            }
        };
    }

    // A search or a query Notion stopped at its result limit says so in request_status.
    private void noteStatus(JsonNode page) {
        if ("incomplete".equals(page.path("request_status").path("type").asText(""))) incomplete = true;
    }

    /**
     * The walk of the chosen pages and databases: each chosen page and every page under it, each
     * database's rows. Lazy, one request at a time - a block's children, a data source's rows - so a
     * failed request ends the listing where it stood.
     */
    private final class Walk implements Iterator<ItemMetadata> {
        // What is still to be read: a page or block whose children to list, or a data source to query.
        private record Task(String id, Kind kind, int pageDepth, int blockDepth) {}

        private final Deque<Task> tasks = new ArrayDeque<>();
        private final Deque<ItemMetadata> ready = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();
        private boolean started;

        @Override
        public boolean hasNext() {
            if (!started) {
                started = true;
                resolveRoots();
                for (Root root : roots) start(root);
            }
            while (ready.isEmpty()) {
                Task task = tasks.pollFirst();
                if (task == null) return false;
                if (task.kind() == Kind.DATA_SOURCE) rows(task.id());
                else children(task);
            }
            return true;
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            return ready.poll();
        }

        private void start(Root root) {
            switch (root.kind()) {
                case PAGE -> {
                    if (seen.add(root.id())) ready.add(pageItem(rootPages.get(root.id())));
                    tasks.add(new Task(root.id(), Kind.PAGE, 0, 0));
                }
                case DATABASE -> {
                    for (String source : dataSources(root.id())) tasks.add(new Task(source, Kind.DATA_SOURCE, 0, 0));
                }
                case DATA_SOURCE -> tasks.add(new Task(root.id(), Kind.DATA_SOURCE, 0, 0));
            }
        }

        // One page's or block's children: a child page is listed and walked in turn, a database's
        // rows are queued, and a block holding blocks (a toggle, a column) is read for pages inside.
        private void children(Task task) {
            Iterator<JsonNode> blocks = NotionApi.getPages(api, "blocks/" + task.id() + "/children");
            List<Task> found = new ArrayList<>();
            while (blocks.hasNext()) {
                JsonNode block = blocks.next();
                if (isTrashed(block)) continue;
                String id = block.path("id").asText("");
                String type = block.path("type").asText("");
                if (id.isEmpty()) continue;
                if (type.equals("child_page")) {
                    if (!seen.add(id)) continue;
                    ready.add(childPageItem(block));
                    if (task.pageDepth() + 1 < MAX_PAGE_DEPTH) found.add(new Task(id, Kind.PAGE, task.pageDepth() + 1, 0));
                } else if (type.equals("child_database")) {
                    if (!seen.add(id)) continue;
                    try {
                        for (String source : dataSources(id)) found.add(new Task(source, Kind.DATA_SOURCE, 0, 0));
                    } catch (ConnectorHttpException e) {
                        // A linked database the integration was not given is not this page's to read.
                        if (!e.isNotFound() && e.status() != 403) throw e;
                    }
                } else if (block.path("has_children").asBoolean(false) && !type.equals("table")
                        && task.blockDepth() + 1 < MAX_BLOCK_DEPTH) {
                    found.add(new Task(id, Kind.PAGE, task.pageDepth(), task.blockDepth() + 1));
                }
            }
            // Depth first, in the page's own order.
            for (int i = found.size() - 1; i >= 0; i--) tasks.addFirst(found.get(i));
        }

        // A data source's rows, every page of them.
        private void rows(String dataSource) {
            if (!seen.add(dataSource)) return;
            Iterator<JsonNode> rows = NotionApi.postPages(api, "data_sources/" + dataSource + "/query", Map.of(),
                    NotionSession.this::noteStatus);
            while (rows.hasNext()) {
                JsonNode row = rows.next();
                if (!"page".equals(row.path("object").asText("page")) || isTrashed(row)) continue;
                if (seen.add(row.path("id").asText(""))) ready.add(pageItem(row));
            }
        }
    }

    // -- the chosen pages and databases --------------------------------------------------------

    /**
     * What each chosen ID is: a page, a database (whose data sources are read), or a data source
     * named directly. One Notion cannot find is noted as unreadable - not shared with the
     * integration, or deleted.
     */
    void resolveRoots() {
        if (roots != null) return;
        List<Root> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : chosen.entrySet()) {
            String id = entry.getKey();
            Kind kind = kindOf(id);
            if (kind == null) {
                unreadable.add(entry.getValue());
                continue;
            }
            out.add(new Root(id, kind));
            rootIds.add(id);
            if (kind == Kind.DATABASE) rootIds.addAll(dataSources(id));
        }
        roots = out;
    }

    // A page, a database or a data source - asked in that order, a 404 or a 400 meaning "not this
    // kind". A chosen page is kept, for the walk to list.
    private Kind kindOf(String id) {
        JsonNode page = found("pages/" + id);
        if (page != null) {
            rootPages.put(id, page);
            return Kind.PAGE;
        }
        if (found("databases/" + id) != null) return Kind.DATABASE;
        if (found("data_sources/" + id) != null) return Kind.DATA_SOURCE;
        return null;
    }

    private JsonNode found(String path) {
        try {
            JsonNode found = api.getJson(path);
            return isTrashed(found) ? null : found;
        } catch (ConnectorHttpException e) {
            if (e.isNotFound() || e.status() == 400 || e.status() == 403) return null;
            throw e;
        }
    }

    private List<String> dataSources(String databaseId) {
        List<String> out = new ArrayList<>();
        JsonNode database = api.getJson("databases/" + databaseId);
        for (JsonNode source : database.path("data_sources")) {
            String id = source.path("id").asText("");
            if (id.isEmpty()) continue;
            out.add(id);
            String name = source.path("name").asText("");
            if (!name.isBlank()) dataSourceTitles.putIfAbsent(id, name);
        }
        return out;
    }

    /**
     * Whether a page is one of the chosen ones or under one, by its parents: a page's parent page, a
     * block's parent, a row's data source and that database's parent, up to the workspace. A page
     * under a database row is not - a walk does not read rows' bodies either, and a changes pass that
     * listed what a full pass leaves out would add and remove it day by day.
     */
    boolean isWithinRoots(JsonNode page) {
        resolveRoots();
        String id = page.path("id").asText("");
        if (rootIds.contains(id)) return true;
        return parentWithin(page.path("parent"), 0, true);
    }

    private boolean parentWithin(JsonNode parent, int depth, boolean fromPage) {
        if (depth > MAX_ANCESTRY) return false;
        String type = parent.path("type").asText("");
        switch (type) {
            case "page_id" -> {
                return pageWithin(parent.path("page_id").asText(""), depth + 1);
            }
            case "block_id" -> {
                return blockWithin(parent.path("block_id").asText(""), depth + 1);
            }
            case "data_source_id" -> {
                String source = parent.path("data_source_id").asText("");
                if (!fromPage) return false;
                if (rootIds.contains(source)) return true;
                return databaseWithin(parent.path("database_id").asText(""), depth + 1);
            }
            case "database_id" -> {
                if (!fromPage) return false;
                return databaseWithin(parent.path("database_id").asText(""), depth + 1);
            }
            default -> {
                return false; // the workspace
            }
        }
    }

    // A page above the one asked about: within if chosen, or under a chosen one - unless it is a
    // database row, whose body a walk does not read.
    private boolean pageWithin(String id, int depth) {
        if (id.isEmpty()) return false;
        if (rootIds.contains(id)) return true;
        Boolean known = within.get(id);
        if (known != null) return known;
        boolean result;
        try {
            JsonNode page = api.getJson("pages/" + id);
            result = parentWithin(page.path("parent"), depth, false);
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound() && e.status() != 403) throw e;
            result = false;
        }
        within.put(id, result);
        return result;
    }

    private boolean blockWithin(String id, int depth) {
        if (id.isEmpty()) return false;
        if (rootIds.contains(id)) return true;
        Boolean known = within.get(id);
        if (known != null) return known;
        boolean result;
        try {
            JsonNode block = api.getJson("blocks/" + id);
            result = parentWithin(block.path("parent"), depth, false);
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound() && e.status() != 403) throw e;
            result = false;
        }
        within.put(id, result);
        return result;
    }

    private boolean databaseWithin(String id, int depth) {
        if (id.isEmpty()) return false;
        if (rootIds.contains(id)) return true;
        Boolean known = within.get(id);
        if (known != null) return known;
        boolean result;
        try {
            JsonNode database = api.getJson("databases/" + id);
            result = parentWithin(database.path("parent"), depth, false);
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound() && e.status() != 403) throw e;
            result = false;
        }
        within.put(id, result);
        return result;
    }

    // -- items ---------------------------------------------------------------------------------

    private ItemMetadata pageItem(JsonNode page) {
        String id = page.path("id").asText("");
        ItemMetadata item = new ItemMetadata();
        item.setId(PAGE + id);
        item.setTitle(title(page));
        item.setSourceType(ConnectorType.NOTION);
        item.setLastModified(instant(page.path("last_edited_time").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", page.path("url").asText(null));
        putExtra(item, ItemMetadata.AUTHOR, userName(page.path("created_by")));
        return item;
    }

    // A child page as its parent's block names it: its title, its times, and the link Notion
    // resolves for any page by its ID.
    private ItemMetadata childPageItem(JsonNode block) {
        String id = block.path("id").asText("");
        ItemMetadata item = new ItemMetadata();
        item.setId(PAGE + id);
        String title = block.path("child_page").path("title").asText("").strip();
        item.setTitle(title.isEmpty() ? "Untitled" : title);
        item.setSourceType(ConnectorType.NOTION);
        item.setLastModified(instant(block.path("last_edited_time").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", "https://www.notion.so/" + id.replace("-", ""));
        putExtra(item, ItemMetadata.AUTHOR, userName(block.path("created_by")));
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    /**
     * One page, read again from Notion. With pages chosen, only a page under one of them: an id names
     * any page, and the agent's readSourceItem passes any id it is given - a page the integration can
     * read but the source left out is not this source's to read.
     */
    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String pageId = id.startsWith(PAGE) ? NotionConnectorPlugin.id(id.substring(PAGE.length())) : null;
        if (pageId == null || !id.substring(PAGE.length()).equalsIgnoreCase(pageId)) {
            throw new ConnectorException("That is not an item of a Notion source.");
        }
        try {
            JsonNode page = api.getJson("pages/" + pageId);
            if (isTrashed(page)) throw new ItemGoneException("That Notion page is in the trash.");
            if (!everything() && !isWithinRoots(page)) {
                throw new ConnectorException("That page is not under a page or database this Notion source reads.");
            }
            return markdown(render(page));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Notion no longer has that page, or no longer shares it with the integration.");
            throw e;
        }
    }

    String render(JsonNode page) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(page)).append("\n\n");
        JsonNode parent = page.path("parent");
        String source = parent.path("data_source_id").asText("");
        if (!source.isEmpty()) {
            String name = dataSourceTitle(source);
            if (!name.isEmpty()) md.append("- Database: ").append(name).append('\n');
        }
        JsonNode properties = page.path("properties");
        for (Map.Entry<String, JsonNode> p : properties.properties()) {
            JsonNode value = p.getValue();
            if ("title".equals(value.path("type").asText(""))) continue;
            String text = NotionText.property(value, this::userName);
            if (text.isBlank()) continue;
            if (text.contains("\n")) {
                md.append("- ").append(p.getKey()).append(":\n\n  ").append(text.strip().replace("\n", "\n  ")).append("\n\n");
            } else {
                md.append("- ").append(p.getKey()).append(": ").append(text.strip()).append('\n');
            }
        }
        String createdBy = userName(page.path("created_by"));
        md.append("- Created: ").append(page.path("created_time").asText(""));
        if (createdBy != null) md.append(" by ").append(createdBy);
        md.append('\n');
        String editedBy = userName(page.path("last_edited_by"));
        md.append("- Last edited: ").append(page.path("last_edited_time").asText(""));
        if (editedBy != null) md.append(" by ").append(editedBy);
        md.append('\n');
        String url = page.path("url").asText("");
        if (!url.isBlank()) md.append("- Link: ").append(url).append('\n');
        String body = body(page.path("id").asText("")).strip();
        if (!body.isEmpty()) md.append('\n').append(body).append('\n');
        return md.toString();
    }

    /**
     * The page's content: Notion's own markdown rendering, and - where it truncated a long page - the
     * blocks it left out, asked for on their own up to a bound. Where the endpoint does not render the
     * page at all, the blocks are rendered here instead.
     */
    private String body(String pageId) {
        JsonNode rendered;
        try {
            rendered = api.getJson("pages/" + pageId + "/markdown");
        } catch (ConnectorHttpException e) {
            if (e.status() != 400 && !e.isNotFound()) throw e;
            return NotionText.blocks(api, pageId, MAX_BLOCK_DEPTH, MAX_RENDERED_BLOCKS);
        }
        StringBuilder out = new StringBuilder(rendered.path("markdown").asText(""));
        int asked = 0;
        for (JsonNode unknown : rendered.path("unknown_block_ids")) {
            if (asked++ >= MAX_UNKNOWN_BLOCKS) break;
            try {
                String part = api.getJson("pages/" + unknown.asText("") + "/markdown").path("markdown").asText("");
                if (!part.isBlank()) out.append("\n\n").append(part.strip());
            } catch (ConnectorHttpException e) {
                if (e.status() != 400 && !e.isNotFound() && e.status() != 403) throw e;
            }
        }
        if (rendered.path("truncated").asBoolean(false)) {
            out.append("\n\n(This page is longer than Notion returns at once; the rest is not indexed.)");
        }
        return out.toString();
    }

    // -- names ---------------------------------------------------------------------------------

    /** A page's title: its title property, or "Untitled". */
    static String title(JsonNode page) {
        for (JsonNode p : page.path("properties")) {
            if ("title".equals(p.path("type").asText(""))) {
                String t = NotionText.plain(p.path("title")).strip();
                if (!t.isEmpty()) return t;
            }
        }
        return "Untitled";
    }

    // A person's (or bot's) name, from the user object a page states - partial, usually just an id -
    // or GET /users/{id}, once per person per session. A name that cannot be had (no user
    // capability) is no name, rather than a failed item.
    private String userName(JsonNode user) {
        if (user == null || user.isMissingNode() || user.isNull()) return null;
        String name = user.path("name").asText("");
        if (!name.isBlank()) return name.strip();
        String id = user.path("id").asText("");
        if (id.isEmpty()) return null;
        String known = userNames.computeIfAbsent(id, key -> {
            try {
                return api.getJson("users/" + key).path("name").asText("").strip();
            } catch (ConnectorHttpException e) {
                return "";
            }
        });
        return known.isEmpty() ? null : known;
    }

    private String dataSourceTitle(String id) {
        return dataSourceTitles.computeIfAbsent(id, key -> {
            try {
                return NotionText.plain(api.getJson("data_sources/" + key).path("title")).strip();
            } catch (ConnectorHttpException e) {
                return "";
            }
        });
    }

    // -- helpers -------------------------------------------------------------------------------

    // In the trash (in_trash since 2026-03-11; archived before it, kept for an older relay), or
    // archived out of a data source.
    static boolean isTrashed(JsonNode json) {
        return json.path("in_trash").asBoolean(false) || json.path("archived").asBoolean(false)
                || json.path("is_archived").asBoolean(false);
    }

    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
