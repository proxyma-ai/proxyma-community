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

// Append-only per-turn latency/outcome row for System > Performance (see
// ai.proxyma.core.chat.event.ChatTurnLatencyRecordedEvent, published by ChatService for every
// turn, and EvaluationMetricsListener, which persists it here). Never updated after insert -
// mirrors ChatSpendEvent's shape (proxyma-core-auth). Deliberately has no column capable of
// holding a user or conversation identity - the metrics are anonymous by construction, with no
// exception for any admin role.
@Entity
@Table(name = "eval_chat_turn_metrics", indexes = {
        @Index(name = "idx_eval_chat_turn_recorded_at", columnList = "recordedAt")
})
public class ChatTurnMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private long timeToFirstTokenMs;

    @Column(nullable = false)
    private long totalDurationMs;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private long outputTokenCount;

    @Column(nullable = false)
    private Instant recordedAt;

    protected ChatTurnMetric() {
        // no-arg ctor for JPA
    }

    public ChatTurnMetric(String provider, String model,
                          long timeToFirstTokenMs, long totalDurationMs, int retryCount,
                          boolean success, long outputTokenCount, Instant recordedAt) {
        this.provider = provider;
        this.model = model;
        this.timeToFirstTokenMs = timeToFirstTokenMs;
        this.totalDurationMs = totalDurationMs;
        this.retryCount = retryCount;
        this.success = success;
        this.outputTokenCount = outputTokenCount;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getTimeToFirstTokenMs() { return timeToFirstTokenMs; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public int getRetryCount() { return retryCount; }
    public boolean isSuccess() { return success; }
    public long getOutputTokenCount() { return outputTokenCount; }
    public Instant getRecordedAt() { return recordedAt; }
}
