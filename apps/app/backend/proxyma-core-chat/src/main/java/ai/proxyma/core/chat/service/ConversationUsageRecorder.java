package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.usage.ChatSpendService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

// Folds one LLM call's token usage onto its conversation's running totals and pushes the fresh
// snapshot to the client as a USAGE event.
//
// Extracted so ChatService (main agent) and SubAgentService (delegated work) cannot drift: both
// must land in the SAME conversation counters - a delegated call is still spend the user incurred
// on this conversation, and leaving it out would make the usage meter and the per-user monthly
// quota under-report by exactly the amount delegation was used. The quota itself is charged by the
// per-call ledger (LlmSpendRecordedEvent), which every one of these calls reached through the gateway.
//
// Note what this deliberately does NOT do: record context tokens. That snapshot is the MAIN
// conversation's prompt size and drives condensation (SmartChatMemory.recordContextTokens); a
// sub-agent's prompt lives in its own throwaway context and must never overwrite it.
@Service
class ConversationUsageRecorder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SmartChatMemory chatMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatEventSink chatEventSink;
    private final ModelPricingService modelPricingService;
    private final ChatSpendService chatSpendService;
    private final ai.proxyma.core.provider.gateway.LlmGateway llmGateway;

    ConversationUsageRecorder(SmartChatMemory chatMemory,
                              ApplicationEventPublisher eventPublisher,
                              ChatEventSink chatEventSink,
                              ModelPricingService modelPricingService,
                              ChatSpendService chatSpendService,
                              ai.proxyma.core.provider.gateway.LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
        this.chatEventSink = chatEventSink;
        this.modelPricingService = modelPricingService;
        this.chatSpendService = chatSpendService;
    }

    // Returns the USD cost this call added, so a caller that also attributes the same spend
    // elsewhere (a sub-agent's own record) doesn't have to re-price it against the pricing table.
    double record(String conversationId, TokenUsage usage, String provider, String model) {
        double costDelta = chatMemory.addCumulativeUsage(conversationId, usage, provider, model);
        Conversation conv = chatMemory.findConversation(conversationId).orElse(null);
        if (conv == null) return costDelta;

        Map<String, Object> payload = new HashMap<>();
        payload.put("inputTokens", conv.getInputTokens());
        payload.put("outputTokens", conv.getOutputTokens());
        payload.put("cacheReadTokens", conv.getCacheReadTokens());
        payload.put("cacheWriteTokens", conv.getCacheWriteTokens());
        payload.put("contextTokens", conv.getContextTokens());
        payload.put("contextTokenLimit", chatMemory.contextWindow());
        PromptCacheFields.put(payload, llmGateway == null ? java.util.Optional.empty() : llmGateway.promptCache(conversationId));
        try {
            eventPublisher.publishEvent(new ChatEvent(conversationId,
                    chatEventSink.nextSequence(conversationId),
                    ChatEvent.Type.USAGE.value(), null, null, JSON.writeValueAsString(payload)));
        } catch (Exception e) {
            // A usage snapshot that fails to serialize is a cosmetic meter miss, never a reason to
            // fail the turn - the totals are already persisted above.
            return costDelta;
        }
        return costDelta;
    }

    // Spend that belongs to a user but to no conversation: a `delegate` or `searchIndexedSources`
    // call arriving over /mcp from an IDE, or a standalone /api/v1/search request. There is no
    // conversation row to fold it into and no usage meter watching, but there IS a user whose
    // monthly $ quota it should count against - which it silently did not, so a user could run an
    // agent against the MCP endpoint all day and stay at $0 spent while a colleague doing the same
    // work in the chat UI burned quota for it.
    //
    // Prices the usage here rather than going through addCumulativeUsage(), which needs a
    // conversation to write to. The user's quota is charged by the per-call ledger, not here - see
    // LlmSpendRecordedEvent. Returns the cost for the caller to log or ignore.
    //
    // It reaches BOTH ledgers, which it did not until 2026-09-12 - see the note inside.
    double recordUserOnly(String userId, TokenUsage usage, String provider, String model) {
        if (userId == null || usage == null) return 0.0;
        double costUsd = modelPricingService.costUsd(provider, model, usage);
        // THE PER-MODEL LEDGER TOO, not the quota alone.
        //
        // This charged the user's monthly quota and stopped, so spend that reached Proxyma over
        // /mcp or /api/v1/search was invisible on Settings > Usage & Cost - the deployment could
        // see that a user had spent their budget and could not see on WHAT. The same work done in
        // the chat UI appeared on both, so the two screens disagreed by exactly the amount anybody
        // was driving the product from an IDE.
        //
        // chat_spend_month wants no conversation and no user - only a month, a provider, a model
        // and counters (see ChatSpendMonth, which stores no identity at all by design) - so the
        // absence of a conversation was never the reason this path skipped it. Written BEFORE the
        // cost-gate below, deliberately: an unpriced model bills nothing and still did real work,
        // and a breakdown that silently omits it is how a model comes to look unused.
        chatSpendService.record(usage, provider, model, costUsd);
        // The user's quota is charged by the per-call ledger, which this call already reached
        // through the gateway - see LlmSpendRecordedEvent. Publishing here as well double-counted.
        return costUsd;
    }
}
