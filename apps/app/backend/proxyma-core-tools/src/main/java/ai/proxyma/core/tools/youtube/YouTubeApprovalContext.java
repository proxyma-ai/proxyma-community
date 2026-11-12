package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a YouTube reply waiting for approval is about (2026-10-31, the maintainer: "which video, which
 * user with which comment and what the agent wants to comment"): the video, the viewer, their comment
 * and the reply - in that order, for the Approvals page and the chat's approval card - and, after the
 * comment, when it was written ({@link #COMMENTED_AT}).
 *
 * <p>Looked up through the conversation's connection resolved for READ (as listYouTubeComments is),
 * and only through one the conversation's USER owns. The comment's THREAD first (commentThreads.list
 * id=): a reply always answers a top-level comment, whose ID is its thread's, and only the thread says
 * which video it is under - a comment read alone (comments.list) carries no videoId, which is why the
 * page showed no video at all (2026-11-02). A comment that is not a thread's - a reply named by its own
 * ID - is read alone, and its video off its parent's thread. Then the video's title (videos.list),
 * remembered for {@link #TITLE_TTL} per connection, so twenty drafts on one video ask for it once: one
 * or two quota units a request. Since 2026-11-06 a title Proxyma already holds - the channel source's index, or any
 * YouTube lookup's (YouTubeVideoTitles) - is used with no videos.list at all when the thread names the video's channel. Since 2026-11-05 a card is WHOLE or there is none ({@link #REQUIRED}):
 * anything that cannot be filled throws {@link ApprovalContextProvider.Unavailable}, and the call is
 * refused before it is kept - a video that cannot be read is no longer shown by its ID.
 *
 * <p>The video links to the video, the comment to itself on it ({@code &lc=}), so the page can offer
 * both beside the viewer.
 *
 * <p><b>ONE CHANNEL</b> (2026-11-05, the maintainer: a team made for @camamtieudao had drafts waiting for comments
 * on @lesyhau's videos - it began on that channel's connection and was switched): every reply says which channel it
 * is posted AS ({@code replyAs}, the connection's channel, linked); a reply to a comment whose video is on another
 * channel than the one the conversation's connection posts as is refused before it is kept ({@link #refusal}); and a
 * waiting one whose comment is not the channel's the conversation uses NOW - drafted before its project moved to
 * another channel - is withdrawn as {@link #OTHER_CHANNEL} by the inbox's check ({@link #settled}). The comment's
 * channel is its thread's {@code snippet.channelId}; one YouTube does not state is not refused.
 */
@Component
public class YouTubeApprovalContext implements ApprovalContextProvider {

    static final String TOOL = "replyToYouTubeComment";
    /** The field carrying when the comment was written, an ISO time (approvalContext.ts shows it as an age). */
    static final String COMMENTED_AT = "commentedAt";
    /** The field naming the channel the reply is posted as, linked (approvalContext.ts shows it as "Reply as"). */
    static final String REPLY_AS = "replyAs";
    static final Duration TITLE_TTL = Duration.ofMinutes(30);
    static final int TITLES_MAX = 500;

    private final ToolConnectorResolver resolver;
    private final ConnectorConnectionService connections;
    private final SourceContextFactory contexts;
    private final ConnectorHttp http;
    private final Map<String, Title> titles = new ConcurrentHashMap<>();
    private YouTubeVideoTitles videoTitles = YouTubeVideoTitles.memoryOnly();
    private Clock clock = Clock.systemUTC();

    private record Title(Video value, Instant at) {}

    public YouTubeApprovalContext(ToolConnectorResolver resolver, ConnectorConnectionService connections,
                                  SourceContextFactory contexts, ConnectorHttp http) {
        this.resolver = resolver;
        this.connections = connections;
        this.contexts = contexts;
        this.http = http;
    }

    /** Tests only: the clock a remembered title's age is read on. */
    void setClock(Clock clock) {
        this.clock = clock;
        videoTitles.setClock(clock);
    }

    /** Where a video's title is held with no call (the channel source's index) and remembered for every lookup. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVideoTitles(YouTubeVideoTitles videoTitles) {
        if (videoTitles != null) this.videoTitles = videoTitles;
    }

    /**
     * EVERY CARD IS WHOLE (2026-11-05, the maintainer: "Some requests are lack information. The cards must always have
     * all the fields"): the video by its title, the viewer, their comment, the channel the reply is posted as and the
     * reply. A call whose card would lack one is refused before it is kept (ApprovalContexts.prepare), and a waiting one
     * is looked up again and, when YouTube says for certain it cannot be, withdrawn ({@link #MISSING_COMMENT} ...).
     */
    static final java.util.Set<String> REQUIRED = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(List.of("video", "viewer", "comment", REPLY_AS, "reply")));

    /** Why a waiting reply is withdrawn: YouTube has no such comment, or none with words to show. */
    static final String MISSING_COMMENT = notice("Missing details: the comment could not be read.");
    /** Why a waiting reply is withdrawn: the video the comment is under is not on YouTube to read (private, deleted). */
    static final String MISSING_VIDEO = notice("Missing details: the video could not be read.");
    /** Why a waiting reply is withdrawn: YouTube does not say who wrote the comment. */
    static final String MISSING_VIEWER = notice("Missing details: the viewer could not be read.");
    /** Why a waiting reply is withdrawn: it has no words to post. */
    static final String MISSING_REPLY = notice("Missing details: the reply is empty.");

    @Override
    public java.util.Set<String> requiredFields(String toolName) {
        return TOOL.equals(toolName) ? REQUIRED : java.util.Set.of();
    }

    /**
     * The card, whole, or {@link Unavailable} saying why it cannot be - with a reason when YouTube's answer is definite
     * (the comment or its video is not there), without one when it may pass (a spent quota, an outage). Empty only for
     * another tool.
     */
    @Override
    public Optional<ApprovalContext> contextFor(String toolName, Map<String, Object> arguments, String conversationId,
                                                String userId) {
        if (!TOOL.equals(toolName) || arguments == null) return Optional.empty();
        String commentId = YouTubeTools.parentId(string(arguments.get("commentId")));
        if (commentId == null) {
            throw new Unavailable("Name the comment to answer by its commentId, from listYouTubeComments. Nothing was "
                    + "kept or changed.", MISSING_COMMENT);
        }
        String reply = string(arguments.get("text")) == null ? "" : string(arguments.get("text")).strip();
        if (reply.isEmpty()) throw new Unavailable("A reply needs text. Nothing was kept or changed.", MISSING_REPLY);
        if (reply.length() > YouTubeTools.MAX_REPLY_CHARS) {
            throw new Unavailable("YouTube takes at most " + YouTubeTools.MAX_REPLY_CHARS + " characters in a reply; this "
                    + "one has " + reply.length() + ". Shorten it. Nothing was kept or changed.", null);
        }
        ApiClient api;
        ResolvedConnection connection;
        try {
            Endpoint endpoint = resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, YouTubeTools.LABEL, null, Access.READ);
            connection = connections.resolveOwned(userId, endpoint.connectionId()).orElse(null);
            // One attempt per call: a slow YouTube refuses this one, and the agent tries again.
            api = connection == null ? null : YouTubeTools.client(http, contexts.credentials(connection), true);
        } catch (RuntimeException e) {
            throw new Unavailable("The YouTube connection this conversation uses could not be used ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip())
                    + "). Nothing was kept or changed; try again once it works.", null);
        }
        if (api == null) {
            throw new Unavailable("This conversation has no YouTube connection of the person's own to read the comment "
                    + "with. Nothing was kept or changed.", null);
        }
        return Optional.of(describe(api, String.valueOf(connection.id()), commentId, reply));
    }

    @Override
    public boolean describes(String toolName) {
        return TOOL.equals(toolName);
    }

    // What YouTube failing - not answering "there is none" - means for the agent: a limit or an outage, which may pass.
    static Unavailable unreadable(RuntimeException e) {
        if (e instanceof Unavailable u) return u;
        String m = e.getMessage() == null ? "" : e.getMessage().strip();
        if (m.contains("quotaExceeded") || m.contains("dailyLimitExceeded") || m.contains(QUOTA_SPENT)) {
            return new Unavailable("YouTube's daily quota for the connection's Google Cloud project is spent, so the "
                    + "comment could not be read. Nothing was kept or changed. This is a limit, not a refusal: try again "
                    + "after midnight Pacific time, when it resets.", null);
        }
        return new Unavailable("YouTube could not be read just now" + (m.isEmpty() ? "" : " (" + m + ")")
                + ", so the comment could not be checked. Nothing was kept or changed; try again in a few minutes.", null);
    }

    /**
     * A reply to a comment under ANOTHER channel's video than the one this conversation's connection posts as is not
     * kept (2026-11-05): it would be posted as this channel on someone else's video. One quota unit for the thread (the
     * channel remembered), and the thread kept a moment for the context's lookup that follows. Empty when the channel
     * or the comment cannot be read - the tool checks again when the reply is made.
     */
    @Override
    public Optional<String> refusal(String toolName, Map<String, Object> arguments, String conversationId, String userId) {
        if (!TOOL.equals(toolName) || arguments == null) return Optional.empty();
        String commentId = YouTubeTools.parentId(string(arguments.get("commentId")));
        if (commentId == null) return Optional.empty();
        try {
            Endpoint endpoint = resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, YouTubeTools.LABEL, null, Access.READ);
            ResolvedConnection connection = connections.resolveOwned(userId, endpoint.connectionId()).orElse(null);
            if (connection == null) return Optional.empty();
            ApiClient api = YouTubeTools.client(http, contexts.credentials(connection), true);
            String scope = String.valueOf(connection.id());
            Channel mine = channel(api, scope);
            if (mine == null) return Optional.empty();
            JsonNode thread = fromSync(scope, threadOf(commentId));
            if (thread == null) thread = thread(api, threadOf(commentId));
            if (thread != null) remember(scope, threadOf(commentId), thread);
            String owner = ownerOf(thread);
            return owner == null || owner.equals(mine.id()) ? Optional.empty() : Optional.of(otherChannel(mine.title()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** What an agent reads when its reply is for a comment of another channel (the tool says the same, YouTubeTools). */
    static String otherChannel(String channelTitle) {
        String channel = channelTitle == null || channelTitle.isBlank() ? "the channel" : "\"" + channelTitle + "\", the channel";
        return "That comment is under a video of another YouTube channel, not " + channel + " this team replies as. "
                + "Reply only to comments listYouTubeComments gives you now; drop any comment noted before the team's "
                + "channel changed.";
    }

    /** A comment's id names the subject of the reply waiting for it: what withdrawApproval takes in place of a request. */
    @Override
    public Optional<String> subjectNamed(String reference) {
        String commentId = YouTubeTools.parentId(reference);
        if (commentId == null) return Optional.empty();
        return Optional.of(subjectFor(commentId));
    }

    /** A reply is about the comment it answers: a second draft for it replaces the first while that one waits. */
    @Override
    public Optional<String> subjectOf(String toolName, Map<String, Object> arguments) {
        if (!TOOL.equals(toolName) || arguments == null) return Optional.empty();
        String commentId = YouTubeTools.parentId(string(arguments.get("commentId")));
        return commentId == null ? Optional.empty() : Optional.of(subjectFor(commentId));
    }

    // -- A draft whose comment was answered meanwhile ----------------------------------------------

    /**
     * Why a waiting reply is withdrawn: the channel has replied to the comment since (the person, on YouTube). Exactly
     * ApprovalContextProvider.ANSWERED_BY_THE_PERSON, which the Overview's step-in figures count by - keep them equal.
     */
    static final String ANSWERED = notice("Already answered on YouTube.");
    /** Why a waiting reply is withdrawn: the comment is no longer on YouTube. */
    static final String GONE = notice("The comment is gone.");
    /**
     * Why a waiting reply is withdrawn: its comment is under a video of another channel than the one its conversation
     * posts as now - drafted before the team moved to another channel (2026-11-05).
     */
    static final String OTHER_CHANNEL = notice("Made for another channel.");
    /** Threads one commentThreads.list asks for - YouTube's own limit on its id list. */
    static final int BATCH = 50;
    /** Threads with more replies than YouTube carries inline, read whole (comments.list parentId), at most, a check. */
    static final int DEEP_MAX = 10;
    static final Duration CHANNEL_TTL = Duration.ofMinutes(30);
    /** The channel a connection posts as: its id, title and handle. */
    record Channel(String id, String title, String handle) {
        /** Where the channel is seen: its handle's page, else its id's. */
        String link() {
            return handle != null && handle.startsWith("@") ? "https://www.youtube.com/@" + enc(handle.substring(1))
                    : "https://www.youtube.com/channel/" + enc(id);
        }
    }

    private record KnownChannel(Channel channel, Instant at) {}

    private final Map<String, KnownChannel> channels = new ConcurrentHashMap<>();
    /** How long a thread read for a refusal is kept for the context's lookup that follows it. */
    static final Duration THREAD_KEPT = Duration.ofMinutes(1);
    private record KnownThread(JsonNode thread, Instant at) {}
    // Threads read by refusal(), taken once by the describe() just after it - never served twice.
    private final Map<String, KnownThread> threads = new ConcurrentHashMap<>();

    @Override
    public boolean settles(String toolName) {
        return TOOL.equals(toolName);
    }

    /**
     * THE WAITING REPLIES WHOSE COMMENT WAS ANSWERED MEANWHILE (2026-11-04, the maintainer: "Many comments I
     * already replied but still asked to approve"): a reply by the channel itself to the comment - newer than
     * the draft or not - is {@link #ANSWERED}; a thread YouTube no longer has is {@link #GONE}. Anything that
     * could not be read is left out, so its draft stays.
     *
     * <p>Through each request's own conversation's connection resolved for READ, and only one the user owns;
     * the requests are grouped by connection. QUOTA, one unit a call: the channel's id (channels.list mine,
     * remembered {@link #CHANNEL_TTL} per connection), one commentThreads.list per {@link #BATCH} comments
     * (part snippet,replies - the replies YouTube carries inline), and for a thread with more replies than
     * that and no answer among them, its replies read whole, at most {@link #DEEP_MAX} a check. Twenty drafts:
     * one or two units; a hundred: two to fifteen. A reply to a viewer's REPLY (its own id) is answered when the
     * channel wrote in the thread after that reply, and gone when its thread, read whole, no longer holds it
     * (YouTubeConversation, 2026-11-05).
     */
    @Override
    public Map<String, String> settled(String toolName, List<Waiting> waiting, String userId) {
        if (!TOOL.equals(toolName) || waiting == null || waiting.isEmpty()) return Map.of();
        Map<String, ResolvedConnection> byConversation = new java.util.HashMap<>();
        Map<String, ResolvedConnection> connectionsById = new java.util.LinkedHashMap<>();
        Map<String, Map<String, List<String>>> commentsByConnection = new java.util.LinkedHashMap<>();
        for (Waiting w : waiting) {
            String commentId = w.arguments() == null ? null : YouTubeTools.parentId(string(w.arguments().get("commentId")));
            if (commentId == null) continue;
            ResolvedConnection c = byConversation.computeIfAbsent(String.valueOf(w.conversationId()),
                    k -> connectionFor(w.conversationId(), userId));
            if (c == null) continue;
            String key = String.valueOf(c.id());
            connectionsById.putIfAbsent(key, c);
            commentsByConnection.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(commentId, k -> new ArrayList<>()).add(w.id());
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> e : commentsByConnection.entrySet()) {
            try {
                ApiClient api = YouTubeTools.client(http, contexts.credentials(connectionsById.get(e.getKey())), true);
                answered(api, e.getKey(), e.getValue().keySet()).forEach((commentId, reason) ->
                        e.getValue().getOrDefault(commentId, List.of()).forEach(id -> out.put(id, reason)));
            } catch (RuntimeException ex) {
                // This connection's drafts stay as they are.
            }
        }
        return out;
    }

    // The connection a request's conversation reads YouTube through, when it is the user's own; else null.
    private ResolvedConnection connectionFor(String conversationId, String userId) {
        try {
            ai.proxyma.core.search.domain.SearchContext.setUserId(userId);
            ai.proxyma.core.search.domain.SearchContext.setConversationId(conversationId);
            Endpoint endpoint = resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, YouTubeTools.LABEL, null, Access.READ);
            return connections.resolveOwned(userId, endpoint.connectionId()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        } finally {
            ai.proxyma.core.search.domain.SearchContext.clear();
        }
    }

    /**
     * Of these comments, those answered by the channel or gone: comment id to the reason. Over a client already
     * resolved; {@code scope} keeps one connection's channel id its own. Empty when the channel cannot be read.
     */
    Map<String, String> answered(ApiClient api, String scope, java.util.Collection<String> commentIds) {
        String channelId = channelId(api, scope);
        if (channelId == null) return Map.of();
        // A reply's own id is "<thread>.<reply>": its thread is the part before the dot.
        Map<String, String> threadOf = new java.util.LinkedHashMap<>();
        for (String id : commentIds) {
            int dot = id.indexOf('.');
            threadOf.put(id, dot > 0 ? id.substring(0, dot) : id);
        }
        List<String> threadIds = threadOf.values().stream().distinct().toList();
        Map<String, JsonNode> threads = new java.util.HashMap<>();
        java.util.Set<String> asked = new java.util.HashSet<>();
        for (int i = 0; i < threadIds.size(); i += BATCH) {
            List<String> batch = threadIds.subList(i, Math.min(threadIds.size(), i + BATCH));
            try {
                JsonNode items = api.getJson(YouTubeTools.V3 + "commentThreads", Map.of("part", "snippet,replies",
                        "id", String.join(",", batch), "textFormat", "plainText")).path("items");
                for (JsonNode t : items) threads.put(t.path("id").asText(""), t);
                asked.addAll(batch);
            } catch (RuntimeException e) {
                // This batch could not be read: its drafts stay.
            }
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        int deep = 0;
        for (Map.Entry<String, String> e : threadOf.entrySet()) {
            String threadId = e.getValue();
            if (!asked.contains(threadId)) continue;
            JsonNode thread = threads.get(threadId);
            if (thread == null) {
                out.put(e.getKey(), GONE);
                continue;
            }
            // Under another channel's video than the one this connection posts as now: not this team's to answer.
            String owner = ownerOf(thread);
            if (owner != null && !owner.equals(channelId)) {
                out.put(e.getKey(), OTHER_CHANNEL);
                continue;
            }
            if (!e.getKey().equals(threadId)) {
                // A viewer's REPLY (2026-11-05, the conversation): answered when the channel wrote in its thread after
                // it; gone when the thread, read whole, no longer holds it.
                List<JsonNode> replies = YouTubeConversation.inlineReplies(thread);
                boolean whole = !YouTubeConversation.truncated(thread);
                if (!whole && deep < DEEP_MAX) {
                    deep++;
                    try {
                        replies = YouTubeConversation.replies(api, threadId, YouTubeConversation.REPLY_PAGES);
                        whole = true;
                    } catch (RuntimeException ex) {
                        // Not known: judged on what came inline.
                    }
                }
                List<YouTubeConversation.Message> all = YouTubeConversation.messages(thread, replies, channelId);
                if (YouTubeConversation.answeredBy(all, e.getKey()) != null) out.put(e.getKey(), ANSWERED);
                else if (whole && !YouTubeConversation.contains(all, e.getKey())) out.put(e.getKey(), GONE);
                continue;
            }
            JsonNode inline = thread.path("replies").path("comments");
            boolean byChannel = false;
            for (JsonNode r : inline) {
                if (channelId.equals(r.path("snippet").path("authorChannelId").path("value").asText(""))) byChannel = true;
            }
            if (!byChannel && thread.path("snippet").path("totalReplyCount").asInt(0) > inline.size() && deep < DEEP_MAX) {
                deep++;
                byChannel = repliedInFull(api, threadId, channelId);
            }
            if (byChannel) out.put(e.getKey(), ANSWERED);
        }
        return out;
    }

    // Whether the channel is among the authors of a thread's replies, read whole - one page, the first hundred.
    private static boolean repliedInFull(ApiClient api, String threadId, String channelId) {
        try {
            for (JsonNode r : api.getJson(YouTubeTools.V3 + "comments", Map.of("part", "snippet", "parentId", threadId,
                    "maxResults", "100", "textFormat", "plainText")).path("items")) {
                if (channelId.equals(r.path("snippet").path("authorChannelId").path("value").asText(""))) return true;
            }
        } catch (RuntimeException e) {
            // Not known: as not answered.
        }
        return false;
    }

    // The connection's own channel id, remembered for CHANNEL_TTL; null when it cannot be read.
    private String channelId(ApiClient api, String scope) {
        Channel c = channel(api, scope);
        return c == null ? null : c.id();
    }

    // The connection's own channel, remembered for CHANNEL_TTL; null when it cannot be read.
    Channel channel(ApiClient api, String scope) {
        try {
            return channelOrThrow(api, scope);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The same, throwing when YouTube could not be read; null when it answered with no channel.
    private Channel channelOrThrow(ApiClient api, String scope) {
        Instant now = clock.instant();
        KnownChannel known = channels.get(scope);
        if (known != null && known.at().plus(CHANNEL_TTL).isAfter(now)) return known.channel();
        JsonNode item = api.getJson(YouTubeTools.V3 + "channels", Map.of("part", "snippet", "mine", "true"))
                .path("items").path(0);
        String id = item.path("id").asText("");
        if (id.isBlank()) return null;
        String title = item.path("snippet").path("title").asText("");
        String handle = item.path("snippet").path("customUrl").asText("");
        Channel c = new Channel(id, title.isBlank() ? null : title, handle.isBlank() ? null : handle);
        if (channels.size() >= TITLES_MAX) channels.clear();
        channels.put(scope, new KnownChannel(c, now));
        return c;
    }

    /**
     * The channel a comment thread belongs to - the channel of the video it is under - as YouTube states it: the
     * thread's {@code snippet.channelId}, else its top-level comment's; null when it states none.
     */
    static String ownerOf(JsonNode thread) {
        if (thread == null) return null;
        String id = thread.path("snippet").path("channelId").asText("");
        if (id.isBlank()) id = thread.path("snippet").path("topLevelComment").path("snippet").path("channelId").asText("");
        return id.isBlank() ? null : id;
    }

    // A comment's thread: a reply's own id is "<thread>.<reply>", a top-level comment's is its thread's.
    static String threadOf(String commentId) {
        int dot = commentId.indexOf('.');
        return dot > 0 ? commentId.substring(0, dot) : commentId;
    }

    /** Whether a write's result is the built-in sign-in's spent daily share (YouTubeCalls.SHARED_QUOTA_SPENT). */
    public static boolean sharedLimit(String result) {
        return result != null && result.contains(YouTubeCalls.SHARED_QUOTA_SPENT);
    }

    /**
     * THREADS THE COMMENT SYNC HAS JUST READ (2026-11-11, the maintainer: one channel spent its share of the built-in
     * sign-in's daily quota early every day): a draft's card and its other-channel check read its thread from here for
     * {@link #SYNCED_KEPT}, with no call - a card asked commentThreads.list once a draft, and a channel's drafts are
     * written from the comments the sync read. What can change in that time - whether the channel answered - is checked
     * live (settled) and again when the reply is approved.
     */
    public void heldFromSync(UUID connectionId, java.util.Collection<JsonNode> read) {
        if (connectionId == null || read == null) return;
        Instant now = clock.instant();
        if (synced.size() + read.size() > SYNCED_MAX) synced.entrySet().removeIf(e -> !e.getValue().at().plus(SYNCED_KEPT).isAfter(now));
        for (JsonNode t : read) {
            if (synced.size() >= SYNCED_MAX) break;
            String id = t == null ? "" : t.path("id").asText("");
            if (!id.isEmpty() && t.path("snippet").has("topLevelComment")) synced.put(connectionId + ":" + id, new KnownThread(t, now));
        }
    }

    static final Duration SYNCED_KEPT = Duration.ofHours(1);
    static final int SYNCED_MAX = 5_000;
    private final Map<String, KnownThread> synced = new ConcurrentHashMap<>();

    // A thread the comment sync read within SYNCED_KEPT; null otherwise.
    private JsonNode fromSync(String scope, String threadId) {
        KnownThread known = synced.get(scope + ":" + threadId);
        return known != null && known.at().plus(SYNCED_KEPT).isAfter(clock.instant()) ? known.thread() : null;
    }

    private void remember(String scope, String threadId, JsonNode thread) {
        Instant now = clock.instant();
        if (threads.size() >= TITLES_MAX) threads.entrySet().removeIf(e -> !e.getValue().at().plus(THREAD_KEPT).isAfter(now));
        if (threads.size() < TITLES_MAX) threads.put(scope + ":" + threadId, new KnownThread(thread, now));
    }

    // A thread refusal() just read, taken once; else read now.
    private JsonNode threadOnce(ApiClient api, String scope, String id) {
        KnownThread known = threads.remove(scope + ":" + id);
        if (known != null && known.at().plus(THREAD_KEPT).isAfter(clock.instant())) return known.thread();
        JsonNode read = fromSync(scope, id);
        return read != null ? read : thread(api, id);
    }

    // -- A reply YouTube refused for its daily quota ------------------------------------------------

    /** What every refusal for a spent quota begins with (YouTubeTools.replyRefused, YouTubeCalls.refusal). */
    static final String QUOTA_SPENT = "YouTube's daily quota";

    /** Whether a refusal's words say YouTube's daily quota is spent - the connection's own or the shared sign-in's. */
    public static boolean quotaSpent(String text) {
        return text != null && text.contains(QUOTA_SPENT);
    }

    /** How long from {@code now} until YouTube's daily quota resets: the next midnight, Pacific time. */
    public static java.time.Duration untilQuotaResets(java.time.Instant now) {
        java.time.ZonedDateTime reset = now.atZone(QUOTA_ZONE).toLocalDate().plusDays(1).atStartOfDay(QUOTA_ZONE);
        return java.time.Duration.between(now, reset.toInstant()).plusMinutes(1);
    }
    /** Where YouTube's daily quota resets: midnight Pacific time. */
    static final java.time.ZoneId QUOTA_ZONE = java.time.ZoneId.of("America/Los_Angeles");
    /** How long after the reset a held reply is tried, so it does not race the reset itself. */
    static final Duration AFTER_RESET = Duration.ofMinutes(5);

    /**
     * A reply YouTube refused because the connection's daily quota is spent is not a failure (2026-11-04, the
     * maintainer: 16 approved replies "not posted because YouTube's quota is exhausted"): it is held, and tried
     * again a few minutes after the next midnight Pacific time, when the quota resets.
     */
    @Override
    public Optional<Instant> heldUntil(String toolName, String result, Instant now) {
        if (!TOOL.equals(toolName) || result == null || now == null) return Optional.empty();
        return ai.proxyma.core.chat.approval.ApprovalContexts.errorOf(result)
                .filter(e -> e.contains(QUOTA_SPENT))
                .map(e -> nextReset(now));
    }

    /** The first reset of YouTube's daily quota after {@code now}, plus {@link #AFTER_RESET}. */
    static Instant nextReset(Instant now) {
        java.time.ZonedDateTime pacific = now.atZone(QUOTA_ZONE);
        return pacific.toLocalDate().plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant().plus(AFTER_RESET);
    }

    // A sentence the page shows and translates (tools/i18n/server-messages.mjs reads notice("...")).
    private static String notice(String sentence) {
        return sentence;
    }

    /** The card's keys for the comment that opened a reply's thread, and its author. */
    public static final String THREAD_START = "threadStart";
    public static final String THREAD_AUTHOR = "threadAuthor";

    /** The subject of a reply to this comment, as the approvals inbox keeps it. */
    static String subjectFor(String commentId) {
        return "youtube-comment:" + commentId;
    }

    /**
     * The card, WHOLE, over a client already resolved ({@code scope} keeps one connection's titles its own): the video
     * by its title, the viewer and their channel, their comment linked where it sits, when it was written, the channel
     * the reply is posted as, and the reply. Throws {@link Unavailable} for anything it cannot fill - definite (with a
     * reason) when YouTube answered that the comment, its thread or its video is not there, or named no author or no
     * words; not definite when YouTube failed to answer (unreadable).
     *
     * <p>A reply named by its own id ("&lt;thread&gt;.&lt;reply&gt;") is no thread's: it is read alone, and its video and its
     * channel are its PARENT thread's, which every reply names - a reply read alone carries no video, which is why some
     * cards had none (2026-11-05). A video's title, and the channel it is on, come from videos.list, remembered.
     */
    ApprovalContext describe(ApiClient api, String scope, String commentId, String replyText) {
        JsonNode comment;
        String videoId;
        String owner;
        // A reply's thread: its top-level comment and that comment's id, for the card's "in reply to" line.
        JsonNode threadTop = null;
        String threadTopId = null;
        try {
            // A reply's own id ("<thread>.<reply>") is never a thread's: not asked as one.
            JsonNode thread = commentId.indexOf('.') > 0 ? null : threadOnce(api, scope, commentId);
            if (thread != null) {
                comment = thread.path("snippet").path("topLevelComment");
                videoId = thread.path("snippet").path("videoId").asText("");
                owner = ownerOf(thread);
            } else {
                comment = comment(api, commentId);
                if (comment == null) throw noSuchComment();
                videoId = comment.path("snippet").path("videoId").asText("");
                owner = null;
                // A reply's video is its thread's: read off the parent, which every reply names.
                String parentId = comment.path("snippet").path("parentId").asText("");
                if (parentId.isEmpty() && commentId.indexOf('.') > 0) parentId = threadOf(commentId);
                if (!parentId.isEmpty()) {
                    JsonNode parent = threadOnce(api, scope, parentId);
                    if (parent == null) throw noSuchComment();
                    String parentVideo = parent.path("snippet").path("videoId").asText("");
                    if (!parentVideo.isEmpty()) videoId = parentVideo;
                    owner = ownerOf(parent);
                    threadTop = parent.path("snippet").path("topLevelComment");
                    threadTopId = parentId;
                }
            }
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
        if (videoId.isEmpty()) {
            throw new Unavailable("YouTube names no video for that comment (it is not under one of the channel's videos), "
                    + "so a person could not be shown it. Nothing was kept or changed; reply only to comments "
                    + "listYouTubeComments gives you.", MISSING_VIDEO);
        }
        Video video;
        Channel as;
        try {
            // The title Proxyma already holds first (the channel source's index, 2026-11-06), when the thread said whose
            // video it is - only videos.list says that otherwise.
            video = owner == null ? null : held(scope, videoId);
            if (video == null) video = video(api, scope, videoId);
            as = channelOrThrow(api, scope);
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
        if (video == null) {
            throw new Unavailable("The video that comment is under could not be read on YouTube - it may be private or "
                    + "deleted. Nothing was kept or changed; reply to another comment.", MISSING_VIDEO);
        }
        if (as == null) {
            throw new Unavailable("The channel this YouTube connection posts as could not be read. Nothing was kept or "
                    + "changed; try again in a few minutes.", null);
        }
        if (owner == null) owner = video.channelId();
        if (owner != null && !owner.equals(as.id())) throw new Unavailable(otherChannel(as.title()), OTHER_CHANNEL);
        if (owner == null) {
            throw new Unavailable("YouTube does not say whose video that comment is under, so it could not be checked "
                    + "against the channel the reply is posted as. Nothing was kept or changed; try again later.", null);
        }

        JsonNode s = comment.path("snippet");
        String author = s.path("authorDisplayName").asText("").strip();
        String channelUrl = s.path("authorChannelUrl").asText("").strip();
        String channelId = s.path("authorChannelId").path("value").asText("").strip();
        if (!channelUrl.matches("(?i)^https?://\\S+$")) channelUrl = "";
        if (channelUrl.isEmpty() && !channelId.isEmpty()) channelUrl = "https://www.youtube.com/channel/" + enc(channelId);
        if (author.isEmpty() || channelUrl.isEmpty()) {
            throw new Unavailable("YouTube does not say who wrote that comment, so a person could not be shown it. "
                    + "Nothing was kept or changed; reply to another comment.", MISSING_VIEWER);
        }
        String text = s.path("textOriginal").asText("");
        if (text.isBlank()) text = s.path("textDisplay").asText("");
        if (text.isBlank()) {
            throw new Unavailable("That comment has no words YouTube will show, so a person could not be shown it. "
                    + "Nothing was kept or changed; reply to another comment.", MISSING_COMMENT);
        }
        String asName = as.title() != null ? as.title() : as.handle();
        if (asName == null) {
            throw new Unavailable("The channel this YouTube connection posts as has no name YouTube will show. Nothing "
                    + "was kept or changed; try again in a few minutes.", null);
        }
        String reply = replyText == null ? "" : replyText.strip();
        if (reply.isEmpty()) throw new Unavailable("A reply needs text. Nothing was kept or changed.", MISSING_REPLY);

        String watch = "https://www.youtube.com/watch?v=" + enc(videoId);
        List<Field> fields = new ArrayList<>();
        fields.add(Field.link("video", video.title(), watch));
        fields.add(Field.link("viewer", author, channelUrl));
        // THE CONVERSATION IT IS PART OF (2026-11-10, the maintainer: "which comment belong to which conversation"): for a
        // reply, the comment that opened its thread and who wrote it - the question another viewer answered, say - so the
        // person sees whom the draft is for.
        if (threadTop != null && !threadTop.isMissingNode()) {
            JsonNode ts = threadTop.path("snippet");
            String opener = ts.path("authorDisplayName").asText("").strip();
            String openerUrl = ts.path("authorChannelUrl").asText("").strip();
            String opened = ts.path("textOriginal").asText("");
            if (opened.isBlank()) opened = ts.path("textDisplay").asText("");
            if (!opener.isEmpty() && !opened.isBlank()) {
                fields.add(openerUrl.matches("(?i)^https?://\\S+$") ? Field.link(THREAD_AUTHOR, opener, openerUrl)
                        : Field.text(THREAD_AUTHOR, opener));
                fields.add(new Field(THREAD_START, opened.strip(), watch + "&lc=" + enc(threadTopId), ApprovalContext.QUOTE));
            }
        }
        fields.add(new Field("comment", text, watch + "&lc=" + enc(commentId), ApprovalContext.QUOTE));
        // When it was written (2026-11-03: the team answers old comments too, and a reply to a comment two years
        // old should read as one) - an ISO time, which the page shows beside the comment as how long ago.
        String commentedAt = s.path("publishedAt").asText("");
        if (!commentedAt.isEmpty() && parses(commentedAt)) fields.add(Field.text(COMMENTED_AT, commentedAt));
        // Which channel the reply is posted AS: the connection's, linked, so a person sees it at a glance (2026-11-05).
        fields.add(Field.link(REPLY_AS, asName, as.link()));
        // The reply is the one thing the person may rewrite before approving: the call's "text".
        fields.add(Field.editable("reply", reply, "text"));
        ApprovalContext card = new ApprovalContext("YouTube", fields).bounded().orElse(null);
        java.util.List<String> missing = ai.proxyma.core.chat.approval.ApprovalContexts.missing(REQUIRED, card);
        if (!missing.isEmpty()) {
            throw new Unavailable("The details a person needs to decide this reply could not all be read (missing: "
                    + String.join(", ", missing) + "). Nothing was kept or changed; try again later.", null);
        }
        return card;
    }

    // YouTube answered: no such comment (or no thread it answers).
    private static Unavailable noSuchComment() {
        return new Unavailable("YouTube has no comment with that commentId - it may have been deleted. Nothing was kept "
                + "or changed; take a commentId from listYouTubeComments.", MISSING_COMMENT);
    }

    // The thread a top-level comment heads, or null when YouTube has none - a reply's own ID included. Throws when
    // YouTube could not be read.
    private static JsonNode thread(ApiClient api, String id) {
        JsonNode item = api.getJson(YouTubeTools.V3 + "commentThreads",
                Map.of("part", "snippet", "id", id, "textFormat", "plainText")).path("items").path(0);
        return item.isMissingNode() || item.isNull() || !item.path("snippet").has("topLevelComment") ? null : item;
    }

    private static JsonNode comment(ApiClient api, String id) {
        JsonNode item = api.getJson(YouTubeTools.V3 + "comments", Map.of("part", "snippet", "id", id, "textFormat", "plainText"))
                .path("items").path(0);
        return item.isMissingNode() || item.isNull() ? null : item;
    }

    /** A video as a card shows it: its title, and the channel it is on (null when YouTube does not say). */
    record Video(String title, String channelId) {}

    // A video's title and channel, remembered for TITLE_TTL per connection; null when YouTube has no such video, or
    // one with no title (private, deleted). Throws when YouTube could not be read - that is not remembered.
    private Video video(ApiClient api, String scope, String videoId) {
        Video v = videoAsked(api, scope, videoId);
        if (v != null) videoTitles.remember(connectionOf(scope), videoId, v.title());
        return v;
    }

    // A title held with no call - the channel source's index, then what any YouTube lookup read before - as a card's video
    // whose channel the thread already gave; null when none is held.
    private Video held(String scope, String videoId) {
        String title = videoTitles.held(null, connectionOf(scope), List.of(videoId)).get(videoId);
        return title == null ? null : new Video(title, null);
    }

    private static UUID connectionOf(String scope) {
        try {
            return scope == null ? null : UUID.fromString(scope);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Video videoAsked(ApiClient api, String scope, String videoId) {
        String key = scope + ":" + videoId;
        Instant now = clock.instant();
        Title known = titles.get(key);
        if (known != null && known.at().plus(TITLE_TTL).isAfter(now)) return known.value();
        JsonNode snippet = api.getJson(YouTubeTools.V3 + "videos", Map.of("part", "snippet", "id", videoId))
                .path("items").path(0).path("snippet");
        String title = snippet.path("title").asText("").strip();
        if (title.isEmpty()) return null;
        String channel = snippet.path("channelId").asText("").strip();
        Video v = new Video(title, channel.isEmpty() ? null : channel);
        if (titles.size() >= TITLES_MAX) titles.clear();
        titles.put(key, new Title(v, now));
        return v;
    }

    private static boolean parses(String iso) {
        try {
            Instant.parse(iso);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
