package ai.proxyma.core.evaluation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Append-only per-search-call stage-latency row for System > Performance (see
// ai.proxyma.core.search.event.SearchStageLatencyRecordedEvent, published by SearchService for
// every search, and EvaluationMetricsListener, which persists it here). Never updated after
// insert - mirrors ChatSpendEvent's shape (proxyma-core-auth). Covers both standalone
// /api/v1/search requests and chat-driven searches, since both paths call the same
// SearchService.search(). Stores queryLength only, never the raw query text - the metrics are
// anonymous by construction, with no exception for any admin role.
@Entity
@Table(name = "eval_search_stage_metrics", indexes = {
        @Index(name = "idx_eval_search_stage_recorded_at", columnList = "recordedAt")
})
public class SearchStageMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private int queryLength;

    @Column(nullable = false)
    private int variantCount;

    @Column(nullable = false)
    private long expansionMs;

    @Column(nullable = false)
    private long denseMs;

    @Column(nullable = false)
    private long bm25Ms;

    @Column(nullable = false)
    private long rerankMs;

    @Column(nullable = false)
    private long totalMs;

    @Column(nullable = false)
    private int resultCount;

    // Whether the knowledge-graph channel ran, and its milliseconds. Nullable: added to a table
    // that already has rows, and null is the honest value for those ("before the channel existed").
    @Column
    private Boolean graphChannel;

    @Column
    private Long graphMs;

    @Column(nullable = false)
    private Instant recordedAt;

    protected SearchStageMetric() {
        // no-arg ctor for JPA
    }

    public SearchStageMetric(int queryLength, int variantCount, long expansionMs, long denseMs,
                             long bm25Ms, long rerankMs, long totalMs, int resultCount, Instant recordedAt) {
        this.queryLength = queryLength;
        this.variantCount = variantCount;
        this.expansionMs = expansionMs;
        this.denseMs = denseMs;
        this.bm25Ms = bm25Ms;
        this.rerankMs = rerankMs;
        this.totalMs = totalMs;
        this.resultCount = resultCount;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public int getQueryLength() { return queryLength; }
    public int getVariantCount() { return variantCount; }
    public long getExpansionMs() { return expansionMs; }
    public long getDenseMs() { return denseMs; }
    public long getBm25Ms() { return bm25Ms; }
    public long getRerankMs() { return rerankMs; }
    public long getTotalMs() { return totalMs; }
    public int getResultCount() { return resultCount; }
    public Instant getRecordedAt() { return recordedAt; }
    public Boolean getGraphChannel() { return graphChannel; }
    public Long getGraphMs() { return graphMs; }

    public SearchStageMetric withGraph(boolean graphChannel, long graphMs) {
        this.graphChannel = graphChannel;
        this.graphMs = graphMs;
        return this;
    }
}
