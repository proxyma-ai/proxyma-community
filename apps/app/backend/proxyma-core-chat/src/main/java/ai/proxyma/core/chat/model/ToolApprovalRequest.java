package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A write an agent asked to make while nobody was watching its conversation - a scheduled run, a
 * teammate's message, an agent of such a run - kept for a person to decide in the approvals inbox
 * (ApprovalInbox). The call did not run: the agent was told it waits, its turn went on, and the
 * decision reaches it as a message, with the result when the write was approved and made.
 *
 * <p>Text columns are {@code text}, never {@code @Lob} (SubAgentRunColumnsTest's lesson).
 */
@Entity
@Table(name = "tool_approval_requests", indexes = {
        @Index(name = "idx_tool_approvals_conversation", columnList = "conversationId, status"),
        @Index(name = "idx_tool_approvals_root", columnList = "rootConversationId, status"),
        @Index(name = "idx_tool_approvals_user", columnList = "userId, status"),
})
public class ToolApprovalRequest {

    public static final String PENDING = "PENDING";
    /** Approved, and the call ran - its result is {@link #result}, whatever it says. */
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    /** Nobody decided in time ({@code ApprovalInbox.EXPIRY}). */
    public static final String EXPIRED = "EXPIRED";

    public static final int ARGUMENTS_MAX = 60_000;
    public static final int RESULT_MAX = 20_000;
    public static final int NOTE_MAX = 2_000;

    @Id
    private UUID id = UUID.randomUUID();

    /** The conversation whose turn asked. */
    @Column(nullable = false, length = 64)
    private String conversationId;

    /** The top of its agent tree - itself for a conversation no agent deployed. */
    @Column(nullable = false, length = 64)
    private String rootConversationId;

    /** The person the conversation works for, who alone decides; null on Desktop. */
    @Column(length = 128)
    private String userId;

    @Column(nullable = false, length = 200)
    private String toolName;

    @Column(columnDefinition = "text")
    private String arguments;

    /** The asking agent's title when a tree deployed it, else null. */
    @Column(length = 200)
    private String agentTitle;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant decidedAt;

    @Column(length = 128)
    private String decidedBy;

    /** What the person wrote with the decision, passed on to the agent. */
    @Column(columnDefinition = "text")
    private String note;

    /** The call's result when it ran. */
    @Column(columnDefinition = "text")
    private String result;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public String getAgentTitle() { return agentTitle; }
    public void setAgentTitle(String agentTitle) { this.agentTitle = agentTitle; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
