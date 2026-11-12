package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.spi.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * What the YouTube source asks the YouTube Data API v3 for, and how a comment thread and a video read
 * once indexed. Every call here costs ONE unit of the project's daily quota (10,000 by default):
 * {@code channels.list}, {@code playlistItems.list}, {@code commentThreads.list},
 * {@code comments.list} and {@code videos.list} - never {@code search.list}, which costs 100.
 */
final class YouTubeApi {

    static final String V3 = "youtube/v3/";

    private YouTubeApi() {}

    /** The signed-in account's channel ({@code mine=true}), or null when the account has none. */
    static JsonNode myChannel(ApiClient api) {
        JsonNode page = api.getJson(V3 + "channels", Map.of("part", "snippet,contentDetails", "mine", "true"));
        JsonNode first = page.path("items").path(0);
        return first.isMissingNode() || first.isNull() ? null : first;
    }

    /** The playlist every upload of a channel is in. */
    static String uploadsPlaylist(JsonNode channel) {
        return channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
    }

    /**
     * The channel's own page: its handle's ({@code snippet.customUrl}, "@name") when it has one, else
     * its id's - or null with neither.
     */
    static String channelUrl(JsonNode channel) {
        if (channel == null) return null;
        String handle = channel.path("snippet").path("customUrl").asText("").strip();
        if (handle.length() > 1 && handle.startsWith("@") && handle.chars().noneMatch(c -> c == '/' || c == '?'
                || c == '#' || Character.isWhitespace(c))) {
            return "https://www.youtube.com/" + URLEncoder.encode(handle, StandardCharsets.UTF_8).replace("%40", "@");
        }
        String id = channel.path("id").asText("").strip();
        return id.matches("[A-Za-z0-9_-]+") ? "https://www.youtube.com/channel/" + id : null;
    }

    static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    static String commentUrl(String videoId, String commentId) {
        return watchUrl(videoId) + "&lc=" + commentId;
    }

    /** The replies a thread carries inline (up to five - the rest are comments.list's). */
    static List<JsonNode> inlineReplies(JsonNode thread) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode reply : thread.path("replies").path("comments")) out.add(reply);
        return out;
    }

    /** Whether a comment was written by the channel itself. */
    static boolean byChannel(JsonNode comment, String channelId) {
        if (channelId == null || channelId.isBlank()) return false;
        return channelId.equals(comment.path("snippet").path("authorChannelId").path("value").asText(""));
    }

    /** The latest moment anything in the thread was written or edited - what makes it changed. */
    static Instant lastActivity(JsonNode thread, List<JsonNode> replies) {
        Instant latest = GoogleApi.instant(thread.path("snippet").path("topLevelComment").path("snippet")
                .path("updatedAt").asText(null));
        for (JsonNode reply : replies) {
            Instant at = GoogleApi.instant(reply.path("snippet").path("updatedAt").asText(null));
            if (at != null && (latest == null || at.isAfter(latest))) latest = at;
        }
        return latest;
    }

    /**
     * A comment thread as the index and the agent read it: the video it is on, who wrote it and when,
     * whether the channel has answered, the comment and every reply - each marked when it is the
     * channel's own. The thread's ids are written out, because they are what a reply is posted to.
     */
    static String renderThread(JsonNode thread, List<JsonNode> replies, String channelId, String videoTitle) {
        JsonNode snippet = thread.path("snippet");
        JsonNode top = snippet.path("topLevelComment");
        JsonNode comment = top.path("snippet");
        String videoId = snippet.path("videoId").asText("");
        String author = author(comment);
        boolean answered = false;
        for (JsonNode reply : replies) if (byChannel(reply, channelId)) answered = true;

        StringBuilder md = new StringBuilder();
        md.append("# Comment by ").append(author);
        if (videoTitle != null && !videoTitle.isBlank()) md.append(" on \"").append(videoTitle).append('"');
        md.append("\n\n");
        if (videoTitle != null && !videoTitle.isBlank()) md.append("- Video: ").append(videoTitle).append('\n');
        if (!videoId.isBlank()) md.append("- Video link: ").append(watchUrl(videoId)).append('\n');
        md.append("- Thread ID: ").append(thread.path("id").asText("")).append('\n');
        md.append("- Comment ID: ").append(top.path("id").asText("")).append('\n');
        md.append("- Author: ").append(author).append('\n');
        if (comment.hasNonNull("publishedAt")) md.append("- Written: ").append(comment.path("publishedAt").asText()).append('\n');
        int likes = comment.path("likeCount").asInt(0);
        if (likes > 0) md.append("- Likes: ").append(likes).append('\n');
        md.append("- Replies: ").append(snippet.path("totalReplyCount").asInt(replies.size())).append('\n');
        md.append("- Answered by the channel: ").append(answered ? "yes" : "no").append('\n');
        if (!videoId.isBlank()) md.append("- Link: ").append(commentUrl(videoId, top.path("id").asText(""))).append('\n');
        md.append("\n## Comment\n\n").append(text(comment)).append('\n');
        if (!replies.isEmpty()) {
            md.append("\n## Replies\n");
            for (JsonNode reply : replies) {
                JsonNode r = reply.path("snippet");
                md.append("\n- ").append(author(r));
                if (byChannel(reply, channelId)) md.append(" (the channel)");
                if (r.hasNonNull("publishedAt")) md.append(", ").append(r.path("publishedAt").asText());
                md.append(": ").append(text(r).replace("\n", " ")).append('\n');
            }
        }
        return md.toString();
    }

    /** A video as the index reads it: its title, when it went up, its privacy, its link and its description. */
    static String renderVideo(JsonNode video) {
        JsonNode snippet = video.path("snippet");
        String id = video.path("id").asText("");
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title(snippet)).append("\n\n");
        md.append("- Video ID: ").append(id).append('\n');
        if (snippet.hasNonNull("publishedAt")) md.append("- Published: ").append(snippet.path("publishedAt").asText()).append('\n');
        String privacy = video.path("status").path("privacyStatus").asText("");
        if (!privacy.isBlank()) md.append("- Visibility: ").append(privacy).append('\n');
        if (!id.isBlank()) md.append("- Link: ").append(watchUrl(id)).append('\n');
        String description = GoogleApi.text(snippet, "description");
        md.append("\n## Description\n\n").append(description.isBlank() ? "(no description)" : description).append('\n');
        return md.toString();
    }

    /** A hash of what an item indexes as: the same text, the same hash, and the sync engine leaves it be. */
    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String title(JsonNode snippet) {
        String t = GoogleApi.text(snippet, "title");
        return t.isEmpty() ? "(untitled video)" : t;
    }

    static String author(JsonNode commentSnippet) {
        String name = GoogleApi.text(commentSnippet, "authorDisplayName");
        return name.isEmpty() ? "(someone)" : name;
    }

    // textOriginal is the comment as written; textDisplay may be HTML.
    static String text(JsonNode commentSnippet) {
        String original = GoogleApi.text(commentSnippet, "textOriginal");
        if (!original.isEmpty()) return original;
        return GoogleApi.plain(commentSnippet.path("textDisplay").asText(""));
    }
}
