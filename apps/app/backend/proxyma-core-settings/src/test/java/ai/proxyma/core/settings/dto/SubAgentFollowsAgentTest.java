package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sub-agent runs with the agent's settings until it is given its own.
 *
 * <p>It had a switch and three numbers of its own, each with a default that drifted from the
 * agent's - so raising the agent's step limit left its sub-agents, which do the long reading, at
 * the old one. Since 2026-10-03 there is no switch, and an unset field is read from the agent.
 */
class SubAgentFollowsAgentTest {

    /** Stored values reach the class the way SettingsService hands them over: a map of strings. */
    private static SubAgentSettings stored(Map<String, String> values) {
        return new ObjectMapper().convertValue(values, SubAgentSettings.class);
    }

    private static ChatSettings agent(int iterations, int output, int context) {
        ChatSettings agent = new ChatSettings();
        agent.setMaxIterations(iterations);
        agent.setMaxOutputTokens(output);
        agent.setMemoryMaxTokens(context);
        return agent;
    }

    @Test
    @DisplayName("with nothing stored, every limit is the agent's - and follows it when it changes")
    void nothingStoredFollowsTheAgent() {
        SubAgentSettings sub = stored(Map.of());

        assertThat(sub.maxIterations(agent(30, 16384, 500000))).isEqualTo(30);
        assertThat(sub.maxOutputTokens(agent(30, 16384, 500000))).isEqualTo(16384);
        assertThat(sub.contextMaxTokens(agent(30, 16384, 500000))).isEqualTo(500000);
        assertThat(sub.maxIterations(agent(100, 32768, 200000))).isEqualTo(100);
        assertThat(sub.getModel()).as("no model of its own: the gateway gives it the agent's").isEmpty();
    }

    @Test
    @DisplayName("a value set for sub-agents wins, and only for that field")
    void aStoredValueWins() {
        SubAgentSettings sub = stored(Map.of("max-iterations", "20", "context-max-tokens", "128000"));
        ChatSettings agent = agent(100, 32768, 1000000);

        assertThat(sub.maxIterations(agent)).isEqualTo(20);
        assertThat(sub.contextMaxTokens(agent)).isEqualTo(128000);
        assertThat(sub.maxOutputTokens(agent)).isEqualTo(32768);
    }

    @Test
    @DisplayName("with neither side's limit set, none is - the sub-agent's own model then decides")
    void neitherSetIsLeftToTheModel() {
        SubAgentSettings sub = stored(Map.of());
        ChatSettings agent = new ChatSettings();

        // What a fresh install has: both follow the model (ChatSettings.FROM_MODEL).
        assertThat(agent.getMaxOutputTokens()).isEqualTo(ChatSettings.FROM_MODEL);
        assertThat(agent.getMemoryMaxTokens()).isEqualTo(ChatSettings.FROM_MODEL);
        assertThat(sub.maxOutputTokens(agent)).isEqualTo(ChatSettings.FROM_MODEL);
        assertThat(sub.contextMaxTokens(agent)).isEqualTo(ChatSettings.FROM_MODEL);
    }

    @Test
    @DisplayName("an agent with no step limit hands that on, and a sub-agent may be given none of its own")
    void noLimitIsHandedOn() {
        ChatSettings unlimited = agent(ChatSettings.NO_ITERATION_LIMIT, 8192, 128000);

        // "Same as the agent" read 50 here until 2026-10-03; the time limit is what bounds it.
        assertThat(stored(Map.of()).maxIterations(unlimited)).isEqualTo(ChatSettings.NO_ITERATION_LIMIT);
        // Zero stored for the sub-agent is its own "no limit", not "follow".
        assertThat(stored(Map.of("max-iterations", "0")).maxIterations(agent(50, 8192, 128000)))
                .isEqualTo(ChatSettings.NO_ITERATION_LIMIT);
        assertThat(ChatSettings.iterationCeiling(stored(Map.of()).maxIterations(unlimited))).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("with no provider of its own, a sub-agent's model is looked for on the agent's provider")
    void theAgentsProviderStandsIn() {
        ChatSettings agent = agent(50, 8192, 128000);
        agent.setProvider("agent-provider");

        assertThat(stored(Map.of("model", "small-model")).provider(agent)).isEqualTo("agent-provider");
        assertThat(stored(Map.of("provider", "other", "model", "small-model")).provider(agent)).isEqualTo("other");
    }

    @Test
    @DisplayName("a sub-agent may run for thirty minutes unless told otherwise")
    void thirtyMinutes() {
        assertThat(stored(Map.of()).getTimeoutMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("a blank or zero number is unset, not a limit of nothing")
    void blankIsUnset() {
        ChatSettings agent = agent(40, 16384, 200000);

        assertThat(stored(Map.of("max-iterations", "")).maxIterations(agent)).isEqualTo(40);
        assertThat(stored(Map.of("max-output-tokens", "0")).maxOutputTokens(agent)).isEqualTo(16384);
    }

    @Test
    @DisplayName("the switch an earlier version stored is read past, and the rest of the settings with it are kept")
    void theOldSwitchIsReadPast() {
        SubAgentSettings sub = stored(Map.of("enabled", "false", "model", "small-model", "timeout-minutes", "30"));

        assertThat(sub.getModel()).isEqualTo("small-model");
        assertThat(sub.getTimeoutMinutes()).isEqualTo(30);
    }
}
