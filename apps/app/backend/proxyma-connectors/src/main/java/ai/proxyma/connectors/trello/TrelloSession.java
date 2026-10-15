package ai.proxyma.connectors.trello;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One Trello source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the boards, the connection's address, API key and token.
 *
 * <p>Items are {@code card:<id>}; a card moved to another chosen board stays the same item. Each is
 * rendered on fetch as one markdown document with its comments.
 *
 * <p>Trello's nested lists are not paged: a request answers at most {@value #PAGE} cards or actions,
 * and the next ones are asked for {@code before} the oldest ID seen - a Trello ID starts with its
 * creation time, so the IDs of one board compare as their age.
 */
final class TrelloSession implements ConnectorSession {

    // Clock skew between this host and Trello: a CHANGES pass keeps cards active from a minute
    // before the last complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int PAGE = 1000;
    static final String LIST_FIELDS = "name,dateLastActivity,shortUrl,url,closed";
    static final String CARD_QUERY_FIELDS = "name,desc,due,dueComplete,start,closed,dateLastActivity,url,shortUrl,labels,idBoard";

    record Board(String id, String name, boolean closed) {}

    private final ApiClient api;
    private final List<String> chosen;
    private List<Board> boards;

    TrelloSession(SourceContext context) {
        this.chosen = TrelloConnectorPlugin.boards(context.config());
        this.api = TrelloApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            if (chosen.isEmpty()) api.getJson("members/me", Map.of("fields", "username"));
            else boards();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Trello did not accept the API key and token. Replace them in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    /**
     * The cards of the boards. A full pass lists the open cards of open boards; a CHANGES pass every
     * card - archived ones too - whose last activity is after the last complete pass.
     */
    @Override
    public Listing list(ListRequest request) {
        boolean changes = !request.isFull() && request.since() != null;
        Instant after = changes ? request.since().minus(OVERLAP) : null;
        List<Board> all;
        try {
            all = boards();
        } catch (ConnectorException e) {
            return Listings.failed(e.getMessage());
        }
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (Board board : all) {
            if (board.closed()) continue;
            parts.add(() -> Listings.mapped(cards(board.id(), changes ? "all" : "open"), card -> {
                Instant active = WorkItems.instant(card.path("dateLastActivity").asText(null));
                if (after != null && active != null && !active.isAfter(after)) return null;
                if (!changes && card.path("closed").asBoolean(false)) return null;
                ItemMetadata item = new ItemMetadata();
                item.setId("card:" + card.path("id").asText());
                item.setTitle(card.path("name").asText(""));
                item.setSourceType(ConnectorType.TRELLO);
                item.setLastModified(active);
                item.setContentType(WorkItems.MARKDOWN);
                WorkItems.putExtra(item, "url", card.path("shortUrl").asText(card.path("url").asText(null)));
                return item;
            }));
        }
        return Listings.concat(parts);
    }

    // One board's cards under a filter (open, all), a thousand at a time.
    private Iterator<JsonNode> cards(String boardId, String filter) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fields", LIST_FIELDS);
        return olderPages("boards/" + ApiClient.segment(boardId) + "/cards/" + filter, query);
    }

    // A nested Trello list asked for in pages: a full page means there may be more before its
    // oldest ID.
    private Iterator<JsonNode> olderPages(String path, Map<String, Object> query) {
        return Paginator.iterate(before -> {
            Map<String, Object> q = new LinkedHashMap<>(query);
            q.put("limit", PAGE);
            if (before != null) q.put("before", before);
            List<JsonNode> page = Paginator.elements(api.getJson(path, q));
            String oldest = page.stream().map(n -> n.path("id").asText("")).filter(id -> !id.isEmpty())
                    .min(Comparator.naturalOrder()).orElse(null);
            return new Paginator.Page<>(page, page.size() >= PAGE ? oldest : null);
        });
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String cardId = id.startsWith("card:") ? id.substring("card:".length()) : "";
        if (!cardId.matches("[A-Za-z0-9]+")) throw new ConnectorException("That is not an item of a Trello source.");
        try {
            return WorkItems.markdown(render(cardId));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Trello no longer has " + id + ".");
            throw e;
        }
    }

    String render(String cardId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fields", CARD_QUERY_FIELDS);
        query.put("list", "true");
        query.put("list_fields", "name");
        query.put("board", "true");
        query.put("board_fields", "name,closed,shortLink");
        query.put("members", "true");
        query.put("member_fields", "fullName,username");
        query.put("checklists", "all");
        query.put("checklist_fields", "name,pos");
        query.put("attachments", "true");
        query.put("attachment_fields", "name,url,date");
        JsonNode card = api.getJson("cards/" + ApiClient.segment(cardId), query);
        JsonNode board = card.path("board");
        if (card.path("closed").asBoolean(false) || board.path("closed").asBoolean(false)) {
            throw new ItemGoneException("Trello's card " + cardId + " is archived.");
        }
        if (!chosen.isEmpty() && !onChosenBoard(card.path("idBoard").asText(""), board.path("shortLink").asText(""))) {
            throw new ItemGoneException("Trello's card " + cardId + " is no longer on a board this source reads.");
        }

        TaskMarkdown md = TaskMarkdown.titled(card.path("name").asText(""))
                .field("Board", board.path("name").asText(""))
                .field("List", card.path("list").path("name").asText(""))
                .field("Labels", names(card.path("labels"), l -> l.path("name").asText("").isBlank()
                        ? l.path("color").asText("") : l.path("name").asText("")))
                .field("Members", names(card.path("members"), m -> m.path("fullName").asText(m.path("username").asText(""))))
                .field("Start", card.path("start").asText(""));
        String due = card.path("due").asText("");
        if (!due.isBlank()) md.field("Due", due + (card.path("dueComplete").asBoolean(false) ? " (complete)" : ""));
        md.field("Last activity", card.path("dateLastActivity").asText(""))
                .field("Link", card.path("shortUrl").asText(card.path("url").asText("")))
                .body("Description", card.path("desc").asText(""));

        List<JsonNode> checklists = sortedByPos(card.path("checklists"));
        for (JsonNode list : checklists) {
            String heading = "Checklist: " + list.path("name").asText("");
            for (JsonNode check : sortedByPos(list.path("checkItems"))) {
                boolean done = "complete".equals(check.path("state").asText());
                md.bullet(heading, (done ? "[x] " : "[ ] ") + check.path("name").asText(""));
            }
        }
        for (JsonNode attachment : card.path("attachments")) {
            md.bullet("Attachments", attachment.path("name").asText(attachment.path("url").asText("")));
        }
        comments(md, cardId);
        return md.toString();
    }

    // Every comment on the card, oldest first: Trello answers its actions newest first.
    private void comments(TaskMarkdown md, String cardId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("filter", "commentCard");
        query.put("fields", "data,date");
        query.put("memberCreator_fields", "fullName,username");
        List<JsonNode> all = new ArrayList<>();
        olderPages("cards/" + ApiClient.segment(cardId) + "/actions", query).forEachRemaining(all::add);
        all.sort(Comparator.comparing(a -> a.path("date").asText("")));
        for (JsonNode action : all) {
            JsonNode who = action.path("memberCreator");
            md.comment(who.path("fullName").asText(who.path("username").asText("")), action.path("date").asText(""),
                    action.path("data").path("text").asText(""));
        }
    }

    private boolean onChosenBoard(String boardId, String shortLink) {
        for (Board b : boards()) {
            if (b.id().equals(boardId)) return true;
        }
        return chosen.contains(shortLink);
    }

    private static List<String> names(JsonNode array, Function<JsonNode, String> name) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(name.apply(n)));
        return out;
    }

    private static List<JsonNode> sortedByPos(JsonNode array) {
        List<JsonNode> out = new ArrayList<>(Paginator.elements(array));
        out.sort(Comparator.comparingDouble(n -> n.path("pos").asDouble(0)));
        return out;
    }

    // -- the boards ----------------------------------------------------------------------------

    /** The boards the source reads: each one it names, or every open board of the member. */
    List<Board> boards() {
        if (boards != null) return boards;
        List<Board> out = new ArrayList<>();
        if (chosen.isEmpty()) {
            JsonNode all = api.getJson("members/me/boards", Map.of("filter", "open", "fields", "name,closed"));
            for (JsonNode b : all) out.add(board(b));
            return boards = out;
        }
        Set<String> missing = new TreeSet<>();
        for (String ref : chosen) {
            try {
                out.add(board(api.getJson("boards/" + ApiClient.segment(ref), Map.of("fields", "name,closed"))));
            } catch (ConnectorHttpException e) {
                if (!e.isNotFound() && e.status() != 400) throw e;
                missing.add(ref);
            }
        }
        if (!missing.isEmpty()) {
            throw new ConnectorException("Trello has no board " + String.join(", ", missing)
                    + ", or the connection cannot see it.");
        }
        return boards = out;
    }

    private static Board board(JsonNode b) {
        return new Board(b.path("id").asText(), b.path("name").asText(""), b.path("closed").asBoolean(false));
    }
}
