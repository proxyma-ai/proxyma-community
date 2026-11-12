package ai.proxyma.core.chat.approval;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * WHAT AN APPROVAL CARD SAYS ABOUT A COMMENT, FOR A PAGE THAT NAMES COMMENTS OUTSIDE AN APPROVAL
 * (2026-11-05, the maintainer, on the Constellations Overview's "What viewers want": "Should display the
 * user, video, and comment, similar to the approval page"). A dashboard an agent wrote names comments by
 * their links and videos by their ids; this answers who wrote each comment, where their channel is, when
 * it was written and which video it is under, and each video's title - the facts an
 * {@link ApprovalContextProvider} looks up for a reply waiting for approval. A bean per service (the
 * YouTube one is in proxyma-core-tools).
 *
 * <p>Looked up through ONE connection, named by its id and owned by {@code ownerUserId} - the caller has
 * already decided whose; a connection that is not theirs, or that does not serve the service, answers
 * {@link Facts#NONE}. Batched, remembered for a while per connection, and never thrown from: a fact that
 * could not be read is simply missing, and {@link Facts#complete()} says whether everything asked was
 * answered (found or known gone) - false when YouTube was unreachable, so the caller may ask again later.
 */
public interface CommentFactsProvider {

    /** The service this answers for, as a connection pin names it ("youtube"). */
    String service();

    /**
     * The facts about these comments (by their ids on the service) and these videos (by theirs). The
     * videos the comments are under are looked up too, so a caller need not name them.
     */
    Facts lookUp(String ownerUserId, UUID connectionId, Collection<String> commentIds, Collection<String> videoIds);

    /**
     * One comment: who wrote it (their display name, and their channel's address when known), when (an
     * ISO instant) and the video it is under - each null when not known.
     */
    record Comment(String author, String authorUrl, String publishedAt, String videoId) {}

    /** What was found: comment id to its facts, video id to its title; {@code complete} as above. */
    record Facts(Map<String, Comment> comments, Map<String, String> videoTitles, boolean complete) {

        public static final Facts NONE = new Facts(Map.of(), Map.of(), true);
    }
}
