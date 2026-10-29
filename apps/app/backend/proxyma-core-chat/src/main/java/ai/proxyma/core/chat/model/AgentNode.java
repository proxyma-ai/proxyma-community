package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent another agent deployed - a CHILD in the conversation's agent tree. Every agent is a
 * conversation of its own, run by the same loop as the one the user opened: this row says whose
 * child it is, how deep it sits, how long it lives and where it stands. The conversation the user
 * opened is the tree's root and has no row.
 *
 * <p>Text columns are {@code text}, never {@code @Lob} - see SubAgentRunColumnsTest for what an oid
 * large object cost on PostgreSQL.
 */
@Entity
@Table(name = "agent_nodes", indexes = {
        @Index(name = "idx_agent_nodes_conversation", columnList = "conversationId", unique = true),
        @Index(name = "idx_agent_nodes_parent", columnList = "parentConversationId"),
        @Index(name = "idx_agent_nodes_root", columnList = "rootConversationId"),
})
public class AgentNode {

    public static final int TITLE_MAX = 200;
    public static final int TASK_MAX = 20_000;

    /** How long an agent lives: SHORT ends with the report on its task; LONG stays to take more. */
    public static final String SHORT = "SHORT";
    public static final String LONG = "LONG";

    /** Working on something for its parent. */
    public static final String RUNNING = "RUNNING";
    /** Its turn has ended and it waits on work of its own: its children, a background job, an alarm. */
    public static final String WAITING = "WAITING";
    /** A LONG agent with nothing to do, until its parent or the user gives it something. */
    public static final String IDLE = "IDLE";
    /** Held by its parent: what reaches it waits, and it starts no turn. */
    public static final String PAUSED = "PAUSED";
    /** A SHORT agent that has reported, or a LONG one dismissed. */
    public static final String DONE = "DONE";
    public static final String STOPPED = "STOPPED";
    public static final String FAILED = "FAILED";

    // Assigned, not generated: an import names a node in the rows it writes beside it.
    @Id
    private UUID id = UUID.randomUUID();

    /** The child's own conversation. */
    @Column(nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false, length = 64)
    private String parentConversationId;

    /** The conversation the user opened, at the top of the tree. */
    @Column(nullable = false, length = 64)
    private String rootConversationId;

    /** The person the tree works for - the root conversation's user; null for nobody signed in. */
    @Column(length = 128)
    private String userId;

    /** 1 for a child of the root, 2 for a grandchild, and so on. */
    @Column(nullable = false)
    private int depth;

    @Column(nullable = false, length = 8)
    private String lifetime = SHORT;

    @Column(nullable = false, length = 16)
    private String status = RUNNING;

    /** The status it had before it was paused, to resume to. */
    @Column(length = 16)
    private String pausedFrom;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(columnDefinition = "text")
    private String task;

    /** A provider instance and model of its own, or null to run on what its parent runs on. */
    @Column(length = 64)
    private String providerId;

    @Column(length = 200)
    private String model;

    /** Whether it owes its parent a report: set by a task or a message, cleared by the report. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean reportOwed = true;

    /** Its latest report, as its parent received it. */
    @Column(columnDefinition = "text")
    private String lastReport;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    private Instant endedAt;

    /** What it - with everything it deploys - may spend, in US dollars; null for no limit of its own. */
    private Double budgetUsd;

    /** When it - with everything it deploys - must be done; null for no deadline of its own. */
    private Instant deadlineAt;

    /** Why it was stopped when the tree stopped it: its budget or deadline, or an ancestor's. */
    @Column(length = 500)
    private String stopNote;

    /** The fan-out (AgentFanOuts) it works one item of, when it does; its report goes there. */
    private UUID fanOutId;

    /** Its item's place in that fan-out, from 0. */
    private Integer itemIndex;

    /** WORKER for an ordinary agent; VERIFIER for one that checks another's report (AgentVerifier). */
    @Column(length = 16)
    private String role;

    /** Whether its reports are checked by a verifier before they reach its parent. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verify;

    /** The agent whose report it checks, for a VERIFIER. */
    private UUID verifiesAgentId;

    /** The scorecard of its latest checked report, as JSON (AgentVerifier.Scorecard). */
    @Column(columnDefinition = "text")
    private String verification;

    public static final String VERIFIER = "VERIFIER";

    public Double getBudgetUsd() { return budgetUsd; }
    public void setBudgetUsd(Double budgetUsd) { this.budgetUsd = budgetUsd; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public String getStopNote() { return stopNote; }
    public void setStopNote(String stopNote) { this.stopNote = stopNote; }
    public UUID getFanOutId() { return fanOutId; }
    public void setFanOutId(UUID fanOutId) { this.fanOutId = fanOutId; }
    public Integer getItemIndex() { return itemIndex; }
    public void setItemIndex(Integer itemIndex) { this.itemIndex = itemIndex; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isVerify() { return verify; }
    public void setVerify(boolean verify) { this.verify = verify; }
    public UUID getVerifiesAgentId() { return verifiesAgentId; }
    public void setVerifiesAgentId(UUID verifiesAgentId) { this.verifiesAgentId = verifiesAgentId; }
    public String getVerification() { return verification; }
    public void setVerification(String verification) { this.verification = verification; }

    /** Whether it checks another agent's report rather than working for its parent. */
    public boolean isVerifier() {
        return VERIFIER.equals(role);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(String parentConversationId) { this.parentConversationId = parentConversationId; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public String getLifetime() { return lifetime; }
    public void setLifetime(String lifetime) { this.lifetime = lifetime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getPausedFrom() { return pausedFrom; }
    public void setPausedFrom(String pausedFrom) { this.pausedFrom = pausedFrom; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isReportOwed() { return reportOwed; }
    public void setReportOwed(boolean reportOwed) { this.reportOwed = reportOwed; }
    public String getLastReport() { return lastReport; }
    public void setLastReport(String lastReport) { this.lastReport = lastReport; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    /** Whether it is over: reported and done, stopped, or failed. */
    public boolean isEnded() {
        return DONE.equals(status) || STOPPED.equals(status) || FAILED.equals(status);
    }

    public boolean isLong() {
        return LONG.equals(lifetime);
    }
}
