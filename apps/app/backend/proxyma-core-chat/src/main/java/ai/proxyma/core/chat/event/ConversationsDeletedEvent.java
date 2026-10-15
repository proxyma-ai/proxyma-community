package ai.proxyma.core.chat.event;

import java.util.List;

/**
 * Conversations that no longer exist - deleted by their owner (the chat page's or a panel's history,
 * the API), by the retention sweep, or with their user's account. Published by ChatService once the
 * conversations are gone, synchronously, for whatever another module keeps about a conversation by
 * its id: the key-account panel's bindings are deleted with it, so the panel never reopens an id
 * that has nothing behind it.
 */
public record ConversationsDeletedEvent(List<String> conversationIds) {

    public ConversationsDeletedEvent {
        conversationIds = conversationIds == null ? List.of() : List.copyOf(conversationIds);
    }
}
