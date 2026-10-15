package ai.proxyma.connectors.slack;

import ai.proxyma.connectors.slack.SlackApi.SlackApiException;
import ai.proxyma.connectors.slack.SlackApi.Tier;
import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Slack source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the channels, the window, the connection's tokens - and every name it
 * looks up (a person, a channel, the workspace) is kept for this session alone.
 *
 * <p>Items, by id:
 * <ul>
 *   <li>{@code thread:<channel>:<ts>} - a message with replies, and the replies: listed from the
 *       channel's history (the first message carries the thread's {@code latest_reply}), fetched with
 *       {@code conversations.replies};</li>
 *   <li>{@code day:<channel>:<yyyy-mm-dd>} - the messages of one channel on one UTC day that started
 *       no thread, fetched with {@code conversations.history} bounded to that day.</li>
 * </ul>
 *
 * <p>Read with the bot token, or - for a channel the person is in and the app is not - with the
 * connection's user token, when it has one.
 */
final class SlackSession implements ConnectorSession {

    // Clock skew between this host and Slack: a changes pass counts as new anything from a minute
    // before the last complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // How far back a changes pass re-reads a channel's history, for replies to recent threads and
    // edits - neither moves a message in the history, so only re-reading finds them.
    static final Duration TRAILING = Duration.ofDays(7);
    // Slack's recommended page size; an internal app may ask for up to 999.
    static final int PAGE = 200;
    static final String MARKDOWN = "text/markdown";
    static final String THREAD = "thread:";
    static final String DAY = "day:";

    // Messages Slack writes about a channel rather than in it.
    static final Set<String> SYSTEM_SUBTYPES = Set.of("channel_join", "channel_leave", "channel_topic",
            "channel_purpose", "channel_name", "channel_archive", "channel_unarchive", "group_join", "group_leave",
            "group_topic", "group_purpose", "group_name", "group_archive", "group_unarchive", "pinned_item",
            "unpinned_item", "channel_convert_to_private", "channel_convert_to_public", "bot_add", "bot_remove",
            "reminder_add", "joiner_notification_for_inviter");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final Pattern ENTITY = Pattern.compile("<([^<>]+)>");

    /** A channel this source reads, and the token that reads it. */
    record Channel(String id, String name, boolean isPrivate, SlackApi reader) {}

    private final SourceContext context;
    private final SlackApi bot;
    private final SlackApi user;
    private final Map<String, String> userNames = new HashMap<>();
    private final Map<String, Channel> channelsById = new HashMap<>();
    private JsonNode workspace;
    private List<Channel> channels;
    private final List<String> unreadable = new ArrayList<>();

    SlackSession(SourceContext context) {
        this.context = context;
        this.bot = SlackApi.bot(context.http(), context.connection());
        this.user = SlackApi.user(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            resolveChannels();
            if (!unreadable.isEmpty()) {
                return AccessCheck.failed("The Slack app cannot read " + String.join(", ", unreadable)
                        + ". Invite it to each channel with /invite, or check the names.");
            }
            if (channels.isEmpty()) {
                return AccessCheck.failed("The Slack app is not in any public channel yet. Invite it to the channels "
                        + "to index with /invite.");
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException | ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Instant changedAfter = request.isFull() || request.since() == null ? null : request.since().minus(OVERLAP);
        Iterator<ItemMetadata> all = new Iterator<>() {
            private Iterator<Channel> remaining;
            private Iterator<ItemMetadata> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                if (remaining == null) {
                    resolveChannels();
                    workspace();
                    remaining = channels.iterator();
                }
                while (!current.hasNext()) {
                    if (!remaining.hasNext()) return false;
                    current = channelItems(remaining.next(), changedAfter);
                }
                return true;
            }

            @Override
            public ItemMetadata next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
        // A chosen channel the app cannot read is not a channel whose messages are gone: the pass is
        // partial, and removes nothing for it.
        return Listings.partialIf(Listings.of(all), () -> unreadable.isEmpty() ? null
                : "Slack did not let the app read " + String.join(", ", unreadable)
                        + ", so nothing of it was removed. Invite the app back, or leave the channel out.");
    }

    // The threads and days of one channel, newest first, as its history pages through.
    private Iterator<ItemMetadata> channelItems(Channel channel, Instant changedAfter) {
        Instant oldest = windowStart();
        if (changedAfter != null) {
            Instant trailing = changedAfter.minus(TRAILING);
            if (oldest == null || trailing.isAfter(oldest)) oldest = trailing;
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("channel", channel.id());
        query.put("limit", PAGE);
        if (oldest != null) query.put("oldest", ts(oldest));
        Iterator<JsonNode> messages = channel.reader().pages(Tier.TIER_3, "conversations.history", query, "messages");
        return new ChannelItems(channel, messages, changedAfter);
    }

    /**
     * Turns a channel's history - newest first - into items: a thread as soon as its first message
     * is read, a day once the history has moved past it. A changes pass keeps only what changed.
     */
    private final class ChannelItems implements Iterator<ItemMetadata> {
        private final Channel channel;
        private final Iterator<JsonNode> messages;
        private final Instant changedAfter;
        private final Deque<ItemMetadata> ready = new ArrayDeque<>();
        private String day;
        private final List<JsonNode> dayMessages = new ArrayList<>();

        ChannelItems(Channel channel, Iterator<JsonNode> messages, Instant changedAfter) {
            this.channel = channel;
            this.messages = messages;
            this.changedAfter = changedAfter;
        }

        @Override
        public boolean hasNext() {
            while (ready.isEmpty()) {
                if (messages.hasNext()) {
                    take(messages.next());
                } else if (day != null) {
                    closeDay();
                } else {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            return ready.poll();
        }

        private void take(JsonNode m) {
            if (!isContent(m)) return;
            if (isThreadStart(m)) {
                offer(threadItem(channel, m));
                return;
            }
            if (!isStandalone(m)) return; // a reply also sent to the channel belongs to its thread
            String key = dayOf(m.path("ts").asText(""));
            if (key == null) return;
            if (!key.equals(day)) {
                closeDay();
                day = key;
            }
            dayMessages.add(m);
        }

        private void closeDay() {
            if (day != null && !dayMessages.isEmpty()) offer(dayItem(channel, day, dayMessages));
            day = null;
            dayMessages.clear();
        }

        private void offer(ItemMetadata item) {
            if (changedAfter == null || item.getLastModified() == null || item.getLastModified().isAfter(changedAfter)) {
                ready.add(item);
            }
        }
    }

    private ItemMetadata threadItem(Channel channel, JsonNode root) {
        String ts = root.path("ts").asText("");
        ItemMetadata item = new ItemMetadata();
        item.setId(THREAD + channel.id() + ":" + ts);
        item.setTitle(title(channel, root));
        item.setSourceType(ConnectorType.SLACK);
        item.setLastModified(latest(root, instant(root.path("latest_reply").asText(null))));
        item.setContentType(MARKDOWN);
        putExtra(item, "url", permalink(channel.id(), ts, null));
        putExtra(item, ItemMetadata.AUTHOR, author(root));
        return item;
    }

    private ItemMetadata dayItem(Channel channel, String day, List<JsonNode> messages) {
        Instant last = null;
        for (JsonNode m : messages) last = latest(m, last);
        ItemMetadata item = new ItemMetadata();
        item.setId(DAY + channel.id() + ":" + day);
        item.setTitle("#" + channel.name() + ", " + day);
        item.setSourceType(ConnectorType.SLACK);
        item.setLastModified(last);
        item.setContentType(MARKDOWN);
        // Newest first: the day's first message is the last one read.
        putExtra(item, "url", permalink(channel.id(), messages.get(messages.size() - 1).path("ts").asText(""), null));
        return item;
    }

    // -- the channels --------------------------------------------------------------------------

    /**
     * The channels this source reads: those the app is in, and - with a user token - those the
     * person is in, never a direct message. With channels chosen, those of them found; the rest are
     * noted as unreadable. With none chosen, every public channel found. Exclusions apply to both.
     */
    void resolveChannels() {
        if (channels != null) return;
        Map<String, Channel> readable = new LinkedHashMap<>();
        collect(bot, readable);
        if (user != null) collect(user, readable);
        Set<String> excluded = new HashSet<>(SlackConnectorPlugin.channels(context.get(SlackConnectorPlugin.EXCLUDE)));
        List<String> chosen = SlackConnectorPlugin.channels(context.get(SlackConnectorPlugin.CHANNELS));
        List<Channel> out = new ArrayList<>();
        if (chosen.isEmpty()) {
            for (Channel c : readable.values()) {
                if (!c.isPrivate() && !isExcluded(c, excluded)) out.add(c);
            }
        } else {
            for (String wanted : chosen) {
                Channel found = readable.get(wanted);
                if (found == null) {
                    for (Channel c : readable.values()) if (c.name().equals(wanted)) found = c;
                }
                if (found == null) {
                    unreadable.add(SlackConnectorPlugin.CHANNEL_ID.matcher(wanted).matches() ? wanted : "#" + wanted);
                } else if (!isExcluded(found, excluded) && !out.contains(found)) {
                    out.add(found);
                }
            }
        }
        out.forEach(c -> channelsById.put(c.id(), c));
        channels = out;
    }

    // The channels one token is a member of - users.conversations lists nothing else, and is asked
    // for channels only, so a direct message is never among them.
    private void collect(SlackApi api, Map<String, Channel> into) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("types", "public_channel,private_channel");
        query.put("exclude_archived", "false");
        query.put("limit", PAGE);
        Iterator<JsonNode> all = api.pages(Tier.TIER_3, "users.conversations", query, "channels");
        while (all.hasNext()) {
            JsonNode c = all.next();
            if (c.path("is_im").asBoolean(false) || c.path("is_mpim").asBoolean(false)) continue;
            String id = c.path("id").asText("");
            if (id.isEmpty() || into.containsKey(id)) continue;
            into.put(id, new Channel(id, c.path("name").asText(id).toLowerCase(java.util.Locale.ROOT),
                    c.path("is_private").asBoolean(false), api));
        }
    }

    private static boolean isExcluded(Channel c, Set<String> excluded) {
        return excluded.contains(c.id()) || excluded.contains(c.name());
    }

    // -- fetching ------------------------------------------------------------------------------

    /**
     * One thread or one day, read again from Slack. Only in a channel this source reads: an id names
     * its channel, and the agent's readSourceItem passes any id it is given - a channel the owner
     * left out, or one only their user token reaches, is not this source's to read.
     */
    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()
                || !(id.startsWith(THREAD) || id.startsWith(DAY))) {
            throw new ConnectorException("That is not an item of a Slack source.");
        }
        resolveChannels();
        Channel channel = channelsById.get(parts[1]);
        if (channel == null) throw new ConnectorException("That conversation is not in a channel this Slack source reads.");
        try {
            return markdown(id.startsWith(THREAD) ? renderThread(channel, parts[2]) : renderDay(channel, parts[2]));
        } catch (SlackApiException e) {
            if (e.isNotFound()) throw new ItemGoneException("Slack no longer has that conversation.");
            throw e;
        }
    }

    String renderThread(Channel channel, String ts) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("channel", channel.id());
        query.put("ts", ts);
        query.put("limit", PAGE);
        List<JsonNode> messages = new ArrayList<>();
        Iterator<JsonNode> all = channel.reader().pages(Tier.TIER_3, "conversations.replies", query, "messages");
        while (all.hasNext()) {
            JsonNode m = all.next();
            if (isContent(m)) messages.add(m);
        }
        if (messages.isEmpty()) throw new ItemGoneException("Slack no longer has that conversation.");
        messages.sort(Comparator.comparing(m -> decimal(m.path("ts").asText("0"))));
        JsonNode root = messages.get(0);

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(channel, root)).append("\n\n");
        header(md, channel);
        md.append("- Started by: ").append(author(root)).append('\n');
        Instant started = instant(ts);
        if (started != null) md.append("- Started: ").append(WHEN.format(started)).append('\n');
        md.append("- Replies: ").append(messages.size() - 1).append('\n');
        String link = permalink(channel.id(), ts, null);
        if (link != null) md.append("- Link: ").append(link).append('\n');
        md.append("\n## Messages\n");
        for (JsonNode m : messages) {
            String mts = m.path("ts").asText("");
            message(md, m, WHEN, mts.equals(ts) ? null : permalink(channel.id(), mts, ts));
        }
        return md.toString();
    }

    String renderDay(Channel channel, String day) {
        LocalDate date;
        try {
            date = LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("That is not an item of a Slack source.");
        }
        long start = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("channel", channel.id());
        query.put("oldest", start + ".000000");
        query.put("latest", (start + 86_399) + ".999999");
        query.put("inclusive", "true");
        query.put("limit", PAGE);
        List<JsonNode> messages = new ArrayList<>();
        Iterator<JsonNode> all = channel.reader().pages(Tier.TIER_3, "conversations.history", query, "messages");
        while (all.hasNext()) {
            JsonNode m = all.next();
            if (isContent(m) && !isThreadStart(m) && isStandalone(m)) messages.add(m);
        }
        if (messages.isEmpty()) throw new ItemGoneException("Slack no longer has messages of that day in the channel.");
        messages.sort(Comparator.comparing(m -> decimal(m.path("ts").asText("0"))));

        StringBuilder md = new StringBuilder();
        md.append("# #").append(channel.name()).append(", ").append(day).append("\n\n");
        header(md, channel);
        md.append("- Date: ").append(day).append(" (UTC)\n");
        md.append("- Messages: ").append(messages.size()).append('\n');
        md.append("\n## Messages\n");
        for (JsonNode m : messages) message(md, m, TIME, permalink(channel.id(), m.path("ts").asText(""), null));
        return md.toString();
    }

    private void header(StringBuilder md, Channel channel) {
        String team = workspace().path("team").asText("");
        if (!team.isEmpty()) md.append("- Workspace: ").append(team).append('\n');
        md.append("- Channel: #").append(channel.name()).append(channel.isPrivate() ? " (private)" : "").append('\n');
    }

    // One message: who, when, a link to it where it is not the item's own, and what it said.
    private void message(StringBuilder md, JsonNode m, DateTimeFormatter when, String link) {
        md.append("\n**").append(author(m)).append("**");
        Instant at = instant(m.path("ts").asText(null));
        if (at != null) md.append(", ").append(when.format(at));
        if (m.has("edited")) md.append(" (edited)");
        if (link != null) md.append(" ([link](").append(link).append("))");
        md.append(":\n\n").append(text(m)).append('\n');
    }

    // -- the workspace, people, text -----------------------------------------------------------

    // auth.test: the workspace's name and address, asked once per session.
    private JsonNode workspace() {
        if (workspace == null) workspace = bot.call(Tier.TIER_4, "auth.test", Map.of());
        return workspace;
    }

    /**
     * A message's link: {@code <workspace>/archives/<channel>/p<ts without its dot>}, the form
     * Slack's own "Copy link" gives, with the thread named for a reply.
     */
    String permalink(String channelId, String ts, String threadTs) {
        String base = workspace().path("url").asText("");
        if (base.isBlank() || ts == null || ts.isBlank()) return null;
        if (!base.endsWith("/")) base = base + "/";
        String link = base + "archives/" + channelId + "/p" + ts.replace(".", "");
        if (threadTs != null) link = link + "?thread_ts=" + threadTs + "&cid=" + channelId;
        return link;
    }

    /** Who wrote a message: a person's name, an app's, or Slack's id for them when it cannot be had. */
    String author(JsonNode m) {
        String userId = m.path("user").asText("");
        if (!userId.isEmpty() && !m.has("bot_id")) return userName(userId);
        String bot = m.path("bot_profile").path("name").asText(m.path("username").asText(""));
        if (!bot.isEmpty()) return bot;
        return userId.isEmpty() ? "Unknown" : userName(userId);
    }

    // users.info, once per person per session. A name that cannot be had - no users:read, a person
    // deleted - is their id, rather than a failed item.
    private String userName(String userId) {
        return userNames.computeIfAbsent(userId, id -> {
            try {
                JsonNode u = bot.call(Tier.TIER_4, "users.info", Map.of("user", id)).path("user");
                String real = u.path("real_name").asText("");
                if (real.isBlank()) real = u.path("profile").path("real_name").asText("");
                if (real.isBlank()) real = u.path("profile").path("display_name").asText("");
                if (real.isBlank()) real = u.path("name").asText("");
                return real.isBlank() ? id : real.strip();
            } catch (ConnectorHttpException e) {
                return id;
            }
        });
    }

    private String title(Channel channel, JsonNode root) {
        String first = text(root).strip();
        int nl = first.indexOf('\n');
        if (nl >= 0) first = first.substring(0, nl);
        first = first.replaceAll("\\s+", " ").strip();
        if (first.length() > 80) first = first.substring(0, 77).strip() + "...";
        return "#" + channel.name() + ": " + (first.isEmpty() ? "thread" : first);
    }

    /**
     * A message's text as markdown: Slack's {@code <@U1>}, {@code <#C1|name>}, {@code <!here>} and
     * {@code <url|label>} as names and links, its three escapes undone, and its attachments named.
     */
    String text(JsonNode m) {
        String raw = m.path("text").asText("");
        if (raw.isBlank()) {
            for (JsonNode a : m.path("attachments")) {
                String t = a.path("text").asText(a.path("fallback").asText(""));
                if (!t.isBlank()) {
                    raw = t;
                    break;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        Matcher e = ENTITY.matcher(raw);
        int at = 0;
        while (e.find()) {
            out.append(raw, at, e.start()).append(entity(e.group(1)));
            at = e.end();
        }
        out.append(raw.substring(at));
        String text = out.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").strip();
        List<String> files = new ArrayList<>();
        for (JsonNode f : m.path("files")) {
            String name = f.path("name").asText(f.path("title").asText(""));
            if (!name.isBlank()) files.add(name);
        }
        if (!files.isEmpty()) text = (text.isEmpty() ? "" : text + "\n\n") + "Attached: " + String.join(", ", files);
        return text.isEmpty() ? "(no text)" : text;
    }

    private String entity(String inner) {
        int bar = inner.indexOf('|');
        String target = bar < 0 ? inner : inner.substring(0, bar);
        String label = bar < 0 ? null : inner.substring(bar + 1);
        if (target.startsWith("@")) return "@" + userName(target.substring(1));
        if (target.startsWith("#")) {
            if (label != null && !label.isBlank()) return "#" + label;
            Channel known = channelsById.get(target.substring(1));
            return "#" + (known == null ? target.substring(1) : known.name());
        }
        if (target.startsWith("!")) {
            if (label != null && !label.isBlank()) return label;
            String special = target.substring(1);
            return switch (special) {
                case "here", "channel", "everyone" -> "@" + special;
                default -> special;
            };
        }
        if (target.startsWith("mailto:")) return label != null ? label : target.substring("mailto:".length());
        if (label == null || label.isBlank() || label.equals(target)) return target;
        return "[" + label + "](" + target + ")";
    }

    // -- what a message is ---------------------------------------------------------------------

    static boolean isContent(JsonNode m) {
        if (!m.path("type").asText("message").equals("message")) return false;
        return !SYSTEM_SUBTYPES.contains(m.path("subtype").asText(""));
    }

    // The first message of a thread with replies - its thread_ts is its own ts.
    static boolean isThreadStart(JsonNode m) {
        if (m.path("reply_count").asInt(0) <= 0) return false;
        String threadTs = m.path("thread_ts").asText("");
        return threadTs.isEmpty() || threadTs.equals(m.path("ts").asText(""));
    }

    // A message in no thread, or the first of one whose replies are all gone.
    static boolean isStandalone(JsonNode m) {
        String threadTs = m.path("thread_ts").asText("");
        return threadTs.isEmpty() || threadTs.equals(m.path("ts").asText(""));
    }

    // The latest of a message's own time, its edit's, and {@code also}.
    private static Instant latest(JsonNode m, Instant also) {
        Instant out = also;
        for (Instant i : new Instant[] {instant(m.path("ts").asText(null)), instant(m.path("edited").path("ts").asText(null))}) {
            if (i != null && (out == null || i.isAfter(out))) out = i;
        }
        return out;
    }

    private Instant windowStart() {
        String days = context.get(SlackConnectorPlugin.DAYS);
        if (days == null || days.isBlank()) return null;
        try {
            return context.clock().instant().minus(Duration.ofDays(Long.parseLong(days.strip())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -- Slack's timestamps --------------------------------------------------------------------

    /** A Slack {@code ts} ("1696150800.000100", seconds and microseconds) as an instant; null if it is not one. */
    static Instant instant(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            BigDecimal seconds = new BigDecimal(ts.strip());
            long whole = seconds.longValue();
            long micros = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(6).longValue();
            return Instant.ofEpochSecond(whole, micros * 1_000);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** An instant as a Slack {@code ts}, for {@code oldest}. */
    static String ts(Instant instant) {
        return instant.getEpochSecond() + "." + String.format("%06d", instant.getNano() / 1_000);
    }

    private static String dayOf(String ts) {
        Instant at = instant(ts);
        return at == null ? null : LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    private static BigDecimal decimal(String ts) {
        try {
            return new BigDecimal(ts);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
