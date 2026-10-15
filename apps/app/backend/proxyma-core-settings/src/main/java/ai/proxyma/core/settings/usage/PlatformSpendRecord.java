package ai.proxyma.core.settings.usage;

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
 * One LLM call the PLATFORM made, rather than one a user's chat turn caused.
 *
 * WHY THIS TABLE EXISTS. Six services already publish their spend and it is charged to the user
 * whose turn caused it - chat, sub-agents, query expansion, reranking, title generation, memory
 * condensation. Three did not publish anything at all, and they are the ones with no user behind
 * them: embedding, OCR, and the memory digest. Embedding in particular is called once per chunk at
 * ingest and once per query at search time, which on any real corpus makes it the largest single
 * LLM cost in the deployment - and until now it did not appear in any total anywhere.
 *
 * Kept separate from the per-user ledger rather than folded into it with a null user id. The two
 * are asked different questions: "what does this user owe" reads only the user ledger, while "what
 * is this deployment spending" reads both, and a nullable-user column invites one of those queries
 * to silently answer the other. It also gives platform spend its own `service` dimension, which is
 * what the Usages page needs to break cost down by embedding vs OCR vs digest.
 */
@Entity
@Table(name = "platform_spend", indexes = {
        @Index(name = "idx_platform_spend_recorded_at", columnList = "recordedAt"),
        @Index(name = "idx_platform_spend_service", columnList = "service"),
})
public class PlatformSpendRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Which platform activity spent this. Stored as a STRING, not an ordinal: rows outlive
     * releases, and an ordinal silently re-points every historical row the day someone reorders
     * the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlatformService service;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    /**
     * Priced at the moment of the call, not derived on read. Model pricing is editable in Settings,
     * so recomputing a two-month-old row against today's rates would silently rewrite history -
     * and a global quota that moves when an administrator edits a price is not a quota.
     */
    @Column(nullable = false)
    private double costUsd;

    @Column(nullable = false)
    private Instant recordedAt;

    protected PlatformSpendRecord() {
        // JPA
    }

    public PlatformSpendRecord(PlatformService service, String provider, String model,
                               long inputTokens, long outputTokens, double costUsd, Instant recordedAt) {
        this.service = service;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public PlatformService getService() { return service; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public double getCostUsd() { return costUsd; }
    public Instant getRecordedAt() { return recordedAt; }
}
