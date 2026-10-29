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
 * An alarm an agent set to wake itself: at {@code dueAt} its conversation is handed the note
 * (AgentInbox, kind ALARM), and a turn starts if none is running. One-shot, kept across a restart,
 * and listed in the conversation so a person can cancel it.
 */
@Entity
@Table(name = "agent_alarms", indexes = {
        @Index(name = "idx_agent_alarms_due", columnList = "firedAt, canceledAt, dueAt"),
        @Index(name = "idx_agent_alarms_conversation", columnList = "conversationId"),
})
public class AgentAlarm {

    public static final int NOTE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false, length = NOTE_MAX)
    private String note;

    @Column(nullable = false)
    private Instant dueAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant firedAt;

    private Instant canceledAt;

    public UUID getId() { return id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFiredAt() { return firedAt; }
    public void setFiredAt(Instant firedAt) { this.firedAt = firedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public void setCanceledAt(Instant canceledAt) { this.canceledAt = canceledAt; }

    public boolean isPending() {
        return firedAt == null && canceledAt == null;
    }
}
