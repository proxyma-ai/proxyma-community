package ai.proxyma.core.chat.dto;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.dto.ConversationAgents.Spend;
import ai.proxyma.core.chat.service.SubAgentService.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The agents graph's figures: the main agent's share is the conversation's total less what its
 * sub-agents spent, and the three always add up.
 */
class ConversationAgentsTest {

    private static AgentUsage sub(String id, long in, long out, double cost) {
        return new AgentUsage(id, "Task " + id, "async", "completed", 1L, 2L, "Fake", "fake-mini",
                in, out, 0, 0, cost, 0, 0);
    }

    @Test
    @DisplayName("the main agent is what the conversation spent less its sub-agents")
    void mainIsTheRest() {
        Spend total = new Spend(10_000, 2_000, 500, 100, 1.00);

        ConversationAgents agents = ConversationAgents.of(total,
                List.of(sub("a", 3_000, 500, 0.30), sub("b", 1_000, 200, 0.10)));

        assertThat(agents.main().inputTokens()).isEqualTo(6_000);
        assertThat(agents.main().outputTokens()).isEqualTo(1_300);
        assertThat(agents.main().cacheReadTokens()).isEqualTo(500);
        assertThat(agents.main().costUsd()).isCloseTo(0.60, within(1e-9));
        assertThat(agents.total()).isEqualTo(total);
        assertThat(agents.subAgents()).hasSize(2);
    }

    @Test
    @DisplayName("with no sub-agents the main agent is the whole conversation")
    void noSubAgents() {
        Spend total = new Spend(10, 2, 0, 0, 0.01);

        assertThat(ConversationAgents.of(total, List.of()).main()).isEqualTo(total);
    }

    private static ConversationAgents.Stamp at(MessageRole role, int second) {
        return new ConversationAgents.Stamp(role, Instant.ofEpochSecond(second), false);
    }

    @Test
    @DisplayName("the main agent's turns run from each user message to the last row before the next one")
    void turnsFromMessages() {
        List<ConversationAgents.Turn> turns = ConversationAgents.turnsOf(List.of(
                at(MessageRole.SYSTEM, 0),
                at(MessageRole.USER, 10), at(MessageRole.ASSISTANT, 15), at(MessageRole.TOOL, 40),
                at(MessageRole.ASSISTANT, 70),
                at(MessageRole.USER, 500), at(MessageRole.ASSISTANT, 530)), false);

        assertThat(turns).containsExactly(new ConversationAgents.Turn(10_000, 70_000L),
                new ConversationAgents.Turn(500_000, 530_000L));
    }

    @Test
    @DisplayName("the last turn is open while one runs, and a summary row starts nothing")
    void openTurnAndSummaries() {
        List<ConversationAgents.Turn> turns = ConversationAgents.turnsOf(List.of(
                at(MessageRole.USER, 10), at(MessageRole.ASSISTANT, 20),
                new ConversationAgents.Stamp(MessageRole.USER, Instant.ofEpochSecond(25), true),
                at(MessageRole.USER, 100), at(MessageRole.ASSISTANT, 110)), true);

        assertThat(turns).containsExactly(new ConversationAgents.Turn(10_000, 20_000L),
                new ConversationAgents.Turn(100_000, null));
        assertThat(ConversationAgents.turnsOf(List.of(), true)).isEmpty();
    }

    @Test
    @DisplayName("a share is never negative")
    void neverNegative() {
        ConversationAgents agents = ConversationAgents.of(new Spend(100, 10, 0, 0, 0.10),
                List.of(sub("a", 150, 20, 0.1000000001)));

        assertThat(agents.main().inputTokens()).isZero();
        assertThat(agents.main().outputTokens()).isZero();
        assertThat(agents.main().costUsd()).isZero();
    }
}
