package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One invocation of a workflow, and the live state of each of its steps.
 *
 * <p>WHY ITS OWN TABLE. Tool calls hang off {@code ChatMessage.toolCallsJson}, which is the
 * obvious home for this and does not work: at the moment step 1 starts there is no assistant row
 * yet - it is written after the model responds - so a refresh during the first step would find
 * nothing and the step table would vanish exactly when the reader most wants it. A run row is
 * written the instant the run starts.
 *
 * <p>New table rather than a column on an existing one, which is the safe kind of schema change
 * here: new tables are fine under {@code ddl-auto=update}, and it is ALTERING existing ones that
 * has already half-applied twice (see SECURITY_AUDITS.md / LESSONS_LEARNED.md).
 *
 * <p>Two costs come with the separation and are handled rather than discovered:
 * <ul>
 *   <li><b>Deletion.</b> A conversation's runs must go when the conversation does - free if this
 *       hung off ChatMessage, deliberate work here. See WorkflowRunRepository.deleteByConversationId
 *       and its caller.
 *   <li><b>Restart.</b> A run interrupted by a restart is not running any more, but its row still
 *       says RUNNING. Anything reading run state has to treat that as failed or the table shows a
 *       spinner that never resolves - the same class of bug as a tool-call row stuck in progress,
 *       and invisible in testing because nothing restarts mid-run there. See
 *       WorkflowRunReconciler.
 * </ul>
 */
@Entity
@Table(name = "workflow_runs", indexes = {
        @Index(name = "idx_workflow_runs_conversation", columnList = "conversationId"),
        @Index(name = "idx_workflow_runs_status", columnList = "status"),
})
public class WorkflowRun implements Persistable<UUID> {

    /**
     * Also the SSE event's {@code callId}, and the {@code parentCallId} each step hangs off.
     *
     * <p>ASSIGNED, NOT GENERATED. Each step's call id is derived from it ({@code {runId}:{index}})
     * and is written into the same row, so the id has to be known BEFORE the row is built - there
     * is nothing to derive them from if the value only comes back from the insert. See
     * WorkflowRunService.start().
     */
    @Id
    private UUID id;

    /**
     * Whether this instance is a row that does not exist yet. Persistable's whole purpose, and it
     * is load-bearing here rather than ceremony.
     *
     * <p>{@code JpaRepository.save()} chooses persist-vs-merge by asking {@link #isNew()}, and the
     * default answer for an entity carrying an assigned id is "not new". So {@code save()} merged,
     * Hibernate issued an UPDATE against a row that had never been inserted, and every run died in
     * {@code start()} with "Row was updated or deleted by another transaction (or unsaved-value
     * mapping was incorrect)".
     *
     * <p>Worth knowing how that survived: it fails BEFORE step 1, so it needed no provider to
     * reproduce - and equally none to miss, because the feature was only ever exercised through
     * its CRUD endpoints and by looking at the UI. Every run since the feature shipped failed this
     * way. The declared id strategy was the only witness.
     */
    @Transient
    private boolean newRow = true;

    @Column(nullable = false)
    private String conversationId;

    /** The workflow's file id. Kept for provenance; the run does not re-read the definition, so
     *  editing or deleting a workflow mid-run changes nothing about the run in flight. */
    @Column(nullable = false)
    private String workflowId;

    @Column(nullable = false)
    private String workflowName;

    /**
     * The id of the USER message that invoked this run - the row the step table is drawn directly
     * below once the conversation is reloaded from the server.
     *
     * <p>The anchor is the USER message rather than the assistant reply because the user message is
     * the only one guaranteed to exist: it is written before step 1 starts, whereas a run that
     * fails in its first step may never produce an assistant row at all, and the table has to
     * survive exactly that case. Nullable for the same reason nothing else here is mandatory - a
     * run whose anchor was rewound away renders in the live stream and simply does not reappear
     * on reload, which is better than a table pinned to a message that no longer exists.
     */
    @Column
    private Long triggerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /**
     * The steps and their per-step state, as JSON.
     *
     * A child table would be the textbook answer and buys nothing here: steps are read and written
     * only as a whole run, never queried across runs, and the snapshot that goes on the wire is
     * this exact list. One row per run keeps a transition to a single UPDATE.
     */
    // TEXT, never @Lob: on PostgreSQL a @Lob String is an `oid` large object, readable only inside
    // the transaction that fetched it - the class of bug SubAgentRun's report hit (2026-10-06).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String stepsJson;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    @Column
    private Instant finishedAt;

    /** Why the run failed, when it did - the step's own failure message, promoted so the run's
     *  summary line can say what went wrong without the reader expanding a row. */
    @Column(length = 2000)
    private String failureMessage;

    public enum Status {
        /** At least one step is still to run. The only status a restart can leave stale. */
        RUNNING,
        COMPLETED,
        /** A step failed, or the user stopped the run. Later steps are SKIPPED, never FAILED -
         *  claiming a step failed when it was never attempted is the same class of lie as
         *  narrating a tool call in the past tense when it was refused. */
        FAILED,
    }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    public boolean isNew() { return newRow; }

    // Anything that came back from the database is a row that exists, however it got here - loaded
    // by findById for a step transition, or by the reconciler's sweep at startup. Both callbacks,
    // not just @PostLoad: the instance start() inserted stays in the persistence context and is
    // saved again by the very next transition.
    @PostLoad
    @PostPersist
    void markNotNew() { this.newRow = false; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public Long getTriggerMessageId() { return triggerMessageId; }
    public void setTriggerMessageId(Long triggerMessageId) { this.triggerMessageId = triggerMessageId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
