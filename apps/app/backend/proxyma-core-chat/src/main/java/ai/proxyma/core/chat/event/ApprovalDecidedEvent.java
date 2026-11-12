package ai.proxyma.core.chat.event;

/**
 * A write kept in the approvals inbox (ApprovalInbox) was decided: approved and run, denied, or left
 * to expire. Published once per request, after the agent was told, for whatever another module learns
 * from a person's decisions - Constellations keeps the replies a person approved, edited or refused in
 * its project's team knowledge, so its agents write the next one the way the person would.
 *
 * <p>An approved write the service refused for a limit that lifts (a spent daily quota) is published
 * twice: once {@code held}, not succeeded, when it is held for its retry, and once more when a retry
 * makes it or gives up (ApprovalInbox.retryHeld).
 *
 * @param outcome         APPROVED, DENIED or EXPIRED (ToolApprovalRequest's statuses)
 * @param draftArguments  the agent's own arguments, when the person edited them before approving; else null
 * @param arguments       what ran (approved), or what was asked (denied, expired)
 * @param context         what the write was about (ApprovalContext JSON), or null
 * @param note            the person's note, or null
 * @param succeeded       approved and the call reported no failure
 * @param held            approved, refused for a limit, and kept for a retry - nothing was made yet
 * @param automatic       approved by the SERVER, not a person (ApprovalInbox.approveAutomatically: the project
 *                        approves its agents' writes, or the agent's runs are set to Auto) - nobody reviewed it,
 *                        so it says nothing about what the person would have written or refused
 */
public record ApprovalDecidedEvent(String requestId, String conversationId, String rootConversationId, String userId,
                                   String toolName, String outcome, String draftArguments, String arguments,
                                   String context, String note, boolean succeeded, boolean held, boolean automatic) {

    public ApprovalDecidedEvent(String requestId, String conversationId, String rootConversationId, String userId,
                                String toolName, String outcome, String draftArguments, String arguments,
                                String context, String note, boolean succeeded, boolean held) {
        this(requestId, conversationId, rootConversationId, userId, toolName, outcome, draftArguments, arguments, context,
                note, succeeded, held, false);
    }

    public ApprovalDecidedEvent(String requestId, String conversationId, String rootConversationId, String userId,
                                String toolName, String outcome, String draftArguments, String arguments,
                                String context, String note, boolean succeeded) {
        this(requestId, conversationId, rootConversationId, userId, toolName, outcome, draftArguments, arguments, context,
                note, succeeded, false, false);
    }

    public boolean edited() {
        return draftArguments != null;
    }
}
