package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * ONE ROW PER MODEL CALL: when, what for, whose, which provider and model, how many tokens, what it
 * cost.
 *
 * <p><b>This reverses what {@link AttributedSpendMonth} argues for, deliberately and at the
 * maintainer's instruction on 2026-09-16.</b> That class says a rollup is enough because "nothing
 * anybody asks of this data needs per-call granularity". Two things turned out to need it. An
 * operator asked to see cost by provider, by model and by service with every call listed - a
 * rollup cannot answer "what did that spike at 14:51 consist of". And the rollups themselves could
 * not be checked: when a ledger silently recorded $0 for months (see EmbeddingModelConfig's
 * provider-id bug) there was no row anywhere to compare against.
 *
 * <p>The volume objection was real and is answered rather than ignored:
 * <ul>
 *   <li><b>Retention.</b> {@code RetentionSettings.usageDays} (365 by default, 0 for forever) is
 *       swept daily - the same setting and sweep {@code platform_spend} has always had.</li>
 *   <li><b>Embedding is per BATCH, not per chunk.</b> The gateway batches by token count
 *       (TokenCountBatchingStrategy), so a 100,000-chunk corpus is thousands of rows, not
 *       100,000.</li>
 *   <li><b>Nothing reads it on a hot path</b> - the spending limit's gate included, which totals it
 *       at most once every few seconds and not at all while no limit is set (GlobalQuotaGate).
 *       The rollups still answer the dashboards; this table is read when somebody opens the
 *       detail view and asks for a page of it.</li>
 * </ul>
 *
 * <p>THE ROLLUPS STAY. This is not a replacement: {@code attributed_spend_month} answers "the month
 * so far" in one indexed row per combination, and would have to be recomputed from here otherwise.
 * Two tables written from ONE place ({@code DefaultLlmGateway.recordSpend}) cannot disagree about
 * what happened, which is the property the four pre-existing ledgers never had.
 *
 * <p>A row is written even when the provider reported NO tokens - a rerank endpoint answering with
 * scores alone, say. Zero tokens and zero cost against a real timestamp is the honest record of a
 * call that happened; the rollup keeps skipping those so its averages stay meaningful.
 */
@Entity
@Table(name = "llm_calls", indexes = {
        // Every read of this table is "most recent first, within a window", optionally narrowed.
        @Index(name = "idx_llm_call_at", columnList = "recordedAt"),
        @Index(name = "idx_llm_call_subject", columnList = "subjectKind,subjectId,recordedAt"),
        @Index(name = "idx_llm_call_role", columnList = "role,recordedAt"),
        @Index(name = "idx_llm_call_model", columnList = "provider,model,recordedAt"),
})
public class LlmCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant recordedAt;

    /**
     * Stored as a STRING, never an ordinal - rows outlive releases, and an ordinal re-points every
     * historical row the day somebody reorders the enum. Same reasoning as AttributedSpendMonth.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SpendSubject.Kind subjectKind;

    /** The user id, the guest id, or the system marker - whatever the subject kind means. */
    @Column(nullable = false, length = 128)
    private String subjectId;

    /** The LlmRole name: CHAT, EMBEDDING, RERANK, GRAPH, OCR, GUARDRAIL and the rest. The SERVICE
     *  an operator groups by, kept as a string for the same reason as the kind above. */
    @Column(nullable = false, length = 32)
    private String role;

    /**
     * The provider's DISPLAY NAME, never its instance id. Pricing is keyed on the name, so a row
     * carrying an id is a row priced at $0 - which is exactly how two other ledgers came to report
     * nothing for the deployment's largest line item.
     */
    @Column(nullable = false, length = 128)
    private String provider;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private long cacheReadTokens;

    @Column(nullable = false)
    private long cacheWriteTokens;

    /** Priced at write time, from the rates in force then. A rate edited later must not silently
     *  rewrite what last month is reported to have cost. */
    @Column(nullable = false)
    private double costUsd;

    public LlmCall() {
        this.recordedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    public SpendSubject.Kind getSubjectKind() { return subjectKind; }
    public void setSubjectKind(SpendSubject.Kind subjectKind) { this.subjectKind = subjectKind; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

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
}
