package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 0.36.0 feedback: "I tried to save this status to memory but the save failed". The agent called
 * {@code remember} before reading the skill that offers it, so it never saw the tool's schema and
 * named the fact itself; the tool found no {@code fact} and refused. Such a call is now answered
 * with the tool's real arguments, and only such a call - see ToolArgumentCheck.
 *
 * <p>The schema is Spring AI's own, generated from a method with remember's signature, so the names
 * and the "required" list are what a real call is checked against.
 */
class ToolArgumentCheckTest {

    // remember's signature and descriptions (RememberTool, proxyma-core-tools).
    static class Memory {
        @Tool(description = "Save one fact for future conversations.")
        public Map<String, Object> remember(
                @ToolParam(required = true, description = "Short self-contained statement") String fact,
                @ToolParam(required = false, description = "\"user\" or \"knowledge\"") String category) {
            return Map.of("success", true);
        }
    }

    private static String schema() {
        ToolCallback remember = Arrays.stream(ToolCallbacks.from(new Memory()))
                .filter(cb -> cb.getToolDefinition().name().equals("remember"))
                .findFirst().orElseThrow();
        return remember.getToolDefinition().inputSchema();
    }

    @Test
    @DisplayName("a fact written under a name of the model's own is answered with the real arguments")
    void guessedNamesAreAnsweredWithTheRealOnes() {
        String answer = ToolArgumentCheck.misnamedArguments("remember", schema(),
                "{\"content\":\"FBL-SDK status: ram-bringup is ahead of main\",\"category\":\"knowledge\"}");

        assertThat(answer).startsWith("Not run: remember")
                .contains("content, which it does not take")
                .contains("without fact, which it needs")
                .contains("fact (required): Short self-contained statement")
                .contains("category");
    }

    @Test
    @DisplayName("a call with the right names runs, extra names or not")
    void rightNamesRun() {
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(), "{\"fact\":\"x\"}")).isNull();
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(),
                "{\"fact\":\"x\",\"importance\":\"high\"}")).isNull();
    }

    @Test
    @DisplayName("a call that only leaves a required argument out still runs, as it always did")
    void missingAloneStillRuns() {
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(), "{}")).isNull();
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(), "{\"category\":\"user\"}")).isNull();
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(), null)).isNull();
    }

    @Test
    @DisplayName("anything unreadable is let through: the check is a hint, never a gate")
    void unreadableIsLetThrough() {
        assertThat(ToolArgumentCheck.misnamedArguments("remember", schema(), "not json")).isNull();
        assertThat(ToolArgumentCheck.misnamedArguments("remember", "not json", "{\"content\":\"x\"}")).isNull();
        assertThat(ToolArgumentCheck.misnamedArguments("remember", "{\"type\":\"object\"}", "{\"content\":\"x\"}")).isNull();
    }
}
