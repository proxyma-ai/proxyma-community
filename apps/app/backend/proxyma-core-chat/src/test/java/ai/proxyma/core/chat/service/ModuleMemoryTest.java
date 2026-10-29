package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A MODULE'S MEMORY FOR THE AGENT IS A MESSAGE, NOT THE SYSTEM PROMPT (2026-10-30, Constellations'
 * workspaces): a saved agent's MEMORY.md, NEXT.md and latest journal reach it at the first model call
 * with no copy in view - a conversation's first step, and the first after a condensation took the
 * earlier copy into its summary - as inbox-shaped mail the thread draws as one line and an earlier
 * conversation read back leaves out.
 */
class ModuleMemoryTest {

    @Test
    @DisplayName("the memory is mail of its own kind: scaffolding for the transcript, the 'memory' kind for the page")
    void readAsMail() {
        Message m = ChatService.memoryMessage("== memory/MEMORY.md ==\n- [Triage](triage.md) - how Jira is triaged");
        String text = ((UserMessage) m).getText();
        assertThat(text).startsWith("[Workspace memory]\n").contains("memory/MEMORY.md");
        assertThat(AgentInbox.isInboxMessage(text)).isTrue();
        assertThat(ChatService.isScaffolding(text)).isTrue();
        assertThat(AgentInbox.entriesIn(text)).singleElement().satisfies(e -> assertThat(e[2]).isEqualTo("memory"));
    }

    @Test
    @DisplayName("due while no copy is in view: a new conversation, and one whose copy went into a summary")
    void dueWhileNoneInView() {
        Message memory = ChatService.memoryMessage("notes");
        List<Message> fresh = List.of(new SystemMessage("You are Proxyma."), new UserMessage("Check the release PRs"));
        assertThat(ChatService.hasMemoryInView(fresh)).isFalse();
        assertThat(ChatService.hasMemoryInView(List.of(fresh.get(0), fresh.get(1), memory, new AssistantMessage("Done."))))
                .isTrue();
        // Condensed: the summary replaced the range the copy was in, so the next call is given it again.
        List<Message> condensed = List.of(new SystemMessage("You are Proxyma."),
                new UserMessage("[Summary of the earlier conversation] ..."), new UserMessage("And now the docs"));
        assertThat(ChatService.hasMemoryInView(condensed)).isFalse();
        // A person quoting the heading in the middle of a message is not a copy.
        assertThat(ChatService.hasMemoryInView(List.of(new UserMessage("what does [Workspace memory] mean?")))).isFalse();
    }
}
