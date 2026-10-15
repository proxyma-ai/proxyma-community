package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reasoning model's thinking is shown as THINKING - a running token count - and never as the
 * answer: not streamed as text, not persisted, not in the title.
 *
 * <p>Driven through the fake provider in the three shapes a real one sends it: the `reasoning`
 * delta field (vLLM, Ollama's /v1, LM Studio - which Spring AI does not read, so the product renames
 * it on the way in), the `reasoning_content` field (DeepSeek, llama.cpp), and `<think>` written
 * into the answer text itself by a model whose server does not parse reasoning.
 */
@DisplayName("Chat: a model that thinks")
class ThinkingIT extends DesktopJourney {

    private static final String THOUGHT = "first consider the orchard ledger then the grove";

    @Test
    @DisplayName("`reasoning` deltas become a thinking count, and none of the thinking reaches the answer")
    void theReasoningFieldIsThinking() {
        assertThinkingNotAnswer(Chat.send(app, "@@reasoning " + THOUGHT + " @@text The ledger balances.").succeeded());
    }

    @Test
    @DisplayName("`reasoning_content` deltas become a thinking count, and none of the thinking reaches the answer")
    void theReasoningContentFieldIsThinking() {
        assertThinkingNotAnswer(Chat.send(app, "@@reasoning-content " + THOUGHT + " @@text The ledger balances.").succeeded());
    }

    @Test
    @DisplayName("inline <think> in the text is thinking, even with its tags split across chunks")
    void inlineThinkTagsAreThinking() {
        assertThinkingNotAnswer(Chat.send(app,
                "@@chunks 40 @@text <think>" + THOUGHT + "</think>The ledger balances.").succeeded());
    }

    private static void assertThinkingNotAnswer(Chat.Turn turn) {
        assertThat(turn.types()).as("the turn announced thinking: %s", turn.types()).contains("thinking");
        assertThat(turn.types().indexOf("thinking")).as("thinking comes before the answer")
                .isLessThan(turn.types().indexOf("text"));

        JsonNode lastCount = null;
        for (JsonNode event : turn.events()) {
            if ("thinking".equals(event.path("type").asText())) lastCount = event;
        }
        assertThat(Integer.parseInt(lastCount.path("data").asText())).as("a token count, not text").isPositive();
        assertThat(turn.events().toString()).as("the thinking TEXT is never on the wire").doesNotContain("orchard");

        assertThat(turn.streamedText()).isEqualTo("The ledger balances.");
        assertThat(turn.text()).as("persisted").isEqualTo("The ledger balances.");
        JsonNode stored = api().get("/api/v1/chat/conversations/" + turn.conversationId()).ok().json();
        for (JsonNode message : stored.path("messages")) {
            if (!"USER".equals(message.path("role").asText())) {
                assertThat(message.toString()).as("nowhere the model's side is stored").doesNotContain("orchard");
            }
        }
        assertThat(stored.path("title").asText()).doesNotContain("orchard");
    }
}
