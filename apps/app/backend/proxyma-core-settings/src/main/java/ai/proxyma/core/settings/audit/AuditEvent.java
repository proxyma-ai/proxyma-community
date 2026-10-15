package ai.proxyma.core.settings.audit;

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
 * ONE ROW PER THING WORTH AUDITING: a tool call, a guardrail check, a diagnostics send, a sign-in,
 * a setting saved, a source synced. What the Audit Logs page reads, beside {@code llm_calls}.
 *
 * <p>Written only by {@link AuditWriter}, in batches, from the queue every module feeds through
 * {@code ai.proxyma.core.common.audit.Audit}. Never updated. Removed by {@link AuditRetentionSweep}
 * after {@code retention.audit-days}, and on Desktop and Home Server by the page's Clear.
 *
 * <p>EVERY CLASSIFIER IS A STRING, never an enum - category, action, outcome and the actor's kind.
 * Rows outlive releases, and a persisted enum refuses a constant added after its table was created
 * (docs/architecture.html#enum-columns).
 */
@Entity
@Table(name = "audit_events", indexes = {
        // Every read is "newest first, within a window, of one tab", optionally narrowed.
        @Index(name = "idx_audit_at", columnList = "occurredAt"),
        @Index(name = "idx_audit_category", columnList = "category,occurredAt"),
        @Index(name = "idx_audit_actor", columnList = "actorId,occurredAt"),
        @Index(name = "idx_audit_conversation", columnList = "conversationId"),
})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 24)
    private String category;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 16)
    private String outcome;

    /** USER, ANONYMOUS or SYSTEM - SpendSubject.Kind's names, as a string. */
    @Column(nullable = false, length = 16)
    private String actorKind;

    @Column(nullable = false, length = 128)
    private String actorId;

    /** Captured when the row was written: the account may be renamed or deleted later. */
    @Column(length = 160)
    private String actorName;

    @Column(length = 255)
    private String target;

    @Column(length = 64)
    private String conversationId;

    @Column(length = 500)
    private String summary;

    private Long durationMs;

    // TEXT, NEVER @Lob - on PostgreSQL Hibernate maps a @Lob String to an oid large object, which
    // cannot be read outside a transaction (SubAgentRunColumnsTest).
    @Column(columnDefinition = "TEXT")
    private String detail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getActorKind() { return actorKind; }
    public void setActorKind(String actorKind) { this.actorKind = actorKind; }

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }

    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
