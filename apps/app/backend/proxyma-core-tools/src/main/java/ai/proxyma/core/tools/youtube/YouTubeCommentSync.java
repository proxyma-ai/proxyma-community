package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.chat.channel.ChannelCommentsProvider;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.system.util.Failures;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A YOUTUBE CHANNEL READ FOR THE COMMENT QUEUE (2026-11-05, the YouTube team v2): what needs a decision, read by the
 * server on a schedule, so no agent keeps a cursor, copies a comment or spends a model call when nothing is new. Each
 * comment is read as its conversation ({@link YouTubeConversation}): a viewer answering the owner back, or replying
 * under the owner's own comment, needs a decision as much as a new comment does.
 *
 * <p>A pass, through the one connection named, only when it is the owner's own, serves YouTube and may READ:
 * <ol>
 *   <li>the channel ({@code channels.list mine}, one unit);</li>
 *   <li>the NEWEST threads, at most {@link #NEW_PAGES} pages of a hundred, stopping at a page where nothing is newer
 *       than the last pass began - read video by video when YouTube refuses the channel at once
 *       (YouTubeThreadScan);</li>
 *   <li>a step into the OLDER ones: {@link #BACKLOG_PAGES} page from where the last pass stopped, by the page token
 *       YouTube handed out, newest first, until the end - restarted from the top when YouTube no longer takes the
 *       token. A channel read video by video has no step: its newest uploads are all it reads;</li>
 *   <li>the comments the caller still holds open ({@code recheck}, at most {@link #RECHECK_MAX}), their threads asked
 *       {@link #BATCH} at a time - answered by the channel since, gone, or still waiting;</li>
 *   <li>the titles of the videos the new comments are under - the channel source's index first, with no call, then
 *       {@link #BATCH} a call, remembered (YouTubeVideoTitles).</li>
 * </ol>
 * A thread with more replies than YouTube carries inline is read whole ({@code comments.list parentId=}) at most
 * {@link #DEEP_PER_PASS} times a pass; past that a newest-page thread waits for the next pass and the older step does
 * not move on. QUOTA, a unit a call: 1 + 2 + 1 + 5x2 + 2 + 1 = 17 at worst, about 4 on a quiet channel - at a pass
 * every fifteen minutes, some 400 to 1,600 of the project's 10,000 a day.
 */
@Component
public class YouTubeCommentSync implements ChannelCommentsProvider {

    private static final Logger log = LoggerFactory.getLogger(YouTubeCommentSync.class);

    static final int NEW_PAGES = 2;
    static final int BACKLOG_PAGES = 1;
    static final int DEEP_PER_PASS = 5;
    static final int RECHECK_MAX = 100;
    static final int BATCH = 50;
    /** How many of the newest uploads a pass reads, and how often: one call, at most hourly a connection. */
    static final int NEW_UPLOADS = 5;
    static final java.time.Duration UPLOADS_EVERY = java.time.Duration.ofHours(1);

    /** When each connection's newest uploads were last read. In memory: a restart reads them at its first pass. */
    private final Map<UUID, Instant> uploadsReadAt = new java.util.concurrent.ConcurrentHashMap<>();

    private final ConnectorEndpointResolver endpoints;
    private final ConnectorConnectionService connections;
    private final SourceContextFactory contexts;
    private final ConnectorHttp http;
    private final YouTubeTools tools;
    private YouTubeVideoTitles titles = YouTubeVideoTitles.memoryOnly();
    private Clock clock = Clock.systemUTC();

    public YouTubeCommentSync(ConnectorEndpointResolver endpoints, ConnectorConnectionService connections,
                              SourceContextFactory contexts, ConnectorHttp http, YouTubeTools tools) {
        this.endpoints = endpoints;
        this.connections = connections;
        this.contexts = contexts;
        this.http = http;
        this.tools = tools;
    }

    /** Tests only. */
    void setClock(Clock clock) {
        this.clock = clock;
        titles.setClock(clock);
    }

    /** Where a video's title is found: the channel source's index, what was read before, then YouTube. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTitles(YouTubeVideoTitles titles) {
        if (titles != null) this.titles = titles;
    }

    @Override
    public String service() {
        return ConnectorType.YOUTUBE_CHANNEL.wireName();
    }

    // The owner's own connection, serving YouTube and allowed to read, as its credentials; null otherwise.
    private ResolvedCredentials credentials(String ownerUserId, UUID connectionId) {
        if (connectionId == null) return null;
        ConnectorEndpointResolver.Endpoint endpoint = endpoints.byId(ConnectorType.YOUTUBE_CHANNEL, connectionId, ownerUserId);
        if (endpoint == null || !endpoint.exists() || !endpoint.allowRead()) return null;
        ResolvedConnection connection = connections.resolveOwned(ownerUserId, connectionId).orElse(null);
        return connection == null ? null : contexts.credentials(connection);
    }

    /** The approval cards, handed every thread a pass reads (YouTubeApprovalContext.heldFromSync); optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCards(org.springframework.beans.factory.ObjectProvider<YouTubeApprovalContext> cards) {
        this.cards = cards;
    }

    private org.springframework.beans.factory.ObjectProvider<YouTubeApprovalContext> cards;

    // The threads the pass on this thread has read so far, for the approval cards.
    private final ThreadLocal<List<JsonNode>> passThreads = new ThreadLocal<>();

    @Override
    public Pass sync(String ownerUserId, UUID connectionId, Cursor cursor, Collection<String> recheck) {
        List<JsonNode> seen = new ArrayList<>();
        passThreads.set(seen);
        try {
            return pass(ownerUserId, connectionId, cursor, recheck);
        } finally {
            passThreads.remove();
            // What the pass read is what a draft's card would ask YouTube for again (2026-11-11: one call a draft).
            YouTubeApprovalContext c = cards == null ? null : cards.getIfAvailable();
            if (c != null && connectionId != null && !seen.isEmpty()) c.heldFromSync(connectionId, seen);
        }
    }

    private Pass pass(String ownerUserId, UUID connectionId, Cursor cursor, Collection<String> recheck) {
        Cursor from = cursor == null ? Cursor.START : cursor;
        Instant began = clock.instant();
        ApiClient api;
        try {
            ResolvedCredentials credentials = credentials(ownerUserId, connectionId);
            if (credentials == null) return new Pass(null, null, List.of(), Map.of(), from, 0, "No YouTube connection to read.");
            api = YouTubeTools.client(http, credentials, false);
        } catch (RuntimeException e) {
            return new Pass(null, null, List.of(), Map.of(), from, 0, Failures.describe(e));
        }
        int[] calls = {0};
        JsonNode channel;
        try {
            // Through YouTubeCalls, so a spent daily quota is said as the limit it is (2026-11-11: every pass while the
            // quota was spent logged "refused access with the connection's credential", which reads as a sign-in problem).
            channel = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(YouTubeTools.V3 + "channels",
                    Map.of("part", "snippet,contentDetails", "mine", "true"))).path("items").path(0);
            calls[0]++;
        } catch (RuntimeException e) {
            return new Pass(null, null, List.of(), Map.of(), from, 1, Failures.describe(e));
        }
        String channelId = channel.path("id").asText("");
        if (channelId.isBlank()) {
            return new Pass(null, null, List.of(), Map.of(), from, calls[0], "The connection's Google account has no YouTube channel.");
        }
        String channelTitle = channel.path("snippet").path("title").asText("");
        Map<String, YouTubeConversation.Pending> pending = new LinkedHashMap<>();
        Map<String, Settled> settled = new LinkedHashMap<>();
        int[] deep = {DEEP_PER_PASS};

        // The newest pages.
        YouTubeThreadScan scan = YouTubeThreadScan.channel(api, channel, NEW_PAGES);
        try {
            List<JsonNode> threads;
            while ((threads = scan.next()) != null) {
                boolean newer = false;
                for (JsonNode thread : threads) {
                    if (touches(thread, from.newSince())) newer = true;
                    read(api, thread, channelId, deep, calls, pending, settled);
                }
                if (from.newSince() != null && !newer) scan.olderSeen();
            }
        } catch (RuntimeException e) {
            calls[0] += scan.calls();
            return new Pass(channelId, channelTitle, List.of(), Map.of(), from, calls[0], Failures.describe(e));
        }
        calls[0] += scan.calls();

        // A step into the older ones.
        String backlogToken = from.backlogToken();
        boolean backlogDone = from.backlogDone();
        if (scan.perVideo()) {
            backlogDone = true;
            backlogToken = null;
        } else if (!backlogDone) {
            YouTubeThreadScan older = YouTubeThreadScan.channel(api, channel, BACKLOG_PAGES).startAt(backlogToken);
            try {
                List<JsonNode> threads = older.next();
                boolean whole = true;
                if (threads != null) {
                    for (JsonNode thread : threads) whole &= read(api, thread, channelId, deep, calls, pending, settled);
                }
                // A page whose long threads could not all be read is read again next pass.
                if (whole) {
                    backlogToken = older.nextToken();
                    backlogDone = backlogToken == null;
                }
            } catch (RuntimeException e) {
                // YouTube no longer takes the token (or failed): the walk starts again from the top next pass.
                log.info("[YouTube] the comment queue's walk into older comments restarts: {}", e.getMessage());
                backlogToken = null;
            }
            calls[0] += older.calls();
        }

        // The comments still held open.
        Set<String> asked = new LinkedHashSet<>();
        if (recheck != null) {
            for (String id : recheck) {
                if (asked.size() >= RECHECK_MAX) break;
                String c = YouTubeTools.parentId(id);
                if (c != null && !settled.containsKey(c) && !pending.containsKey(c)) asked.add(c);
            }
        }
        recheck(api, channelId, asked, deep, calls, pending, settled);

        // The titles of the videos the comments needing a decision are under.
        Set<String> videos = new LinkedHashSet<>();
        for (YouTubeConversation.Pending p : pending.values()) if (!p.videoId().isEmpty()) videos.add(p.videoId());
        Map<String, String> titleOf = titles.titles(api, ownerUserId, connectionId, videos, calls);

        List<Comment> out = new ArrayList<>();
        for (YouTubeConversation.Pending p : pending.values()) out.add(comment(p, titleOf.get(p.videoId())));
        Cursor next = new Cursor(backlogToken, from.backlogBefore(), backlogDone, began);
        return new Pass(channelId, channelTitle, out, settled, next, calls[0], null,
                newUploads(api, channel, connectionId, began, calls));
    }

    /**
     * NEW VIDEOS FIRST (2026-11-10): the channel's {@link #NEW_UPLOADS} newest uploads and when each was published - one
     * {@code playlistItems.list}, at most every {@link #UPLOADS_EVERY} a connection, since the quota a Google project has
     * is shared. Null when not due or not read: the caller keeps what it had.
     */
    private Map<String, Instant> newUploads(ApiClient api, JsonNode channel, UUID connectionId, Instant now, int[] calls) {
        String uploads = channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        if (uploads.isBlank() || connectionId == null) return null;
        Instant last = uploadsReadAt.get(connectionId);
        if (last != null && last.plus(UPLOADS_EVERY).isAfter(now)) return null;
        try {
            calls[0]++;
            JsonNode page = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(YouTubeTools.V3 + "playlistItems",
                    Map.of("part", "contentDetails", "playlistId", uploads, "maxResults", String.valueOf(NEW_UPLOADS))));
            Map<String, Instant> out = new LinkedHashMap<>();
            for (JsonNode item : page.path("items")) {
                String id = item.path("contentDetails").path("videoId").asText("");
                String at = item.path("contentDetails").path("videoPublishedAt").asText("");
                if (id.isEmpty() || at.isEmpty()) continue;
                try {
                    out.put(id, Instant.parse(at));
                } catch (java.time.format.DateTimeParseException e) {
                    // A time YouTube did not state is no time.
                }
            }
            uploadsReadAt.put(connectionId, now);
            return out;
        } catch (RuntimeException e) {
            log.debug("[YouTube] the newest uploads could not be read: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One thread: its messages needing a decision into {@code pending}, and where every other message the channel
     * answered stands into {@code settled}. False when it was a long thread that could not be read whole this pass.
     */
    private boolean read(ApiClient api, JsonNode thread, String channelId, int[] deep, int[] calls,
                         Map<String, YouTubeConversation.Pending> pending, Map<String, Settled> settled) {
        List<JsonNode> seen = passThreads.get();
        if (seen != null) seen.add(thread);
        List<JsonNode> replies = YouTubeConversation.inlineReplies(thread);
        if (YouTubeConversation.truncated(thread)) {
            if (deep[0] <= 0) return false;
            deep[0]--;
            replies = YouTubeConversation.replies(api, thread.path("id").asText(""), YouTubeConversation.REPLY_PAGES);
            calls[0] += Math.max(1, (replies.size() + 99) / 100);
        }
        List<YouTubeConversation.Message> all = YouTubeConversation.messages(thread, replies, channelId);
        List<YouTubeConversation.Pending> waiting = YouTubeConversation.pending(thread, replies, channelId);
        Set<String> waitingIds = new LinkedHashSet<>();
        for (YouTubeConversation.Pending p : waiting) {
            waitingIds.add(p.message().commentId());
            pending.put(p.message().commentId(), p);
            settled.put(p.message().commentId(), new Settled(Settled.WAITING, null));
        }
        for (YouTubeConversation.Message m : all) {
            if (m.byChannel() || m.commentId().isEmpty() || waitingIds.contains(m.commentId())) continue;
            YouTubeConversation.Message answer = YouTubeConversation.answeredBy(all, m.commentId());
            if (answer != null) {
                settled.put(m.commentId(), new Settled(Settled.ANSWERED,
                        YouTubeConversation.bounded(answer.text(), YouTubeConversation.CONTEXT_TEXT)));
            }
        }
        return true;
    }

    // The comments still held open, by their threads, BATCH a call: a thread YouTube no longer has is gone with all its
    // comments; a reply a thread read whole no longer holds is gone; the rest answered or waiting.
    private void recheck(ApiClient api, String channelId, Set<String> ids, int[] deep, int[] calls,
                         Map<String, YouTubeConversation.Pending> pending, Map<String, Settled> settled) {
        if (ids.isEmpty()) return;
        Map<String, List<String>> byThread = new LinkedHashMap<>();
        for (String id : ids) byThread.computeIfAbsent(YouTubeApprovalContext.threadOf(id), k -> new ArrayList<>()).add(id);
        List<String> threadIds = new ArrayList<>(byThread.keySet());
        for (int i = 0; i < threadIds.size(); i += BATCH) {
            List<String> batch = threadIds.subList(i, Math.min(threadIds.size(), i + BATCH));
            Map<String, JsonNode> found = new LinkedHashMap<>();
            try {
                for (JsonNode t : YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(YouTubeTools.V3 + "commentThreads",
                        Map.of("part", "snippet,replies", "id", String.join(",", batch), "textFormat", "plainText")))
                        .path("items")) {
                    found.put(t.path("id").asText(""), t);
                }
                calls[0]++;
            } catch (RuntimeException e) {
                // This batch could not be read: its comments stand as they were.
                continue;
            }
            for (String threadId : batch) {
                JsonNode thread = found.get(threadId);
                if (thread == null) {
                    for (String id : byThread.get(threadId)) settled.put(id, new Settled(Settled.GONE, null));
                    continue;
                }
                boolean whole;
                try {
                    whole = read(api, thread, channelId, deep, calls, pending, settled);
                } catch (RuntimeException e) {
                    continue;
                }
                for (String id : byThread.get(threadId)) {
                    if (settled.containsKey(id)) continue;
                    // Not waiting and not answered: a top-level comment is waiting no more only when answered, so a
                    // reply missing from a thread read whole is gone; anything else stays as it was.
                    boolean inline = YouTubeConversation.contains(YouTubeConversation.messages(thread,
                            YouTubeConversation.inlineReplies(thread), channelId), id);
                    if (whole && !YouTubeConversation.truncated(thread) && !inline) settled.put(id, new Settled(Settled.GONE, null));
                }
            }
        }
    }

    // Whether anything in a thread - its comment or a reply it carries inline - is newer than the last pass.
    private static boolean touches(JsonNode thread, Instant since) {
        if (since == null) return true;
        Instant top = YouTubeConversation.instant(thread.path("snippet").path("topLevelComment").path("snippet")
                .path("publishedAt").asText(""));
        if (top == null || top.isAfter(since)) return true;
        for (JsonNode r : YouTubeConversation.inlineReplies(thread)) {
            Instant at = YouTubeConversation.instant(r.path("snippet").path("publishedAt").asText(""));
            if (at == null || at.isAfter(since)) return true;
        }
        return false;
    }

    private static Comment comment(YouTubeConversation.Pending p, String videoTitle) {
        YouTubeConversation.Message m = p.message();
        List<Message> before = new ArrayList<>();
        for (YouTubeConversation.Message b : p.before()) before.add(new Message(b.author(), b.byChannel(), b.text()));
        return new Comment(m.commentId(), p.threadId(), p.reply(), p.videoId().isEmpty() ? null : p.videoId(), videoTitle,
                m.author(), m.authorChannelId(), m.authorUrl(), m.text(), m.publishedAt(), before, p.inReplyTo());
    }

    /** The most channel ids one call asks for: channels.list takes fifty at one quota unit. */
    static final int VIEWERS_PER_CALL = 50;

    @Override
    public Map<String, Viewer> viewers(String ownerUserId, UUID connectionId, Collection<String> channelIds) {
        List<String> ids = channelIds == null ? List.of() : channelIds.stream()
                .filter(id -> id != null && YouTubeAvatar.CHANNEL_ID.matcher(id).matches()).distinct().limit(VIEWERS_PER_CALL).toList();
        if (ids.isEmpty()) return Map.of();
        ResolvedCredentials credentials;
        try {
            credentials = credentials(ownerUserId, connectionId);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (credentials == null) return Map.of();
        Map<String, Viewer> out = new java.util.LinkedHashMap<>();
        try {
            ApiClient data = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.DATA);
            com.fasterxml.jackson.databind.JsonNode items = YouTubeCalls.json(data, YouTubeCalls.Api.DATA,
                    // By id alone: channels.list takes no maxResults beside an id list, and refuses the call with one.
                    data.get(YouTubeTools.V3 + "channels", Map.of("part", "snippet", "id", String.join(",", ids)))).path("items");
            for (com.fasterxml.jackson.databind.JsonNode c : items) {
                String id = c.path("id").asText("");
                if (!ids.contains(id)) continue;
                com.fasterxml.jackson.databind.JsonNode s = c.path("snippet");
                String name = s.path("title").asText("").strip();
                String handle = s.path("customUrl").asText("").strip();
                // The 88px picture: a viewer's is drawn small.
                String avatar = YouTubeAvatar.inline(http, s.path("thumbnails").path("default").path("url").asText(null));
                out.put(id, new Viewer(name.isEmpty() ? null : name, handle.isEmpty() ? null : handle, avatar));
            }
        } catch (RuntimeException e) {
            log.info("[YouTube] {} viewers' channels could not be read: {}", ids.size(), e.getMessage());
        }
        return out;
    }

    @Override
    public Map<String, String> videoTitles(String ownerUserId, UUID connectionId, Collection<String> videoIds, boolean ask) {
        if (connectionId == null) return Map.of();
        ResolvedConnection connection;
        try {
            // Only through the owner's own connection that may read: nothing of another's is lent, held titles included.
            ConnectorEndpointResolver.Endpoint endpoint = endpoints.byId(ConnectorType.YOUTUBE_CHANNEL, connectionId, ownerUserId);
            if (endpoint == null || !endpoint.exists() || !endpoint.allowRead()) return Map.of();
            connection = connections.resolveOwned(ownerUserId, connectionId).orElse(null);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (connection == null) return Map.of();
        if (!ask) return titles.held(ownerUserId, connectionId, videoIds);
        ApiClient api = null;
        try {
            api = YouTubeTools.client(http, contexts.credentials(connection), true);
        } catch (RuntimeException e) {
            // YouTube cannot be asked now: what is held.
        }
        return titles.titles(api, ownerUserId, connectionId, videoIds, null);
    }

    @Override
    public Numbers numbers(String ownerUserId, UUID connectionId, LocalDate end) {
        LocalDate start = end.minusDays(27);
        ResolvedCredentials credentials;
        try {
            credentials = credentials(ownerUserId, connectionId);
        } catch (RuntimeException e) {
            return new Numbers(null, null, null, null, null, start, end, Failures.describe(e));
        }
        if (credentials == null) return new Numbers(null, null, null, null, null, start, end, "No YouTube connection to read.");
        ApiClient data = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.DATA);
        // The channel's picture with its numbers, downloaded here and carried inline (YouTubeAvatar): nothing asks
        // YouTube for it again until tomorrow, and no page loads it from Google.
        com.fasterxml.jackson.databind.JsonNode[] snippet = new com.fasterxml.jackson.databind.JsonNode[1];
        Map<String, Object> stats = quietly(() -> tools.channelStats(data, s -> snippet[0] = s));
        Map<String, Object> channel = stats;
        String avatar = stats == null ? null : YouTubeAvatar.inline(http, YouTubeAvatar.url(snippet[0]));
        if (avatar != null) {
            channel = new java.util.LinkedHashMap<>(stats);
            channel.put("avatar", avatar);
        }
        Map<String, Object> videos = quietly(() -> tools.videos(data, 10, null, false));
        Map<String, Object> current = null;
        Map<String, Object> previous = null;
        Map<String, Object> reach = null;
        if (!YouTubeCalls.analyticsNotGranted(credentials)) {
            ApiClient analytics = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.ANALYTICS);
            current = quietly(() -> YouTubeAnalytics.run(analytics, data, YouTubeAnalytics.Report.OVERVIEW, start, end, null));
            previous = quietly(() -> YouTubeAnalytics.run(analytics, data, YouTubeAnalytics.Report.OVERVIEW,
                    start.minusDays(28), start.minusDays(1), null));
            ApiClient reporting = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.REPORTING);
            reach = quietly(() -> tools.reach(reporting, data, connectionId, start, end));
        }
        String error = channel == null && videos == null && current == null ? "YouTube could not be read." : null;
        return new Numbers(channel, current, previous, videos, reach, start, end, error);
    }

    // A report, or null when it failed or said it failed.
    private static Map<String, Object> quietly(java.util.function.Supplier<Map<String, Object>> read) {
        try {
            Map<String, Object> out = read.get();
            return out == null || Boolean.FALSE.equals(out.get("success")) ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
