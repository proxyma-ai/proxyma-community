package ai.proxyma.core.chat.dto;

import java.time.Instant;
import java.util.List;

// inputTokens/outputTokens/cacheReadTokens/cacheWriteTokens: cumulative provider-reported usage
// across every turn of this conversation (see Conversation entity / TokenUsageExtractor).
// contextTokens: estimated size of the live message history this conversation would send on its
// NEXT call - a snapshot, not a running total, so it drops back down after condensation.
// contextTokenLimit: the live ChatSettings.memoryMaxTokens value (not persisted on the
// conversation itself - always the CURRENT setting, so a later change is reflected immediately
// rather than freezing at whatever it was when this conversation was created).
public record ConversationDetail(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<ChatMessageResponse> messages,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        int contextTokens,
        int contextTokenLimit,
        // The position in the conversation's event stream these messages are true as of. A page
        // that goes on to follow the stream handles only events numbered above this: everything at
        // or below it is already in `messages`, and applying it again would double it. -1 means
        // nothing has been published yet.
        long eventSeq,
        // What is waiting for the user right now - an approval, a consent switch, a question -
        // and was asked at or before eventSeq, so no later event will ask it again.
        List<PendingPrompt> prompts,
        // Whether sub-agents are running in the conversation, or a report is on its way to the main
        // agent - reasons to follow the stream although no turn is running.
        boolean background) {

    /**
     * One prompt still open, as the event that asked it: {@code type} is the event's type
     * ('tool-approval-request', 'tool-enable-request' or 'user-question'), {@code data} its
     * payload, and {@code parentCallId} the sub-agent asking, when one is.
     */
    public record PendingPrompt(String type, String callId, String toolName, String data, String parentCallId) {}
}
