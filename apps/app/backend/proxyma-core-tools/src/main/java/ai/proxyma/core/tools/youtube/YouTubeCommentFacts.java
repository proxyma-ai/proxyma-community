package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.system.util.Utf16;
import ai.proxyma.core.chat.approval.CommentFactsProvider;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * WHO WROTE A YOUTUBE COMMENT, WHEN, UNDER WHICH VIDEO, AND WHAT THAT VIDEO IS CALLED - for a page that names
 * comments outside an approval (the Constellations Overview's topics, 2026-11-05), the same facts
 * {@link YouTubeApprovalContext} shows on an approval card.
 *
 * <p>Through the one connection named, only when it is the owner's own, serves YouTube and may READ. QUOTA,
 * one unit a call: one commentThreads.list per {@link #BATCH} threads (a top-level comment's id is its
 * thread's; a reply, "&lt;thread&gt;.&lt;reply&gt;", is read with comments.list and its video off its thread), and
 * one videos.list per {@link #BATCH} videos whose title Proxyma does not already hold (YouTubeVideoTitles: the
 * channel source's index first, 2026-11-06). Every answer - a comment or a video YouTube no longer has
 * included - is remembered for {@link #TTL} per connection, so a page read every thirty seconds asks YouTube
 * nothing new until a dashboard names something new. At most {@link #COMMENTS_MAX} comments and
 * {@link #VIDEOS_MAX} videos a lookup. A call that fails leaves its ids unknown and the facts incomplete;
 * nothing is thrown.
 */
@Component
public class YouTubeCommentFacts implements CommentFactsProvider {

    static final int BATCH = 50;
    static final int COMMENTS_MAX = 300;
    static final int VIDEOS_MAX = 200;
    static final Duration TTL = Duration.ofMinutes(30);
    static final int CACHE_MAX = 5_000;

    private static final Pattern COMMENT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}(\\.[A-Za-z0-9_-]{1,100})?$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final int NAME_MAX = 300;

    private final ConnectorEndpointResolver endpoints;
    private final ConnectorConnectionService connections;
    private final SourceContextFactory contexts;
    private final ConnectorHttp http;
    /** scope:commentId -> what YouTube said; a null comment is one YouTube no longer has. */
    private final Map<String, Known<Comment>> comments = new ConcurrentHashMap<>();
    /** Where a video's title is found: the channel source's index, what was read before, then YouTube. */
    private YouTubeVideoTitles titles = YouTubeVideoTitles.memoryOnly();
    private Clock clock = Clock.systemUTC();

    private record Known<T>(T value, Instant at) {}

    public YouTubeCommentFacts(ConnectorEndpointResolver endpoints, ConnectorConnectionService connections,
                               SourceContextFactory contexts, ConnectorHttp http) {
        this.endpoints = endpoints;
        this.connections = connections;
        this.contexts = contexts;
        this.http = http;
    }

    /** Tests only. */
    void setClock(Clock clock) {
        this.clock = clock;
        titles.setClock(clock);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTitles(YouTubeVideoTitles titles) {
        if (titles != null) this.titles = titles;
    }

    @Override
    public String service() {
        return ConnectorType.YOUTUBE_CHANNEL.wireName();
    }

    @Override
    public Facts lookUp(String ownerUserId, UUID connectionId, Collection<String> commentIds, Collection<String> videoIds) {
        Set<String> wantedComments = valid(commentIds, COMMENT_ID, COMMENTS_MAX);
        Set<String> wantedVideos = valid(videoIds, VIDEO_ID, VIDEOS_MAX);
        if (connectionId == null || (wantedComments.isEmpty() && wantedVideos.isEmpty())) return Facts.NONE;
        ApiClient api;
        try {
            ConnectorEndpointResolver.Endpoint endpoint = endpoints.byId(ConnectorType.YOUTUBE_CHANNEL, connectionId, ownerUserId);
            if (!endpoint.exists() || !endpoint.allowRead()) return Facts.NONE;
            ResolvedConnection connection = connections.resolveOwned(ownerUserId, connectionId).orElse(null);
            if (connection == null) return Facts.NONE;
            api = YouTubeTools.client(http, contexts.credentials(connection), true);
        } catch (RuntimeException e) {
            return new Facts(Map.of(), titles.held(ownerUserId, connectionId, wantedVideos), false);
        }
        String scope = connectionId.toString();
        boolean complete = true;

        // The comments not yet known: replies read alone, every thread - a reply's included, for its video.
        List<String> replies = new ArrayList<>();
        Set<String> threads = new LinkedHashSet<>();
        for (String id : wantedComments) {
            if (fresh(comments.get(scope + ":" + id))) continue;
            int dot = id.indexOf('.');
            if (dot > 0) {
                replies.add(id);
                threads.add(id.substring(0, dot));
            } else {
                threads.add(id);
            }
        }
        Map<String, String> threadVideo = new LinkedHashMap<>();
        Map<String, JsonNode> threadComment = new LinkedHashMap<>();
        Set<String> threadsAnswered = new LinkedHashSet<>();
        for (List<String> batch : batches(threads)) {
            try {
                JsonNode items = api.getJson(YouTubeTools.V3 + "commentThreads", Map.of("part", "snippet",
                        "id", String.join(",", batch), "textFormat", "plainText")).path("items");
                for (JsonNode t : items) {
                    String id = t.path("id").asText("");
                    threadVideo.put(id, t.path("snippet").path("videoId").asText(""));
                    threadComment.put(id, t.path("snippet").path("topLevelComment"));
                }
                threadsAnswered.addAll(batch);
            } catch (RuntimeException e) {
                complete = false;
            }
        }
        Map<String, JsonNode> replyComment = new LinkedHashMap<>();
        Set<String> repliesAnswered = new LinkedHashSet<>();
        for (List<String> batch : batches(replies)) {
            try {
                JsonNode items = api.getJson(YouTubeTools.V3 + "comments", Map.of("part", "snippet",
                        "id", String.join(",", batch), "textFormat", "plainText")).path("items");
                for (JsonNode c : items) replyComment.put(c.path("id").asText(""), c);
                repliesAnswered.addAll(batch);
            } catch (RuntimeException e) {
                complete = false;
            }
        }
        Instant now = clock.instant();
        trim(comments);
        for (String id : wantedComments) {
            if (fresh(comments.get(scope + ":" + id))) continue;
            int dot = id.indexOf('.');
            String thread = dot > 0 ? id.substring(0, dot) : id;
            boolean answered = dot > 0 ? repliesAnswered.contains(id) && threadsAnswered.contains(thread) : threadsAnswered.contains(id);
            if (!answered) continue;
            JsonNode node = dot > 0 ? replyComment.get(id) : threadComment.get(id);
            String video = threadVideo.get(thread);
            comments.put(scope + ":" + id, new Known<>(node == null ? null : comment(node, video), now));
        }

        // The videos: the ones asked for, and the ones the comments are under.
        Set<String> videos = new LinkedHashSet<>(wantedVideos);
        for (String id : wantedComments) {
            Known<Comment> c = comments.get(scope + ":" + id);
            if (c != null && c.value() != null && c.value().videoId() != null && videos.size() < VIDEOS_MAX) videos.add(c.value().videoId());
        }
        // The channel source's index first, with no call, then what was read before, then videos.list (YouTubeVideoTitles).
        Map<String, String> knownTitles = titles.titles(api, ownerUserId, connectionId, videos, null);
        for (String v : videos) if (!knownTitles.containsKey(v) && !titles.gone(connectionId, v)) complete = false;

        Map<String, Comment> outComments = new LinkedHashMap<>();
        for (String id : wantedComments) {
            Known<Comment> c = comments.get(scope + ":" + id);
            if (c != null && c.value() != null) outComments.put(id, c.value());
        }
        Map<String, String> outTitles = new LinkedHashMap<>(knownTitles);
        return new Facts(outComments, outTitles, complete);
    }

    // One comment's facts from its snippet, under the video its thread names.
    private static Comment comment(JsonNode node, String videoId) {
        JsonNode s = node.path("snippet");
        String author = cut(s.path("authorDisplayName").asText(""));
        String url = s.path("authorChannelUrl").asText("").strip();
        String channelId = s.path("authorChannelId").path("value").asText("").strip();
        if (!url.matches("(?i)^https?://\\S+$")) url = "";
        if (url.isEmpty() && channelId.matches("^[A-Za-z0-9_-]{1,100}$")) url = "https://www.youtube.com/channel/" + enc(channelId);
        String at = s.path("publishedAt").asText("");
        try {
            Instant.parse(at);
        } catch (java.time.format.DateTimeParseException e) {
            at = "";
        }
        String video = videoId != null && VIDEO_ID.matcher(videoId).matches() ? videoId : null;
        return new Comment(author, url.isEmpty() || url.length() > 2_000 ? null : url, at.isEmpty() ? null : at, video);
    }

    private boolean fresh(Known<?> known) {
        return known != null && known.at().plus(TTL).isAfter(clock.instant());
    }

    // A bounded cache: past its size, what is stale goes, and if that is not enough, everything.
    private void trim(Map<String, ? extends Known<?>> cache) {
        if (cache.size() < CACHE_MAX) return;
        cache.values().removeIf(k -> !fresh(k));
        if (cache.size() >= CACHE_MAX) cache.clear();
    }

    private static Set<String> valid(Collection<String> ids, Pattern shape, int max) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (out.size() >= max) break;
            String s = id == null ? "" : id.strip();
            if (shape.matcher(s).matches()) out.add(s);
        }
        return out;
    }

    private static List<List<String>> batches(Collection<String> ids) {
        List<String> all = new ArrayList<>(ids);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += BATCH) out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
        return out;
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > NAME_MAX ? Utf16.cut(t, NAME_MAX - 3) + "..." : t;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
