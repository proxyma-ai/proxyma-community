package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One run of a graph workflow.
 *
 * <p>A NEW TABLE RATHER THAN A CHANGE TO {@link WorkflowRun}. The markdown runner is still live and
 * still owns that one, and `CLAUDE.md` is explicit that new tables are the safe kind of schema
 * change under {@code ddl-auto=update} while altering an existing one has already half-applied
 * twice. The two coexist until the old runner is deleted, which is the last step of this build
 * rather than this one.
 *
 * <p>{@code conversationId} IS NULLABLE HERE, and that is the whole point of the change: a run is
 * a first-class object with its own id, its own event stream and its own controls. A conversation
 * id means "this run was invoked from a chat turn", which is the only thing it should ever have
 * meant - see proxyma-docs/proposals/workflow-design-page.md.
 *
 * <p>Persistable with an assigned id, and NOT {@code @GeneratedValue}. That combination is what
 * broke every run of the markdown runner from the day it shipped (see LESSONS_LEARNED, 2026-08-17):
 * Spring Data reads a populated id as evidence the row exists and issues an UPDATE. The id is
 * assigned here for the same reason it was there - callers need it before the row is written, to
 * key the event stream a client subscribes to.
 */
@Entity
@Table(name = "workflow_graph_runs", indexes = {
        @Index(name = "idx_graph_runs_workflow", columnList = "workflowId"),
        @Index(name = "idx_graph_runs_user", columnList = "userId"),
        @Index(name = "idx_graph_runs_status", columnList = "status"),
        @Index(name = "idx_graph_runs_conversation", columnList = "conversationId"),
})
public class WorkflowGraphRun implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean newRow = true;

    @Column(nullable = false)
    private String workflowId;

    @Column(nullable = false)
    private String workflowName;

    /** Who started it, and therefore who may pause, stop or approve. Null in desktop, which has one
     *  user and no auth. */
    @Column
    private String userId;

    /** Set only when a chat turn started this run. Null for one started from the design page. */
    @Column
    private String conversationId;

    /** {@code RunStatus} by name. Stored as a string rather than an enum mapping so a status added
     *  later by a newer build reads back on an older one as itself rather than as a load failure. */
    @Column(nullable = false, length = 24)
    private String status;

    /** nodeId -> status, as JSON. The SMALL whole-snapshot half of the stream: it is resent
     *  complete on every transition, which is what lets a refresh mid-run redraw the true state.
     *  The unbounded half - the execution log - is its own table for exactly that reason. */
    // TEXT, never @Lob: on PostgreSQL a @Lob String is an `oid` large object, readable only inside
    // the transaction that fetched it - the class of bug SubAgentRun's report hit (2026-10-06).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String nodeStatusJson;

    /** What the run was started with, as JSON. Kept so a finished run can say what it was asked,
     *  which is half of "what did this step see". */
    @Column(columnDefinition = "TEXT")
    private String inputsJson;

    /** The Result node's resolved value, as JSON. */
    @Column(columnDefinition = "TEXT")
    private String answerJson;

    @Column(nullable = false)
    private Instant startedAt;

    @Column
    private Instant finishedAt;

    @Column(length = 2000)
    private String failureMessage;

    @Column
    private String failedNode;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private double costUsd;

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    public boolean isNew() { return newRow; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.newRow = false; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNodeStatusJson() { return nodeStatusJson; }
    public void setNodeStatusJson(String nodeStatusJson) { this.nodeStatusJson = nodeStatusJson; }

    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }

    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public String getFailedNode() { return failedNode; }
    public void setFailedNode(String failedNode) { this.failedNode = failedNode; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public double getCostUsd() { return costUsd; }
    public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
}
