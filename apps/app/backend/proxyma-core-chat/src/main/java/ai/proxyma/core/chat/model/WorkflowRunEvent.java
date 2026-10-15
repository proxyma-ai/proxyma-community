package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a run's execution log. Append-only.
 *
 * <p>WHY A TABLE AND NOT A COLUMN ON THE RUN. The markdown runner keeps its whole state in one
 * {@code stepsJson} blob and republishes the entire thing on every transition. That is right for a
 * plan of at most twenty rows whose statuses change - a refresh mid-run redraws the true state,
 * which is the property that feature got right. It is wrong for a log: a graph with loops emits an
 * unbounded number of events, and resending all of them on every one is quadratic.
 *
 * <p>So a run's stream carries two things, and this is the second: the run row holds the small
 * whole snapshot (node statuses, resent complete), and these rows are appended with a {@code seq}
 * the client holds as a cursor - {@code ?after=} on reconnect instead of replaying everything.
 *
 * <p>It is also the substrate resume would need. Every node's output is here, so rebuilding a run
 * scope from the log is possible later; that is not a promise this build keeps, but it is the
 * reason outputs are persisted rather than only streamed.
 *
 * <p>{@code @GeneratedValue} here and NOT on {@link WorkflowGraphRun}, deliberately: nothing needs
 * this id before the insert, so letting the database mint it is safe. See the run's own javadoc for
 * what happens when an assigned id meets a generated-value declaration.
 */
@Entity
@Table(name = "workflow_run_events", indexes = {
        @Index(name = "idx_run_events_run_seq", columnList = "runId,seq"),
})
public class WorkflowRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID runId;

    /** Per-run, monotonic, and the client's cursor. Not the primary key: a database-assigned id is
     *  unique but says nothing about order within one run. */
    @Column(nullable = false)
    private long seq;

    @Column(nullable = false)
    private Instant at;

    /** Null for a whole-run event (PAUSED, RESUMED). */
    @Column
    private String nodeId;

    /** Which visit of that node - a node inside a loop produces one row per iteration, and a log
     *  that collapsed them would make a loop unreadable. */
    @Column(nullable = false)
    private int iteration;

    @Column(nullable = false, length = 32)
    private String kind;

    /** Human-readable, already scrubbed of absolute paths - see SensitivePaths.scrub. This is what
     *  the Log view shows. */
    @Column(length = 4000)
    private String detail;

    /** The node's output or a tool's arguments, as JSON, for the Values view. Also scrubbed. */
    // TEXT, never @Lob: on PostgreSQL a @Lob String is an `oid` large object, readable only inside
    // the transaction that fetched it - the class of bug SubAgentRun's report hit (2026-10-06).
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private double costUsd;

    @Column
    private Long millis;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public double getCostUsd() { return costUsd; }
    public void setCostUsd(double costUsd) { this.costUsd = costUsd; }

    public Long getMillis() { return millis; }
    public void setMillis(Long millis) { this.millis = millis; }
}
