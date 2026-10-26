package ai.proxyma.core.common.usage;

import java.time.Instant;

/**
 * What a provider's response said about ONE call beyond its token counts, plus when it ran - the
 * things a rate table alone cannot price. Read off the wire by the provider module (WireUsage) and
 * handed to ModelPricingService when the call's cost is settled.
 *
 * @param reportedCostUsd   what the provider says the call cost, or null when it did not say
 *                          (OpenRouter's usage.cost, LiteLLM's x-litellm-response-cost)
 * @param serviceTier       the tier the response says served the call ("priority", "flex",
 *                          "batch", "default", "standard"...), or null
 * @param inferenceGeo      Anthropic's inference_geo ("us", "global"), or null
 * @param webSearchRequests server-side web searches the call made (Anthropic's
 *                          usage.server_tool_use.web_search_requests), each billed by the request
 * @param protocol          the provider protocol that served it (ANTHROPIC, OPENAI, GEMINI ...), or
 *                          null when unknown - decides which list prices may apply
 * @param at                when the call ran, for prices that depend on the time of day
 */
public record CallFacts(Double reportedCostUsd, String serviceTier, String inferenceGeo,
                        long webSearchRequests, String protocol, Instant at) {

    /** Nothing stated, now, by an unknown protocol - what a call priced outside the gateway gets. */
    public static CallFacts now() {
        return new CallFacts(null, null, null, 0, null, Instant.now());
    }

    public CallFacts withProtocol(String value) {
        return new CallFacts(reportedCostUsd, serviceTier, inferenceGeo, webSearchRequests, value, at);
    }
}
