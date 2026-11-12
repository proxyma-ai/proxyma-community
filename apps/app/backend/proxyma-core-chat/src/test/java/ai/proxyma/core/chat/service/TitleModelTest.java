package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.provider.gateway.LlmGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A conversation is titled on the model it runs on (2026-11-11, the maintainer: "Let the configured model generate
 * conversation title" - a YouTube team all on Ollama
 * showed a cost - its lead chat's title, made on the app's Anthropic model).
 */
class TitleModelTest {

    private static AgentProfile on(String provider, String model) {
        return new AgentProfile(provider, model, null, null, null, null, Set.of(), false, null, null);
    }

    @Test
    @DisplayName("titled on the model the turn runs on: a workflow's, a saved agent's own, else the main agent's (null)")
    void titledOnTheTurnsModel() {
        assertThat(ChatService.titleModel("anthropic-1", null, null, Optional.of(on("ollama-1", "qwen3:14b"))))
                .isEqualTo(new LlmGateway.ModelOverride("ollama-1", "qwen3:14b"));
        assertThat(ChatService.titleModel("anthropic-1", null, null, Optional.of(on(null, "qwen3:14b"))))
                .as("its model on the chat's provider").isEqualTo(new LlmGateway.ModelOverride("anthropic-1", "qwen3:14b"));
        assertThat(ChatService.titleModel("anthropic-1", "ollama-1", "llama3", Optional.empty()))
                .as("a workflow's own model").isEqualTo(new LlmGateway.ModelOverride("ollama-1", "llama3"));
        assertThat(ChatService.titleModel("anthropic-1", null, null, Optional.of(on(null, null)))).isNull();
        assertThat(ChatService.titleModel("anthropic-1", null, null, Optional.of(AgentProfile.LOCKED))).isNull();
        assertThat(ChatService.titleModel("anthropic-1", null, null, Optional.empty()))
                .as("an ordinary chat: the main agent's model").isNull();
    }
}
