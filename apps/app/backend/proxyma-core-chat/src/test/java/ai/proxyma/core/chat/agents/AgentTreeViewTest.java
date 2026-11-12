package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.model.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the agents map reads of one agent (2026-11-02 feedback: the map printed the tree's own
 * English sentence, "... its deadline (2026-11-02T04:50:57.201843Z) has passed", as a status). A
 * stop is a CODE the page words in the reader's language; the figures the page sets against their
 * limits - the context against the window, the spend against the budget, the run time against the
 * deadline - are all there; and what runs it is named only when it is known.
 */
class AgentTreeViewTest {

    static AgentNode node(String status) {
        AgentNode n = new AgentNode();
        n.setConversationId("conv-a");
        n.setParentConversationId("conv-root");
        n.setRootConversationId("conv-root");
        n.setDepth(1);
        n.setTitle("Bitbucket activity");
        n.setStatus(status);
        return n;
    }

    static Conversation conversation() {
        Conversation c = new Conversation();
        c.setId("conv-a");
        c.setInputTokens(1200);
        c.setOutputTokens(300);
        c.setContextTokens(29_800);
        c.setEstimatedCostUsd(0.25);
        return c;
    }

    @Test
    @DisplayName("an ended agent's stop is a code, never the sentence the agents read")
    void stopIsACode() {
        AgentNode n = node(AgentNode.STOPPED);
        n.setStopReason(AgentNode.REASON_DEADLINE);
        n.setStopNote("Bitbucket activity was stopped: its deadline (2026-11-02T04:50:57.201843Z) has passed.");
        Map<String, Object> v = AgentTreeView.of(n, conversation(), false, 200_000, null, null);
        assertThat(v).containsEntry("stopReason", AgentNode.REASON_DEADLINE).doesNotContainKey("stopNote");
        assertThat(v.values()).noneMatch(x -> String.valueOf(x).contains("has passed"));
    }

    @Test
    @DisplayName("an agent that has not ended has no stop reason, whatever an earlier stop left on its row")
    void liveHasNoReason() {
        AgentNode n = node(AgentNode.RUNNING);
        n.setStopReason(AgentNode.REASON_ERROR);
        assertThat(AgentTreeView.of(n, conversation(), true, 200_000, null, null)).containsEntry("stopReason", null);
    }

    @Test
    @DisplayName("the figures and their limits: context and window, spend and budget, start, change, end and deadline")
    void figuresAndLimits() {
        AgentNode n = node(AgentNode.DONE);
        Instant deadline = Instant.parse("2026-11-02T05:00:00Z");
        n.setBudgetUsd(3.0);
        n.setDeadlineAt(deadline);
        n.setEndedAt(Instant.parse("2026-11-02T04:55:00Z"));
        Map<String, Object> v = AgentTreeView.of(n, conversation(), false, 1_000_000, null, null);
        assertThat(v).containsEntry("contextTokens", 29_800).containsEntry("contextWindow", 1_000_000)
                .containsEntry("costUsd", 0.25).containsEntry("budgetUsd", 3.0)
                .containsEntry("inputTokens", 1200L).containsEntry("outputTokens", 300L)
                .containsEntry("deadlineAt", deadline.toString())
                .containsEntry("endedAt", "2026-11-02T04:55:00Z");
        assertThat(v.get("createdAt")).isNotNull();
        assertThat(v.get("updatedAt")).isNotNull();
        // A window nobody could read is unknown, not zero; a conversation not found spent nothing.
        Map<String, Object> bare = AgentTreeView.of(n, null, false, 0, null, null);
        assertThat(bare).containsEntry("contextWindow", null).containsEntry("contextTokens", 0).containsEntry("costUsd", 0.0);
    }

    @Test
    @DisplayName("what it runs on is named only when a model was chosen for it; otherwise the page uses the chat's")
    void runsOn() {
        AgentNode n = node(AgentNode.RUNNING);
        Map<String, Object> own = AgentTreeView.of(n, null, true, 0,
                new AgentTreeModule.RunsOn("p-1", "claude-sonnet-5-5"), "LiteLLM SAI");
        assertThat(own).containsEntry("model", "claude-sonnet-5-5").containsEntry("providerName", "LiteLLM SAI");
        Map<String, Object> chats = AgentTreeView.of(n, null, true, 0, new AgentTreeModule.RunsOn(null, null), "LiteLLM SAI");
        assertThat(chats).containsEntry("model", null).containsEntry("providerName", null);
    }

    // -- The main agent's row (2026-11-03: "Main agent must have Idle status. Its cost and run time must be shown.")

    static AgentTreeView.Stamp at(MessageRole role, long second) {
        return new AgentTreeView.Stamp(role, Instant.ofEpochSecond(second), false);
    }

    @Test
    @DisplayName("the main agent's time is the wall clock of its turns: from each user message to the last row before the next")
    void mainAgentTurns() {
        List<AgentTreeView.Stamp> rows = List.of(
                at(MessageRole.SYSTEM, 0),
                at(MessageRole.USER, 100), at(MessageRole.ASSISTANT, 110), at(MessageRole.TOOL, 130), at(MessageRole.ASSISTANT, 160),
                // An hour idle between the two turns counts for nothing.
                at(MessageRole.USER, 3700), at(MessageRole.ASSISTANT, 3720));
        assertThat(AgentTreeView.workedOf(rows, false)).isEqualTo(new AgentTreeView.Worked(80_000, null));
        // A turn running now is left open for the page to count on, from its user message.
        assertThat(AgentTreeView.workedOf(rows, true))
                .isEqualTo(new AgentTreeView.Worked(60_000, Instant.ofEpochSecond(3700)));
    }

    @Test
    @DisplayName("a condensation's summary rows and the system message start no turn; nothing stored is no time at all")
    void mainAgentSummariesStartNothing() {
        List<AgentTreeView.Stamp> rows = List.of(
                at(MessageRole.USER, 10), at(MessageRole.ASSISTANT, 20),
                new AgentTreeView.Stamp(MessageRole.USER, Instant.ofEpochSecond(500), true),
                new AgentTreeView.Stamp(MessageRole.ASSISTANT, Instant.ofEpochSecond(501), true),
                at(MessageRole.SYSTEM, 600));
        assertThat(AgentTreeView.workedOf(rows, false).finishedMs()).isEqualTo(10_000);
        assertThat(AgentTreeView.workedOf(List.of(), true)).isEqualTo(new AgentTreeView.Worked(0, null));
        assertThat(AgentTreeView.workedOf(null, false)).isEqualTo(new AgentTreeView.Worked(0, null));
    }

    @Test
    @DisplayName("the main agent's row: its own conversation's spend and context, its time worked, and what it is doing")
    void mainAgentRow() {
        Conversation root = conversation();
        Map<String, Object> idle = AgentTreeView.main(root, false, false, false, false, 1_000_000,
                new AgentTreeView.Worked(80_000, null));
        assertThat(idle).containsEntry("working", false).containsEntry("awaitingApproval", false)
                .containsEntry("rateLimited", false).containsEntry("waiting", false)
                .containsEntry("costUsd", 0.25).containsEntry("contextTokens", 29_800)
                .containsEntry("contextWindow", 1_000_000).containsEntry("workedMs", 80_000L)
                .containsEntry("turnStartedAt", null);

        Map<String, Object> busy = AgentTreeView.main(root, true, true, true, true, 0,
                new AgentTreeView.Worked(60_000, Instant.parse("2026-11-03T08:00:00Z")));
        // Waiting on its agents is only while no turn of its own runs; a rate limit only while one does.
        assertThat(busy).containsEntry("working", true).containsEntry("awaitingApproval", true)
                .containsEntry("rateLimited", true).containsEntry("waiting", false)
                .containsEntry("contextWindow", null).containsEntry("turnStartedAt", "2026-11-03T08:00:00Z");
        assertThat(AgentTreeView.main(root, false, false, true, true, 0, null))
                .containsEntry("rateLimited", false).containsEntry("waiting", true).containsEntry("workedMs", 0L);

        // A conversation that cannot be read spent nothing that can be shown.
        assertThat(AgentTreeView.main(null, false, false, false, false, 0, null))
                .containsEntry("costUsd", 0.0).containsEntry("contextTokens", 0);
    }
}
