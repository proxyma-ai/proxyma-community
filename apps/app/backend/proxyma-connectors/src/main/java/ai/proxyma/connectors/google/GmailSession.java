package ai.proxyma.connectors.google;

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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Gmail source, opened for one pass or one batch of fetches. Everything is read as
 * {@code users/me} - the signed-in account, or the user a service account acts as.
 *
 * <p>Items are {@code thread:<threadId>}. A thread is in the source while one of its messages that
 * is not a draft, spam or trash carries a chosen label and its newest such message is inside the
 * window; the fetch asks that again, so a thread archived out of the inbox, deleted, or aged past
 * the window is found gone.
 *
 * <p>Listing a thread costs one small request of its own (its subject, senders and dates - Gmail's
 * thread list carries only ids), so a full pass of a large mailbox takes a while; a CHANGES pass
 * asks only about the threads its history names.
 */
final class GmailSession implements ConnectorSession {

    /** Labels whose messages are never read, whichever labels the source names. */
    static final Set<String> NEVER = Set.of("DRAFT", "SPAM", "TRASH");

    private final SourceContext context;
    private final ApiClient api;
    private final Set<String> labels;
    private final int days;
    // Typed label -> Gmail's label id, read once per session.
    private Map<String, String> labelIds;
    private String email;
    // The mailbox's history id to resume from next - the cursor.
    private String historyId;

    GmailSession(SourceContext context) {
        this.context = context;
        this.labels = GmailConnectorPlugin.labels(context.config());
        this.days = GmailConnectorPlugin.days(context.config());
        this.api = GoogleApi.client(context.http(), context.connection(), "Gmail");
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (labels.isEmpty()) return AccessCheck.failed("Choose at least one label to read.");
        for (String label : labels) {
            if (NEVER.contains(label.toUpperCase(Locale.ROOT))) {
                return AccessCheck.failed("Gmail drafts, spam and trash are never read. Choose another label.");
            }
        }
        try {
            labelIds();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            return AccessCheck.failed(GoogleApi.refusal(e, "Gmail API"));
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        String cursor = request.isFull() ? null : request.cursor();
        Supplier<Iterator<ItemMetadata>> pass = () -> cursor == null || cursor.isBlank() ? full() : changes(cursor.strip());
        return Listings.withCursor(Listings.concat(List.of(pass)), () -> historyId);
    }

    // Every thread under each chosen label with a message inside the window - the mailbox's history
    // id asked for FIRST, so a message arriving while the listing runs is in the next pass's history.
    private Iterator<ItemMetadata> full() {
        historyId = profile().path("historyId").asText(null);
        Map<String, String> ids = labelIds();
        long after = cutoff().getEpochSecond();
        Set<String> seen = new HashSet<>();
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String labelId : new LinkedHashSet<>(ids.values())) {
            parts.add(() -> {
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("labelIds", labelId);
                query.put("q", "after:" + after);
                query.put("maxResults", 500);
                query.put("includeSpamTrash", "false");
                Iterator<JsonNode> threads = Paginator.cursor(api, "gmail/v1/users/me/threads", query, "pageToken",
                        page -> page.path("threads"), page -> page.path("nextPageToken").asText(null));
                return Listings.mapped(threads, t -> {
                    String id = t.path("id").asText("");
                    return !id.isEmpty() && seen.add(id) ? threadItem(id, false) : null;
                });
            });
        }
        return Listings.concat(parts);
    }

    // The threads Gmail's history names since the stored history id. A history Gmail no longer keeps
    // answers 404, and the mailbox is listed in full instead.
    private Iterator<ItemMetadata> changes(String startHistoryId) {
        Set<String> threads = new LinkedHashSet<>();
        String pageToken = null;
        String latest = null;
        boolean first = true;
        do {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("startHistoryId", startHistoryId);
            query.put("maxResults", 500);
            if (pageToken != null) query.put("pageToken", pageToken);
            JsonNode page;
            try {
                page = api.getJson("gmail/v1/users/me/history", query);
            } catch (ConnectorHttpException e) {
                if (first && (e.status() == 404 || e.status() == 400)) return full();
                throw e;
            }
            first = false;
            for (JsonNode record : page.path("history")) {
                record.path("messages").forEach(m -> addThread(threads, m));
                for (String kind : List.of("messagesAdded", "messagesDeleted", "labelsAdded", "labelsRemoved")) {
                    record.path(kind).forEach(entry -> addThread(threads, entry.path("message")));
                }
            }
            if (page.hasNonNull("historyId")) latest = page.path("historyId").asText();
            String next = page.path("nextPageToken").asText("");
            pageToken = next.isBlank() || next.equals(pageToken) ? null : next;
        } while (pageToken != null);
        historyId = latest != null ? latest : startHistoryId;
        return Listings.mapped(threads.iterator(), id -> threadItem(id, true));
    }

    private static void addThread(Set<String> threads, JsonNode message) {
        String id = message.path("threadId").asText("");
        if (!id.isEmpty()) threads.add(id);
    }

    // A thread as an item, from its headers alone. A CHANGES pass lists a thread that is gone or
    // out of the source too, dated now, so the engine fetches it and finds it gone.
    private ItemMetadata threadItem(String threadId, boolean changed) {
        JsonNode thread;
        try {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("format", "metadata");
            query.put("metadataHeaders", List.of("Subject", "From"));
            query.put("fields", "id,historyId,messages(id,internalDate,labelIds,payload/headers)");
            thread = api.getJson("gmail/v1/users/me/threads/" + ApiClient.segment(threadId), query);
        } catch (ConnectorHttpException e) {
            if (!e.isNotFound()) throw e;
            return changed ? gone(threadId) : null;
        }
        List<JsonNode> kept = kept(thread);
        if (!inScope(kept)) return changed ? gone(threadId) : null;
        JsonNode last = kept.get(kept.size() - 1);
        ItemMetadata item = new ItemMetadata();
        item.setId("thread:" + threadId);
        item.setTitle(subject(kept));
        item.setSourceType(ConnectorType.GMAIL);
        item.setLastModified(changed ? context.clock().instant() : newest(kept));
        item.setContentType(GoogleApi.MARKDOWN);
        GoogleApi.putExtra(item, "url", link(threadId));
        GoogleApi.putExtra(item, ItemMetadata.AUTHOR, displayName(header(last, "From")));
        return item;
    }

    private ItemMetadata gone(String threadId) {
        ItemMetadata item = new ItemMetadata();
        item.setId("thread:" + threadId);
        item.setTitle(threadId);
        item.setSourceType(ConnectorType.GMAIL);
        item.setLastModified(context.clock().instant());
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("thread:") || !id.substring(7).matches("[A-Za-z0-9]+")) {
            throw new ConnectorException("That is not an item of a Gmail mailbox.");
        }
        String threadId = id.substring(7);
        JsonNode thread;
        try {
            thread = api.getJson("gmail/v1/users/me/threads/" + ApiClient.segment(threadId), Map.of("format", "full"));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Gmail no longer has the conversation " + threadId + ".");
            throw e;
        }
        List<JsonNode> kept = kept(thread);
        if (!inScope(kept)) {
            throw new ItemGoneException("The conversation " + threadId + " is no longer under this source's labels and dates.");
        }
        return GoogleApi.markdown(render(threadId, kept));
    }

    String render(String threadId, List<JsonNode> messages) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(subject(messages)).append("\n\n");
        if (email() != null) md.append("- Mailbox: ").append(email()).append('\n');
        Set<String> under = new LinkedHashSet<>();
        Map<String, String> ids = labelIds();
        for (JsonNode m : messages) {
            for (JsonNode l : m.path("labelIds")) {
                ids.forEach((typed, labelId) -> {
                    if (labelId.equals(l.asText())) under.add(typed);
                });
            }
        }
        if (!under.isEmpty()) md.append("- Labels: ").append(String.join(", ", under)).append('\n');
        Set<String> people = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            for (String h : List.of("From", "To", "Cc")) people.addAll(addresses(header(m, h)));
        }
        if (!people.isEmpty()) md.append("- Participants: ").append(String.join(", ", people)).append('\n');
        md.append("- Started: ").append(when(messages.get(0))).append('\n');
        md.append("- Last message: ").append(when(messages.get(messages.size() - 1))).append('\n');
        md.append("- Messages: ").append(messages.size()).append('\n');
        md.append("- Link: ").append(link(threadId)).append('\n');
        for (JsonNode m : messages) {
            md.append("\n## ").append(header(m, "From").isEmpty() ? "(unknown sender)" : header(m, "From"))
                    .append(", ").append(when(m)).append("\n\n");
            if (!header(m, "To").isEmpty()) md.append("To: ").append(header(m, "To")).append("  \n");
            if (!header(m, "Cc").isEmpty()) md.append("Cc: ").append(header(m, "Cc")).append("  \n");
            String subject = header(m, "Subject");
            if (!subject.isEmpty() && !subject.equals(subject(messages))) md.append("Subject: ").append(subject).append("  \n");
            String body = body(m);
            md.append('\n').append(body.isBlank() ? "(no text)" : body.strip()).append('\n');
            List<String> attachments = new ArrayList<>();
            attachments(m.path("payload"), attachments);
            if (!attachments.isEmpty()) md.append("\nAttachments: ").append(String.join(", ", attachments)).append('\n');
        }
        return md.toString();
    }

    // The message's text: its text/plain part, else its HTML part as text.
    private String body(JsonNode message) {
        JsonNode payload = message.path("payload");
        JsonNode plain = part(payload, "text/plain");
        if (plain != null) return decode(plain, message.path("id").asText(""));
        JsonNode html = part(payload, "text/html");
        if (html != null) return GoogleApi.htmlToText(decode(html, message.path("id").asText("")));
        return "";
    }

    // The first part of this type that is not an attachment, depth first.
    private static JsonNode part(JsonNode part, String type) {
        if (part == null || part.isMissingNode()) return null;
        String mime = part.path("mimeType").asText("").toLowerCase(Locale.ROOT);
        if (mime.startsWith(type) && part.path("filename").asText("").isEmpty()) return part;
        for (JsonNode child : part.path("parts")) {
            JsonNode found = part(child, type);
            if (found != null) return found;
        }
        return null;
    }

    // A part's body, in its own charset. A large body is not inline - it is fetched by its id.
    private String decode(JsonNode part, String messageId) {
        String data = part.path("body").path("data").asText("");
        String attachmentId = part.path("body").path("attachmentId").asText("");
        if (data.isEmpty() && !attachmentId.isEmpty() && !messageId.isEmpty()) {
            data = api.getJson("gmail/v1/users/me/messages/" + ApiClient.segment(messageId) + "/attachments/"
                    + ApiClient.segment(attachmentId)).path("data").asText("");
        }
        if (data.isEmpty()) return "";
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(data.replace('+', '-').replace('/', '_').strip());
        } catch (IllegalArgumentException e) {
            return "";
        }
        return new String(bytes, charsetOf(part));
    }

    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)");

    private static Charset charsetOf(JsonNode part) {
        for (JsonNode h : part.path("headers")) {
            if (!"content-type".equalsIgnoreCase(h.path("name").asText())) continue;
            Matcher m = CHARSET.matcher(h.path("value").asText(""));
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void attachments(JsonNode part, List<String> out) {
        String name = part.path("filename").asText("");
        if (!name.isBlank()) out.add(name.strip());
        for (JsonNode child : part.path("parts")) attachments(child, out);
    }

    // -- the thread's messages -----------------------------------------------------------------

    // The messages the source reads: none that is a draft, spam or trash.
    private static List<JsonNode> kept(JsonNode thread) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : thread.path("messages")) {
            boolean never = false;
            for (JsonNode l : m.path("labelIds")) never |= NEVER.contains(l.asText());
            if (!never) out.add(m);
        }
        out.sort((a, b) -> Long.compare(a.path("internalDate").asLong(0), b.path("internalDate").asLong(0)));
        return out;
    }

    private boolean inScope(List<JsonNode> kept) {
        if (kept.isEmpty()) return false;
        Set<String> wanted = new HashSet<>(labelIds().values());
        boolean labelled = false;
        for (JsonNode m : kept) {
            for (JsonNode l : m.path("labelIds")) labelled |= wanted.contains(l.asText());
        }
        Instant newest = newest(kept);
        return labelled && newest != null && !newest.isBefore(cutoff());
    }

    private static Instant newest(List<JsonNode> kept) {
        return GoogleApi.epochMillis(kept.get(kept.size() - 1).path("internalDate").asText(null));
    }

    private static String when(JsonNode message) {
        Instant at = GoogleApi.epochMillis(message.path("internalDate").asText(null));
        return at == null ? "" : at.toString();
    }

    private static String subject(List<JsonNode> messages) {
        for (JsonNode m : messages) {
            String s = header(m, "Subject");
            if (!s.isEmpty()) return s;
        }
        return "(no subject)";
    }

    static String header(JsonNode message, String name) {
        for (JsonNode h : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(h.path("name").asText())) return h.path("value").asText("").strip();
        }
        return "";
    }

    /** The addresses of a To, Cc or From header, split on the commas that are not inside quotes. */
    static List<String> addresses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null || header.isBlank()) return out;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : header.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) {
                if (!current.toString().isBlank()) out.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) out.add(current.toString().strip());
        return out;
    }

    /** "Ana Lee" from {@code "Ana Lee" <ana@example.com>}; the address where there is no name. */
    static String displayName(String from) {
        if (from == null || from.isBlank()) return null;
        int lt = from.indexOf('<');
        String name = lt > 0 ? from.substring(0, lt).strip() : "";
        name = name.replaceAll("^\"|\"$", "").strip();
        if (!name.isEmpty()) return name;
        return from.replaceAll("[<>]", "").strip();
    }

    // -- the mailbox ---------------------------------------------------------------------------

    private Instant cutoff() {
        return context.clock().instant().minus(Duration.ofDays(days));
    }

    private JsonNode profile() {
        JsonNode profile = api.getJson("gmail/v1/users/me/profile");
        email = profile.path("emailAddress").asText(null);
        return profile;
    }

    private String email() {
        if (email == null) {
            try {
                profile();
            } catch (ConnectorHttpException e) {
                return null;
            }
        }
        return email;
    }

    // Gmail's link to a thread; the account in it so the right mailbox opens where several are signed in.
    private String link(String threadId) {
        String account = email();
        return account == null || account.isBlank()
                ? "https://mail.google.com/mail/#all/" + threadId
                : "https://mail.google.com/mail/u/" + account + "/#all/" + threadId;
    }

    /**
     * The source's labels as Gmail's ids: a system label by its id (INBOX, SENT), one of the
     * person's own by its name or id, without case. A label Gmail does not have is refused with its
     * name rather than read as nothing.
     */
    private Map<String, String> labelIds() {
        if (labelIds != null) return labelIds;
        JsonNode all = api.getJson("gmail/v1/users/me/labels").path("labels");
        Map<String, String> out = new LinkedHashMap<>();
        for (String typed : labels) {
            if (NEVER.contains(typed.toUpperCase(Locale.ROOT))) continue;
            String found = null;
            for (JsonNode l : all) {
                if (typed.equalsIgnoreCase(l.path("id").asText()) || typed.equalsIgnoreCase(l.path("name").asText())) {
                    found = l.path("id").asText();
                    break;
                }
            }
            if (found == null) throw new ConnectorException("Gmail has no label called " + typed + ".");
            out.put(typed, found);
        }
        labelIds = out;
        return out;
    }
}
