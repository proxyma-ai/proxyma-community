package ai.proxyma.connectors.freshdesk;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Freshdesk source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - what it reads, the connection's account and API key - and nothing it holds
 * is seen by any other source.
 *
 * <p>Items, by id:
 * <ul>
 *   <li>{@code ticket:<id>} - listed from {@code /tickets} by {@code updated_since}, oldest change
 *       first; rendered on fetch as one markdown document with its description and its
 *       conversation ({@code /tickets/<id>/conversations});</li>
 *   <li>{@code article:<id>} - a published solution article, listed category by category, folder by
 *       folder (subfolders included), rendered on fetch from its text.</li>
 * </ul>
 */
final class FreshdeskSession implements ConnectorSession {

    // Clock skew between this host and Freshdesk: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int PER_PAGE = 100;
    // Freshdesk answers at most 300 pages of one ticket query; past that the window moves on.
    static final int MAX_PAGE = 300;
    static final int MAX_FOLDER_DEPTH = 10;
    static final String MARKDOWN = "text/markdown";
    static final String EPOCH = "1970-01-01T00:00:00Z";

    private static final Map<Integer, String> STATUSES = Map.of(2, "Open", 3, "Pending", 4, "Resolved", 5, "Closed",
            6, "Waiting on customer", 7, "Waiting on third party");
    private static final Map<Integer, String> PRIORITIES = Map.of(1, "Low", 2, "Medium", 3, "High", 4, "Urgent");

    private final SourceContext context;
    private final boolean tickets;
    private final boolean privateNotes;
    private final boolean articles;
    private final List<String> categories;
    private ApiClient api;
    private String account;
    private final Map<Long, String> people = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    // The page past which a ticket query moves its window on - Freshdesk's own ceiling; lowered by tests.
    int maxPage = MAX_PAGE;

    FreshdeskSession(SourceContext context) {
        this.context = context;
        this.tickets = context.flag(FreshdeskConnectorPlugin.TICKETS, true);
        this.privateNotes = context.flag(FreshdeskConnectorPlugin.PRIVATE_NOTES, false);
        this.articles = context.flag(FreshdeskConnectorPlugin.ARTICLES, false);
        this.categories = FreshdeskConnectorPlugin.categories(context.config());
    }

    // Built on first use, so a connection with no domain is a failure a pass reports rather than a
    // session that cannot be opened.
    private ApiClient api() {
        if (api == null) api = FreshdeskApi.client(context.http(), context.connection());
        return api;
    }

    private String account() {
        if (account == null) account = FreshdeskApi.accountUrl(context.connection());
        return account;
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (!tickets && !articles) return AccessCheck.failed("Choose tickets, knowledge base articles or both.");
        try {
            if (tickets) api().getJson("tickets", Map.of("per_page", 1));
            if (articles) {
                for (String category : categories) {
                    try {
                        api().getJson("solutions/categories/" + ApiClient.segment(category));
                    } catch (ConnectorHttpException e) {
                        if (e.isNotFound()) return AccessCheck.failed("The knowledge base has no category " + category + ".");
                        throw e;
                    }
                }
                if (categories.isEmpty()) api().getJson("solutions/categories");
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return AccessCheck.failed("Freshdesk did not accept the API key. Replace it in Settings > Connections.");
            if (e.status() == 403) {
                return AccessCheck.failed("Freshdesk refused the API key's agent. Tickets and articles are read as an agent.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        if (!tickets && !articles) return Listings.failed("Choose tickets, knowledge base articles or both.");
        Instant since = request.isFull() || request.since() == null ? null : request.since().minus(OVERLAP);
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (tickets) parts.add(() -> ticketItems(since));
        if (articles) parts.add(() -> articleItems(since));
        return Listings.concat(parts);
    }

    // Tickets updated since `since` (every ticket on a full pass), oldest change first, a hundred a
    // page. Without updated_since Freshdesk lists only the last 30 days' tickets, so a full pass asks
    // from 1970. A query answers 300 pages at most: at the last one the window moves on to the last
    // change it saw and starts again at page 1, a ticket seen twice listed once.
    private Iterator<ItemMetadata> ticketItems(Instant since) {
        Set<Long> seen = new HashSet<>();
        String first = "1@" + (since == null ? EPOCH : since.toString());
        return Paginator.iterate(next -> {
            String token = next == null ? first : next;
            int at = token.indexOf('@');
            int page = Integer.parseInt(token.substring(0, at));
            String from = token.substring(at + 1);
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("updated_since", from);
            query.put("order_by", "updated_at");
            query.put("order_type", "asc");
            query.put("include", "requester");
            query.put("per_page", PER_PAGE);
            query.put("page", page);
            ApiResponse response = api().requireOk(api().get("tickets", query));
            List<JsonNode> got = Paginator.elements(response.json());
            List<ItemMetadata> items = new ArrayList<>();
            String last = null;
            for (JsonNode ticket : got) {
                last = ticket.path("updated_at").asText(last);
                if (!seen.add(ticket.path("id").asLong())) continue;
                if (ticket.path("deleted").asBoolean(false) || ticket.path("spam").asBoolean(false)) continue;
                items.add(ticketItem(ticket));
            }
            boolean more = response.nextLink() != null || got.size() >= PER_PAGE;
            if (!more) return new Paginator.Page<>(items, null);
            if (page < maxPage) return new Paginator.Page<>(items, (page + 1) + "@" + from);
            // The window's last page: on from the last change seen. Never the same window twice.
            return new Paginator.Page<>(items, last == null || last.equals(from) ? null : "1@" + last);
        });
    }

    private ItemMetadata ticketItem(JsonNode ticket) {
        long id = ticket.path("id").asLong();
        ItemMetadata item = new ItemMetadata();
        item.setId("ticket:" + id);
        String subject = ticket.path("subject").asText("");
        item.setTitle("#" + id + (subject.isBlank() ? "" : " " + subject));
        item.setSourceType(ConnectorType.FRESHDESK);
        item.setLastModified(instant(ticket.path("updated_at").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", account() + "/a/tickets/" + id);
        putExtra(item, ItemMetadata.AUTHOR, ticket.path("requester").path("name").asText(null));
        return item;
    }

    // Published articles of the chosen categories - every category when none is chosen - folder by
    // folder, subfolders included. On a CHANGES pass only those updated since the last pass, drafts
    // among them, so a fetch can say an article that went back to draft is gone.
    private Iterator<ItemMetadata> articleItems(Instant since) {
        List<String> chosen = categories;
        if (chosen.isEmpty()) {
            chosen = new ArrayList<>();
            for (JsonNode c : Paginator.elements(api().getJson("solutions/categories"))) chosen.add(c.path("id").asText());
        }
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String category : chosen) {
            parts.add(() -> {
                List<Supplier<Iterator<ItemMetadata>>> folders = new ArrayList<>();
                for (JsonNode folder : Paginator.elements(api().getJson("solutions/categories/" + ApiClient.segment(category) + "/folders"))) {
                    addFolder(folders, folder.path("id").asText(), since, 0);
                }
                return Listings.concat(folders);
            });
        }
        return Listings.concat(parts);
    }

    private void addFolder(List<Supplier<Iterator<ItemMetadata>>> parts, String folder, Instant since, int depth) {
        parts.add(() -> folderArticles(folder, since));
        if (depth >= MAX_FOLDER_DEPTH) return;
        parts.add(() -> {
            List<Supplier<Iterator<ItemMetadata>>> sub = new ArrayList<>();
            for (JsonNode child : subfolders(folder)) addFolder(sub, child.path("id").asText(), since, depth + 1);
            return Listings.concat(sub);
        });
    }

    private List<JsonNode> subfolders(String folder) {
        try {
            return Paginator.elements(api().getJson("solutions/folders/" + ApiClient.segment(folder) + "/subfolders"));
        } catch (ConnectorHttpException e) {
            // An account whose knowledge base has no subfolders answers that it has none to list.
            if (e.isNotFound()) return List.of();
            throw e;
        }
    }

    private Iterator<ItemMetadata> folderArticles(String folder, Instant since) {
        Iterator<JsonNode> all = Paginator.linkHeader(api(), "solutions/folders/" + ApiClient.segment(folder) + "/articles",
                Map.of("per_page", PER_PAGE), page -> page);
        return Listings.mapped(all, article -> {
            Instant updated = instant(article.path("updated_at").asText(null));
            boolean draft = article.path("status").asInt(2) != 2;
            if (since == null) return draft ? null : articleItem(article, updated);
            return updated != null && updated.isBefore(since) ? null : articleItem(article, updated);
        });
    }

    private ItemMetadata articleItem(JsonNode article, Instant updated) {
        long id = article.path("id").asLong();
        ItemMetadata item = new ItemMetadata();
        item.setId("article:" + id);
        item.setTitle(article.path("title").asText(""));
        item.setSourceType(ConnectorType.FRESHDESK);
        item.setLastModified(updated);
        item.setContentType(MARKDOWN);
        putExtra(item, "url", account() + "/support/solutions/articles/" + id);
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        try {
            if (id.startsWith("ticket:")) return markdown(renderTicket(number(id.substring("ticket:".length()))));
            if (id.startsWith("article:")) return markdown(renderArticle(number(id.substring("article:".length()))));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Freshdesk no longer has " + id + ".");
            throw e;
        }
        throw new ConnectorException("That is not an item of a Freshdesk source.");
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConnectorException("That is not an item of a Freshdesk source.");
        }
    }

    String renderTicket(long id) {
        JsonNode ticket = api().getJson("tickets/" + id, Map.of("include", "requester"));
        if (ticket.path("deleted").asBoolean(false)) throw new ItemGoneException("Freshdesk has deleted ticket " + id + ".");
        if (ticket.path("spam").asBoolean(false)) throw new ItemGoneException("Freshdesk has marked ticket " + id + " as spam.");
        StringBuilder md = new StringBuilder();
        String subject = ticket.path("subject").asText("");
        md.append("# #").append(id).append(subject.isBlank() ? "" : " " + subject).append("\n\n");
        md.append("- Type: Ticket").append(ticket.hasNonNull("type") ? " (" + ticket.path("type").asText() + ")" : "").append('\n');
        md.append("- Status: ").append(coded(STATUSES, ticket.path("status"))).append('\n');
        md.append("- Priority: ").append(coded(PRIORITIES, ticket.path("priority"))).append('\n');
        String requester = ticket.path("requester").path("name").asText("");
        long requesterId = ticket.path("requester_id").asLong(-1);
        if (!requester.isBlank() && requesterId >= 0) people.put(requesterId, requester);
        md.append("- Requester: ").append(requester.isBlank() ? person(ticket.path("requester_id")) : requester).append('\n');
        if (ticket.hasNonNull("responder_id")) md.append("- Agent: ").append(person(ticket.path("responder_id"))).append('\n');
        List<String> tags = new ArrayList<>();
        ticket.path("tags").forEach(t -> tags.add(t.asText()));
        if (!tags.isEmpty()) md.append("- Tags: ").append(String.join(", ", tags)).append('\n');
        md.append("- Created: ").append(ticket.path("created_at").asText("")).append('\n');
        md.append("- Updated: ").append(ticket.path("updated_at").asText("")).append('\n');
        md.append("- Link: ").append(account()).append("/a/tickets/").append(id).append('\n');
        String description = ticket.path("description_text").asText("").strip();
        md.append("\n## Description\n\n").append(description.isEmpty() ? "(no description)" : description).append('\n');
        conversation(md, id);
        return md.toString();
    }

    // Replies and notes, oldest first, a hundred a page. A private note is read only where the source
    // reads them, and says that it is one.
    private void conversation(StringBuilder md, long id) {
        Iterator<JsonNode> all = Paginator.linkHeader(api(), "tickets/" + id + "/conversations",
                Map.of("per_page", PER_PAGE), page -> page);
        boolean any = false;
        while (all.hasNext()) {
            JsonNode c = all.next();
            boolean isPrivate = c.path("private").asBoolean(false);
            if (isPrivate && !privateNotes) continue;
            if (!any) md.append("\n## Conversation\n");
            any = true;
            md.append("\n**").append(person(c.path("user_id"))).append("**");
            if (isPrivate) md.append(" (private note)");
            else if (c.path("source").asInt(-1) == 2) md.append(" (note)");
            md.append(", ").append(c.path("created_at").asText("")).append(":\n\n");
            md.append(c.path("body_text").asText("").strip()).append('\n');
        }
    }

    String renderArticle(long id) {
        JsonNode article = api().getJson("solutions/articles/" + id);
        if (article.path("status").asInt(2) != 2) throw new ItemGoneException("Freshdesk article " + id + " is a draft now.");
        String category = article.path("category_id").asText("");
        if (!categories.isEmpty() && !categories.contains(category)) {
            throw new ItemGoneException("Freshdesk article " + id + " is no longer in the chosen categories.");
        }
        StringBuilder md = new StringBuilder();
        md.append("# ").append(article.path("title").asText("")).append("\n\n");
        md.append("- Type: Knowledge base article\n");
        String categoryName = name("solutions/categories/", category);
        if (categoryName != null) md.append("- Category: ").append(categoryName).append('\n');
        String folderName = name("solutions/folders/", article.path("folder_id").asText(""));
        if (folderName != null) md.append("- Folder: ").append(folderName).append('\n');
        if (article.hasNonNull("agent_id")) md.append("- Author: ").append(person(article.path("agent_id"))).append('\n');
        List<String> tags = new ArrayList<>();
        article.path("tags").forEach(t -> tags.add(t.asText()));
        if (!tags.isEmpty()) md.append("- Tags: ").append(String.join(", ", tags)).append('\n');
        md.append("- Updated: ").append(article.path("updated_at").asText("")).append('\n');
        md.append("- Link: ").append(account()).append("/support/solutions/articles/").append(id).append('\n');
        String body = article.path("description_text").asText("").strip();
        md.append('\n').append(body.isEmpty() ? "(no text)" : body).append('\n');
        return md.toString();
    }

    // -- helpers -------------------------------------------------------------------------------

    // A person by id: an agent's name, else a contact's - asked once per session. "User <id>" when
    // Freshdesk knows neither.
    private String person(JsonNode idNode) {
        if (idNode == null || idNode.isNull() || idNode.isMissingNode()) return "(nobody)";
        long id = idNode.asLong();
        return people.computeIfAbsent(id, key -> {
            for (String[] lookup : new String[][]{{"agents/", "contact"}, {"contacts/", ""}}) {
                try {
                    JsonNode found = api().getJson(lookup[0] + key);
                    JsonNode who = lookup[1].isEmpty() ? found : found.path(lookup[1]);
                    String name = who.path("name").asText("");
                    if (!name.isBlank()) return name;
                } catch (ConnectorHttpException e) {
                    if (!e.isNotFound() && e.status() != 403) throw e;
                }
            }
            return "User " + key;
        });
    }

    // A category's or folder's name, asked once per session; null when Freshdesk does not answer.
    private String name(String path, String id) {
        if (id == null || id.isBlank()) return null;
        String key = path + id;
        if (names.containsKey(key)) return names.get(key);
        String name = null;
        try {
            name = api().getJson(path + ApiClient.segment(id)).path("name").asText(null);
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound()) throw e;
        }
        names.put(key, name == null || name.isBlank() ? null : name);
        return names.get(key);
    }

    private static String coded(Map<Integer, String> names, JsonNode code) {
        if (code == null || code.isNull() || code.isMissingNode()) return "(none)";
        return names.getOrDefault(code.asInt(), String.valueOf(code.asInt()));
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
