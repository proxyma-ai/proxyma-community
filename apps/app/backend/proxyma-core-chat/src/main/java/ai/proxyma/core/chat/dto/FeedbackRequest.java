package ai.proxyma.core.chat.dto;

import ai.proxyma.core.chat.domain.FeedbackMood;

import java.util.Map;

// includeConversation/conversationTranscript are only meaningful when conversationId/messageId
// are present (chat-context submissions) - the general floating-button flow never sends them.
// source is nullable - null (the existing floating-button/dislike call sites, unchanged) is
// treated as GENERAL by FeedbackController. ratings is only ever populated for the
// QUOTA_MILESTONE survey (5 questions, see QuotaMilestoneFeedbackModal on the frontend) - null
// for every GENERAL submission, which only ever uses the single `mood` field above.
public record FeedbackRequest(
        String mood,
        String comment,
        // Retained so an older client's body still binds, and DELIBERATELY IGNORED. Feedback is
        // now anonymous unconditionally - see FeedbackController.submit(). A field that used to
        // mean "attach my identity" must not silently start meaning it again because some caller
        // still sends true.
        boolean anonymous,
        // Optional, user-typed, and the only identifying value a submission can carry now.
        String contactEmail,
        // Attach the tail of this installation's own log file. Its own flag rather than another
        // entry in `files`, because the PRODUCT assembles it rather than the user picking it -
        // which is exactly why the modal has to say what a log can contain.
        boolean includeLogs,
        String conversationId,
        Long messageId,
        boolean includeConversation,
        String conversationTranscript,
        String source,
        Map<String, FeedbackMood> ratings,
        // True for a thumbs-up/down on an answer. Null from an older client reads as false - the
        // form - which is the stricter default: see FeedbackShipper.ratingsMayLeave().
        Boolean messageRating) {}
