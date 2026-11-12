package ai.proxyma.core.chat.event;

/**
 * How a write kept in the approvals inbox went was written into the conversation that asked for it AS A NOTE,
 * starting no turn (ApprovalInbox, where ChatModule.approvalOutcomeQuiet says the agent need not act on it).
 * Whatever waited on that request - a module's run whose reply was owed once nothing of the conversation's own
 * was still running - may settle now, since no turn will come to settle it.
 */
public record ApprovalOutcomeNotedEvent(String conversationId) {}
