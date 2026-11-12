package ai.proxyma.core.chat.approval;

import java.util.Map;
import java.util.Optional;

/**
 * Says what one kind of write is about, for the person asked to approve it - a bean per tool family
 * (the YouTube reply's is in proxyma-core-tools). The first that answers wins.
 *
 * <p>Called by {@link ApprovalContexts} once per request, on a worker thread of its own with the
 * conversation's user and id in SearchContext, and given a few seconds: a provider that is slow,
 * throws or answers empty leaves the request with no context, and never holds or fails it - except for a
 * tool with {@link #requiredFields}, whose call is refused instead (2026-11-05). Answer
 * empty at once for a tool that is not yours. Look up only through the user's OWN connections - what
 * comes back is shown to that user.
 */
public interface ApprovalContextProvider {

    Optional<ApprovalContext> contextFor(String toolName, Map<String, Object> arguments, String conversationId,
                                         String userId);

    /**
     * What this write is ABOUT, as a key - the YouTube reply's is the comment it answers - or empty for
     * a write that has none. A second request with the same tool and subject, by the same person, while
     * the first still waits, REPLACES the first (ApprovalInbox.park): an agent asked to revise a draft
     * leaves one request, not two for the same thing. Pure: no lookup, called on the turn's thread.
     */
    default Optional<String> subjectOf(String toolName, Map<String, Object> arguments) {
        return Optional.empty();
    }

    /**
     * Why this write must not be kept at all - a sentence for the agent - or empty when it may (2026-11-05, the
     * maintainer: a team posting as one YouTube channel had drafts waiting for comments under the OTHER channel's
     * videos): the YouTube reply's is a comment whose video is not on the channel the conversation's connection
     * posts as. Asked before a write is kept for a person (ApprovalInbox.park, approveAutomatically), on a worker
     * thread with SearchContext set, bounded like a context's lookup; a provider that cannot tell answers empty, and
     * the write goes on - the tool itself checks again when it runs. Look up only through the user's OWN connections.
     */
    default Optional<String> refusal(String toolName, Map<String, Object> arguments, String conversationId,
                                     String userId) {
        return Optional.empty();
    }

    /**
     * The subject ({@link #subjectOf}) a reference names, when it names one of this provider's - the YouTube reply's
     * takes a comment's id and answers {@code youtube-comment:<id>} - so an agent can withdraw the draft waiting for a
     * comment by the comment, without knowing the request. Pure.
     */
    default Optional<String> subjectNamed(String reference) {
        return Optional.empty();
    }

    /**
     * Whether this provider can describe a call of this tool at all. A request of such a tool kept without
     * a context - its lookup slow or failing when it was made - is looked up again later
     * (ApprovalInbox.fillMissingContexts), so the page is not left with the call's arguments alone. Pure.
     */
    default boolean describes(String toolName) {
        return false;
    }

    /**
     * THE FIELDS EVERY CARD OF THIS TOOL CARRIES (2026-11-05, the maintainer: "The cards must always have all the
     * fields. In case the agents may request without providing enough fields, those requests must already auto
     * rejected by invalid params."), by key - the YouTube reply's video, viewer, comment, replyAs and reply - or none,
     * the default, for a tool whose card may show what it found. A tool with any is held to them wherever its write is
     * to be shown to a person (ApprovalContexts.prepare): its context is looked up BEFORE anything is kept, and a call
     * whose card would lack one is refused instead, nothing kept. Its {@link #contextFor} answers whole or throws
     * {@link Unavailable}, never a card with fields missing. Pure.
     */
    default java.util.Set<String> requiredFields(String toolName) {
        return java.util.Set.of();
    }

    /**
     * Why {@link #contextFor} could not make a whole card of a tool with {@link #requiredFields}. Its message is the
     * sentence the agent reads - what was wrong and what to do. {@code reason} is set only for a DEFINITE answer, that
     * the thing the write is about does not exist or cannot be read (a deleted comment, a private video): the short
     * sentence a page shows on a waiting request withdrawn for it. Null for a failure that may pass - a spent quota,
     * an outage, no answer in time - which refuses a new call and never withdraws a waiting one.
     */
    final class Unavailable extends RuntimeException {

        private final String reason;

        public Unavailable(String forAgent, String reason) {
            super(forAgent, null, false, false);
            this.reason = reason;
        }

        /** What a page shows on a waiting request withdrawn for it; null when the failure may pass. */
        public String reason() {
            return reason;
        }

        /** Whether the answer is definite: the thing the write is about does not exist or cannot be read. */
        public boolean definite() {
            return reason != null;
        }
    }

    /**
     * The reason {@link #settled} gives when the PERSON did the write themselves - answered the comment on YouTube - rather
     * than its subject being gone. Stored as the withdrawn request's note, and compared EXACTLY by the Constellations
     * Overview's step-in figures (2026-11-05), so a provider meaning "the person did it" gives this constant, never its
     * own wording (YouTubeApprovalContext.ANSWERED is this sentence, and YouTubeApprovalContextTest holds them equal).
     */
    String ANSWERED_BY_THE_PERSON = "Already answered on YouTube.";

    /** A request still waiting, as {@link #settled} reads it: its id, the conversation that asked, its arguments. */
    record Waiting(String id, String conversationId, Map<String, Object> arguments) {}

    /**
     * Of these waiting requests of this tool - all one person's - those no longer worth making because it
     * was done another way meanwhile (2026-11-04, the maintainer: a comment he answered himself on YouTube
     * still asked him to approve the team's reply): request id to the reason, a short sentence the page
     * shows. A request it could not check is left out, so it stays. Called off the request path, on a worker
     * thread, and bounded by the caller ({@link ApprovalContexts#settled}); look up only through the user's
     * OWN connections, set SearchContext yourself per conversation, and keep the calls few and batched.
     */
    default Map<String, String> settled(String toolName, java.util.List<Waiting> waiting, String userId) {
        return Map.of();
    }

    /** Whether {@link #settled} checks requests of this tool at all. Pure. */
    default boolean settles(String toolName) {
        return false;
    }

    /**
     * When an APPROVED call of this tool that failed with {@code result} may be made again - the service
     * refused it for a limit that lifts by then (YouTube's daily quota, at midnight Pacific time) - or empty for
     * a failure that waiting does not cure. The approvals inbox then keeps the write approved and retries it
     * (ApprovalInbox.retryHeld) rather than failing it. Pure: no lookup.
     */
    default Optional<java.time.Instant> heldUntil(String toolName, String result, java.time.Instant now) {
        return Optional.empty();
    }
}
