package ai.proxyma.core.chat.dto;

// What an upload returns. `fileName` is the name the file is stored under - unique within the
// conversation, and what a chat request's `attachments` names - and `url` serves it back for a
// preview. The server-side path it lives at is NEVER returned (.claude/rules/security.md): it used
// to be, as `filePath`, and the client handed it back verbatim on the next message.
public record AttachmentResponse(String fileName, long sizeBytes, String url) {}
