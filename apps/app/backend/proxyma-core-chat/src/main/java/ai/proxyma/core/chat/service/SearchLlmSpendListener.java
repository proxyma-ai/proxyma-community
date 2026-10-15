package ai.proxyma.core.chat.service;

import ai.proxyma.core.search.event.SearchLlmSpendRecordedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

// Lands the search pipeline's own LLM spend (query expansion, prompt-scoring reranking) wherever it
// can be charged. Lives here, in proxyma-core-chat, because this is the module that owns the
// conversation counters and the pricing path - proxyma-core-search cannot depend on it (chat
// depends on search, never the reverse), which is exactly why the spend arrives as an event. See
// SearchLlmSpendRecordedEvent for what each null id means. Always on: the conversation's cumulative
// totals and the user's quota are accounting, not an optional record.
@Service
class SearchLlmSpendListener {

    private static final Logger log = LoggerFactory.getLogger(SearchLlmSpendListener.class);

    private final ConversationUsageRecorder usageRecorder;

    SearchLlmSpendListener(ConversationUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
    }

    // Synchronous (no @Async): it runs on the searching thread, inside the turn that caused it. The
    // work is one row update, the same one every chat iteration already does.
    @EventListener
    void onSearchLlmSpend(SearchLlmSpendRecordedEvent event) {
        if (event.conversationId() != null) {
            usageRecorder.record(event.conversationId(), event.usage(), event.provider(), event.model());
            return;
        }
        // No conversation: an MCP or /api/v1/search caller. Quota only.
        double costUsd = usageRecorder.recordUserOnly(event.userId(), event.usage(),
                event.provider(), event.model());
        if (costUsd > 0) {
            log.debug("[SearchSpend] {} charged {} to user quota (no conversation)", event.stage(), costUsd);
        }
    }
}
