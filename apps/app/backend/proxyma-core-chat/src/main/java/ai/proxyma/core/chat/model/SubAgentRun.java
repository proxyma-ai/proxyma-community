package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One sub-agent: who started it, how it ended, what it did and what it reported.
 *
 * <p>WHY ITS OWN TABLE, when a delegation is a tool call and tool calls live on
 * {@code ChatMessage}. Three things a tool-call row cannot hold. A background sub-agent outlives
 * the call that started it - the call returns "running" at once and the report arrives turns
 * later - so the record has to exist apart from the call's own result. Its steps are shown to the
 * person and never to the model, so they do not belong in the rows the model's memory is built
 * from. And whether its report has reached the main agent yet has to survive a restart, or a
 * report that finished while nobody was looking is lost.
 *
 * <p>The same two costs {@link WorkflowRun} names come with a separate table, and are handled the
 * same way: the rows go when the conversation does (SubAgentRunRepository.deleteByConversationId),
 * and a row still RUNNING at startup is settled then (SubAgentService.settleInterrupted).
 *
 * <p>{@code status} and {@code mode} are STRINGS, not {@code @Enumerated} columns, on purpose. A
 * constant added to a persisted enum is refused by every table created before it existed (see
 * docs/architecture.html#enum-columns); a string column has no list to fall behind.
 */
@Entity
@Table(name = "sub_agent_runs", indexes = {
        @Index(name = "idx_sub_agent_runs_conversation", columnList = "conversationId"),
        @Index(name = "idx_sub_agent_runs_status", columnList = "status"),
})
public class SubAgentRun implements Persistable<String> {

    /** The agent's id: minted when it starts, carried by the delegate call's result and by every
     *  event it publishes. ASSIGNED, hence Persistable - see WorkflowRun.isNew for what happens
     *  to an assigned id without it. */
    @Id
    @Column(length = 64)
    private String id;

    @Transient
    private boolean newRow = true;

    @Column(nullable = false)
    private String conversationId;

    /** The `delegate` tool call that started it. Not unique on its own: some providers number
     *  their calls and reuse the number on the next round. */
    @Column(length = 200)
    private String callId;

    @Column(length = 500)
    private String title;

    /** SYNC or ASYNC. */
    @Column(nullable = false, length = 16)
    private String mode;

    /** RUNNING, then COMPLETED, PARTIAL, FAILED or STOPPED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    @Column
    private Instant endedAt;

    // TEXT, NEVER @Lob - as ChatMessage's columns are. On PostgreSQL Hibernate maps a @Lob String to
    // an `oid` large object, which can be read only inside the transaction that fetched it, and
    // every reader of this row runs outside one: the report handed to the main agent (takeReports),
    // the agents graph (usage) and a key account's run log. On Enterprise Server each of them threw
    // "Unable to access lob stream", so a background sub-agent's report never reached its agent,
    // and the turn's finally woke the conversation again to deliver it - a loop that logged a stack
    // trace several times a second for as long as the server ran (found 2026-10-06, driving a key
    // account's "Run the review"). SubAgentRunColumnsTest pins it.

    /** What it reported - the text the main agent is given. */
    @Column(columnDefinition = "TEXT")
    private String report;

    /** What it did, in order - tool calls and the text between them - as JSON. For the person
     *  reading the thread; no model is ever sent this. */
    @Column(columnDefinition = "TEXT")
    private String itemsJson;

    /**
     * Whether the main agent has been given the report. Always true for a SYNC run, whose report
     * is its call's result. For an ASYNC one it turns true when the report is put in front of the
     * main agent - and until then the report is still owed, across a restart included.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean delivered;

    // WHAT IT COST, since 2026-10-03 - for the conversation's agents graph (maintainer's request).
    // The same figures already reach the conversation's totals and the user's quota
    // (SubAgentService.recordUsage); kept here as well because those are sums, and the graph has to
    // say which agent spent what. The main agent's share is the conversation's total less these.
    // Defaulted to 0, so a row written before them reads as having cost nothing it can account for.

    /** The provider's display name and the model of its last call; null until it has made one. */
    @Column(length = 128)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long inputTokens;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long outputTokens;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long cacheReadTokens;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long cacheWriteTokens;

    @Column(nullable = false)
    @ColumnDefault("0")
    private double costUsd;

    // How full its context was: the prompt of its last call, and the window it ran against - the
    // agents graph's "context" (2026-10-04), the sub-agent's counterpart of the main agent's meter.
    @Column(nullable = false)
    @ColumnDefault("0")
    private long contextTokens;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long contextWindow;

    protected SubAgentRun() {}

    public SubAgentRun(String id, String conversationId, String callId, String title, String mode) {
        this.id = id;
        this.conversationId = conversationId;
        this.callId = callId;
        this.title = title;
        this.mode = mode;
        this.status = "RUNNING";
    }

    @Override
    public String getId() { return id; }

    @Override
    public boolean isNew() { return newRow; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.newRow = false; }

    public String getConversationId() { return conversationId; }
    public String getCallId() { return callId; }
    public String getTitle() { return title; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
    public double getCostUsd() { return costUsd; }
    public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
    public long getContextTokens() { return contextTokens; }
    public void setContextTokens(long contextTokens) { this.contextTokens = contextTokens; }
    public long getContextWindow() { return contextWindow; }
    public void setContextWindow(long contextWindow) { this.contextWindow = contextWindow; }
}
