package ai.proxyma.core.chat.service;

import ai.proxyma.core.provider.gateway.LlmGateway;

import java.util.Map;
import java.util.Optional;

/**
 * The conversation's prompt cache in a usage payload, for the composer's cache timer: until when it
 * is warm and how long a write or read keeps it - both null once it expired, which hides the timer.
 * The page counts down from cacheSecondsLeft, taken when the payload arrives, rather than to
 * cacheWarmUntil: a browser's clock and the server's need not agree. One writer for the usage
 * endpoint and the live usage event, so the two cannot come to say it differently.
 */
public final class PromptCacheFields {

    private PromptCacheFields() {}

    public static void put(Map<String, Object> payload, Optional<LlmGateway.PromptCacheState> cache) {
        payload.put("cacheWarmUntil", cache.map(c -> c.warmUntil().toString()).orElse(null));
        payload.put("cacheLifetimeSeconds", cache.map(c -> c.lifetime().toSeconds()).orElse(null));
        payload.put("cacheSecondsLeft", cache
                .map(c -> Math.max(0, java.time.Duration.between(java.time.Instant.now(), c.warmUntil()).toSeconds()))
                .orElse(null));
    }
}
