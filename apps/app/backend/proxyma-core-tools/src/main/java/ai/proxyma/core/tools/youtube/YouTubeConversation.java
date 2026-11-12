package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ONE COMMENT THREAD READ AS A CONVERSATION (2026-11-05, the maintainer: "Get the comments that's not get a reply
 * yet. This includes the viewer reply comments as well. E.g. the viewer comment, I reply, then he/she reply, then I
 * need to reply another one."). Until then a thread was "answered" as soon as any reply in it was the channel's, and a
 * thread the channel started was never listed - so a viewer answering the owner back, or replying under the owner's
 * own comment, was never seen.
 *
 * <p>The thread's messages in the order they were written - its top-level comment, then every reply by when it was
 * published. A message NEEDS A DECISION when it is not the channel's and nothing the channel wrote comes after it: a
 * top-level comment with no channel reply, or a reply posted after the channel's last message in the thread (the
 * channel's own threads included). At most {@link #PER_THREAD} of a thread's newest replies are taken, so a thread
 * where viewers talk among themselves does not flood a listing.
 *
 * <p>Each comes with its conversation so far - the messages before it, the newest {@link #CONTEXT_MESSAGES}, each cut
 * at {@link #CONTEXT_TEXT} characters - and, for a reply, the text it answers ({@link #inReplyTo}): the earlier message
 * of the viewer it names with "@", else the channel's last message before it, else the thread's top-level comment.
 *
 * <p>YouTube carries at most five replies inline with a thread ({@code commentThreads.list part=replies}); a thread
 * with more is judged whole only when its replies were read ({@link #replies}, {@code comments.list parentId=}, one
 * quota unit a hundred). Pure, apart from that read.
 */
public final class YouTubeConversation {

    /** The most replies of one thread that need a decision at once: its newest. */
    public static final int PER_THREAD = 5;
    /** How many earlier messages a pending one carries, and how much of each. */
    public static final int CONTEXT_MESSAGES = 6;
    public static final int CONTEXT_TEXT = 300;
    /** How much of the message a reply answers is carried. */
    public static final int IN_REPLY_TO = 300;
    /** How many pages of a long thread's replies are read, a hundred each. */
    public static final int REPLY_PAGES = 2;

    private YouTubeConversation() {
    }

    /** One message of a thread, as written. */
    public record Message(String commentId, String author, String authorChannelId, String authorUrl, String text,
                          Instant publishedAt, boolean byChannel) {}

    /** A message that needs a decision, with what came before it. */
    public record Pending(Message message, String threadId, String videoId, boolean reply, List<Message> before,
                          String inReplyTo) {}

    /** The thread's messages in the order written: the top-level comment, then the replies by publication. */
    public static List<Message> messages(JsonNode thread, List<JsonNode> replies, String channelId) {
        JsonNode top = thread.path("snippet").path("topLevelComment");
        List<Message> out = new ArrayList<>();
        out.add(message(top, channelId));
        List<Message> rs = new ArrayList<>();
        for (JsonNode r : replies) rs.add(message(r, channelId));
        // A stable sort: replies YouTube gives in another order are put in the order they were written.
        rs.sort(Comparator.comparing(m -> m.publishedAt() == null ? Instant.EPOCH : m.publishedAt()));
        out.addAll(rs);
        return out;
    }

    /** The replies YouTube carried inline with the thread. */
    public static List<JsonNode> inlineReplies(JsonNode thread) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode r : thread.path("replies").path("comments")) out.add(r);
        return out;
    }

    /** Whether the thread has more replies than YouTube carried inline - judged whole only once they are read. */
    public static boolean truncated(JsonNode thread) {
        return thread.path("snippet").path("totalReplyCount").asInt(0) > thread.path("replies").path("comments").size();
    }

    /**
     * A thread's replies read whole, oldest first as YouTube gives them, at most {@code pages} pages of a hundred;
     * throws when YouTube cannot be read. One quota unit a page.
     */
    public static List<JsonNode> replies(ApiClient api, String threadId, int pages) {
        List<JsonNode> out = new ArrayList<>();
        String token = null;
        int read = 0;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("part", "snippet");
            q.put("parentId", threadId);
            q.put("maxResults", 100);
            q.put("textFormat", "plainText");
            if (token != null) q.put("pageToken", token);
            JsonNode page = api.getJson(YouTubeTools.V3 + "comments", q);
            for (JsonNode c : page.path("items")) out.add(c);
            token = page.path("nextPageToken").asText("");
            if (token.isBlank()) token = null;
            read++;
        } while (token != null && read < pages);
        return out;
    }

    /** The messages of a thread that need a decision, oldest first, at most {@link #PER_THREAD} replies. */
    public static List<Pending> pending(JsonNode thread, List<JsonNode> replies, String channelId) {
        List<Message> all = messages(thread, replies, channelId);
        String threadId = thread.path("id").asText("");
        if (threadId.isEmpty()) threadId = all.get(0).commentId();
        String videoId = thread.path("snippet").path("videoId").asText("");
        int lastChannel = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).byChannel()) lastChannel = i;
        List<Integer> waiting = new ArrayList<>();
        for (int i = lastChannel + 1; i < all.size(); i++) {
            if (!all.get(i).byChannel() && !all.get(i).commentId().isEmpty()) waiting.add(i);
        }
        // The top-level comment always, when it waits; of the replies, the newest few.
        List<Integer> kept = new ArrayList<>();
        int replies0 = 0;
        for (int k = waiting.size() - 1; k >= 0; k--) {
            int i = waiting.get(k);
            if (i == 0) kept.add(0, i);
            else if (replies0 < PER_THREAD) {
                kept.add(0, i);
                replies0++;
            }
        }
        kept.sort(Integer::compareTo);
        List<Pending> out = new ArrayList<>();
        for (int i : kept) {
            Message m = all.get(i);
            List<Message> before = new ArrayList<>();
            for (int j = Math.max(0, i - CONTEXT_MESSAGES); j < i; j++) before.add(cut(all.get(j)));
            out.add(new Pending(m, threadId, videoId, i > 0, before, i > 0 ? inReplyTo(all, i) : null));
        }
        return out;
    }

    /**
     * Whether the channel wrote in this thread after the comment {@code commentId} - which answers it - and what it
     * wrote last; null when it did not, or when the comment is not in the thread's messages.
     */
    public static Message answeredBy(List<Message> all, String commentId) {
        int at = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).commentId().equals(commentId)) at = i;
        if (at < 0) return null;
        Message last = null;
        for (int i = at + 1; i < all.size(); i++) if (all.get(i).byChannel()) last = all.get(i);
        return last;
    }

    /** Whether a comment is among the thread's messages. */
    public static boolean contains(List<Message> all, String commentId) {
        for (Message m : all) if (m.commentId().equals(commentId)) return true;
        return false;
    }

    // What a reply answers: the earlier message of the viewer it names with "@", else the channel's last message
    // before it, else the top-level comment.
    static String inReplyTo(List<Message> all, int i) {
        String text = all.get(i).text();
        if (text != null && text.startsWith("@")) {
            String name = text.substring(1).split("\\s", 2)[0].strip().toLowerCase(Locale.ROOT);
            for (int j = i - 1; j >= 0 && !name.isEmpty(); j--) {
                String author = all.get(j).author() == null ? "" : all.get(j).author().strip();
                if (author.startsWith("@")) author = author.substring(1);
                if (author.toLowerCase(Locale.ROOT).equals(name)) return bounded(all.get(j).text(), IN_REPLY_TO);
            }
        }
        for (int j = i - 1; j >= 0; j--) if (all.get(j).byChannel()) return bounded(all.get(j).text(), IN_REPLY_TO);
        return bounded(all.get(0).text(), IN_REPLY_TO);
    }

    static Message message(JsonNode comment, String channelId) {
        JsonNode s = comment.path("snippet");
        String text = s.path("textOriginal").asText("");
        if (text.isEmpty()) text = s.path("textDisplay").asText("");
        String author = s.path("authorChannelId").path("value").asText("");
        String url = s.path("authorChannelUrl").asText("").strip();
        if (!url.matches("(?i)^https?://\\S+$")) url = "";
        if (url.isEmpty() && author.matches("^[A-Za-z0-9_-]{1,100}$")) url = "https://www.youtube.com/channel/" + author;
        return new Message(comment.path("id").asText(""), s.path("authorDisplayName").asText(""), author,
                url.isEmpty() ? null : url, text, instant(s.path("publishedAt").asText("")),
                !channelId.isEmpty() && channelId.equals(author));
    }

    private static Message cut(Message m) {
        return new Message(m.commentId(), m.author(), m.authorChannelId(), m.authorUrl(), bounded(m.text(), CONTEXT_TEXT),
                m.publishedAt(), m.byChannel());
    }

    static String bounded(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() <= max) return t;
        int end = Character.isHighSurrogate(t.charAt(max - 4)) ? max - 4 : max - 3;
        return t.substring(0, end) + "...";
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
