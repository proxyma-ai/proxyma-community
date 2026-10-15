package ai.proxyma.core.chat.dto;

import java.time.Instant;
import java.util.List;

// What a viewer of a shared link gets. Deliberately NOT ConversationDetail: the conversation id is
// absent (the token is the only handle a viewer ever holds, and leaking the real id would let them
// probe the owner-gated /chat/conversations/{id}/** routes), and so is every token/cost figure -
// those are the owner's billing data, not part of the transcript being shared.
public record SharedConversationResponse(
        String title,
        // Best-effort display name of whoever shared it, null when unknown (desktop mode, or an
        // anonymous conversation) - see ConversationShare.createdByName.
        String sharedByName,
        Instant sharedAt,
        // The transcript from the first message through the shared one, inclusive.
        List<ChatMessageResponse> messages) {}
