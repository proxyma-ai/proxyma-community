package ai.proxyma.core.chat.event;

// Fired once per completed ReAct turn (see ChatService.react()/chat()) with per-stage latency,
// retry, and outcome data for that turn - part of "Evaluation Mode" (see
// ../proxyma-docs/architecture/evaluation-mode.md). Published only when EvaluationSettings.enabled is true
// (proxyma-core-chat cannot depend on proxyma-core-evaluation - evaluation depends on chat, never
// the reverse - so this crosses the module boundary via a plain Spring application event, same
// pattern as LlmSpendRecordedEvent). Listened to by proxyma-core-evaluation's
// EvaluationMetricsListener (server profile only) and persisted to eval_chat_turn_metrics.
// Deliberately carries no user or conversation identity - Evaluation Mode is anonymous by
// construction, with no exception for any admin role, so no field here is capable of holding one.
public record ChatTurnLatencyRecordedEvent(
        String provider,
        String model,
        long timeToFirstTokenMs,
        long totalDurationMs,
        int retryCount,
        boolean success,
        long outputTokenCount) {
}
