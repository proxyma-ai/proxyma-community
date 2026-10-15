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

import java.util.UUID;

/**
 * Every model call the deployment makes, rolled up per month by WHO it was for and WHAT it was for.
 *
 * <p>THE TWO DIMENSIONS ON ONE ROW ARE THE POINT. Attribution used to be decided at WRITE time: a
 * call went either to the per-user ledger or to platform spend, and whoever wrote the call site
 * made that choice once, in code, forever. That is also an include/exclude policy - "is embedding
 * part of a user's cost" was answered by which table it landed in - and it could only be changed by
 * editing code and losing the history. With subject and role on the same row the question moves to
 * read time, where it belongs: a user's cost excluding ingest is a WHERE clause.
 *
 * <p>A ROLLUP, not a ledger, for the reason ChatSpendMonth already gives: one row per call is
 * unbounded in the highest-volume path in the product (embedding, once per chunk), and nothing
 * anybody asks of this data needs per-call granularity. The rolling quota windows DO need it, and
 * they have their own 8-day table - {@code chat_spend_events} - which this does not replace.
 * Enforcement and accounting are different jobs with different retention.
 *
 * <p>WHY NOT A COLUMN ON chat_spend_month. Its unique index is (yearMonth, provider, model), and
 * Hibernate's {@code ddl-auto=update} creates missing indexes but never alters existing ones - so
 * adding a subject to the key would leave the old three-column constraint in place, rejecting the
 * second row for any month/model. ChatSpendService swallows its own failures by design, so that
 * would have shown up as guest spend silently ceasing to be recorded. A new table needs no index
 * migration and cannot change what an existing view means.
 */
@Entity
@Table(name = "attributed_spend_month", indexes = {
        @Index(name = "idx_attr_spend_month", columnList = "yearMonth"),
        @Index(name = "idx_attr_spend_subject", columnList = "yearMonth,subjectKind,subjectId"),
        @Index(name = "idx_attr_spend_role", columnList = "yearMonth,role"),
        @Index(name = "idx_attr_spend_key",
                columnList = "yearMonth,subjectKind,subjectId,role,provider,model", unique = true),
})
public class AttributedSpendMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** "YYYY-MM" in the server's own zone, resolved at write time - see ChatSpendMonth. */
    @Column(nullable = false, length = 7)
    private String yearMonth;

    /**
     * Stored as a STRING, not an ordinal: rows outlive releases, and an ordinal silently re-points
     * every historical row the day someone reorders the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SpendSubject.Kind subjectKind;

    /** A user id, or the one shared id every anonymous visitor shares. Never null. */
    @Column(nullable = false, length = 64)
    private String subjectId;

    /**
     * What the call was for - the LlmRole, as a string for the same reason as subjectKind.
     *
     * <p>Not the enum type itself: LlmRole lives in proxyma-core-provider, which depends on this
     * module rather than the other way round. The string IS the contract, and it is written from
     * one place.
     */
    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private long cacheReadTokens;

    @Column(nullable = false)
    private long cacheWriteTokens;

    /**
     * Priced at the moment of the call, never derived on read. Rates are editable, so recomputing a
     * two-month-old row against today's rates would silently rewrite history.
     */
    @Column(nullable = false)
    private double costUsd;

    @Column(nullable = false)
    private long callCount;

    protected AttributedSpendMonth() {
        // for JPA
    }

    public AttributedSpendMonth(String yearMonth, SpendSubject subject, String role,
                                String provider, String model) {
        this.yearMonth = yearMonth;
        this.subjectKind = subject.kind();
        this.subjectId = subject.id();
        this.role = role;
        this.provider = provider;
        this.model = model;
    }

    public void add(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                    double costUsd) {
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
        this.cacheReadTokens += cacheReadTokens;
        this.cacheWriteTokens += cacheWriteTokens;
        this.costUsd += costUsd;
        this.callCount++;
    }

    public UUID getId() { return id; }
    public String getYearMonth() { return yearMonth; }
    public SpendSubject.Kind getSubjectKind() { return subjectKind; }
    public String getSubjectId() { return subjectId; }
    public String getRole() { return role; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public double getCostUsd() { return costUsd; }
    public long getCallCount() { return callCount; }
}
