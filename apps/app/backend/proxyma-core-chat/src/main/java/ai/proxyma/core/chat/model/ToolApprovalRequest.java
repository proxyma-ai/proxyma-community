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
        @Index(name = "idx_tool_approvals_subject", columnList = "subject, status"),
        @Index(name = "idx_tool_approvals_retry", columnList = "runOutcome, retryAt"),
})
public class ToolApprovalRequest {

    public static final String PENDING = "PENDING";
    /** Approved, and the call ran - its result is {@link #result}, whatever it says. */
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    /** Nobody decided in time ({@code ApprovalInbox.EXPIRY}). */
    public static final String EXPIRED = "EXPIRED";
    /**
     * Never decided: a newer draft for the same subject took its place while it waited (ApprovalInbox.park),
     * whichever agent or run wrote it. Listed with the decided ones, so the person sees what became of it.
     */
    public static final String REPLACED = "REPLACED";
    /** Never decided: an agent of the same person withdrew it while it waited (ApprovalInbox.withdraw). */
    public static final String WITHDRAWN = "WITHDRAWN";

    /** {@link #runOutcome}: an approved write that ran and reported no failure. */
    public static final String RAN_DONE = "DONE";
    /** {@link #runOutcome}: an approved write that ran and failed - for good, or past its retries. */
    public static final String RAN_FAILED = "FAILED";
    /**
     * {@link #runOutcome}: an approved write the service refused for a limit that lifts - a spent daily quota -
     * so it was NOT made yet and is retried after {@link #retryAt} (ApprovalInbox.retryHeld). Still APPROVED.
     */
    public static final String RAN_HELD = "HELD";
    /** {@link #runOutcome}: a held write being retried now; back to HELD if the server stopped meanwhile. */
    public static final String RAN_RETRYING = "RETRYING";

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

    /**
     * What the write is about, for the person deciding (ApprovalContext as JSON) - the video, viewer,
     * comment and reply of a YouTube reply. Null when no provider could say within its limit.
     */
    @Column(columnDefinition = "text")
    private String context;

    /**
     * What the write is about (ApprovalContextProvider.subjectOf) - the comment a YouTube reply answers.
     * A later request with the same tool and subject, for the same person, replaces this one while it waits.
     */
    @Column(length = 200)
    private String subject;

    /**
     * The arguments as the agent wrote them, kept only when the person edited them before approving -
     * {@link #arguments} is then what ran. Read back to the agent, so it learns from the change.
     */
    @Column(columnDefinition = "text")
    private String draftArguments;

    /**
     * How many times {@link #context} was looked up and nothing answered (ApprovalInbox fills a missing
     * one later, up to ApprovalInbox.CONTEXT_ATTEMPTS). Set to that limit when no provider describes the
     * tool, so nothing is asked again for a write that has no context to give.
     */
    private Integer contextAttempts = 0;

    /**
     * How an APPROVED write's call went: {@link #RAN_DONE}, {@link #RAN_FAILED}, {@link #RAN_HELD} or
     * {@link #RAN_RETRYING}; null while it has not run yet, and for every other status.
     */
    @Column(length = 16)
    private String runOutcome;

    /** When the call last ran - what "posted today" counts by, a retry's time for a write held first. */
    private Instant ranAt;

    /** A held write's next try. */
    private Instant retryAt;

    /** How many times an approved write's call was made: one, and one more each retry. */
    private Integer runAttempts;

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
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDraftArguments() { return draftArguments; }
    public void setDraftArguments(String draftArguments) { this.draftArguments = draftArguments; }
    public int getContextAttempts() { return contextAttempts == null ? 0 : contextAttempts; }
    public void setContextAttempts(Integer contextAttempts) { this.contextAttempts = contextAttempts; }
    public String getRunOutcome() { return runOutcome; }
    public void setRunOutcome(String runOutcome) { this.runOutcome = runOutcome; }
    public Instant getRanAt() { return ranAt; }
    public void setRanAt(Instant ranAt) { this.ranAt = ranAt; }
    public Instant getRetryAt() { return retryAt; }
    public void setRetryAt(Instant retryAt) { this.retryAt = retryAt; }
    public int getRunAttempts() { return runAttempts == null ? 0 : runAttempts; }
    public void setRunAttempts(Integer runAttempts) { this.runAttempts = runAttempts; }

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
