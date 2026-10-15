package ai.proxyma.core.common.spend;

/**
 * One priced model call, as the per-call ledger recorded it - published by LlmCallLogService for
 * every call with a cost, whatever made it.
 *
 * <p>THE ONE SOURCE OF PER-USER SPEND, since 2026-09-29. A user's quota used to be fed by an event
 * three services each remembered to publish (a chat turn's usage, a sub-agent's, a search's), so
 * the guardrail classifier, workflow nodes, rerank and embeddings charged nobody - and each new
 * caller was one more place to forget. Every model call reaches the per-call ledger by
 * construction (the gateway and the metered embedding bean), so the quota listens there.
 *
 * <p>A plain Spring event because the listener (proxyma-core-auth's UserTokenQuotaService, server
 * only) sits in a module the ledger cannot depend on. On desktop nothing listens.
 *
 * @param subject who the call was for, already mapped for the edition (SpendSubject.forEdition)
 * @param role    the LlmRole name the call was made under
 * @param costUsd what it cost, above zero
 */
public record LlmSpendRecordedEvent(SpendSubject subject, String role, double costUsd) {}
