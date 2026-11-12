package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.GoogleError;
import ai.proxyma.core.connector.spi.RefusalReason;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import ai.proxyma.core.tools.ToolResults;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import ai.proxyma.core.chat.agents.ApprovalInbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The YouTube channel, live (2026-10-30, the maintainer: big channels get "so many comments and
 * questions"): the comments still waiting for the channel's answer, one viewer's earlier comments with
 * what the channel replied to them, a video's title and description, and a reply posted under a comment.
 *
 * <p>NO LIKE. The YouTube Data API has no way to like or heart a comment - comments can be listed,
 * replied to, moderated and deleted, nothing more - so a comment that needs only a like gets a short
 * reply or none (the maintainer asked, 2026-10-31).
 *
 * <p>Over the caller's own YouTube connection - the one ToolConnectorResolver picks and checks: Read
 * for the two reads, WRITE for the reply. {@code replyToYouTubeComment} is in WriteToolRegistry, so
 * every call takes the same gate as any other write - asked in Ask, refused in Read-only, held in the
 * approval inbox when nobody is watching (a Constellations run) - and the connection's Write switch
 * decides whether Google was asked for the reply permission at all.
 *
 * <p>The YouTube Data API v3 answers every request here for ONE quota unit, except posting a reply
 * (50). A reply is POSTED ONCE: its client makes a single attempt, so a server error after the comment
 * was written cannot write it twice.
 *
 * <p><b>The channel's numbers</b> (2026-10-31, the YouTube assistant's Channel Analyst): the channel and
 * its videos from the Data API, a fixed set of named reports from the YouTube Analytics API
 * (YouTubeAnalytics), and impressions with their click-through rate from the YouTube Reporting API
 * (YouTubeReach) - all READ, through the same connection, which asks {@code yt-analytics.readonly}
 * beside {@code youtube.readonly}. A connection signed in before that scope was asked keeps working for
 * comments; the analytics tools tell the model to have the user sign in again (YouTubeCalls).
 *
 * <p><b>The owner's voice</b> (2026-11-03, the maintainer: the team "should learn how the user reply/react to
 * the audience from the existing comments first, and keep learning it"): {@code listYouTubeChannelReplies}
 * reads the channel's own replies across its newest threads, each beside the comment it answered, and
 * {@code listYouTubeVideos} gives each upload's description when asked - with the channel's description in
 * the overview, what a team learns the voice from before its first draft.
 *
 * <p><b>A channel YouTube will not list at once</b> (2026-11-03, the maintainer's second channel): when
 * {@code allThreadsRelatedToChannelId} is refused ({@code commentsDisabled}, {@code forbidden}) while the
 * channel answered, the three comment readers read its newest uploads one video at a time instead
 * (YouTubeThreadScan) and say so in the result, with how many videos have comments off. Every refusal
 * carries Google's own reason (YouTubeCalls, RefusalReason.GOOGLE).
 *
 * <p><b>Old comments too</b> (2026-11-03, the maintainer: "there are comments that long long time ago also
 * need to be replied. So the agents should start with the latest-not-replied"): {@code listYouTubeComments}
 * looks deeper for unanswered comments ({@link #DEEP_PAGES}), takes {@code publishedBefore}, and returns
 * {@code oldestScanned}, so the Comment Scout walks the channel's backlog a batch per pass, newest first.
 * Quota, one unit a call: the channel, then at most {@link #DEEP_PAGES} pages looked through - fewer, since
 * it stops at the page that fills its limit - plus, below publishedBefore, at most
 * {@link YouTubeThreadScan#SKIP_PAGES} pages passed over to reach it: 71 units at worst.
 */
@Component
public class YouTubeTools {

    static final String LABEL = "YouTube";
    static final String DEFAULT_BASE_URL = "https://www.googleapis.com";
    static final String V3 = "youtube/v3/";
    /** YouTube's limit for a comment's text. */
    static final int MAX_REPLY_CHARS = 10_000;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    // How many pages of threads a listing of every comment looks through: two.
    static final int SCAN_PAGES = 2;
    // How many an unanswered-only listing may look through for its limit (2026-11-03): twenty pages, 2,000
    // threads, one quota unit each - and it stops at the page that fills its limit. Below publishedBefore
    // the pages newer than it are passed over first, at most YouTubeThreadScan.SKIP_PAGES.
    static final int DEEP_PAGES = 20;
    // How many threads with more replies than YouTube carries inline an unanswered-only listing reads whole
    // (comments.list parentId=, a unit a hundred replies, at most YouTubeConversation.REPLY_PAGES each).
    static final int DEEP_THREADS = 10;
    // How many of the newest threads a viewer's history looks through: five pages, five quota units.
    static final int HISTORY_PAGES = 5;
    static final int DEFAULT_HISTORY = 10;
    static final int MAX_HISTORY = 20;
    // How much of the channel's description the overview returns; YouTube itself allows 1,000 characters.
    static final int MAX_CHANNEL_DESCRIPTION = 1_000;
    // How much of each video's description the uploads listing returns, when asked; YouTube allows 5,000.
    static final int MAX_VIDEO_DESCRIPTION = 600;
    // The channel's own replies: how many of the newest threads are looked through (five pages, five quota
    // units), how many pairs are returned, and how much of each comment and reply.
    static final int REPLY_PAGES = 5;
    static final int DEFAULT_REPLIES = 50;
    static final int MAX_REPLIES = 100;
    static final int MAX_PAIR_TEXT = 500;
    static final String CUT = "...(truncated)";
    // What a listing says when YouTube refused the channel's comments at once and its newest uploads were
    // read one by one (YouTubeThreadScan).
    static final String VIDEOS_READ = "Only when the channel's comments could not be listed at once: how many of its "
            + "newest videos were read one by one instead.";
    static final String COMMENTS_OFF = "Only then: how many of those videos have comments turned off.";
    static final String SCAN_NOTE = "Only then: how the channel was read, or that it has no comments to read.";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,200}");

    private final ToolConnectorResolver resolver;
    private final ConnectorConnectionService connections;
    private final SourceContextFactory contexts;
    private final ConnectorHttp http;
    private final McpGuardService mcpGuard;
    private final Clock clock;
    // The reach job of each connection and the reach CSVs already read - in memory, per process.
    private final YouTubeReach reach = new YouTubeReach();

    // What waits for approval (ApprovalInbox): optional, and looked up per call, so the tools build without it.
    private ObjectProvider<ApprovalInbox> approvals;

    @Autowired(required = false)
    void setApprovals(ObjectProvider<ApprovalInbox> approvals) {
        this.approvals = approvals;
    }

    /** Tests: what waits for approval. */
    void setApprovalsForTest(ApprovalInbox inbox) {
        this.approvals = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("approvals", inbox)).getBeanProvider(ApprovalInbox.class);
    }

    @Autowired
    public YouTubeTools(ToolConnectorResolver resolver, ConnectorConnectionService connections,
                        SourceContextFactory contexts, ConnectorHttp http, McpGuardService mcpGuard) {
        this(resolver, connections, contexts, http, mcpGuard, Clock.systemUTC());
    }

    YouTubeTools(ToolConnectorResolver resolver, ConnectorConnectionService connections, SourceContextFactory contexts,
                 ConnectorHttp http, McpGuardService mcpGuard, Clock clock) {
        this.resolver = resolver;
        this.connections = connections;
        this.contexts = contexts;
        this.http = http;
        this.mcpGuard = mcpGuard;
        this.clock = clock;
    }

    @Tool(description = "Comments on the user's YouTube channel, live, newest first - by default only those still "
            + "waiting for the channel: a comment nothing the channel wrote follows, and a viewer's reply after the "
            + "channel's last reply in its thread (the channel's own threads too), each with the conversation before it. "
            + "Each comes with its commentId (what replyToYouTubeComment answers), the video, the author and their "
            + "authorChannelId, the text, publishedAt, emojiOnly (no words at all - a heart, a smiley), channelReplies "
            + "and draftWaiting. unansweredOnly=false lists threads, answered ones too - the way to learn how the owner "
            + "writes to viewers. Optionally one video's only, or only those published after or before a time.")
    @ToolResult({
            @ToolResultField(name = "channel", type = ToolFieldType.STRING, always = true,
                    description = "The channel's title."),
            @ToolResultField(name = "comments", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Newest first: threadId, commentId, parentId (a reply's thread), videoId, author, "
                            + "authorChannelId, text, emojiOnly, publishedAt, replyCount, answeredByChannel, channelReplies "
                            + "(the channel's own replies' text, when it has any), conversation (a reply's earlier messages: "
                            + "author, byChannel, text), inReplyTo (what a reply answers), draftWaiting (true when a reply "
                            + "to it waits for approval), link."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when there may be more than were returned."),
            @ToolResultField(name = "oldestScanned", type = ToolFieldType.STRING,
                    description = "How far back it looked: pass it as publishedBefore to continue below it."),
            @ToolResultField(name = "videosRead", type = ToolFieldType.INTEGER, description = VIDEOS_READ),
            @ToolResultField(name = "videosWithCommentsOff", type = ToolFieldType.INTEGER, description = COMMENTS_OFF),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = SCAN_NOTE)
    })
    public Map<String, Object> listYouTubeComments(
            @ToolParam(required = false, description = "A video's ID, to list only its comments") String videoId,
            @ToolParam(required = false, description = "Only comments the channel has not answered (default true)") Boolean unansweredOnly,
            @ToolParam(required = false, description = "How many to return, 1-50 (default 20)") Integer limit,
            @ToolParam(required = false, description = "An ISO time, e.g. 2026-11-01T08:00:00Z: only comments published "
                    + "after it") String publishedAfter,
            @ToolParam(required = false, description = "An ISO time: only comments published before it - an earlier "
                    + "listing's oldestScanned") String publishedBefore) {
        try {
            if (videoId != null && !videoId.isBlank() && !ID.matcher(videoId.strip()).matches()) {
                return failure("That is not a YouTube video ID.");
            }
            Instant after = null;
            if (publishedAfter != null && !publishedAfter.isBlank()) {
                after = instant(publishedAfter.strip());
                if (after == null) return failure("publishedAfter is an ISO time, such as 2026-11-01T08:00:00Z.");
            }
            Instant before = null;
            if (publishedBefore != null && !publishedBefore.isBlank()) {
                before = instant(publishedBefore.strip());
                if (before == null) return failure("publishedBefore is an ISO time, such as 2026-11-01T08:00:00Z.");
            }
            ApiClient api = client(Access.READ, false);
            return listComments(api, videoId == null ? null : videoId.strip(), unansweredOnly == null || unansweredOnly,
                    limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit)), after, before);
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "One viewer's earlier comments on the user's YouTube channel, with what the channel replied to "
            + "each - how to learn how the owner addresses THIS viewer (how they call themselves and the viewer, a name, "
            + "their tone) before replying to them. Takes the authorChannelId listYouTubeComments gives. Looks through "
            + "the channel's newest " + (HISTORY_PAGES * 100) + " comment threads; an empty list means none there.")
    @ToolResult({
            @ToolResultField(name = "viewer", type = ToolFieldType.STRING, always = true,
                    description = "The viewer's name, as their newest comment shows it."),
            @ToolResultField(name = "comments", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Their comments, newest first: threadId, commentId, videoId, text, emojiOnly, publishedAt, "
                            + "answeredByChannel, channelReplies (the channel's own words to them), link."),
            @ToolResultField(name = "repliedTo", type = ToolFieldType.INTEGER, always = true,
                    description = "How many of those the channel replied to."),
            @ToolResultField(name = "scanned", type = ToolFieldType.INTEGER, always = true,
                    description = "How many of the channel's newest threads were looked through."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when older threads were not looked through, or more comments were found than returned."),
            @ToolResultField(name = "videosRead", type = ToolFieldType.INTEGER, description = VIDEOS_READ),
            @ToolResultField(name = "videosWithCommentsOff", type = ToolFieldType.INTEGER, description = COMMENTS_OFF),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = SCAN_NOTE)
    })
    public Map<String, Object> getYouTubeViewerHistory(
            @ToolParam(description = "The viewer's authorChannelId, from listYouTubeComments") String authorChannelId,
            @ToolParam(required = false, description = "How many of their comments to return, 1-20 (default 10)") Integer limit) {
        try {
            String viewer = authorChannelId == null ? "" : authorChannelId.strip();
            if (!ID.matcher(viewer).matches()) return failure("That is not a YouTube channel ID. Take authorChannelId from listYouTubeComments.");
            return viewerHistory(client(Access.READ, false), viewer,
                    limit == null ? DEFAULT_HISTORY : Math.max(1, Math.min(MAX_HISTORY, limit)));
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "The channel's OWN replies to viewers, live, newest first, each with the comment it answered - "
            + "how to learn the owner's voice: how they call themselves and viewers, length, emoji, how they answer praise, "
            + "questions, requests and emoji-only comments. Looks through the newest " + (REPLY_PAGES * 100) + " comment "
            + "threads.")
    @ToolResult({
            @ToolResultField(name = "channel", type = ToolFieldType.STRING, always = true, description = "The channel's title."),
            @ToolResultField(name = "replies", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Newest first: videoId, commentId, author, authorChannelId, comment, emojiOnly, commentedAt "
                            + "(the comment answered), reply, repliedAt (the owner's words), link."),
            @ToolResultField(name = "scanned", type = ToolFieldType.INTEGER, always = true,
                    description = "How many comment threads were looked through."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when more replies were found than returned, or older threads were not looked through."),
            @ToolResultField(name = "videosRead", type = ToolFieldType.INTEGER, description = VIDEOS_READ),
            @ToolResultField(name = "videosWithCommentsOff", type = ToolFieldType.INTEGER, description = COMMENTS_OFF),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, description = SCAN_NOTE)
    })
    public Map<String, Object> listYouTubeChannelReplies(
            @ToolParam(required = false, description = "How many to return, 1-" + MAX_REPLIES + " (default "
                    + DEFAULT_REPLIES + ")") Integer limit,
            @ToolParam(required = false, description = "An ISO time, e.g. 2026-11-01T08:00:00Z: only replies written "
                    + "after it") String publishedAfter) {
        try {
            Instant after = null;
            if (publishedAfter != null && !publishedAfter.isBlank()) {
                after = instant(publishedAfter.strip());
                if (after == null) return failure("publishedAfter is an ISO time, such as 2026-11-01T08:00:00Z.");
            }
            ApiClient api = YouTubeCalls.client(http, credentials(Access.READ), YouTubeCalls.Api.DATA);
            return channelReplies(api, limit == null ? DEFAULT_REPLIES : Math.max(1, Math.min(MAX_REPLIES, limit)), after);
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "One video of the user's YouTube channel, live: its title, description, when it was published "
            + "and its visibility.")
    @ToolResult({
            @ToolResultField(name = "videoId", type = ToolFieldType.STRING, always = true, description = "The video's ID."),
            @ToolResultField(name = "title", type = ToolFieldType.STRING, always = true, description = "Its title."),
            @ToolResultField(name = "description", type = ToolFieldType.STRING, always = true, description = "Its description."),
            @ToolResultField(name = "publishedAt", type = ToolFieldType.STRING, description = "When it was published."),
            @ToolResultField(name = "visibility", type = ToolFieldType.STRING, description = "public, unlisted or private."),
            @ToolResultField(name = "link", type = ToolFieldType.STRING, always = true, description = "Its address.")
    })
    public Map<String, Object> getYouTubeVideo(@ToolParam(description = "The video's ID") String videoId) {
        try {
            String id = videoId == null ? "" : videoId.strip();
            if (!ID.matcher(id).matches()) return failure("That is not a YouTube video ID.");
            JsonNode video = client(Access.READ, false).getJson(V3 + "videos", Map.of("part", "snippet,status", "id", id))
                    .path("items").path(0);
            if (video.isMissingNode() || video.isNull()) return failure("The channel has no video " + id + ".");
            JsonNode s = video.path("snippet");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("videoId", id);
            out.put("title", s.path("title").asText(""));
            out.put("description", s.path("description").asText(""));
            out.put("publishedAt", s.path("publishedAt").asText(""));
            out.put("visibility", video.path("status").path("privacyStatus").asText(""));
            out.put("link", "https://www.youtube.com/watch?v=" + id);
            return out;
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "Post a reply under a comment on the user's YouTube channel, as the channel. Takes the comment's "
            + "commentId - a top-level comment's or a viewer's reply's - and the reply's text, in the commenter's language. "
            + "A reply to a reply appears in the same thread; begin it with the viewer's @name. Write a short, friendly "
            + "reply grounded in the user's own sources; never invent prices, stock or dates.")
    @ToolResult({
            @ToolResultField(name = "commentId", type = ToolFieldType.STRING, always = true,
                    description = "The comment answered - echoed back from the request."),
            @ToolResultField(name = "replyId", type = ToolFieldType.STRING, always = true,
                    description = "The new reply's ID."),
            @ToolResultField(name = "link", type = ToolFieldType.STRING, description = "Where the reply can be seen.")
    })
    public Map<String, Object> replyToYouTubeComment(
            @ToolParam(description = "The commentId of the comment to answer") String commentId,
            @ToolParam(description = "The reply, as it will appear under the comment") String text,
            @ToolParam(required = false, description = CONFIDENCE) String confidence) {
        return replyToYouTubeComment(commentId, text);
    }

    /**
     * How sure the agent is that the person sends the reply as written: HIGH, MEDIUM or LOW (2026-11-07, the maintainer:
     * "when propose reply, the agent should also provide a confident level"). It posts nothing and changes nothing here -
     * it is kept with the approval request's arguments, where a team's Approvals page reads it, checked against what the
     * person did with drafts like it (Constellations' DraftConfidence).
     */
    public static final String CONFIDENCE = "HIGH, MEDIUM or LOW: how likely the owner sends it unchanged";

    /** The reply with no confidence stated. */
    public Map<String, Object> replyToYouTubeComment(String commentId, String text) {
        try {
            String parent = parentId(commentId);
            if (parent == null) return failure("Name the comment to answer by its commentId, from listYouTubeComments.");
            String reply = text == null ? "" : text.strip();
            if (reply.isEmpty()) return failure("A reply needs text.");
            if (reply.length() > MAX_REPLY_CHARS) {
                return failure("YouTube takes at most " + MAX_REPLY_CHARS + " characters in a reply; this one has "
                        + reply.length() + ". Shorten it.");
            }
            mcpGuard.checkYouTubeWrite(SearchContext.getUserId());
            ResolvedCredentials credentials = credentials(Access.WRITE);
            // NEVER AS ONE CHANNEL ON ANOTHER'S VIDEO (2026-11-05): a draft made before its team moved to another
            // channel, approved after, would post as the new channel under the old one's video. Nothing is posted.
            String other = otherChannel(client(http, credentials, false), parent);
            if (other != null) return failure("Not posted: " + other);
            return postReply(client(http, credentials, true), parent, reply);
        } catch (ConnectorHttpException e) {
            // No answer at all: the request may have reached YouTube. Retrying blind could post it twice.
            if (e.status() == 0) {
                return failure(e.getMessage() + " It is not known whether the reply was posted - check the comment with "
                        + "listYouTubeComments before trying again.");
            }
            return failure(describe(e));
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    /** The tool an agent revising the drafts still waiting reads them with. */
    public static final String LIST_WAITING = "listWaitingYouTubeReplies";
    static final int WAITING_MAX = 50;

    /**
     * THE REPLIES STILL WAITING FOR THE PERSON (2026-11-03, the maintainer: after a partial batch "the agent
     * should be aware and revise the remaining comments ... only the comments that are not actively being
     * edited by the user"): the turn's person's replyToYouTubeComment requests still waiting, newest first,
     * from the approvals inbox - no YouTube call - each with what it answers and {@code beingEdited} while the
     * person has its editor open (ApprovalInbox.holdEditing), when a revision of it is refused anyway.
     */
    @Tool(name = LIST_WAITING, description = "Your replies to YouTube comments still waiting for the person's approval, "
            + "newest first, a page at a time, with how many wait in all. Skip one with beingEdited true: the person is "
            + "editing it. To revise one, post replyToYouTubeComment again for its commentId.")
    @ToolResult({
            @ToolResultField(name = "replies", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "commentId, link, author, comment, draft, waitingMinutes, beingEdited."),
            @ToolResultField(name = "total", type = ToolFieldType.INTEGER, always = true,
                    description = "How many wait in all - the number to give, never a count of one page."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "true when more wait after this page: ask for the next page.")
    })
    public Map<String, Object> listWaitingYouTubeReplies(
            @ToolParam(required = false, description = "How many to return, 1-" + WAITING_MAX + " (default 20)") Integer limit,
            @ToolParam(required = false, description = "Which page, from 1 (default 1)") Integer page) {
        ApprovalInbox inbox = approvals == null ? null : approvals.getIfAvailable();
        if (inbox == null) return failure("No approvals inbox here: nothing waits for approval.");
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, WAITING_MAX));
        int p = page == null ? 0 : Math.max(0, page - 1);
        try {
            ApprovalInbox.WaitingPage found = inbox.waiting(SearchContext.getUserId(), YouTubeApprovalContext.TOOL, p, n);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ApprovalInbox.Waiting w : found.items()) rows.add(waitingRow(w, clock.instant()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("replies", rows);
            out.put("total", found.total());
            out.put("more", (long) (p + 1) * n < found.total());
            return out;
        } catch (RuntimeException e) {
            return failure("Could not read the replies waiting for approval: " + e.getMessage());
        }
    }

    // One waiting reply as the tool lists it: the comment from the request's context, the draft from its arguments.
    static Map<String, Object> waitingRow(ApprovalInbox.Waiting w, java.time.Instant now) {
        ApprovalInbox.View v = w.view();
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode a = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(v.arguments() == null ? "{}" : v.arguments());
            if (a != null) {
                args.put("commentId", a.path("commentId").asText(""));
                args.put("text", a.path("text").asText(""));
            }
        } catch (Exception e) {
            // Unreadable arguments: the row says what the context says.
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("commentId", args.getOrDefault("commentId", ""));
        String link = null, author = null, comment = null;
        if (v.context() != null) {
            for (ai.proxyma.core.chat.approval.ApprovalContext.Field f : v.context().fields()) {
                if ("comment".equals(f.key())) {
                    comment = f.value();
                    link = f.href();
                } else if ("viewer".equals(f.key())) {
                    author = f.value();
                }
            }
        }
        row.put("link", link);
        row.put("author", author);
        row.put("comment", comment);
        row.put("draft", args.getOrDefault("text", ""));
        row.put("waitingMinutes", v.requestedAt() == null ? null
                : Math.max(0, java.time.Duration.between(v.requestedAt(), now).toMinutes()));
        row.put("beingEdited", w.beingEdited());
        return row;
    }

    // -- the channel's numbers ------------------------------------------------------------------

    @Tool(description = "The user's YouTube channel at a glance, live: title, handle, description, subscriber count, lifetime views, "
            + "number of public videos, when it was created and its uploads playlist. For numbers over a period "
            + "(views, watch time, subscribers gained) use getYouTubeAnalytics; for impressions and click-through rate "
            + "getYouTubeReach.")
    @ToolResult({
            @ToolResultField(name = "channelId", type = ToolFieldType.STRING, always = true, description = "The channel's ID."),
            @ToolResultField(name = "title", type = ToolFieldType.STRING, always = true, description = "The channel's title."),
            @ToolResultField(name = "handle", type = ToolFieldType.STRING, description = "Its handle (@name), when it has one."),
            @ToolResultField(name = "description", type = ToolFieldType.STRING, always = true,
                    description = "The channel's description, empty when it has none."),
            @ToolResultField(name = "subscriberCount", type = ToolFieldType.INTEGER, nullable = true,
                    description = "Subscribers, rounded down by YouTube to three significant figures; null when the "
                            + "channel hides it."),
            @ToolResultField(name = "viewCount", type = ToolFieldType.INTEGER, always = true,
                    description = "Lifetime views of all its videos."),
            @ToolResultField(name = "videoCount", type = ToolFieldType.INTEGER, always = true,
                    description = "Its PUBLIC videos - unlisted and private ones are not counted, even for the owner."),
            @ToolResultField(name = "publishedAt", type = ToolFieldType.STRING, description = "When the channel was created."),
            @ToolResultField(name = "uploadsPlaylistId", type = ToolFieldType.STRING, description = "The playlist of all its uploads."),
            @ToolResultField(name = "link", type = ToolFieldType.STRING, always = true, description = "The channel's address."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, always = true, description = "How to read the numbers.")
    })
    public Map<String, Object> getYouTubeChannelStats() {
        try {
            ApiClient api = YouTubeCalls.client(http, credentials(Access.READ), YouTubeCalls.Api.DATA);
            return channelStats(api);
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "The newest uploads of the user's YouTube channel, live, with their numbers: videoId, title, link, "
            + "publishedAt, durationSeconds, visibility, views, likes and comments (lifetime). Optionally only those "
            + "published after a date, and each one's description. For a video's numbers over a period use getYouTubeAnalytics.")
    @ToolResult({
            @ToolResultField(name = "channel", type = ToolFieldType.STRING, always = true, description = "The channel's title."),
            @ToolResultField(name = "videos", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Newest first: videoId, title, link, publishedAt, durationSeconds, visibility (public, "
                            + "unlisted, private), live (live or upcoming, when it is a broadcast), views, likes, comments "
                            + "(a count the owner hid is left out), description (when asked, cut at " + MAX_VIDEO_DESCRIPTION
                            + " characters)."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when older uploads were not returned."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, always = true, description = "How to read the numbers.")
    })
    public Map<String, Object> listYouTubeVideos(
            @ToolParam(required = false, description = "How many to return, 1-50 (default 20)") Integer limit,
            @ToolParam(required = false, description = "Only videos published after this date, YYYY-MM-DD") String publishedAfter,
            @ToolParam(required = false, description = "true to add each video's description (default false)") Boolean withDescriptions) {
        try {
            Instant after = null;
            if (publishedAfter != null && !publishedAfter.isBlank()) {
                after = instant(publishedAfter.strip());
                if (after == null) return failure("publishedAfter is a date written YYYY-MM-DD, such as 2026-10-01.");
            }
            ApiClient api = YouTubeCalls.client(http, credentials(Access.READ), YouTubeCalls.Api.DATA);
            return videos(api, limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit)), after,
                    Boolean.TRUE.equals(withDescriptions));
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "The user's YouTube channel's numbers over a period, from YouTube Analytics, as one of these named "
            + "reports: overview (by day: engagedViews, views, estimatedMinutesWatched, averageViewDuration, "
            + "averageViewPercentage, subscribersGained, subscribersLost, likes, comments, shares - with totals), "
            + "topVideos (the same by video, top 50), trafficSources (where views came from), searchTerms (what viewers "
            + "searched on YouTube, top 25), subscribedStatus (subscribers against others), contentType (Shorts, videos, "
            + "live), geography (top 25 countries) and retention (one video's audience curve - needs videoId). Optionally "
            + "one video (not for topVideos). Dates YYYY-MM-DD, at most 365 days; data lags two to three days.")
    @ToolResult({
            @ToolResultField(name = "report", type = ToolFieldType.STRING, always = true, description = "The report run."),
            @ToolResultField(name = "startDate", type = ToolFieldType.STRING, always = true, description = "The period's first day."),
            @ToolResultField(name = "endDate", type = ToolFieldType.STRING, always = true, description = "The period's last day."),
            @ToolResultField(name = "videoId", type = ToolFieldType.STRING, description = "The video it is about, when one was named."),
            @ToolResultField(name = "grouping", type = ToolFieldType.STRING,
                    description = "overview only: day, or week for a period past 60 days."),
            @ToolResultField(name = "totals", type = ToolFieldType.OBJECT,
                    description = "overview only: the whole period's figures, averages weighted by views."),
            @ToolResultField(name = "columns", type = ToolFieldType.STRING_ARRAY, always = true,
                    description = "The names of each row's values, in order."),
            @ToolResultField(name = "rows", type = ToolFieldType.ANY, always = true,
                    description = "At most 50 rows, each an array of values in the order of columns; numbers as numbers."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when rows were left out."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, always = true,
                    description = "How to read the report: units, what it leaves out, the lag.")
    })
    public Map<String, Object> getYouTubeAnalytics(
            @ToolParam(description = "The period's first day, YYYY-MM-DD") String startDate,
            @ToolParam(description = "The period's last day, YYYY-MM-DD") String endDate,
            @ToolParam(description = "overview, topVideos, trafficSources, searchTerms, subscribedStatus, contentType, "
                    + "geography or retention") String report,
            @ToolParam(required = false, description = "A video's ID, for one video's numbers (required for retention)") String videoId) {
        try {
            YouTubeAnalytics.Report r = YouTubeAnalytics.Report.of(report);
            if (r == null) {
                return failure("report is one of overview, topVideos, trafficSources, searchTerms, subscribedStatus, "
                        + "contentType, geography, retention.");
            }
            String video = videoId == null || videoId.isBlank() ? null : videoId.strip();
            if (video != null && !ID.matcher(video).matches()) return failure("That is not a YouTube video ID.");
            if (r.needsVideo && video == null) {
                return failure("The retention report is about one video: give its videoId, from listYouTubeVideos.");
            }
            if (!r.takesVideo && video != null) return failure("topVideos covers the whole channel; leave videoId out.");
            LocalDate[] period = period(startDate, endDate);
            ResolvedCredentials credentials = credentials(Access.READ);
            if (YouTubeCalls.analyticsNotGranted(credentials)) return failure(YouTubeCalls.analyticsNotAllowed().getMessage());
            ApiClient analytics = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.ANALYTICS);
            ApiClient data = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.DATA);
            return YouTubeAnalytics.run(analytics, data, r, period[0], period[1], video);
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    @Tool(description = "How often YouTube showed the user's videos (thumbnail impressions) and how often that led to a "
            + "view (click-through rate), per day and per video, from the YouTube Reporting API - the only place YouTube "
            + "publishes them. The first call starts YouTube's collection: the first day arrives about two days later, "
            + "and YouTube keeps 60 days. Dates YYYY-MM-DD.")
    @ToolResult({
            @ToolResultField(name = "startDate", type = ToolFieldType.STRING, always = true, description = "The period's first day."),
            @ToolResultField(name = "endDate", type = ToolFieldType.STRING, always = true, description = "The period's last day."),
            @ToolResultField(name = "collecting", type = ToolFieldType.STRING, always = true,
                    description = "started (this call started collection - nothing to read yet), waiting (started "
                            + "recently, no day has arrived) or ready."),
            @ToolResultField(name = "collectionStartedOn", type = ToolFieldType.STRING,
                    description = "The day YouTube started collecting for this channel, when known."),
            @ToolResultField(name = "days", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Per day, in date order: date, impressions, ctr."),
            @ToolResultField(name = "videos", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Per video, most shown first, at most 50: videoId, title, impressions, ctr."),
            @ToolResultField(name = "totals", type = ToolFieldType.OBJECT,
                    description = "The period's impressions and ctr, when any day was read."),
            @ToolResultField(name = "missingDays", type = ToolFieldType.INTEGER,
                    description = "Days of the period with no report yet."),
            @ToolResultField(name = "more", type = ToolFieldType.BOOLEAN, always = true,
                    description = "True when videos were left out."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING, always = true,
                    description = "What the numbers are, and when more arrives.")
    })
    public Map<String, Object> getYouTubeReach(
            @ToolParam(description = "The period's first day, YYYY-MM-DD") String startDate,
            @ToolParam(description = "The period's last day, YYYY-MM-DD") String endDate) {
        try {
            LocalDate[] period = period(startDate, endDate);
            ResolvedCredentials credentials = credentials(Access.READ);
            if (YouTubeCalls.analyticsNotGranted(credentials)) return failure(YouTubeCalls.analyticsNotAllowed().getMessage());
            ApiClient reporting = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.REPORTING);
            ApiClient data = YouTubeCalls.client(http, credentials, YouTubeCalls.Api.DATA);
            return reach(reporting, data, credentials.connectionId(), period[0], period[1]);
        } catch (Exception e) {
            return failure(describe(e));
        }
    }

    Map<String, Object> channelStats(ApiClient api) {
        return channelStats(api, null);
    }

    /** The same, handing the channel's snippet to {@code snippet} too - its picture is read from it (YouTubeAvatar). */
    Map<String, Object> channelStats(ApiClient api, java.util.function.Consumer<JsonNode> snippet) {
        JsonNode channel = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(V3 + "channels",
                Map.of("part", "snippet,statistics,contentDetails", "mine", "true"))).path("items").path(0);
        if (channel.isMissingNode() || channel.isNull()) return failure("The connection's Google account has no YouTube channel.");
        JsonNode s = channel.path("snippet");
        if (snippet != null) snippet.accept(s);
        JsonNode stats = channel.path("statistics");
        String id = channel.path("id").asText("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("channelId", id);
        out.put("title", s.path("title").asText(""));
        String handle = s.path("customUrl").asText("");
        if (!handle.isEmpty()) out.put("handle", handle);
        out.put("description", cut(s.path("description").asText("").strip(), MAX_CHANNEL_DESCRIPTION));
        boolean hidden = stats.path("hiddenSubscriberCount").asBoolean(false);
        out.put("subscriberCount", hidden || !stats.has("subscriberCount") ? null : stats.path("subscriberCount").asLong());
        out.put("viewCount", stats.path("viewCount").asLong(0));
        out.put("videoCount", stats.path("videoCount").asLong(0));
        if (s.hasNonNull("publishedAt")) out.put("publishedAt", s.path("publishedAt").asText());
        String uploads = channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        if (!uploads.isEmpty()) out.put("uploadsPlaylistId", uploads);
        out.put("link", handle.startsWith("@") ? "https://www.youtube.com/" + handle : "https://www.youtube.com/channel/" + id);
        out.put("note", "subscriberCount is rounded down by YouTube to three significant figures; videoCount counts public "
                + "videos only; viewCount is lifetime, and since 2026-08-24 also counts plays that begin on autoplay or "
                + "hover - follow getYouTubeAnalytics' engagedViews for trends.");
        return out;
    }

    // At most max characters of text, the cut marked so the model does not take it for the whole.
    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end).stripTrailing() + CUT;
    }

    /**
     * The uploads listing. Descriptions, when asked, cost nothing more: the one videos.list call for the
     * page already carries the snippet they are in.
     */
    Map<String, Object> videos(ApiClient api, int limit, Instant after, boolean withDescriptions) {
        JsonNode channel = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(V3 + "channels",
                Map.of("part", "snippet,contentDetails", "mine", "true"))).path("items").path(0);
        if (channel.isMissingNode() || channel.isNull()) return failure("The connection's Google account has no YouTube channel.");
        String uploads = channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        List<String> ids = new ArrayList<>();
        boolean more = false;
        if (!uploads.isEmpty()) {
            // One page of the uploads playlist, newest first: limit is at most 50, its page size.
            ApiResponse listed = api.get(V3 + "playlistItems", Map.of("part", "contentDetails", "playlistId", uploads,
                    "maxResults", 50));
            // A channel that never uploaded has no uploads playlist to read.
            JsonNode page = listed.status() == 404 ? null : YouTubeCalls.json(api, YouTubeCalls.Api.DATA, listed);
            if (page != null) {
                boolean older = false;
                for (JsonNode item : page.path("items")) {
                    String videoId = item.path("contentDetails").path("videoId").asText("");
                    if (videoId.isEmpty()) continue;
                    if (after != null) {
                        Instant published = instantOrNull(item.path("contentDetails").path("videoPublishedAt").asText(""));
                        if (published != null && !published.isAfter(after)) {
                            older = true;
                            continue;
                        }
                    }
                    if (ids.size() >= limit) {
                        more = true;
                        continue;
                    }
                    ids.add(videoId);
                }
                String next = page.path("nextPageToken").asText("");
                if (!next.isEmpty() && !older) more = true;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!ids.isEmpty()) {
            JsonNode found = YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(V3 + "videos",
                    Map.of("part", "snippet,contentDetails,statistics,status", "id", String.join(",", ids), "maxResults", 50)));
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode v : found.path("items")) byId.put(v.path("id").asText(""), v);
            for (String id : ids) {
                JsonNode v = byId.get(id);
                if (v == null) continue;
                Map<String, Object> row = videoRow(v);
                if (withDescriptions) {
                    row.put("description", cut(v.path("snippet").path("description").asText("").strip(), MAX_VIDEO_DESCRIPTION));
                }
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("channel", channel.path("snippet").path("title").asText(""));
        out.put("videos", rows);
        out.put("more", more);
        out.put("note", "Lifetime counts, live. Since 2026-08-24 views also counts plays that begin on autoplay or hover. "
                + "YouTube's Data API does not say which video is a Short: getYouTubeAnalytics report=contentType splits "
                + "the channel's numbers by format.");
        return out;
    }

    private static Map<String, Object> videoRow(JsonNode v) {
        JsonNode s = v.path("snippet");
        JsonNode stats = v.path("statistics");
        String id = v.path("id").asText("");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("videoId", id);
        row.put("title", s.path("title").asText(""));
        row.put("link", "https://www.youtube.com/watch?v=" + id);
        row.put("publishedAt", s.path("publishedAt").asText(""));
        Long seconds = seconds(v.path("contentDetails").path("duration").asText(""));
        if (seconds != null) row.put("durationSeconds", seconds);
        row.put("visibility", v.path("status").path("privacyStatus").asText(""));
        String live = s.path("liveBroadcastContent").asText("none");
        if (!live.isEmpty() && !"none".equals(live)) row.put("live", live);
        if (stats.has("viewCount")) row.put("views", stats.path("viewCount").asLong());
        if (stats.has("likeCount")) row.put("likes", stats.path("likeCount").asLong());
        if (stats.has("commentCount")) row.put("comments", stats.path("commentCount").asLong());
        return row;
    }

    // An ISO 8601 duration ("PT4M13S", "P1DT2H") in seconds, or null.
    static Long seconds(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Duration.parse(iso).getSeconds();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    Map<String, Object> reach(ApiClient reporting, ApiClient data, UUID connectionId, LocalDate start, LocalDate end) {
        YouTubeReach.Job job = reach.job(reporting, connectionId);
        List<YouTubeReach.Row> rows = job.madeNow() ? List.of() : reach.rows(reporting, job.id(), start, end);
        if (rows == null) {
            // YouTube no longer has the job (it expires one nobody downloads from): make it again.
            reach.forget(connectionId);
            job = reach.job(reporting, connectionId);
            rows = List.of();
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate started = job.madeNow() ? today : YouTubeReach.madeOn(job.createTime());
        List<Map<String, Object>> days = YouTubeReach.byDay(rows, start, end);
        List<Map<String, Object>> videos = YouTubeReach.byVideo(rows, start, end);
        String keep = "YouTube keeps 60 days, so keep your own history of what you read (a daily snapshot).";
        String state;
        String note;
        if (job.madeNow()) {
            state = "started";
            note = "Collection started on " + today + "; the first day arrives in about two days. " + keep;
        } else if (days.isEmpty() && started != null && !started.isBefore(today.minusDays(3))) {
            state = "waiting";
            note = "Collection started on " + started + "; the first day arrives about two days after that, so there is "
                    + "nothing to read yet. " + keep;
        } else {
            state = "ready";
            note = days.isEmpty()
                    ? "YouTube has no reach report for that period: a day arrives about two days after it ends. " + keep
                    : "impressions are thumbnail impressions (shown for over a second, at least half visible); ctr is the "
                            + "click-through rate as YouTube's report states it (clicks over impressions), averaged here "
                            + "weighted by impressions - compare videos with each other and with the channel's own past. "
                            + "A day arrives about two days after it ends. " + keep;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("startDate", start.toString());
        out.put("endDate", end.toString());
        out.put("collecting", state);
        if (started != null) out.put("collectionStartedOn", started.toString());
        out.put("days", days);
        boolean more = videos.size() > YouTubeReach.MAX_VIDEOS;
        if (more) videos = new ArrayList<>(videos.subList(0, YouTubeReach.MAX_VIDEOS));
        out.put("videos", withTitles(data, videos));
        if (!days.isEmpty()) {
            out.put("totals", YouTubeReach.totals(rows, start, end));
            LocalDate last = end.isAfter(today) ? today : end;
            long expected = last.toEpochDay() - start.toEpochDay() + 1;
            if (expected > days.size()) out.put("missingDays", expected - days.size());
        }
        out.put("more", more);
        out.put("note", note);
        return out;
    }

    // Each video with its title, from one Data API call; a title that cannot be read is left empty.
    private static List<Map<String, Object>> withTitles(ApiClient data, List<Map<String, Object>> videos) {
        if (videos.isEmpty()) return videos;
        Map<String, String> titles = new LinkedHashMap<>();
        try {
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> v : videos) ids.add(String.valueOf(v.get("videoId")));
            JsonNode page = data.getJson(V3 + "videos", Map.of("part", "snippet", "id", String.join(",", ids), "maxResults", 50));
            for (JsonNode v : page.path("items")) titles.put(v.path("id").asText(""), v.path("snippet").path("title").asText(""));
        } catch (RuntimeException unreadable) {
            // the numbers stand without their titles
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : videos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoId", v.get("videoId"));
            row.put("title", titles.getOrDefault(String.valueOf(v.get("videoId")), ""));
            row.put("impressions", v.get("impressions"));
            row.put("ctr", v.get("ctr"));
            out.add(row);
        }
        return out;
    }

    // A period as two dates, checked: written YYYY-MM-DD, in order, at most a year, not in the future.
    LocalDate[] period(String startDate, String endDate) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate == null ? "" : startDate.strip());
            end = LocalDate.parse(endDate == null ? "" : endDate.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("startDate and endDate are dates written YYYY-MM-DD, such as 2026-10-01.");
        }
        if (start.isAfter(end)) throw new IllegalArgumentException("startDate is after endDate.");
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        if (days > YouTubeAnalytics.MAX_DAYS) {
            throw new IllegalArgumentException("A period is at most 365 days; this one has " + days + ".");
        }
        // A day ahead of UTC is still today somewhere.
        if (end.isAfter(LocalDate.now(clock).plusDays(1))) {
            throw new IllegalArgumentException("endDate is in the future. YouTube has numbers up to about three days ago.");
        }
        return new LocalDate[] {start, end};
    }

    // A date (YYYY-MM-DD, the start of that day in UTC) or an instant, or null.
    private static Instant instant(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return instantOrNull(value);
        }
    }

    private static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // -- the calls, over a client already resolved ---------------------------------------------

    /**
     * The listing. Threads come newest first ({@code order=time}), so one published before {@code after} ends
     * it: no further page is asked for. A comment a reply to which waits for approval for this person
     * (ApprovalInbox, subject "youtube-comment:<id>") is marked draftWaiting, and left out of an
     * unanswered-only listing - its draft exists already.
     *
     * <p><b>Old comments too, newest first</b> (2026-11-03, the maintainer: comments from "long long time ago
     * also need to be replied", "start with the latest-not-replied"). An unanswered-only listing looks through
     * up to {@link #DEEP_PAGES} pages, stopping as soon as it has {@code limit}; {@code before} starts it below
     * a point (YouTubeThreadScan.before); and {@code oldestScanned} says how far down it looked, so the next
     * listing continues below it: everything newer was looked at. Read at once that is the publication of the
     * last thread looked at - the {@code limit}-th match when it stopped there, {@code after} when it stopped
     * at that. Read video by video, threads are grouped by video, so the point is a VIDEO's publication -
     * {@code before} then reads only older videos, every comment of each - and it moves past a video only once
     * all its unanswered comments were returned: a video that would overflow the limit waits for the next
     * listing, unless it is the first, which then gives the limit and is passed with a note.
     */
    Map<String, Object> listComments(ApiClient api, String videoId, boolean unansweredOnly, int limit, Instant after,
                                     Instant before) {
        JsonNode channel = myChannel(api);
        if (channel.isMissingNode() || channel.isNull()) return failure("The connection's Google account has no YouTube channel.");
        String channelId = channel.path("id").asText("");
        List<Map<String, Object>> found = new ArrayList<>();
        int pages = unansweredOnly ? DEEP_PAGES : SCAN_PAGES;
        YouTubeThreadScan scan = videoId != null && !videoId.isBlank()
                ? YouTubeThreadScan.video(api, videoId, pages)
                : YouTubeThreadScan.channel(api, channel, pages);
        if (before != null) scan.before(before);
        // How many long threads may still be read whole (comments.list parentId=) by this listing.
        int[] deep = {DEEP_THREADS};
        boolean full = false;
        boolean left = false;
        String oldest = null;
        String partly = null;
        List<JsonNode> threads;
        while (!full && (threads = scan.next()) != null) {
            if (scan.perVideo()) {
                if (scan.passedAt() != null) oldest = scan.passedAt().toString();
                List<Map<String, Object>> rows = matching(api, threads, channelId, unansweredOnly, after, deep);
                if (found.size() + rows.size() <= limit) {
                    found.addAll(rows);
                    if (scan.batchVideoAt() != null) oldest = scan.batchVideoAt().toString();
                    if (!scan.batchWhole()) partly = scan.batchVideo();
                    full = found.size() >= limit;
                } else if (found.isEmpty()) {
                    // One video holds more than a listing returns: the newest of them, and the video is passed.
                    found.addAll(rows.subList(0, limit));
                    if (scan.batchVideoAt() != null) oldest = scan.batchVideoAt().toString();
                    partly = scan.batchVideo();
                    full = true;
                    left = true;
                } else {
                    // This video's comments wait for the next listing, whole.
                    full = true;
                    left = true;
                }
                continue;
            }
            // Read at once: in order, each thread inside the window looked at, until the limit-th match.
            List<Instant> times = new ArrayList<>();
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            List<Map<String, Object>> candidates = new ArrayList<>();
            boolean older = false;
            for (JsonNode thread : threads) {
                Instant at = instantOrNull(thread.path("snippet").path("topLevelComment").path("snippet").path("publishedAt").asText(""));
                times.add(at);
                List<Map<String, Object>> row = null;
                if (after != null && at != null && !at.isAfter(after)) {
                    older = true;
                } else if (before == null || at == null || at.isBefore(before)) {
                    row = candidates(api, thread, channelId, unansweredOnly, deep);
                    candidates.addAll(row);
                    if (row.isEmpty()) row = null;
                } else {
                    // Newer than the point: not looked at by this listing.
                    times.set(times.size() - 1, null);
                }
                rows.add(row);
            }
            Set<String> waiting = draftsWaiting(candidates);
            for (int i = 0; i < threads.size() && !full; i++) {
                Instant at = times.get(i);
                boolean inWindow = at == null ? rows.get(i) != null : after == null || at.isAfter(after);
                if (!inWindow) continue;
                if (at != null) oldest = at.toString();
                List<Map<String, Object>> thread = rows.get(i);
                if (thread == null) continue;
                for (int k = 0; k < thread.size() && !full; k++) {
                    Map<String, Object> row = thread.get(k);
                    boolean drafted = waiting.contains(String.valueOf(row.get("commentId")));
                    if (drafted && unansweredOnly) continue;
                    if (drafted) row.put("draftWaiting", true);
                    found.add(row);
                    if (found.size() >= limit) {
                        full = true;
                        left = k < thread.size() - 1 || hasWindowAfter(threads, i, after, before);
                    }
                }
            }
            if (older) {
                scan.olderSeen();
                // Everything newer than after was looked at.
                if (!full) oldest = after.toString();
            }
        }
        // Videos with comments off after the last one read were passed too.
        if (scan.perVideo() && !full && scan.passedAt() != null) oldest = scan.passedAt().toString();
        boolean beyond = scan.beyondReach();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("channel", channel.path("snippet").path("title").asText(""));
        out.put("comments", found);
        out.put("more", !beyond && (full ? left || scan.more() : scan.more()));
        if (oldest != null) out.put("oldestScanned", oldest);
        noted(out, scan);
        if (beyond) {
            out.put("note", "publishedBefore lies deeper than a listing reaches (the channel's newest "
                    + (YouTubeThreadScan.SKIP_PAGES * 100) + " comment threads), so nothing older can be listed.");
        } else if (partly != null) {
            String said = out.get("note") == null ? "" : out.get("note") + " ";
            out.put("note", said + "Video " + partly + " has more comments than one listing reads; list them with videoId="
                    + partly + ".");
        }
        return out;
    }

    // One video's threads as listed, in order, without those an unanswered-only listing leaves out (answered,
    // the channel's own, a reply waiting for approval); after: only newer ones.
    private List<Map<String, Object>> matching(ApiClient api, List<JsonNode> threads, String channelId,
                                               boolean unansweredOnly, Instant after, int[] deep) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode thread : threads) {
            Instant at = instantOrNull(thread.path("snippet").path("topLevelComment").path("snippet").path("publishedAt").asText(""));
            if (after != null && at != null && !at.isAfter(after)) continue;
            rows.addAll(candidates(api, thread, channelId, unansweredOnly, deep));
        }
        Set<String> waiting = draftsWaiting(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean drafted = waiting.contains(String.valueOf(row.get("commentId")));
            if (drafted && unansweredOnly) continue;
            if (drafted) row.put("draftWaiting", true);
            out.add(row);
        }
        return out;
    }

    /**
     * A thread as listed. Listing everything: the thread, one row. Unanswered only (2026-11-05, the conversation):
     * a row per message still waiting for the channel (YouTubeConversation.pending) - its top-level comment when
     * nothing the channel wrote follows it, and each viewer reply after the channel's last message, the channel's
     * own threads included - each with the conversation before it. A thread with more replies than YouTube carries
     * inline is read whole while {@code deep} lasts, else judged on what came inline.
     */
    private static List<Map<String, Object>> candidates(ApiClient api, JsonNode thread, String channelId,
                                                        boolean unansweredOnly, int[] deep) {
        if (!unansweredOnly) {
            Map<String, Object> row = row(thread, channelId);
            row.remove("byChannel");
            return List.of(row);
        }
        List<JsonNode> replies = YouTubeConversation.inlineReplies(thread);
        if (YouTubeConversation.truncated(thread) && deep[0] > 0) {
            deep[0]--;
            try {
                replies = YouTubeConversation.replies(api, thread.path("id").asText(""), YouTubeConversation.REPLY_PAGES);
            } catch (RuntimeException e) {
                // Judged on what came inline.
            }
        }
        List<YouTubeConversation.Pending> pending = YouTubeConversation.pending(thread, replies, channelId);
        if (pending.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (YouTubeConversation.Pending p : pending) out.add(pendingRow(thread, channelId, p));
        return out;
    }

    // A message waiting for the channel, as the agent reads it: the thread's row for its top-level comment, else the
    // reply's own, with the conversation before it and what it answers.
    private static Map<String, Object> pendingRow(JsonNode thread, String channelId, YouTubeConversation.Pending p) {
        Map<String, Object> row = row(thread, channelId);
        row.remove("byChannel");
        if (!p.reply()) return row;
        YouTubeConversation.Message m = p.message();
        row.put("commentId", m.commentId());
        row.put("parentId", p.threadId());
        row.put("author", m.author());
        row.put("authorChannelId", m.authorChannelId());
        row.put("text", m.text());
        row.put("emojiOnly", emojiOnly(m.text()));
        row.put("publishedAt", m.publishedAt() == null ? "" : m.publishedAt().toString());
        row.put("answeredByChannel", false);
        List<Map<String, Object>> conversation = new ArrayList<>();
        for (YouTubeConversation.Message b : p.before()) {
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("author", b.author());
            if (b.byChannel()) said.put("byChannel", true);
            said.put("text", b.text());
            conversation.add(said);
        }
        row.put("conversation", conversation);
        if (p.inReplyTo() != null) row.put("inReplyTo", p.inReplyTo());
        if (!p.videoId().isEmpty()) row.put("link", "https://www.youtube.com/watch?v=" + p.videoId() + "&lc=" + m.commentId());
        return row;
    }

    // Whether a thread after index i on this page is inside the listing's window.
    private static boolean hasWindowAfter(List<JsonNode> threads, int i, Instant after, Instant before) {
        for (int j = i + 1; j < threads.size(); j++) {
            Instant at = instantOrNull(threads.get(j).path("snippet").path("topLevelComment").path("snippet").path("publishedAt").asText(""));
            if (after != null && at != null && !at.isAfter(after)) continue;
            if (before != null && at != null && !at.isBefore(before)) continue;
            return true;
        }
        return false;
    }

    // The signed-in account's channel, with its uploads playlist - what a scan falls back on.
    private static JsonNode myChannel(ApiClient api) {
        return YouTubeCalls.json(api, YouTubeCalls.Api.DATA, api.get(V3 + "channels",
                Map.of("part", "snippet,contentDetails", "mine", "true"))).path("items").path(0);
    }

    // A channel read video by video says so, with how many videos had comments off.
    private static void noted(Map<String, Object> out, YouTubeThreadScan scan) {
        String note = scan.note();
        if (note == null) return;
        out.put("videosRead", scan.videosRead());
        out.put("videosWithCommentsOff", scan.commentsOff());
        out.put("note", note);
    }

    // The commentIds of these rows a reply to which waits for approval for the turn's person.
    private Set<String> draftsWaiting(List<Map<String, Object>> rows) {
        ApprovalInbox inbox = approvals == null ? null : approvals.getIfAvailable();
        if (inbox == null || rows.isEmpty()) return Set.of();
        Map<String, String> bySubject = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object id = r.get("commentId");
            if (id != null) bySubject.put(YouTubeApprovalContext.subjectFor(String.valueOf(id)), String.valueOf(id));
        }
        Set<String> out = new HashSet<>();
        for (String subject : inbox.waitingSubjects(SearchContext.getUserId(), bySubject.keySet())) {
            String id = bySubject.get(subject);
            if (id != null) out.add(id);
        }
        return out;
    }

    Map<String, Object> viewerHistory(ApiClient api, String viewerId, int limit) {
        JsonNode channel = myChannel(api);
        if (channel.isMissingNode() || channel.isNull()) return failure("The connection's Google account has no YouTube channel.");
        String channelId = channel.path("id").asText("");
        List<Map<String, Object>> found = new ArrayList<>();
        String viewerName = "";
        int scanned = 0;
        boolean more = false;
        YouTubeThreadScan scan = YouTubeThreadScan.channel(api, channel, HISTORY_PAGES);
        List<JsonNode> threads;
        while ((threads = scan.next()) != null) {
            for (JsonNode thread : threads) {
                scanned++;
                JsonNode top = thread.path("snippet").path("topLevelComment").path("snippet");
                if (!viewerId.equals(top.path("authorChannelId").path("value").asText(""))) continue;
                if (found.size() >= limit) {
                    more = true;
                    continue;
                }
                Map<String, Object> row = row(thread, channelId);
                if (viewerName.isEmpty()) viewerName = String.valueOf(row.get("author"));
                row.remove("byChannel");
                row.remove("author");
                row.remove("authorChannelId");
                found.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("viewer", viewerName);
        out.put("comments", found);
        out.put("repliedTo", found.stream().filter(r -> Boolean.TRUE.equals(r.get("answeredByChannel"))).count());
        out.put("scanned", scanned);
        out.put("more", more || scan.more());
        noted(out, scan);
        return out;
    }

    /**
     * The channel's own replies, each paired with the comment it answered, over the newest threads
     * ({@code order=time}, newest first; one quota unit a page, at most {@link #REPLY_PAGES}). A reply is
     * paired with its thread's top-level comment, or - when it opens with a viewer's @name, as a reply to
     * a reply does - with that viewer's reply in the thread. Only what YouTube carries inline is seen, up
     * to five replies a thread. With {@code after}: only replies written after it, and no further page
     * once a whole page of threads is older than it.
     */
    Map<String, Object> channelReplies(ApiClient api, int limit, Instant after) {
        JsonNode channel = myChannel(api);
        if (channel.isMissingNode() || channel.isNull()) return failure("The connection's Google account has no YouTube channel.");
        String channelId = channel.path("id").asText("");
        List<Map<String, Object>> found = new ArrayList<>();
        int scanned = 0;
        YouTubeThreadScan scan = YouTubeThreadScan.channel(api, channel, REPLY_PAGES);
        List<JsonNode> threads;
        while ((threads = scan.next()) != null) {
            boolean newer = false;
            for (JsonNode thread : threads) {
                scanned++;
                Instant at = instantOrNull(thread.path("snippet").path("topLevelComment").path("snippet").path("publishedAt").asText(""));
                if (after == null || at == null || at.isAfter(after)) newer = true;
                found.addAll(pairs(thread, channelId, after));
            }
            // A reply is never older than its thread: past a batch of threads older than after, none is newer.
            if (after != null && !newer) scan.olderSeen();
        }
        found.sort((a, b) -> String.valueOf(b.get("repliedAt")).compareTo(String.valueOf(a.get("repliedAt"))));
        boolean more = found.size() > limit;
        if (more) found = new ArrayList<>(found.subList(0, limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("channel", channel.path("snippet").path("title").asText(""));
        out.put("replies", found);
        out.put("scanned", scanned);
        out.put("more", more || scan.more());
        noted(out, scan);
        return out;
    }

    // The channel's replies in one thread, each with the comment it answered. A thread the channel started
    // is left out: what the owner says there answers viewers' replies to the owner's own comment.
    private static List<Map<String, Object>> pairs(JsonNode thread, String channelId, Instant after) {
        JsonNode top = thread.path("snippet").path("topLevelComment");
        if (channelId.equals(authorOf(top))) return List.of();
        String videoId = thread.path("snippet").path("videoId").asText("");
        List<JsonNode> viewers = new ArrayList<>();
        List<JsonNode> owner = new ArrayList<>();
        for (JsonNode reply : thread.path("replies").path("comments")) {
            (channelId.equals(authorOf(reply)) ? owner : viewers).add(reply);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode reply : owner) {
            String repliedAt = reply.path("snippet").path("publishedAt").asText("");
            if (after != null) {
                Instant at = instantOrNull(repliedAt);
                if (at != null && !at.isAfter(after)) continue;
            }
            String said = textOf(reply);
            JsonNode answered = mentioned(said, viewers);
            if (answered == null) answered = top;
            JsonNode c = answered.path("snippet");
            String comment = textOf(answered);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("videoId", videoId);
            pair.put("commentId", answered.path("id").asText(""));
            pair.put("author", c.path("authorDisplayName").asText(""));
            pair.put("authorChannelId", authorOf(answered));
            pair.put("comment", cut(comment, MAX_PAIR_TEXT));
            pair.put("emojiOnly", emojiOnly(comment));
            pair.put("commentedAt", c.path("publishedAt").asText(""));
            pair.put("reply", cut(said, MAX_PAIR_TEXT));
            pair.put("repliedAt", repliedAt);
            String replyId = reply.path("id").asText("");
            if (!videoId.isEmpty() && !replyId.isEmpty()) pair.put("link", "https://www.youtube.com/watch?v=" + videoId + "&lc=" + replyId);
            out.add(pair);
        }
        return out;
    }

    // The viewer's reply that a reply opening with "@name" answers, or null.
    private static JsonNode mentioned(String text, List<JsonNode> viewers) {
        if (text == null || !text.startsWith("@")) return null;
        String name = text.substring(1).split("\\s", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return null;
        for (JsonNode v : viewers) {
            String author = v.path("snippet").path("authorDisplayName").asText("").strip();
            if (author.startsWith("@")) author = author.substring(1);
            if (author.toLowerCase(Locale.ROOT).equals(name)) return v;
        }
        return null;
    }

    private static String authorOf(JsonNode comment) {
        return comment.path("snippet").path("authorChannelId").path("value").asText("");
    }

    private static String textOf(JsonNode comment) {
        JsonNode s = comment.path("snippet");
        String text = s.path("textOriginal").asText("");
        return text.isEmpty() ? s.path("textDisplay").asText("") : text;
    }

    // Words in a comment: a letter or a digit anywhere. One without - hearts, a smiley, "!!!" - asks for
    // no answer in words (the maintainer: "comments with emoji only just need a like").
    static boolean emojiOnly(String text) {
        if (text == null || text.isBlank()) return false;
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    // One thread as the agent reads it. "Answered" is a reply BY THE CHANNEL among those YouTube carries
    // inline - up to five; a long thread whose answer is older than its last five replies reads as
    // unanswered, which errs toward a person looking at it.
    private static Map<String, Object> row(JsonNode thread, String channelId) {
        JsonNode s = thread.path("snippet");
        JsonNode top = s.path("topLevelComment");
        JsonNode c = top.path("snippet");
        // What the channel itself replied: the owner's own words, which is how an agent learns how they write
        // to viewers - how they call themselves and the viewer, how long, which emoji (2026-10-31).
        List<String> channelReplies = new ArrayList<>();
        for (JsonNode reply : thread.path("replies").path("comments")) {
            JsonNode r = reply.path("snippet");
            if (channelId.equals(r.path("authorChannelId").path("value").asText(""))) {
                String said = r.path("textOriginal").asText("");
                channelReplies.add(said.isEmpty() ? r.path("textDisplay").asText("") : said);
            }
        }
        boolean answered = !channelReplies.isEmpty();
        String videoId = s.path("videoId").asText("");
        String commentId = top.path("id").asText("");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threadId", thread.path("id").asText(""));
        row.put("commentId", commentId);
        row.put("videoId", videoId);
        row.put("author", c.path("authorDisplayName").asText(""));
        row.put("authorChannelId", c.path("authorChannelId").path("value").asText(""));
        String text = c.path("textOriginal").asText("");
        if (text.isEmpty()) text = c.path("textDisplay").asText("");
        row.put("text", text);
        row.put("emojiOnly", emojiOnly(text));
        row.put("publishedAt", c.path("publishedAt").asText(""));
        row.put("replyCount", s.path("totalReplyCount").asInt(0));
        row.put("answeredByChannel", answered);
        if (!channelReplies.isEmpty()) row.put("channelReplies", channelReplies);
        row.put("byChannel", channelId.equals(c.path("authorChannelId").path("value").asText("")));
        if (!videoId.isEmpty()) row.put("link", "https://www.youtube.com/watch?v=" + videoId + "&lc=" + commentId);
        return row;
    }

    /**
     * Posts the reply. YouTube has ONE level of replies (2026-11-05): a reply to a viewer's reply is posted under the
     * thread's top-level comment - a reply's own id is "&lt;thread&gt;.&lt;reply&gt;" and its thread's id is the top-level
     * comment's - while the result still names the comment it answers.
     */
    Map<String, Object> postReply(ApiClient api, String parentId, String text) {
        Map<String, Object> body = Map.of("snippet", Map.of("parentId", YouTubeApprovalContext.threadOf(parentId),
                "textOriginal", text));
        ApiResponse answer = api.send(HttpMethod.POST, V3 + "comments", Map.of("part", "snippet"), body, Map.of());
        if (!answer.ok()) throw replyRefused(api, answer);
        // POSTED. Nothing from here on may read as a failure, or the model would post it again: an
        // answer that cannot be read leaves the ids out, with ToolResults' note that it was made.
        String replyId = null;
        String link = null;
        try {
            JsonNode made = answer.json();
            replyId = blankToNull(made.path("id").asText(""));
            String videoId = made.path("snippet").path("videoId").asText("");
            if (replyId != null && !videoId.isEmpty()) link = "https://www.youtube.com/watch?v=" + videoId + "&lc=" + replyId;
        } catch (RuntimeException unreadable) {
            // see above
        }
        Map<String, Object> out = ToolResults.written("commentId", parentId, "replyId", replyId);
        if (link != null) out.put("link", link);
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // What the model is told when YouTube refuses a reply - by the reason YouTube gives.
    private static RuntimeException replyRefused(ApiClient api, ApiResponse answer) {
        if (YouTubeCalls.reasons(answer).contains(YouTubeCalls.SHARED_QUOTA)) {
            return new IllegalStateException(YouTubeCalls.SHARED_QUOTA_SPENT + " Nothing was posted; try again after "
                    + "midnight Pacific time, when it resets.");
        }
        String reason = reason(answer);
        String sentence = switch (reason) {
            case "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" ->
                    "YouTube refused the reply: the connection was signed in without permission to reply. Tell the user "
                            + "to allow Write on the YouTube connection in Settings > Connections and sign in again, and do "
                            + "not retry until they confirm.";
            case "commentsDisabled" -> "YouTube refused the reply: comments are turned off on that video.";
            case "processingFailure", "commentNotFound", "parentCommentNotFound", "notFound" ->
                    "YouTube has no comment with that commentId. Take it from listYouTubeComments.";
            case "quotaExceeded", "dailyLimitExceeded" ->
                    "YouTube's daily quota for the connection's Google Cloud project is spent. Nothing was posted; "
                            + "try again tomorrow.";
            default -> null;
        };
        return sentence != null ? new IllegalStateException(sentence) : api.failure(answer);
    }

    private static String reason(ApiResponse response) {
        try {
            JsonNode error = response.json().path("error");
            String r = error.path("errors").path(0).path("reason").asText("");
            if (r.isEmpty()) r = error.path("details").path(0).path("reason").asText("");
            return r;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Why a reply to this comment must not be posted through this connection - its video is on another channel than
     * the one the connection posts as (YouTubeApprovalContext.otherChannel) - or null when it may, or when YouTube
     * does not say: two quota units, the channel and the comment's thread.
     */
    static String otherChannel(ApiClient api, String commentId) {
        try {
            JsonNode mine = api.getJson(V3 + "channels", Map.of("part", "snippet", "mine", "true")).path("items").path(0);
            String id = mine.path("id").asText("");
            if (id.isBlank()) return null;
            JsonNode thread = api.getJson(V3 + "commentThreads", Map.of("part", "snippet",
                    "id", YouTubeApprovalContext.threadOf(commentId), "textFormat", "plainText")).path("items").path(0);
            String owner = thread.isMissingNode() || thread.isNull() ? null : YouTubeApprovalContext.ownerOf(thread);
            if (owner == null || owner.equals(id)) return null;
            return YouTubeApprovalContext.otherChannel(mine.path("snippet").path("title").asText(""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // A comment's ID, from the bare ID or a thread item's ("thread:<id>") - a top-level comment's ID is
    // its thread's.
    static String parentId(String value) {
        if (value == null) return null;
        String id = value.strip();
        if (id.startsWith("thread:")) id = id.substring("thread:".length());
        return ID.matcher(id).matches() ? id : null;
    }

    // -- the connection ------------------------------------------------------------------------

    // The caller's YouTube connection, checked for this access, as a client whose bearer is its
    // sign-in's token. A write's client tries once.
    private ApiClient client(Access access, boolean once) {
        return client(http, credentials(access), once);
    }

    // The caller's YouTube connection, checked for this access, as its decrypted credentials.
    private ResolvedCredentials credentials(Access access) {
        String userId = SearchContext.getUserId();
        Endpoint endpoint = resolver.resolve(ConnectorType.YOUTUBE_CHANNEL, LABEL, null, access);
        ResolvedConnection connection = connections.resolveOwned(userId, endpoint.connectionId())
                .orElseThrow(() -> new IllegalStateException("That YouTube connection is not yours."));
        return contexts.credentials(connection);
    }

    static ApiClient client(ConnectorHttp http, ResolvedCredentials credentials, boolean once) {
        String base = credentials.hasBaseUrl() ? credentials.baseUrl() : DEFAULT_BASE_URL;
        ApiClient.Builder b = http.client(base).service(LABEL).auth(ApiAuth.bearer(credentials))
                .refusalReason(RefusalReason.GOOGLE);
        if (once) b.maxAttempts(1);
        return b.build();
    }

    private static String describe(Exception e) {
        if (e instanceof ConnectorHttpException http) return http.getMessage();
        return Failures.describe(e);
    }

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message);
    }
}
