package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.GoogleError;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The channel's newest comment threads, a batch at a time, for the tools that look through them
 * (listYouTubeComments, getYouTubeViewerHistory, listYouTubeChannelReplies).
 *
 * <p><b>The channel at once</b>, normally: {@code commentThreads.list} with
 * {@code allThreadsRelatedToChannelId}, newest first, 100 a page, at most {@code maxPages} pages.
 *
 * <p><b>Video by video, when YouTube refuses that</b> (2026-11-03, the maintainer's second channel: its
 * overview answered, every comment read was a 403). A channel can refuse the all-threads listing -
 * {@code commentsDisabled} when comments are off on the channel or on some of its videos (a video made
 * for kids has none), {@code forbidden} otherwise - while each video's own comments still read. Then the
 * newest {@link #FALLBACK_VIDEOS} uploads are read one at a time ({@code commentThreads.list videoId=},
 * one page of 100 each, newest first), a video whose comments are off is skipped and counted, and a batch
 * is one video's threads - so the threads come grouped by video, the newest video first.
 *
 * <p><b>Below a point</b> (2026-11-03, the maintainer: comments from "long long time ago also need to be
 * replied", the latest first - {@link #before}). Read at once, the threads still come newest first, so the
 * pages wholly newer than the point are passed over to reach it: at most {@link #SKIP_PAGES}, which do not
 * count against {@code maxPages}; past that the point is out of reach ({@link #beyondReach()}). Read video
 * by video, only uploads published before the point are read - a video has no comment older than itself -
 * the uploads paged as they are needed, 50 a page and at most {@link #UPLOAD_PAGES} pages, each video's
 * threads whole (at most {@link #VIDEO_PAGES} pages) as one batch, at most {@link #DEEP_VIDEO_CALLS}
 * calls for them in all.
 *
 * <p><b>Quota.</b> Every call costs one unit. The fallback adds the refused call, one
 * {@code playlistItems.list} and at most {@link #FALLBACK_VIDEOS} {@code commentThreads.list} - 22 units
 * at most, and fewer when the caller stops at its limit. Below a point: at most {@link #SKIP_PAGES} +
 * {@code maxPages} read at once (70 for listYouTubeComments); video by video the refused call,
 * {@link #UPLOAD_PAGES} and {@link #DEEP_VIDEO_CALLS} (71). {@link #calls()} counts them.
 */
final class YouTubeThreadScan {

    /** How many of the newest uploads are read one by one when the channel cannot be read at once. */
    static final int FALLBACK_VIDEOS = 20;
    /** Below a point, read at once: how many pages wholly newer than it may be passed over to reach it. */
    static final int SKIP_PAGES = 50;
    /** Below a point, video by video: pages of the uploads (50 a page), pages of one video, and video calls in all. */
    static final int UPLOAD_PAGES = 20;
    static final int VIDEO_PAGES = 5;
    static final int DEEP_VIDEO_CALLS = 50;

    private final ApiClient api;
    private final String channelId;
    private final String uploads;
    // One video's threads instead of the channel's: paged like the channel's, never read video by video.
    private final String videoId;
    private final int maxPages;

    private String next;
    private int pages;
    private boolean done;
    private boolean first = true;
    private boolean perVideo;
    // The uploads to read, and when each was published (null when the listing did not say).
    private final LinkedList<String> videos = new LinkedList<>();
    private final LinkedList<Instant> videoDates = new LinkedList<>();
    // The uploads had more than were listed, or a video more than was read of its threads.
    private boolean videosLeft;
    private boolean videoTruncated;
    private int videosRead;
    private int commentsOff;
    private int calls;

    // Below a point: the point, the pages passed over, whether it is out of reach, and - video by video -
    // the uploads' next page ("" for the first, null when there is none) and the calls spent on videos.
    private Instant before;
    private int skipped;
    private boolean beyondReach;
    private String uploadsNext;
    private int uploadPages;
    private int videoCalls;

    // Video by video: the batch just returned - its video, when it was published, whether it is all its
    // threads - and the last video the latest next() passed over without a batch.
    private String batchVideo;
    private Instant batchVideoAt;
    private boolean batchWhole = true;
    private Instant passedAt;

    private YouTubeThreadScan(ApiClient api, String channelId, String uploads, String videoId, int maxPages) {
        this.api = api;
        this.channelId = channelId;
        this.uploads = uploads == null ? "" : uploads;
        this.videoId = videoId;
        this.maxPages = maxPages;
    }

    /** The channel's threads: at once, else - refused - its newest uploads one by one. */
    static YouTubeThreadScan channel(ApiClient api, JsonNode channel, int maxPages) {
        return new YouTubeThreadScan(api, channel.path("id").asText(""),
                channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText(""), null, maxPages);
    }

    /** One video's threads; a refusal is the video's, said as it is. */
    static YouTubeThreadScan video(ApiClient api, String videoId, int maxPages) {
        return new YouTubeThreadScan(api, "", "", videoId, maxPages);
    }

    /**
     * Only threads published before this point are wanted (exclusive): read at once, the pages wholly newer
     * are passed over; read video by video, only uploads published before it are read. Set before the first
     * {@link #next()}.
     */
    YouTubeThreadScan before(Instant point) {
        this.before = point;
        return this;
    }

    /**
     * Read at once, start at this page the channel's listing handed out before (a comment queue walking the older
     * comments a page a pass, YouTubeCommentSync); null starts at the newest. Set before the first {@link #next()}.
     */
    YouTubeThreadScan startAt(String pageToken) {
        this.next = pageToken == null || pageToken.isBlank() ? null : pageToken;
        return this;
    }

    /** Read at once, the page after the last one read - what {@link #startAt} takes next time - or null at the end. */
    String nextToken() {
        return perVideo ? null : next;
    }

    /** The next batch of threads, newest first within it; null once there are no more within the bound. */
    List<JsonNode> next() {
        passedAt = null;
        if (done) return null;
        if (!perVideo) return nextAtOnce();
        if (before != null) return nextVideoWhole();
        while (!videos.isEmpty()) {
            String id = videos.poll();
            Instant at = videoDates.poll();
            Map<String, Object> query = query();
            query.put("videoId", id);
            ApiResponse answer = api.get(YouTubeTools.V3 + "commentThreads", query);
            calls++;
            videosRead++;
            if (answer.status() == 403 && GoogleError.has(answer, YouTubeCalls.COMMENTS_DISABLED)) {
                commentsOff++;
                passedAt = at;
                continue;
            }
            // A video gone since the uploads were listed has nothing to read.
            if (answer.status() == 404) {
                passedAt = at;
                continue;
            }
            JsonNode page = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, answer);
            boolean whole = page.path("nextPageToken").asText("").isBlank();
            if (!whole) videoTruncated = true;
            batch(id, at, whole);
            return items(page);
        }
        done = true;
        return null;
    }

    private List<JsonNode> nextAtOnce() {
        while (true) {
            if (pages >= maxPages) {
                done = true;
                return null;
            }
            Map<String, Object> query = query();
            if (videoId != null) query.put("videoId", videoId);
            else query.put("allThreadsRelatedToChannelId", channelId);
            if (next != null) query.put("pageToken", next);
            ApiResponse answer = api.get(YouTubeTools.V3 + "commentThreads", query);
            calls++;
            boolean firstCall = first;
            first = false;
            if (firstCall && videoId == null && refusedForTheChannel(answer)) {
                startPerVideo();
                return next();
            }
            JsonNode page = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, answer);
            next = page.path("nextPageToken").asText("");
            if (next.isBlank()) {
                next = null;
                done = true;
            }
            List<JsonNode> items = items(page);
            if (before != null && !items.isEmpty() && items.stream().noneMatch(this::beforePoint)) {
                // Wholly newer than the point: passed over, not looked through.
                skipped++;
                if (done) return null;
                if (skipped >= SKIP_PAGES) {
                    beyondReach = true;
                    next = null;
                    done = true;
                    return null;
                }
                continue;
            }
            pages++;
            return items;
        }
    }

    // Below a point, video by video: the next upload published before it, its threads whole (bounded), as
    // one batch. The uploads are paged as they are needed.
    private List<JsonNode> nextVideoWhole() {
        while (true) {
            if (videoCalls >= DEEP_VIDEO_CALLS) {
                // The bound: what is left is left for the next call.
                videosLeft = videosLeft || !videos.isEmpty() || uploadsNext != null;
                done = true;
                return null;
            }
            if (videos.isEmpty()) {
                if (uploadsNext == null || uploadPages >= UPLOAD_PAGES) {
                    if (uploadsNext != null) videosLeft = true;
                    uploadsNext = null;
                    done = true;
                    return null;
                }
                if (!listUploads(uploadsNext.isEmpty() ? null : uploadsNext, 50)) {
                    done = true;
                    return null;
                }
                continue;
            }
            String id = videos.poll();
            Instant at = videoDates.poll();
            // A video has no comment older than itself.
            if (at != null && !at.isBefore(before)) continue;
            List<JsonNode> threads = new ArrayList<>();
            String token = null;
            int read = 0;
            boolean off = false;
            boolean gone = false;
            do {
                Map<String, Object> query = query();
                query.put("videoId", id);
                if (token != null) query.put("pageToken", token);
                ApiResponse answer = api.get(YouTubeTools.V3 + "commentThreads", query);
                calls++;
                videoCalls++;
                read++;
                if (answer.status() == 403 && GoogleError.has(answer, YouTubeCalls.COMMENTS_DISABLED)) {
                    off = true;
                    break;
                }
                if (answer.status() == 404) {
                    gone = true;
                    break;
                }
                JsonNode page = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, answer);
                threads.addAll(items(page));
                token = page.path("nextPageToken").asText("");
                if (token.isBlank()) token = null;
            } while (token != null && read < VIDEO_PAGES && videoCalls < DEEP_VIDEO_CALLS);
            videosRead++;
            if (off || gone) {
                if (off) commentsOff++;
                passedAt = at;
                continue;
            }
            if (token != null) videoTruncated = true;
            batch(id, at, token == null);
            return threads;
        }
    }

    private void batch(String id, Instant at, boolean whole) {
        batchVideo = id;
        batchVideoAt = at;
        batchWhole = whole;
    }

    /**
     * A thread older than the caller's bound was seen. Read at once, nothing past it is newer, so no
     * further page is asked for; read video by video, only that video's list was past it - the next
     * video may have newer threads.
     */
    void olderSeen() {
        if (perVideo) return;
        next = null;
        done = true;
    }

    /** True when threads were left unread: another page, another upload, or a video's older threads. */
    boolean more() {
        return perVideo ? !videos.isEmpty() || videosLeft || videoTruncated || uploadsNext != null : next != null;
    }

    /** True when the point given to {@link #before} lies deeper than a listing reaches at once. */
    boolean beyondReach() {
        return beyondReach;
    }

    /** True when the channel could not be read at once and was read video by video. */
    boolean perVideo() {
        return perVideo;
    }

    /** Video by video: the video of the batch just returned. */
    String batchVideo() {
        return batchVideo;
    }

    /** Video by video: when the batch's video was published, or null when the uploads did not say. */
    Instant batchVideoAt() {
        return batchVideoAt;
    }

    /** Video by video: whether the batch is all its video's threads. */
    boolean batchWhole() {
        return batchWhole;
    }

    /**
     * Video by video: when the last video the latest {@link #next()} passed over without a batch - its
     * comments off, or gone - was published, or null.
     */
    Instant passedAt() {
        return passedAt;
    }

    /** How many videos were read one by one. */
    int videosRead() {
        return videosRead;
    }

    /** How many of those have comments turned off. */
    int commentsOff() {
        return commentsOff;
    }

    /** True when every video read has comments turned off - or the channel has no upload to read. */
    boolean noReadableComments() {
        return perVideo && commentsOff == videosRead && done && !more();
    }

    /** The quota units this scan spent. */
    int calls() {
        return calls;
    }

    /**
     * What the tool's result says about how the channel was read, or null when it was read at once.
     */
    String note() {
        if (!perVideo) return null;
        if (noReadableComments()) {
            return videosRead == 0
                    ? "YouTube does not list this channel's comments, and the channel has no upload to read them from, so "
                            + "there are no comments to read."
                    : "Comments are turned off on all of the channel's newest " + videosRead + " videos (videos made for "
                            + "kids have none), so the channel has no comments to read.";
        }
        return "YouTube does not list this channel's comments all at once, so its newest videos were read one by one: "
                + videosRead + " read, " + commentsOff + " with comments turned off. Comments come grouped by video, the "
                + "newest video first.";
    }

    // The all-threads listing refused while the channel itself answered: comments off somewhere, or
    // the listing forbidden - each video is still asked on its own.
    private static boolean refusedForTheChannel(ApiResponse answer) {
        return answer.status() == 403 && GoogleError.has(answer, YouTubeCalls.COMMENTS_DISABLED, "forbidden");
    }

    private void startPerVideo() {
        perVideo = true;
        next = null;
        if (uploads.isBlank()) return;
        if (before != null) {
            // Paged as the videos are needed (nextVideoWhole); "" is the first page.
            uploadsNext = "";
            return;
        }
        if (!listUploads(null, FALLBACK_VIDEOS)) return;
        while (videos.size() > FALLBACK_VIDEOS) {
            videos.pollLast();
            videoDates.pollLast();
            videosLeft = true;
        }
    }

    // One page of the uploads, newest first, onto the videos to read; false when the channel has none.
    private boolean listUploads(String token, int size) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("part", "contentDetails");
        query.put("playlistId", uploads);
        query.put("maxResults", size);
        if (token != null) query.put("pageToken", token);
        ApiResponse listed = api.get(YouTubeTools.V3 + "playlistItems", query);
        calls++;
        uploadPages++;
        uploadsNext = null;
        // A channel that never uploaded has no uploads playlist to read.
        if (listed.status() == 404) return false;
        JsonNode page = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, listed);
        for (JsonNode item : page.path("items")) {
            String id = item.path("contentDetails").path("videoId").asText("");
            if (id.isEmpty()) continue;
            videos.add(id);
            videoDates.add(at(item.path("contentDetails").path("videoPublishedAt").asText("")));
        }
        String more = page.path("nextPageToken").asText("");
        if (!more.isBlank()) {
            if (before != null) uploadsNext = more;
            else videosLeft = true;
        }
        return true;
    }

    // A thread published before the point (one whose time cannot be read counts as before it).
    private boolean beforePoint(JsonNode thread) {
        Instant at = at(thread.path("snippet").path("topLevelComment").path("snippet").path("publishedAt").asText(""));
        return at == null || at.isBefore(before);
    }

    private static Instant at(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<String, Object> query() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("part", "snippet,replies");
        query.put("order", "time");
        query.put("textFormat", "plainText");
        query.put("maxResults", 100);
        return query;
    }

    private static List<JsonNode> items(JsonNode page) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode thread : page.path("items")) out.add(thread);
        return out;
    }
}
