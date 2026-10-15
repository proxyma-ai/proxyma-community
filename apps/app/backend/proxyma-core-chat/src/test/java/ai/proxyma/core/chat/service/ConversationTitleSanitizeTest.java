package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A title names the topic. What a small model sends back is not always one, so the reply is
 * cleaned before it reaches the history list - and a sentence ABOUT the request, "The user is
 * asking about ...", is the shape the maintainer reported (2026-09-24).
 */
class ConversationTitleSanitizeTest {

    @Test
    @DisplayName("a sentence describing the request is cut down to its subject")
    void describesTheRequest() {
        assertEquals("Their latest SAI project tasks",
                ConversationTitleService.sanitize("The user is asking about their latest SAI project tasks."));
        assertEquals("Indexed documents", ConversationTitleService.sanitize("User asks: indexed documents"));
        assertEquals("SAI project tasks", ConversationTitleService.sanitize("The user wants to know SAI project tasks"));
    }

    @Test
    @DisplayName("a label, quotes, trailing punctuation and anything after the first line are dropped")
    void noise() {
        assertEquals("SAI project tasks", ConversationTitleService.sanitize("Title: \"SAI project tasks\""));
        assertEquals("Indexed documents", ConversationTitleService.sanitize("Topic: Indexed documents?\nThis names the topic."));
    }

    @Test
    @DisplayName("a clean title passes through, the user's casing intact")
    void clean() {
        assertEquals("ABC organization updates", ConversationTitleService.sanitize("ABC organization updates"));
        assertEquals("iOS build failures", ConversationTitleService.sanitize("iOS build failures"));
    }

    @Test
    @DisplayName("a quoted description of the request is still cut to its subject")
    void quotedDescription() {
        assertEquals("SAI project tasks",
                ConversationTitleService.sanitize("\"The user is asking about SAI project tasks\""));
    }

    @Test
    @DisplayName("a label on a line of its own does not lose the title under it")
    void labelOnItsOwnLine() {
        assertEquals("Indexed documents", ConversationTitleService.sanitize("Title:\nIndexed documents"));
    }
}
