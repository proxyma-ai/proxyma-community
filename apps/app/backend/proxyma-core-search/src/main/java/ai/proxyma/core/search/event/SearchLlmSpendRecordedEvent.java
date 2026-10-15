package ai.proxyma.core.search.event;

import ai.proxyma.core.common.usage.TokenUsage;

// Fired whenever the search pipeline itself calls an LLM - query expansion (one call per search)
// and the prompt-scoring reranking tier (one event per rerank, carrying the summed usage of its
// per-candidate calls, rather than one event per candidate). These are real, billed provider calls
// that used to be recorded nowhere at all: not on the conversation's cumulative totals, not in the
// per-turn Evaluation record, not against the user's monthly quota. A deployment with reranking on
// could therefore spend more on scoring a search than on answering with it, invisibly.
//
// Crosses the module boundary as a plain Spring application event because proxyma-core-search
// cannot depend on proxyma-core-chat (chat depends on search, never the reverse) - the same reason
// and the same pattern as SearchStageLatencyRecordedEvent and
// ai.proxyma.core.common.spend.LlmSpendRecordedEvent. Listened to by proxyma-core-chat's
// SearchLlmSpendListener, which is where the pricing table and the conversation counters live.
//
// The three ids are read off SearchContext at publish time and any of them can be null, which is
// information rather than an omission - it says where this spend can and cannot be charged:
//  - conversationId + turnId set: a chat-driven search. Charged to the conversation's cumulative
//    totals and staged against that turn for the Evaluation record.
//  - userId only: an MCP `searchIndexedSources` call from an IDE, or /api/v1/search. There is no
//    conversation to bill, but there is a user, so it still counts against their quota.
//  - nothing set: desktop mode, single user, no quota. Priced by nobody, owed to nobody.
//
// Never carries the query text or any result content - see SearchStageLatencyRecordedEvent's
// javadoc for why nothing user-authored may travel on a search event.
public record SearchLlmSpendRecordedEvent(
        String conversationId,
        String turnId,
        String userId,
        String provider,
        String model,
        String stage,
        TokenUsage usage) {
}
