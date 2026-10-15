package ai.proxyma.core.settings.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings > Agents offers "Unlimited" for the main agent, stored as {@code 0}.
 *
 * <p>Before it was offered, a stored 0 reached the ReAct loop raw and meant ONE model call whose
 * tool calls were silently not followed - the opposite of what the option says. The loop reads
 * the ceiling through {@link ChatSettings#iterationCeiling}, and this pins what that returns.
 */
class IterationCeilingTest {

    @Test
    @DisplayName("0 is no limit")
    void zeroIsNoLimit() {
        assertThat(ChatSettings.iterationCeiling(ChatSettings.NO_ITERATION_LIMIT)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a negative value is no limit too, never a turn that cannot follow a tool call")
    void negativeIsNoLimit() {
        assertThat(ChatSettings.iterationCeiling(-1)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a real ceiling is used as it is")
    void ceilingIsKept() {
        assertThat(ChatSettings.iterationCeiling(10)).isEqualTo(10);
        assertThat(ChatSettings.iterationCeiling(200)).isEqualTo(200);
    }
}
