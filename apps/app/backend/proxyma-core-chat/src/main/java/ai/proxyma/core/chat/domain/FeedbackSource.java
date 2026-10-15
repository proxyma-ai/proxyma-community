package ai.proxyma.core.chat.domain;

// Discriminates which flow produced a Feedback row - GENERAL covers both the floating
// feedback-button and chat-message-dislike flows (their original single-purpose behavior,
// unchanged), QUOTA_MILESTONE is the $5-spend-bracket survey (see QuotaMilestoneFeedbackModal on
// the frontend). Nullable on the entity (existing rows predate this column) - treat a null value
// as GENERAL wherever it's read back out.
public enum FeedbackSource {
    GENERAL,
    QUOTA_MILESTONE
}
