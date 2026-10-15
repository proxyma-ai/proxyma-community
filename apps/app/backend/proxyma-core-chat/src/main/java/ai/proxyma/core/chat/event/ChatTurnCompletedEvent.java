package ai.proxyma.core.chat.event;

import java.time.Instant;

// Fired exactly once per genuinely completed chat turn (see ChatService.chat(), right after the
// turn succeeds), unconditionally - unlike LlmSpendRecordedEvent/ChatTurnLatencyRecordedEvent,
// this event is never skipped for anonymous/desktop conversations, zero-cost turns, or when
// Evaluation Mode is off, and it is deliberately never published from SmartChatMemory's
// condensation path - only from a real user-initiated turn.
//
// This event has NO identifying fields by construction (no userId, no conversationId, no
// content) - not because a listener promises not to read them, but because the one consumer, the
// completed-turn count in the daily diagnostics (ChatTelemetryListener, sent only where
// TelemetryConsent allows: a Community edition with "Send diagnostics to Proxyma" on), must be
// structurally incapable of receiving individual-level data. Do not add identifying fields to this
// event - if a future consumer needs per-user data, it should listen to a different, already-
// identifying event instead (e.g. LlmSpendRecordedEvent).
//
// It carried a `contributesToUsageStats` yes/no until 2026-10-15: Enterprise Server's per-account
// "Contribute anonymous usage counts" choice, which nothing read - the local counters it once fed
// were gone, and Enterprise Server sends no diagnostics at all. The choice was removed with it
// (release review 2026-10-15, W26): a consent control that controls nothing is not one to ask for.
public record ChatTurnCompletedEvent(Instant occurredAt) {}
