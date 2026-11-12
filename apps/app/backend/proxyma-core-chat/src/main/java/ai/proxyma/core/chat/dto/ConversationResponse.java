package ai.proxyma.core.chat.dto;

import java.time.Instant;

/**
 * One conversation in a list. {@code titleKind} is set for a conversation a module or an agent opened
 * (ConversationOpening): the page words it in the reader's language, with {@code title} as its data.
 */
public record ConversationResponse(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int messageCount,
        boolean pinned,
        String titleKind) {

    public ConversationResponse(String id, String title, Instant createdAt, Instant updatedAt, int messageCount,
                                boolean pinned) {
        this(id, title, createdAt, updatedAt, messageCount, pinned, null);
    }
}
