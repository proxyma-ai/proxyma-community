package ai.proxyma.core.chat.channel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A CHANNEL'S COMMENTS, READ BY THE SERVER FOR A TEAM THAT ANSWERS THEM (2026-11-05, the YouTube team v2: "Code does
 * the bookkeeping; models only judge"). A module keeping a queue of the comments that need a decision (Constellations'
 * comment queue) asks here, on its own schedule and through ONE connection named by its id and owned by
 * {@code ownerUserId} - the caller has already decided whose - for what changed since its last pass. A bean per service
 * (the YouTube one is in proxyma-core-tools).
 *
 * <p>Never thrown from: a pass that could not read the service says so in {@link Pass#error()} and keeps the cursor it
 * was given, so the next pass tries again. Every pass is bounded in the calls it makes - the provider says how.
 */
public interface ChannelCommentsProvider {

    /** The service this answers for, as a connection pin names it ("youtube"). */
    String service();

    /**
     * One pass: the newest comments, a step further into the older ones ({@link Cursor}), and where each of
     * {@code recheck} - comments the caller still holds open - stands now. Empty when the connection is not the
     * owner's, or does not serve the service.
     */
    Pass sync(String ownerUserId, UUID connectionId, Cursor cursor, Collection<String> recheck);

    /** The channel's numbers for a dashboard, for the 28 days ending {@code end} and the 28 before. */
    Numbers numbers(String ownerUserId, UUID connectionId, LocalDate end);

    /**
     * VIDEOS BY THEIR TITLES (2026-11-06, the maintainer: "It must always the video title"): video id to title, for the
     * videos it knows. What Proxyma already holds first, with no call - the channel source's index, then what the
     * service said before - and only when {@code ask}, the service itself for the rest, batched and bounded. A video
     * whose title is not known is absent. Never thrown from.
     */
    default Map<String, String> videoTitles(String ownerUserId, UUID connectionId, Collection<String> videoIds, boolean ask) {
        return Map.of();
    }

    /**
     * Where a channel's reading stands between passes: how far into the older comments it got ({@code backlogToken}, a
     * page the service handed out, else {@code backlogBefore}), whether it reached the end, and when the last pass
     * began, so the newest pages stop where nothing is new.
     */
    /**
     * VIEWERS BY THEIR CHANNEL (2026-11-09, the maintainer: "can we get the username? I mean the viewer name when they
     * comment"): a comment names its author by handle alone; this asks the service for each author's channel - its name,
     * handle and picture (an inline image, never an address) - for at most a page of channel ids, through the owner's own
     * connection. Channels the service does not return are left out; empty when it cannot be asked.
     */
    default Map<String, Viewer> viewers(String ownerUserId, UUID connectionId, Collection<String> channelIds) {
        return Map.of();
    }

    /** A viewer's channel as the service states it; {@code avatar} a {@code data:image/...} address or null. */
    record Viewer(String name, String handle, String avatar) {}

    record Cursor(String backlogToken, Instant backlogBefore, boolean backlogDone, Instant newSince) {

        public static final Cursor START = new Cursor(null, null, false, null);
    }

    /** One earlier message of a comment's conversation. */
    record Message(String author, boolean byChannel, String text) {}

    /**
     * A comment that needs a decision: the newest message of its conversation by someone other than the channel.
     * {@code before} is the conversation so far, bounded; {@code inReplyTo} what a reply answers.
     */
    record Comment(String commentId, String threadId, boolean reply, String videoId, String videoTitle, String author,
                   String authorChannelId, String authorUrl, String text, Instant publishedAt, List<Message> before,
                   String inReplyTo) {}

    /** Where a comment stands: {@link #ANSWERED} (with what the channel wrote), {@link #GONE}, or {@link #WAITING}. */
    record Settled(String state, String channelReply) {

        public static final String ANSWERED = "ANSWERED";
        public static final String GONE = "GONE";
        public static final String WAITING = "WAITING";
    }

    /**
     * What a pass found: the channel, the comments needing a decision (newest pages and the step into older ones),
     * where each comment seen or rechecked stands (comment id to {@link Settled}), the cursor for the next pass, the
     * calls it made, and why it stopped short, when it did.
     */
    record Pass(String channelId, String channelTitle, List<Comment> pending, Map<String, Settled> settled, Cursor next,
                int calls, String error, Map<String, Instant> newUploads) {

        /** A pass that did not read the channel's newest uploads. */
        public Pass(String channelId, String channelTitle, List<Comment> pending, Map<String, Settled> settled, Cursor next,
                    int calls, String error) {
            this(channelId, channelTitle, pending, settled, next, calls, error, null);
        }

        /**
         * NEW VIDEOS FIRST (2026-11-10): the channel's newest uploads and when each was published, video id to time - or
         * null when this pass did not read them (they are read at most hourly), which keeps what the last pass found.
         */
        @Override
        public Map<String, Instant> newUploads() {
            return newUploads;
        }

        public boolean failed() {
            return error != null;
        }
    }

    /**
     * The channel's numbers as the service states them, each a map of what its tool would return (the channel's
     * overview, the period's and the period before's daily report, the newest uploads, and reach), any null that could
     * not be read; {@code error} when nothing could.
     */
    record Numbers(Map<String, Object> channel, Map<String, Object> current, Map<String, Object> previous,
                   Map<String, Object> videos, Map<String, Object> reach, LocalDate start, LocalDate end, String error) {}
}
