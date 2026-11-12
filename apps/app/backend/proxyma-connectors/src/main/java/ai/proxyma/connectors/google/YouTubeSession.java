package ai.proxyma.connectors.google;

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
import ai.proxyma.core.connector.spi.GoogleError;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceLabel;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One YouTube channel source, opened for one pass or one batch of fetches.
 *
 * <p>Items are {@code video:<videoId>} and {@code thread:<commentThreadId>}. A pass lists the
 * channel's uploads ({@code playlistItems.list} over its uploads playlist, 50 a page, each page's videos
 * then read in one {@code videos.list} call) and then every comment thread related to the channel
 * ({@code commentThreads.list} with {@code allThreadsRelatedToChannelId}, newest first, 100 a page) -
 * each up to its bound. A channel YouTube refuses that listing for ({@code commentsDisabled} or
 * {@code forbidden}, while the channel itself answered) has its newest {@link #FALLBACK_VIDEOS} uploads'
 * threads read one video at a time instead, a video with comments off skipped - and that pass is
 * PARTIAL, so threads it did not reach are never purged.
 *
 * <p>A video is listed with a hash of the text it indexes as - title, published, visibility, link and
 * description - and as seen now, so the sync engine reads it again exactly when that text changed: a
 * description edited after upload is re-indexed, an unchanged video is not. YouTube states no moment a
 * video was edited, so the hash is the whole of the change test. An upload {@code videos.list} does not
 * return (deleted, or not readable) is not listed, so a COMPLETE pass lets it go.
 *
 * <p>A thread's last-modified is the latest moment anything in it was written or edited, so a new reply
 * makes it changed and it is read again; YouTube hands at most five replies inline, so a sixth on a long
 * thread moves it only when one of those five changes.
 *
 * <p>A fetch reads the thread again by id, with all its replies when it has more than five (one
 * {@code comments.list} page of 100, up to {@link #MAX_REPLIES}), or the video by id.
 */
final class YouTubeSession implements ConnectorSession {

    static final int MAX_REPLIES = 300;
    /** The most ids one {@code videos.list} call takes. */
    static final int VIDEOS_PER_CALL = 50;
    /** How many of the newest uploads are read one by one when the channel's threads cannot be listed at once. */
    static final int FALLBACK_VIDEOS = 20;

    private static final Logger log = LoggerFactory.getLogger(YouTubeSession.class);

    private final ApiClient api;
    private final int maxVideos;
    private final int maxThreads;
    // When this pass looked: a video's last-modified, its hash deciding whether it changed.
    private final Instant observed;
    // Uploads listed but not yet read through videos.list, in the playlist's order...
    private final Set<String> undetailed = new LinkedHashSet<>();
    // ...and those read and not yet listed - null for one videos.list did not return.
    private final Map<String, JsonNode> details = new HashMap<>();
    // The channel, asked once per session: its id says which replies are its own.
    private JsonNode channel;
    // Video titles seen in this pass, so a thread is named by its video without asking again.
    private final Map<String, String> videoTitles = new HashMap<>();
    private boolean videosCapped;
    private boolean threadsCapped;
    // The channel's threads refused at once and read video by video (threads()): how many videos were
    // asked, and how many of them have comments turned off.
    private boolean perVideo;
    private int videosAsked;
    private int commentsOff;

    YouTubeSession(SourceContext context) {
        Map<String, String> config = context.config();
        this.maxVideos = YouTubeConnectorPlugin.bound(config, YouTubeConnectorPlugin.MAX_VIDEOS,
                YouTubeConnectorPlugin.DEFAULT_MAX_VIDEOS, 1000);
        this.maxThreads = YouTubeConnectorPlugin.bound(config, YouTubeConnectorPlugin.MAX_THREADS,
                YouTubeConnectorPlugin.DEFAULT_MAX_THREADS, 5000);
        this.observed = context.clock().instant().truncatedTo(ChronoUnit.SECONDS);
        this.api = GoogleApi.client(context.http(), context.connection(), "YouTube");
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            return channel() == null
                    ? AccessCheck.failed("This Google account has no YouTube channel. Connect the account that owns the channel.")
                    : AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            return AccessCheck.failed(GoogleApi.refusal(e, "YouTube Data API v3"));
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    private JsonNode channel() {
        if (channel == null) channel = YouTubeApi.myChannel(api);
        return channel;
    }

    private String channelId() {
        JsonNode c = channel();
        return c == null ? "" : c.path("id").asText("");
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        JsonNode c = channel();
        if (c == null) return Listings.failed("This Google account has no YouTube channel.");
        String uploads = YouTubeApi.uploadsPlaylist(c);
        String channelId = channelId();
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (maxVideos > 0 && !uploads.isBlank()) parts.add(() -> videos(uploads));
        if (maxThreads > 0) parts.add(() -> threads(channelId, uploads));
        Listing all = Listings.concat(parts);
        // Read video by video, a pass reaches only the newest uploads' threads: never complete, so what it
        // did not reach is not purged.
        return Listings.partialIf(all, () -> perVideo
                ? (commentsOff == videosAsked
                        ? "Comments are turned off for this channel or its videos (videos made for kids have none), so "
                                + "there are no comments to read."
                        : "YouTube does not list this channel's comments all at once, so its newest " + videosAsked
                                + " videos were read one by one (" + commentsOff + " with comments turned off).")
                : videosCapped || threadsCapped
                        ? "Only the newest " + maxVideos + " videos and " + maxThreads + " comment threads are read each time."
                        : null);
    }

    // A read that must succeed: its JSON, or its refusal said for what it is (YouTubeRefusals).
    private JsonNode get(String path, Map<String, ?> query) {
        return YouTubeRefusals.json(api, api.get(path, query));
    }

    private Iterator<ItemMetadata> videos(String uploads) {
        Iterator<JsonNode> pages = Paginator.iterate(next -> {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("part", "contentDetails");
            query.put("playlistId", uploads);
            query.put("maxResults", 50);
            if (next != null) query.put("pageToken", next);
            JsonNode page = get(YouTubeApi.V3 + "playlistItems", query);
            for (JsonNode playlistItem : page.path("items")) {
                String videoId = playlistVideoId(playlistItem);
                if (!videoId.isEmpty()) undetailed.add(videoId);
            }
            return new Paginator.Page<>(Paginator.elements(page.path("items")), page.path("nextPageToken").asText(null));
        });
        return Listings.mapped(bounded(pages, maxVideos, () -> videosCapped = true), this::videoItem);
    }

    private static String playlistVideoId(JsonNode playlistItem) {
        String id = playlistItem.path("contentDetails").path("videoId").asText("");
        return id.isEmpty() ? playlistItem.path("snippet").path("resourceId").path("videoId").asText("") : id;
    }

    private ItemMetadata videoItem(JsonNode playlistItem) {
        String videoId = playlistVideoId(playlistItem);
        if (videoId.isEmpty()) return null;
        JsonNode video = detail(videoId);
        // Gone, or not readable: not listed, so only a complete pass lets it go.
        if (video == null) return null;
        String title = YouTubeApi.title(video.path("snippet"));
        videoTitles.put(videoId, title);
        ItemMetadata item = new ItemMetadata();
        item.setId("video:" + videoId);
        item.setTitle(title);
        item.setSourceType(ConnectorType.YOUTUBE_CHANNEL);
        item.setLastModified(observed);
        item.setContentType(GoogleApi.MARKDOWN);
        GoogleApi.putExtra(item, ItemMetadata.CONTENT_HASH, YouTubeApi.hash(YouTubeApi.renderVideo(video)));
        GoogleApi.putExtra(item, "url", YouTubeApi.watchUrl(videoId));
        return item;
    }

    /**
     * One listed upload as {@code videos.list} reads it, or null when it returns nothing for it. The
     * first upload not yet read is asked with the next ones listed, up to {@link #VIDEOS_PER_CALL} a call
     * - one quota unit for a page of the playlist.
     */
    private JsonNode detail(String videoId) {
        if (!details.containsKey(videoId)) {
            List<String> ids = new ArrayList<>();
            ids.add(videoId);
            undetailed.remove(videoId);
            Iterator<String> rest = undetailed.iterator();
            while (ids.size() < VIDEOS_PER_CALL && rest.hasNext()) {
                ids.add(rest.next());
                rest.remove();
            }
            JsonNode page = get(YouTubeApi.V3 + "videos", Map.of("part", "snippet,status", "id", String.join(",", ids)));
            for (String id : ids) details.put(id, null);
            for (JsonNode video : page.path("items")) {
                String id = video.path("id").asText("");
                if (details.containsKey(id)) details.put(id, video);
            }
        }
        return details.remove(videoId);
    }

    /**
     * The channel's comment threads: all at once, or - when YouTube refuses that while the channel answered
     * (YouTubeRefusals.channelThreadsRefused) - the newest {@link #FALLBACK_VIDEOS} uploads' threads, read
     * one video at a time, a video whose comments are off skipped and counted. The fallback costs one
     * {@code playlistItems.list} and one {@code commentThreads.list} a video: 21 units at most.
     */
    private Iterator<ItemMetadata> threads(String channelId, String uploads) {
        Iterator<JsonNode> channelPages = Paginator.iterate(next -> {
            Map<String, Object> query = threadQuery();
            query.put("allThreadsRelatedToChannelId", channelId);
            if (next != null) query.put("pageToken", next);
            ApiResponse answer = api.get(YouTubeApi.V3 + "commentThreads", query);
            if (next == null && YouTubeRefusals.channelThreadsRefused(answer)) {
                log.info("[YouTube] The channel's comments cannot be listed at once ({}); reading its newest videos one by one",
                        GoogleError.describe(answer));
                perVideo = true;
                return new Paginator.Page<>(List.of(), null);
            }
            JsonNode page = YouTubeRefusals.json(api, answer);
            return new Paginator.Page<>(Paginator.elements(page.path("items")), page.path("nextPageToken").asText(null));
        });
        Iterator<JsonNode> all = new Iterator<>() {
            private Iterator<JsonNode> videos;

            @Override
            public boolean hasNext() {
                if (channelPages.hasNext()) return true;
                if (!perVideo) return false;
                if (videos == null) videos = perVideoThreads(uploads);
                return videos.hasNext();
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                return videos == null ? channelPages.next() : videos.next();
            }
        };
        return Listings.mapped(bounded(all, maxThreads, () -> threadsCapped = true), this::threadItem);
    }

    // The newest uploads' threads, one video at a time: one page of 100 a video, newest first.
    private Iterator<JsonNode> perVideoThreads(String uploads) {
        Deque<String> ids = new ArrayDeque<>();
        if (!uploads.isBlank()) {
            ApiResponse listed = api.get(YouTubeApi.V3 + "playlistItems", Map.of("part", "contentDetails",
                    "playlistId", uploads, "maxResults", FALLBACK_VIDEOS));
            // A channel that never uploaded has no uploads playlist to read.
            if (listed.status() != 404) {
                for (JsonNode item : YouTubeRefusals.json(api, listed).path("items")) {
                    String id = playlistVideoId(item);
                    if (!id.isEmpty() && ids.size() < FALLBACK_VIDEOS) ids.add(id);
                }
            }
        }
        return new Iterator<>() {
            private Iterator<JsonNode> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && !ids.isEmpty()) current = videoThreads(ids.poll()).iterator();
                return current.hasNext();
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }

    private List<JsonNode> videoThreads(String videoId) {
        Map<String, Object> query = threadQuery();
        query.put("videoId", videoId);
        ApiResponse answer = api.get(YouTubeApi.V3 + "commentThreads", query);
        videosAsked++;
        if (YouTubeRefusals.commentsOff(answer)) {
            commentsOff++;
            return List.of();
        }
        // A video gone since the uploads were listed has nothing to read.
        if (answer.status() == 404) return List.of();
        return Paginator.elements(YouTubeRefusals.json(api, answer).path("items"));
    }

    private static Map<String, Object> threadQuery() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("part", "snippet,replies");
        query.put("order", "time");
        query.put("textFormat", "plainText");
        query.put("maxResults", 100);
        return query;
    }

    private ItemMetadata threadItem(JsonNode thread) {
        String id = thread.path("id").asText("");
        if (id.isEmpty()) return null;
        JsonNode snippet = thread.path("snippet");
        JsonNode comment = snippet.path("topLevelComment").path("snippet");
        String videoId = snippet.path("videoId").asText("");
        String videoTitle = videoTitles.get(videoId);
        ItemMetadata item = new ItemMetadata();
        item.setId("thread:" + id);
        item.setTitle("Comment by " + YouTubeApi.author(comment)
                + (videoTitle != null ? " on " + videoTitle : ""));
        item.setSourceType(ConnectorType.YOUTUBE_CHANNEL);
        item.setLastModified(YouTubeApi.lastActivity(thread, YouTubeApi.inlineReplies(thread)));
        item.setContentType(GoogleApi.MARKDOWN);
        GoogleApi.putExtra(item, ItemMetadata.AUTHOR, YouTubeApi.author(comment));
        if (!videoId.isEmpty()) {
            GoogleApi.putExtra(item, "url", YouTubeApi.commentUrl(videoId, snippet.path("topLevelComment").path("id").asText("")));
        }
        GoogleApi.putExtra(item, "videoTitle", videoTitle);
        return item;
    }

    // At most `max` elements; `capped` is told when there were more.
    private static Iterator<JsonNode> bounded(Iterator<JsonNode> source, int max, Runnable capped) {
        return new Iterator<>() {
            private int taken;

            @Override
            public boolean hasNext() {
                if (taken >= max) {
                    if (source.hasNext()) capped.run();
                    return false;
                }
                return source.hasNext();
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                taken++;
                return source.next();
            }
        };
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (id.startsWith("thread:") && id.length() > "thread:".length()) {
            return GoogleApi.markdown(thread(id.substring("thread:".length()), item.getExtra("videoTitle")));
        }
        if (id.startsWith("video:") && id.length() > "video:".length()) {
            return GoogleApi.markdown(video(id.substring("video:".length())));
        }
        throw new ConnectorException("That is not an item of a YouTube channel.");
    }

    String thread(String threadId, String knownVideoTitle) {
        JsonNode page = get(YouTubeApi.V3 + "commentThreads",
                Map.of("part", "snippet,replies", "id", threadId, "textFormat", "plainText"));
        JsonNode thread = page.path("items").path(0);
        if (thread.isMissingNode() || thread.isNull()) {
            throw new ItemGoneException("YouTube no longer has the comment thread " + threadId + ".");
        }
        List<JsonNode> replies = YouTubeApi.inlineReplies(thread);
        int total = thread.path("snippet").path("totalReplyCount").asInt(replies.size());
        if (total > replies.size()) replies = allReplies(thread.path("snippet").path("topLevelComment").path("id").asText(""));
        String videoId = thread.path("snippet").path("videoId").asText("");
        String videoTitle = knownVideoTitle != null && !knownVideoTitle.isBlank() ? knownVideoTitle : videoTitle(videoId);
        return YouTubeApi.renderThread(thread, replies, channelId(), videoTitle);
    }

    // Every reply to one comment, oldest first as YouTube lists them, up to MAX_REPLIES.
    private List<JsonNode> allReplies(String parentId) {
        if (parentId.isEmpty()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        String next = null;
        do {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("part", "snippet");
            query.put("parentId", parentId);
            query.put("textFormat", "plainText");
            query.put("maxResults", 100);
            if (next != null) query.put("pageToken", next);
            JsonNode page = get(YouTubeApi.V3 + "comments", query);
            for (JsonNode reply : page.path("items")) {
                if (out.size() >= MAX_REPLIES) return out;
                out.add(reply);
            }
            next = page.path("nextPageToken").asText(null);
        } while (next != null && !next.isBlank());
        return out;
    }

    // A video's title, asked by id - for a thread fetched on its own (a live read, or a video past the bound).
    private String videoTitle(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        String known = videoTitles.get(videoId);
        if (known != null) return known;
        try {
            JsonNode video = get(YouTubeApi.V3 + "videos", Map.of("part", "snippet", "id", videoId))
                    .path("items").path(0);
            String title = video.isMissingNode() ? null : YouTubeApi.title(video.path("snippet"));
            if (title != null) videoTitles.put(videoId, title);
            return title;
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    String video(String videoId) {
        JsonNode video = get(YouTubeApi.V3 + "videos", Map.of("part", "snippet,status", "id", videoId))
                .path("items").path(0);
        if (video.isMissingNode() || video.isNull()) {
            throw new ItemGoneException("YouTube no longer has the video " + videoId + ".");
        }
        return YouTubeApi.renderVideo(video);
    }

    // -- the source's name ---------------------------------------------------------------------

    /**
     * The channel's own title and page, from the channel this session already read (the probe or a
     * listing) - so the source is listed under the channel's name rather than "YouTube channel", and
     * renamed when the channel is. Null when the channel was never read.
     */
    @Override
    public SourceLabel label() {
        if (channel == null) return null;
        String title = GoogleApi.text(channel.path("snippet"), "title");
        return new SourceLabel(title.isBlank() ? null : title, YouTubeApi.channelUrl(channel));
    }

    @Override
    public void close() {
        videoTitles.clear();
        details.clear();
        undetailed.clear();
    }
}
