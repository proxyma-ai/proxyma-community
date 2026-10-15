package ai.proxyma.core.chat.dto;

public record FeedbackResponse(
        Long id,
        String mood,
        String comment,
        boolean anonymous,
        String source) {}
