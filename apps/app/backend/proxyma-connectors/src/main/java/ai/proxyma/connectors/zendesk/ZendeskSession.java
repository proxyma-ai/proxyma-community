package ai.proxyma.connectors.zendesk;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConfigJson;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Zendesk source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - what it reads, the connection's account and credential - and nothing it
 * holds is seen by any other source.
 *
 * <p>Items, by id:
 * <ul>
 *   <li>{@code ticket:<id>} - listed from the cursor-based incremental ticket export
 *       ({@code /incremental/tickets/cursor}), rendered on fetch as one markdown document: the
 *       ticket's fields and its comment thread, internal notes only when the source reads them;</li>
 *   <li>{@code article:<locale>:<id>} - a published Help Center article in one language, listed per
 *       language (and section) on a full pass and from the incremental article export on a CHANGES
 *       pass, rendered on fetch from its HTML body.</li>
 * </ul>
 *
 * <p>The stored cursor is a small JSON object: the ticket export's {@code after_cursor} under
 * {@code tickets}, the article export's last {@code end_time} under {@code articles}.
 */
final class ZendeskSession implements ConnectorSession {

    // Clock skew between this host and Zendesk, where a pass has no cursor to resume from and asks
    // by time: from a minute before the last complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int EXPORT_PAGE_SIZE = 1000;
    static final int PAGE_SIZE = 100;
    static final String MARKDOWN = "text/markdown";
    static final String TICKETS_CURSOR = "tickets";
    static final String ARTICLES_CURSOR = "articles";

    private final SourceContext context;
    private final boolean tickets;
    private final boolean internalNotes;
    private final boolean articles;
    private final List<String> chosenLocales;
    private final Set<String> sections;
    private ApiClient api;
    private ApiClient exports;
    private String account;
    private List<String> locales;
    // What this pass will hand back as its cursor, learned as the listing runs.
    private String ticketCursor;
    private String articleCursor;

    ZendeskSession(SourceContext context) {
        this.context = context;
        this.tickets = context.flag(ZendeskConnectorPlugin.TICKETS, true);
        this.internalNotes = context.flag(ZendeskConnectorPlugin.INTERNAL_NOTES, false);
        this.articles = context.flag(ZendeskConnectorPlugin.ARTICLES, false);
        this.chosenLocales = ZendeskConnectorPlugin.locales(context.config());
        this.sections = new HashSet<>(ZendeskConnectorPlugin.sections(context.config()));
    }

    // Built on first use, so a connection with no subdomain is a failure a pass reports rather than a
    // session that cannot be opened.
    private ApiClient api() {
        if (api == null) api = ZendeskApi.client(context.http(), context.connection());
        return api;
    }

    private ApiClient exports() {
        if (exports == null) exports = ZendeskApi.exportClient(context.http(), context.connection());
        return exports;
    }

    private String account() {
        if (account == null) account = ZendeskApi.accountUrl(context.connection());
        return account;
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (!tickets && !articles) return AccessCheck.failed("Choose tickets, Help Center articles or both.");
        try {
            if (tickets) api().getJson("tickets", Map.of("page[size]", 1));
            if (articles) {
                List<String> available = helpCenterLocales();
                for (String locale : locales()) {
                    if (!available.contains(locale)) {
                        return AccessCheck.failed("The Help Center has no articles in " + locale + ". It has "
                                + String.join(", ", available) + ".");
                    }
                }
                for (String section : sections) {
                    try {
                        api().getJson("help_center/sections/" + ApiClient.segment(section));
                    } catch (ConnectorHttpException e) {
                        if (e.isNotFound()) return AccessCheck.failed("The Help Center has no section " + section + ".");
                        throw e;
                    }
                }
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Zendesk did not accept the connection's credential. "
                        + "Replace it in Settings > Connections.");
            }
            if (e.status() == 403) {
                return AccessCheck.failed("Zendesk refused the connection's agent. Tickets and the Help Center's "
                        + "articles are read as an agent or an administrator.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        if (!tickets && !articles) return Listings.failed("Choose tickets, Help Center articles or both.");
        Map<String, String> stored = ConfigJson.parse(request.cursor());
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (tickets) parts.add(() -> ticketItems(request, stored.get(TICKETS_CURSOR)));
        if (articles) {
            parts.add(() -> request.isFull() ? allArticles() : changedArticles(request, stored.get(ARTICLES_CURSOR)));
        }
        return Listings.withCursor(Listings.concat(parts), this::nextCursor);
    }

    private String nextCursor() {
        Map<String, String> next = new LinkedHashMap<>();
        if (ticketCursor != null) next.put(TICKETS_CURSOR, ticketCursor);
        if (articleCursor != null) next.put(ARTICLES_CURSOR, articleCursor);
        return next.isEmpty() ? null : ConfigJson.write(next);
    }

    // Tickets from the cursor-based export, oldest change first, a thousand a page. A full pass reads
    // it from the beginning and leaves deleted tickets out; a CHANGES pass resumes from the stored
    // cursor (else from just before the last pass) and lists deleted ones too, so their fetch can
    // say they are gone. The last page's after_cursor is where the next pass resumes.
    private Iterator<ItemMetadata> ticketItems(ListRequest request, String storedCursor) {
        boolean full = request.isFull();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("per_page", EXPORT_PAGE_SIZE);
        first.put("include", "users");
        if (!full && storedCursor != null && !storedCursor.isBlank()) {
            first.put("cursor", storedCursor);
        } else {
            Instant from = full || request.since() == null ? Instant.EPOCH : request.since().minus(OVERLAP);
            first.put("start_time", Math.max(0, from.getEpochSecond()));
        }
        Set<String> seen = new HashSet<>();
        return Paginator.iterate(next -> {
            Map<String, Object> query = new LinkedHashMap<>();
            if (next == null) {
                query.putAll(first);
            } else {
                query.put("per_page", EXPORT_PAGE_SIZE);
                query.put("include", "users");
                query.put("cursor", next);
            }
            JsonNode page;
            try {
                page = exports().getJson("incremental/tickets/cursor", query);
            } catch (ConnectorHttpException e) {
                // A stored cursor Zendesk no longer takes: ask by time instead, from just before the
                // last pass, rather than failing every pass after it.
                if (next != null || !query.containsKey("cursor") || (e.status() != 400 && e.status() != 422)) throw e;
                query.remove("cursor");
                Instant from = request.since() == null ? Instant.EPOCH : request.since().minus(OVERLAP);
                query.put("start_time", Math.max(0, from.getEpochSecond()));
                page = exports().getJson("incremental/tickets/cursor", query);
            }
            String after = page.path("after_cursor").asText(null);
            if (after != null && !after.isBlank()) ticketCursor = after;
            Map<Long, String> users = users(page);
            List<ItemMetadata> items = new ArrayList<>();
            for (JsonNode ticket : Paginator.elements(page.path("tickets"))) {
                String id = ticket.path("id").asText("");
                if (id.isEmpty() || !seen.add(id)) continue;
                if (full && "deleted".equals(ticket.path("status").asText())) continue;
                items.add(ticketItem(ticket, users));
            }
            boolean more = !page.path("end_of_stream").asBoolean(true) && after != null && !after.isBlank();
            return new Paginator.Page<>(items, more ? after : null);
        });
    }

    private ItemMetadata ticketItem(JsonNode ticket, Map<Long, String> users) {
        long id = ticket.path("id").asLong();
        ItemMetadata item = new ItemMetadata();
        item.setId("ticket:" + id);
        String subject = ticket.path("subject").asText("");
        item.setTitle("#" + id + (subject.isBlank() ? "" : " " + subject));
        item.setSourceType(ConnectorType.ZENDESK);
        item.setLastModified(instant(ticket.path("updated_at").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", account() + "/agent/tickets/" + id);
        putExtra(item, ItemMetadata.AUTHOR, users.get(ticket.path("requester_id").asLong(-1)));
        return item;
    }

    // Every published article of the chosen languages - and sections - one page of a hundred at a time.
    private Iterator<ItemMetadata> allArticles() {
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String locale : locales()) {
            String base = "help_center/" + ApiClient.segment(locale);
            if (sections.isEmpty()) {
                parts.add(() -> articlePages(base + "/articles", locale));
            } else {
                for (String section : sections.stream().sorted().toList()) {
                    parts.add(() -> articlePages(base + "/sections/" + ApiClient.segment(section) + "/articles", locale));
                }
            }
        }
        return Listings.concat(parts);
    }

    private Iterator<ItemMetadata> articlePages(String path, String locale) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page[size]", PAGE_SIZE);
        query.put("include", "users");
        return Paginator.iterate(next -> {
            JsonNode page = next == null ? api().getJson(path, query) : api().getJson(next);
            Map<Long, String> users = users(page);
            List<ItemMetadata> items = new ArrayList<>();
            for (JsonNode article : Paginator.elements(page.path("articles"))) {
                if (article.path("draft").asBoolean(false)) continue;
                items.add(articleItem(article, locale, users));
            }
            boolean more = page.path("meta").path("has_more").asBoolean(false);
            String nextUrl = page.path("links").path("next").asText(null);
            return new Paginator.Page<>(items, more ? nextUrl : null);
        });
    }

    // Articles changed since the last pass, from the Help Center's incremental export - a thousand a
    // page, the next page starting at this one's end_time. One in another language or section is
    // not this source's; a draft is listed, so its fetch can say it is gone.
    private Iterator<ItemMetadata> changedArticles(ListRequest request, String storedEnd) {
        long start;
        try {
            start = storedEnd != null && !storedEnd.isBlank() ? Long.parseLong(storedEnd.strip())
                    : (request.since() == null ? Instant.EPOCH : request.since().minus(OVERLAP)).getEpochSecond();
        } catch (NumberFormatException e) {
            start = request.since() == null ? 0 : request.since().minus(OVERLAP).getEpochSecond();
        }
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("start_time", Math.max(0, start));
        first.put("include", "users");
        List<String> wanted = locales();
        return Paginator.iterate(next -> {
            JsonNode page = next == null ? exports().getJson("help_center/incremental/articles", first) : exports().getJson(next);
            if (page.hasNonNull("end_time")) articleCursor = page.path("end_time").asText();
            Map<Long, String> users = users(page);
            List<JsonNode> got = Paginator.elements(page.path("articles"));
            List<ItemMetadata> items = new ArrayList<>();
            for (JsonNode article : got) {
                String locale = article.path("locale").asText("").toLowerCase(Locale.ROOT);
                if (!wanted.contains(locale)) continue;
                if (!sections.isEmpty() && !sections.contains(article.path("section_id").asText())) continue;
                items.add(articleItem(article, locale, users));
            }
            String nextUrl = page.path("next_page").asText(null);
            boolean more = got.size() >= EXPORT_PAGE_SIZE && nextUrl != null && !nextUrl.isBlank();
            return new Paginator.Page<>(items, more ? nextUrl : null);
        });
    }

    private ItemMetadata articleItem(JsonNode article, String locale, Map<Long, String> users) {
        ItemMetadata item = new ItemMetadata();
        item.setId("article:" + locale + ":" + article.path("id").asText());
        item.setTitle(article.path("title").asText(""));
        item.setSourceType(ConnectorType.ZENDESK);
        item.setLastModified(instant(article.path("updated_at").asText(null)));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", article.path("html_url").asText(null));
        putExtra(item, ItemMetadata.AUTHOR, users.get(article.path("author_id").asLong(-1)));
        return item;
    }

    // The languages articles are read in: those chosen, else the Help Center's default one.
    private List<String> locales() {
        if (locales == null) {
            if (!chosenLocales.isEmpty()) {
                locales = chosenLocales;
            } else {
                String fallback = api().getJson("help_center/locales").path("default_locale").asText("");
                if (fallback.isBlank()) throw new ConnectorException("Zendesk did not say which language its Help Center is in.");
                locales = List.of(fallback.toLowerCase(Locale.ROOT));
            }
        }
        return locales;
    }

    private List<String> helpCenterLocales() {
        List<String> out = new ArrayList<>();
        for (JsonNode l : Paginator.elements(api().getJson("help_center/locales").path("locales"))) {
            out.add(l.asText().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        try {
            if (id.startsWith("ticket:")) return markdown(renderTicket(number(id.substring("ticket:".length()))));
            if (id.startsWith("article:")) {
                String rest = id.substring("article:".length());
                int colon = rest.indexOf(':');
                if (colon > 0) return markdown(renderArticle(rest.substring(0, colon), number(rest.substring(colon + 1))));
            }
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Zendesk no longer has " + id + ".");
            throw e;
        }
        throw new ConnectorException("That is not an item of a Zendesk source.");
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConnectorException("That is not an item of a Zendesk source.");
        }
    }

    String renderTicket(long id) {
        JsonNode answer = api().getJson("tickets/" + id, Map.of("include", "users,groups,organizations"));
        JsonNode ticket = answer.path("ticket");
        if ("deleted".equals(ticket.path("status").asText())) {
            throw new ItemGoneException("Zendesk has deleted ticket " + id + ".");
        }
        Map<Long, String> users = users(answer);
        StringBuilder md = new StringBuilder();
        String subject = ticket.path("subject").asText("");
        md.append("# #").append(id).append(subject.isBlank() ? "" : " " + subject).append("\n\n");
        md.append("- Type: Ticket").append(ticket.hasNonNull("type") ? " (" + ticket.path("type").asText() + ")" : "").append('\n');
        md.append("- Status: ").append(ticket.path("status").asText("")).append('\n');
        if (ticket.hasNonNull("priority")) md.append("- Priority: ").append(ticket.path("priority").asText()).append('\n');
        md.append("- Requester: ").append(person(users, ticket.path("requester_id"))).append('\n');
        if (ticket.hasNonNull("assignee_id")) md.append("- Assignee: ").append(person(users, ticket.path("assignee_id"))).append('\n');
        String group = named(answer.path("groups"), ticket.path("group_id"));
        if (group != null) md.append("- Group: ").append(group).append('\n');
        String organization = named(answer.path("organizations"), ticket.path("organization_id"));
        if (organization != null) md.append("- Organization: ").append(organization).append('\n');
        List<String> tags = new ArrayList<>();
        ticket.path("tags").forEach(t -> tags.add(t.asText()));
        if (!tags.isEmpty()) md.append("- Tags: ").append(String.join(", ", tags)).append('\n');
        md.append("- Created: ").append(ticket.path("created_at").asText("")).append('\n');
        md.append("- Updated: ").append(ticket.path("updated_at").asText("")).append('\n');
        md.append("- Link: ").append(account()).append("/agent/tickets/").append(id).append('\n');
        comments(md, id);
        return md.toString();
    }

    // The ticket's conversation, oldest first, a hundred a page. The first comment is the ticket's
    // description. An internal note is read only where the source reads them, and says that it is one.
    private void comments(StringBuilder md, long id) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page[size]", PAGE_SIZE);
        query.put("include", "users");
        Map<Long, String> users = new HashMap<>();
        Iterator<JsonNode> all = Paginator.iterate(next -> {
            JsonNode page = next == null ? api().getJson("tickets/" + id + "/comments", query) : api().getJson(next);
            users.putAll(users(page));
            boolean more = page.path("meta").path("has_more").asBoolean(false);
            String nextUrl = page.path("links").path("next").asText(null);
            return new Paginator.Page<>(Paginator.elements(page.path("comments")), more ? nextUrl : null);
        });
        boolean any = false;
        while (all.hasNext()) {
            JsonNode c = all.next();
            boolean internal = !c.path("public").asBoolean(true);
            if (internal && !internalNotes) continue;
            if (!any) md.append("\n## Conversation\n");
            any = true;
            md.append("\n**").append(person(users, c.path("author_id"))).append("**");
            if (internal) md.append(" (internal note)");
            md.append(", ").append(c.path("created_at").asText("")).append(":\n\n");
            String body = c.path("plain_body").asText("");
            if (body.isBlank()) body = c.path("body").asText("");
            md.append(body.strip()).append('\n');
        }
    }

    String renderArticle(String locale, long id) {
        JsonNode answer = api().getJson("help_center/" + ApiClient.segment(locale) + "/articles/" + id,
                Map.of("include", "users,sections"));
        JsonNode article = answer.path("article");
        if (article.path("draft").asBoolean(false)) {
            throw new ItemGoneException("Zendesk article " + id + " is a draft now.");
        }
        if (!sections.isEmpty() && !sections.contains(article.path("section_id").asText())) {
            throw new ItemGoneException("Zendesk article " + id + " is no longer in the chosen sections.");
        }
        Map<Long, String> users = users(answer);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(article.path("title").asText("")).append("\n\n");
        md.append("- Type: Help Center article\n");
        String section = named(answer.path("sections"), article.path("section_id"));
        if (section != null) md.append("- Section: ").append(section).append('\n');
        md.append("- Author: ").append(person(users, article.path("author_id"))).append('\n');
        md.append("- Language: ").append(article.path("locale").asText(locale)).append('\n');
        List<String> labels = new ArrayList<>();
        article.path("label_names").forEach(l -> labels.add(l.asText()));
        if (!labels.isEmpty()) md.append("- Labels: ").append(String.join(", ", labels)).append('\n');
        md.append("- Updated: ").append(article.path("edited_at").asText(article.path("updated_at").asText(""))).append('\n');
        if (article.hasNonNull("html_url")) md.append("- Link: ").append(article.path("html_url").asText()).append('\n');
        String body = ZendeskHtml.toMarkdown(article.path("body").asText(""));
        md.append('\n').append(body.isBlank() ? "(no text)" : body).append('\n');
        return md.toString();
    }

    // -- helpers -------------------------------------------------------------------------------

    // The users a page sideloaded, by id - the names a ticket, a comment or an article is shown with.
    private static Map<Long, String> users(JsonNode page) {
        Map<Long, String> out = new HashMap<>();
        for (JsonNode u : Paginator.elements(page.path("users"))) {
            String name = u.path("name").asText("");
            if (name.isBlank()) name = u.path("email").asText("");
            if (!name.isBlank()) out.put(u.path("id").asLong(), name);
        }
        return out;
    }

    private static String person(Map<Long, String> users, JsonNode id) {
        if (id == null || id.isNull() || id.isMissingNode()) return "(nobody)";
        String name = users.get(id.asLong());
        return name != null ? name : "User " + id.asText();
    }

    // The name of a sideloaded group, organization or section by its id - null when there is none.
    private static String named(JsonNode sideload, JsonNode id) {
        if (id == null || id.isNull() || id.isMissingNode()) return null;
        for (JsonNode n : Paginator.elements(sideload)) {
            if (n.path("id").asLong() == id.asLong()) {
                String name = n.path("name").asText("");
                return name.isBlank() ? null : name;
            }
        }
        return null;
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
