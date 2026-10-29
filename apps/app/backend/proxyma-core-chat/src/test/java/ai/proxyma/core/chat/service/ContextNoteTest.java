package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE AGENT IS TOLD ITS CONTEXT WINDOW IS FILLING before condensation takes the detail - the user's
 * request (feedback 499724c9, 2026-10-27): an agent that knows a summary is coming can save what
 * matters with remember first. Once from 65% of the window, never at or past condensation (80%),
 * and as inbox-shaped mail, so the thread draws it as one line and an earlier conversation read back
 * leaves it out.
 */
class ContextNoteTest {

    @Test
    @DisplayName("from 65% of the window and below condensation, once")
    void onceInTheBand() {
        assertThat(ChatService.contextNoteFor(60_000, 100_000, false)).isNull();
        String note = ChatService.contextNoteFor(70_000, 100_000, false);
        assertThat(note).startsWith("[Context]").contains("70%").contains("80%").contains("remember")
                .contains("70,000 of 100,000");
        assertThat(ChatService.contextNoteFor(72_000, 100_000, true)).as("told already").isNull();
        assertThat(ChatService.contextNoteFor(80_000, 100_000, false)).as("condensation is here").isNull();
        assertThat(ChatService.contextNoteFor(70_000, 0, false)).as("window unknown").isNull();
        assertThat(ChatService.CONTEXT_NOTE_AT).isLessThan(SmartChatMemory.CONDENSE_THRESHOLD);
    }

    @Test
    @DisplayName("the note is read as mail: scaffolding for the transcript, the 'context' kind for the page")
    void readAsMail() {
        String note = ChatService.contextNoteFor(70_000, 100_000, false);
        assertThat(AgentInbox.isInboxMessage(note)).isTrue();
        assertThat(ChatService.isScaffolding(note)).isTrue();
        assertThat(AgentInbox.entriesIn(note)).singleElement().satisfies(e -> assertThat(e[2]).isEqualTo("context"));
    }
}
