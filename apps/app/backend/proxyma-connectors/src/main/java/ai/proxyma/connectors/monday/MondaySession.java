package ai.proxyma.connectors.monday;

import ai.proxyma.connectors.workitems.TaskMarkdown;
import ai.proxyma.connectors.workitems.WorkItems;
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

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * One monday.com source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the boards, the connection's address and credential.
 *
 * <p>Items are {@code item:<id>}, by monday.com's item ID, which stays the item's when it moves
 * between groups. Subitems are not items of their own (they live on a board of their own, which an
 * all-boards source skips); each is listed in its parent's document.
 *
 * <p>Pages are {@value #PAGE} items: monday.com prices a query by what it may return, and a page of
 * a hundred items with their newest update stays well inside one query's budget.
 */
final class MondaySession implements ConnectorSession {

    static final int PAGE = 100;
    static final int UPDATES_PAGE = 100;
    static final String LISTED = "id name updated_at url creator { name } updates(limit: 1) { created_at updated_at }";

    static final String BOARDS = """
            query Boards($page: Int!) {
              boards(limit: 100, page: $page, state: active) { id name state type }
            }""";

    static final String BOARDS_BY_ID = """
            query BoardsById($ids: [ID!]) {
              boards(ids: $ids, limit: 100, state: all) { id name state type }
            }""";

    static final String ITEMS = """
            query Items($board: [ID!], $limit: Int!) {
              boards(ids: $board) { items_page(limit: $limit) { cursor items { %s } } }
            }""".formatted(LISTED);

    static final String MORE_ITEMS = """
            query MoreItems($cursor: String!, $limit: Int!) {
              next_items_page(cursor: $cursor, limit: $limit) { cursor items { %s } }
            }""".formatted(LISTED);

    static final String UPDATES = "updates(limit: 100, page: $page) { id text_body created_at creator { name } "
            + "replies { text_body created_at creator { name } } }";

    static final String ITEM = """
            query Item($ids: [ID!], $page: Int!) {
              items(ids: $ids) {
                id name state created_at updated_at url board { id name } group { title } creator { name }
                column_values { id text type column { title }
                  ... on MirrorValue { display_value } ... on BoardRelationValue { display_value }
                  ... on DependencyValue { display_value } }
                subitems { id name column_values { text column { title } } }
                %s
              }
            }""".formatted(UPDATES);

    static final String ITEM_UPDATES = """
            query ItemUpdates($ids: [ID!], $page: Int!) {
              items(ids: $ids) { %s }
            }""".formatted(UPDATES);

    record Board(String id, String name, boolean active) {}

    private final ApiClient api;
    private final List<String> chosen;
    private List<Board> boards;

    MondaySession(SourceContext context) {
        this.chosen = MondayConnectorPlugin.boards(context.config());
        this.api = MondayApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            if (chosen.isEmpty()) MondayApi.query(api, "Me", MondayConnectionPlugin.ME, Map.of());
            else boards();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("monday.com did not accept the token. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    /** Every active item of the active boards, with the times that say whether it changed. */
    @Override
    public Listing list(ListRequest request) {
        List<Board> all;
        try {
            all = boards();
        } catch (ConnectorException e) {
            return Listings.failed(e.getMessage());
        }
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (Board board : all) {
            if (!board.active()) continue;
            parts.add(() -> Listings.mapped(items(board.id()), MondaySession::listed));
        }
        return Listings.concat(parts);
    }

    private Iterator<JsonNode> items(String boardId) {
        return Paginator.iterate(cursor -> {
            Map<String, Object> variables = new HashMap<>();
            variables.put("limit", PAGE);
            JsonNode page;
            if (cursor == null) {
                variables.put("board", List.of(boardId));
                page = MondayApi.query(api, "Items", ITEMS, variables).path("boards").path(0).path("items_page");
            } else {
                variables.put("cursor", cursor);
                page = MondayApi.query(api, "MoreItems", MORE_ITEMS, variables).path("next_items_page");
            }
            String next = page.path("cursor").asText("");
            return new Paginator.Page<>(Paginator.elements(page.path("items")), next.isBlank() ? null : next);
        });
    }

    private static ItemMetadata listed(JsonNode json) {
        String id = json.path("id").asText("");
        if (id.isEmpty()) return null;
        ItemMetadata item = new ItemMetadata();
        item.setId("item:" + id);
        item.setTitle(json.path("name").asText(id));
        item.setSourceType(ConnectorType.MONDAY);
        // An update (a comment) does not move the item's own updated_at, so the newest update's time
        // counts as a change too.
        JsonNode newest = json.path("updates").path(0);
        Instant updated = WorkItems.latest(WorkItems.instant(json.path("updated_at").asText(null)),
                WorkItems.latest(WorkItems.instant(newest.path("updated_at").asText(null)),
                        WorkItems.instant(newest.path("created_at").asText(null))));
        item.setLastModified(updated);
        item.setContentType(WorkItems.MARKDOWN);
        WorkItems.putExtra(item, "url", json.path("url").asText(null));
        WorkItems.putExtra(item, ItemMetadata.AUTHOR, json.path("creator").path("name").asText(null));
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String itemId = id.startsWith("item:") ? id.substring("item:".length()) : "";
        if (!itemId.matches("\\d+")) throw new ConnectorException("That is not an item of a monday.com source.");
        try {
            return WorkItems.markdown(render(itemId));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("monday.com no longer has " + id + ".");
            throw e;
        }
    }

    String render(String itemId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("ids", List.of(itemId));
        variables.put("page", 1);
        JsonNode item = MondayApi.query(api, "Item", ITEM, variables).path("items").path(0);
        String id = "item:" + itemId;
        if (item.isMissingNode() || item.isNull()) throw new ItemGoneException("monday.com no longer has " + id + ".");
        if (!"active".equals(item.path("state").asText("active"))) {
            throw new ItemGoneException("monday.com's item " + itemId + " is archived or deleted.");
        }
        String boardId = item.path("board").path("id").asText("");
        if (!chosen.isEmpty() && !chosen.contains(boardId)) {
            throw new ItemGoneException("monday.com's item " + itemId + " is no longer on a board this source reads.");
        }
        TaskMarkdown md = TaskMarkdown.titled(item.path("name").asText(""))
                .field("Board", item.path("board").path("name").asText(""))
                .field("Group", item.path("group").path("title").asText(""))
                .field("Created by", item.path("creator").path("name").asText(""));
        for (JsonNode column : item.path("column_values")) {
            md.field(column.path("column").path("title").asText(column.path("id").asText("Column")), columnText(column));
        }
        md.field("Created", item.path("created_at").asText(""))
                .field("Updated", item.path("updated_at").asText(""))
                .field("Link", item.path("url").asText(""));

        List<JsonNode> updates = new ArrayList<>(Paginator.elements(item.path("updates")));
        for (int page = 2; updates.size() == (page - 1) * UPDATES_PAGE && page < 1000; page++) {
            variables.put("page", page);
            List<JsonNode> more = Paginator.elements(MondayApi.query(api, "ItemUpdates", ITEM_UPDATES, variables)
                    .path("items").path(0).path("updates"));
            if (more.isEmpty()) break;
            updates.addAll(more);
        }
        updates.sort(Comparator.comparing(u -> u.path("created_at").asText("")));
        for (JsonNode update : updates) {
            md.entry("Updates", update.path("creator").path("name").asText(""), update.path("created_at").asText(""),
                    update.path("text_body").asText(""));
            List<JsonNode> replies = new ArrayList<>(Paginator.elements(update.path("replies")));
            replies.sort(Comparator.comparing(r -> r.path("created_at").asText("")));
            for (JsonNode reply : replies) {
                md.entry("Updates", reply.path("creator").path("name").asText("") + " (reply)",
                        reply.path("created_at").asText(""), reply.path("text_body").asText(""));
            }
        }
        for (JsonNode sub : item.path("subitems")) {
            List<String> values = new ArrayList<>();
            for (JsonNode column : sub.path("column_values")) {
                String text = column.path("text").asText("");
                if (!text.isBlank()) values.add(column.path("column").path("title").asText("") + ": " + text);
            }
            md.bullet("Subitems", sub.path("name").asText("") + (values.isEmpty() ? "" : " (" + String.join("; ", values) + ")"));
        }
        return md.toString();
    }

    // A column's value as the board shows it: its text, or for a mirror, a connected board or a
    // dependency - whose text the API leaves empty - its display value.
    private static String columnText(JsonNode column) {
        String text = column.path("text").asText("");
        return text.isBlank() ? column.path("display_value").asText("") : text;
    }

    // -- the boards ----------------------------------------------------------------------------

    /** The boards the source reads: each one it names, or every active board the connection can see. */
    List<Board> boards() {
        if (boards != null) return boards;
        List<Board> out = new ArrayList<>();
        if (chosen.isEmpty()) {
            for (int page = 1; page < 10_000; page++) {
                List<JsonNode> got = Paginator.elements(MondayApi.query(api, "Boards", BOARDS, Map.of("page", page)).path("boards"));
                for (JsonNode b : got) {
                    String type = b.path("type").asText("board");
                    if (type.isEmpty() || "board".equals(type)) out.add(board(b));
                }
                if (got.size() < 100) break;
            }
            return boards = out;
        }
        JsonNode found = MondayApi.query(api, "BoardsById", BOARDS_BY_ID, Map.of("ids", chosen)).path("boards");
        Set<String> missing = new TreeSet<>(chosen);
        for (JsonNode b : found) {
            missing.remove(b.path("id").asText());
            out.add(board(b));
        }
        if (!missing.isEmpty()) {
            throw new ConnectorException("monday.com has no board " + String.join(", ", missing)
                    + ", or the connection cannot see it.");
        }
        return boards = out;
    }

    private static Board board(JsonNode b) {
        String state = b.path("state").asText("active");
        return new Board(b.path("id").asText(), b.path("name").asText(""), "active".equals(state));
    }
}
