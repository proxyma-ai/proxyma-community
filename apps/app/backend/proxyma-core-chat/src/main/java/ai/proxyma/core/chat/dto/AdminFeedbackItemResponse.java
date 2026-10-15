package ai.proxyma.core.chat.dto;

import ai.proxyma.core.chat.domain.FeedbackMood;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AdminFeedbackItemResponse(
        Long id,
        String mood,
        String comment,
        boolean anonymous,
        String userId,
        String submitterName,
        String conversationId,
        Long messageId,
        String conversationTranscriptSnapshot,
        Instant createdAt,
        boolean resolved,
        Instant resolvedAt,
        String resolutionNote,
        String source,
        Map<String, FeedbackMood> ratings,
        List<AttachmentSummary> attachments) {

    // Metadata only - the blob itself is fetched on demand via
    // AdminFeedbackController.downloadAttachment(), never inlined into the list/detail payload.
    public record AttachmentSummary(Long id, String fileName, String contentType, long sizeBytes) {}
}
