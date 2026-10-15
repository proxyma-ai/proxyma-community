package ai.proxyma.core.settings.dto;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The prompt cache's Auto (2026-10-07 feedback: "Change Off to Auto ... the harness should be able
 * to determine which option is better during a conversation, but won't go below the option").
 *
 * <p>Auto is never less than five minutes - there is no off any more - and asks for an hour only
 * when what reads the prefix next is likely to come later than five minutes: a background sub-agent
 * still at work, or a reader whose last pause was between five minutes and an hour. A sub-agent's
 * own calls come seconds apart, so Auto is five minutes there, and "Same as the agent" follows the
 * agent's choice.
 */
class PromptCacheAutoTest {

    @Test
    @DisplayName("auto is the default, and an old stored off reads as auto")
    void autoByDefault() {
        assertEquals(PromptCache.AUTO, new ChatSettings().getPromptCache());
        assertEquals(PromptCache.AUTO, PromptCache.fromValue("off"));
    }

    @Test
    @DisplayName("a fixed choice is itself, whatever the conversation is doing")
    void fixedChoicesStay() {
        assertEquals(PromptCache.FIVE_MINUTES, PromptCache.FIVE_MINUTES.forTurn(true, Duration.ofMinutes(20)));
        assertEquals(PromptCache.ONE_HOUR, PromptCache.ONE_HOUR.forTurn(false, null));
    }

    @Test
    @DisplayName("auto asks for an hour while background work will wake the agent later")
    void backgroundWorkTakesAnHour() {
        assertEquals(PromptCache.ONE_HOUR, PromptCache.AUTO.forTurn(true, null));
        assertEquals(PromptCache.ONE_HOUR, PromptCache.AUTO.forTurn(true, Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("auto follows the reader's pace: an hour for a pause between five minutes and an hour")
    void readersPace() {
        assertEquals(PromptCache.FIVE_MINUTES, PromptCache.AUTO.forTurn(false, null));
        assertEquals(PromptCache.FIVE_MINUTES, PromptCache.AUTO.forTurn(false, Duration.ofMinutes(2)));
        assertEquals(PromptCache.FIVE_MINUTES, PromptCache.AUTO.forTurn(false, Duration.ofMinutes(5)));
        assertEquals(PromptCache.ONE_HOUR, PromptCache.AUTO.forTurn(false, Duration.ofMinutes(12)));
        assertEquals(PromptCache.ONE_HOUR, PromptCache.AUTO.forTurn(false, Duration.ofMinutes(60)));
        // Past an hour neither lifetime would have caught it.
        assertEquals(PromptCache.FIVE_MINUTES, PromptCache.AUTO.forTurn(false, Duration.ofMinutes(90)));
    }

    @Test
    @DisplayName("a sub-agent follows the agent until given its own, and auto is five minutes there")
    void subAgents() {
        ChatSettings agent = new ChatSettings();
        SubAgentSettings sub = new SubAgentSettings();

        assertEquals(PromptCache.FIVE_MINUTES, sub.promptCache(agent));
        agent.setPromptCache(PromptCache.ONE_HOUR);
        assertEquals(PromptCache.ONE_HOUR, sub.promptCache(agent));

        sub.setPromptCache("5m");
        assertEquals(PromptCache.FIVE_MINUTES, sub.promptCache(agent));
        sub.setPromptCache("auto");
        assertEquals(PromptCache.FIVE_MINUTES, sub.promptCache(agent));
        sub.setPromptCache("");
        assertEquals(PromptCache.ONE_HOUR, sub.promptCache(agent));
    }
}
