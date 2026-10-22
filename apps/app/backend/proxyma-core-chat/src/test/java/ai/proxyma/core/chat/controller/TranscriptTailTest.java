package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.model.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a page reads after each turn (2026-10-17): the ids of that turn's messages from the end of the
 * transcript (`/message-ids?tail=`), and the usage meter's six numbers - not the whole conversation,
 * twice, after every turn. With background sub-agents each report is a turn, and those two loads were most of what a
 * page following them received.
 */
class TranscriptTailTest {

    @Test
    @DisplayName("a tail is the last messages, in order; none asked for, or more than there are, is all of them")
    void tailIsTheLastMessages() {
        List<Integer> all = List.of(1, 2, 3, 4, 5);
        assertThat(ChatController.tailOf(all, 2)).containsExactly(4, 5);
        assertThat(ChatController.tailOf(all, 1)).containsExactly(5);
        assertThat(ChatController.tailOf(all, null)).isSameAs(all);
        assertThat(ChatController.tailOf(all, 0)).isSameAs(all);
        assertThat(ChatController.tailOf(all, -3)).isSameAs(all);
        assertThat(ChatController.tailOf(all, 5)).isSameAs(all);
        assertThat(ChatController.tailOf(all, 50)).isSameAs(all);
        assertThat(ChatController.tailOf(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("the usage meter is the conversation's counts and the context limit, and nothing of its messages")
    void usageIsTheCountsAlone() {
        Conversation conv = new Conversation();
        assertThat(ChatController.usageOf(conv, 200_000))
                .containsOnlyKeys("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
                        "contextTokens", "contextTokenLimit")
                .containsEntry("contextTokenLimit", 200_000L)
                .containsEntry("inputTokens", 0L);
    }
}
