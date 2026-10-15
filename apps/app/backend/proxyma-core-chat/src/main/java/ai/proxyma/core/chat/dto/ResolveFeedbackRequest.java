package ai.proxyma.core.chat.dto;

// note is optional (may be null/blank) - the admin resolving a feedback item is not required to
// leave a resolution note.
public record ResolveFeedbackRequest(String note) {}
