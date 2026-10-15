package ai.proxyma.core.chat.dto;

import java.time.Instant;

public record ConversationResponse(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int messageCount,
        boolean pinned) {}
