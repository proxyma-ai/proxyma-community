package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.api.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Settings > Agents "Prompt cache", as the cache instruction Anthropic receives.
 *
 * <p>The one-hour case is the one worth pinning: Spring AI gives tool definitions the SYSTEM
 * message type's lifetime, and Anthropic refuses a prompt in which a longer-lived breakpoint
 * follows a shorter one. Setting one hour on the system prompt alone would be valid; setting it on
 * history alone would be refused. Every type at one hour is the shape that is always accepted.
 */
class AnthropicPromptCacheTest {

    private static AnthropicCacheTtl ttlOf(AnthropicCacheOptions options, MessageType type) {
        return options.getMessageTypeTtl().getOrDefault(type, AnthropicCacheTtl.FIVE_MINUTES);
    }

    @Test
    @DisplayName("five minutes caches the conversation history at the default lifetime - the behavior before this was a setting")
    void fiveMinutes() {
        AnthropicCacheOptions options = AnthropicAdapter.cacheOptions(PromptCache.FIVE_MINUTES);

        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.getStrategy());
        for (MessageType type : MessageType.values()) {
            assertEquals(AnthropicCacheTtl.FIVE_MINUTES, ttlOf(options, type), type.name());
        }
    }

    @Test
    @DisplayName("no choice at all is five minutes")
    void nullIsTheDefault() {
        AnthropicCacheOptions options = AnthropicAdapter.cacheOptions(null);

        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.getStrategy());
        assertEquals(AnthropicCacheTtl.FIVE_MINUTES, ttlOf(options, MessageType.SYSTEM));
    }

    @Test
    @DisplayName("one hour applies to EVERY message type, so no breakpoint outlives the one before it")
    void oneHourEverywhere() {
        AnthropicCacheOptions options = AnthropicAdapter.cacheOptions(PromptCache.ONE_HOUR);

        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.getStrategy());
        for (MessageType type : MessageType.values()) {
            assertEquals(AnthropicCacheTtl.ONE_HOUR, ttlOf(options, type), type.name());
        }
    }

    @Test
    @DisplayName("auto reaching the adapter unresolved still caches, for five minutes - there is no off")
    void autoIsNeverOff() {
        AnthropicCacheOptions options = AnthropicAdapter.cacheOptions(PromptCache.AUTO);
        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.getStrategy());
        for (MessageType type : MessageType.values()) {
            assertNotEquals(AnthropicCacheTtl.ONE_HOUR, ttlOf(options, type), type.name());
        }
    }

    @Test
    @DisplayName("an unknown stored value - the old off among them - reads as auto rather than failing the settings group")
    void lenientStoredValue() {
        assertEquals(PromptCache.ONE_HOUR, PromptCache.fromValue("1h"));
        assertEquals(PromptCache.AUTO, PromptCache.fromValue("off"));
        assertEquals(PromptCache.AUTO, PromptCache.fromValue("forever"));
    }
}
