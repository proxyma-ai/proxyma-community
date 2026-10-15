package ai.proxyma.core.chat.dto;

import java.util.List;

public record ChatRequest(
        String conversationId,
        String message,
        List<String> mentions,
        // The stored `fileName` of each file uploaded to this conversation (AttachmentResponse).
        List<String> attachments,
        // DEPRECATED, still accepted: what `attachments` replaced, when an upload returned a server
        // path. Only each entry's file NAME is used, resolved in the caller's own attachments
        // folder exactly like `attachments` - a path in a request is never trusted as one.
        List<String> attachmentPaths,
        // Skill names the user explicitly picked for THIS message (like `mentions` for
        // documents) - each one's instructions are loaded into the conversation outright rather
        // than relying on the agent to notice the skill is relevant on its own (see
        // ChatService.applyPickedSkills). Sent fresh per message; what a pick LOADS persists, the
        // pick itself does not.
        List<String> skillMentions,
        // ASK, AUTO or READ_ONLY (PermissionMode) - absent is ASK, so an older client asks as it did.
        String permissionMode,
        // Where the conversation is held when it is not the chat page (ConversationSurface) - read
        // from a conversation's FIRST message only and never changed afterwards. Absent is the chat.
        String surface) {}
