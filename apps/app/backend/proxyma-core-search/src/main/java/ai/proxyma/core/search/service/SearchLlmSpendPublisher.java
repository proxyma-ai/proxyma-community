package ai.proxyma.core.search.service;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.event.SearchLlmSpendRecordedEvent;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.service.SettingsService;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Turns a search-side LLM response into a SearchLlmSpendRecordedEvent, stamped with whatever
// SearchContext says this thread is doing. Shared by QueryExpansionService and RerankingService so
// the two cannot drift on how they name the model they used or which ids they attach - the reason
// ConversationUsageRecorder exists on the chat side, for the same failure mode.
//
// Both callers resolve their model the same way: a dedicated query-expansion/reranking model when
// one is configured, otherwise the main chat model. Pricing has to follow that fallback or a
// deployment that configured neither would price cheap-model calls at the main model's rate (or
// the reverse), so `usedDedicatedModel` is passed in by the caller that knows which branch it took.
@Service
public class SearchLlmSpendPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final SettingsService settingsService;
    private final LlmProviderFactory providerFactory;

    public SearchLlmSpendPublisher(ApplicationEventPublisher eventPublisher, SettingsService settingsService,
                                   LlmProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
        this.eventPublisher = eventPublisher;
        this.settingsService = settingsService;
    }

    // Extracts usage from one response and returns it, so a caller that makes several calls (the
    // reranking loop) can sum them and publish once instead of once per candidate - a rerank of 40
    // candidates would otherwise fire 40 events and 40 conversation-row updates for one search.
    // TokenUsage.EMPTY for a provider that reported nothing, which sums harmlessly.
    public TokenUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return TokenUsage.EMPTY;
        return TokenUsageExtractor.extract(response);
    }

    // Silently does nothing for zero usage: providers that report no usage at all (Ollama on some
    // endpoints) would otherwise generate a stream of events that price to 0 and write nothing.
    public void publish(String stage, String dedicatedProvider, String dedicatedModel,
                        boolean usedDedicatedModel, TokenUsage usage) {
        if (usage == null || (usage.inputTokens() == 0 && usage.outputTokens() == 0
                && usage.cacheReadTokens() == 0 && usage.cacheWriteTokens() == 0)) {
            return;
        }
        String provider = dedicatedProvider;
        String model = dedicatedModel;
        if (!usedDedicatedModel) {
            ChatSettings chat = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
            // providerDisplayName, not the raw id: roles store an instance id and the ledger is read
            // by people (and matched against the price list) by name.
            provider = providerFactory.providerDisplayName(chat.getProvider());
            model = chat.getModel();
        }
        eventPublisher.publishEvent(new SearchLlmSpendRecordedEvent(
                SearchContext.getConversationId(), SearchContext.getTurnId(), SearchContext.getUserId(),
                provider, model, stage, usage));
    }
}
