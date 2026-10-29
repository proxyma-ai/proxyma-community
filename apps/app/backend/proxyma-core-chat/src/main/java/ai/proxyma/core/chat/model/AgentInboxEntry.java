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
 * Something waiting for a conversation's agent that no person typed: a child's report, a message
 * from its parent, a child or a peer, an alarm going off, a background job's result, or what the
 * user said to one of its children. Kept until the agent has read it - a running turn takes it at
 * its next step, and with none running one is started for it - so a restart loses none of it.
 */
@Entity
@Table(name = "agent_inbox", indexes = {
        @Index(name = "idx_agent_inbox_pending", columnList = "conversationId, deliveredAt"),
})
public class AgentInboxEntry {

    public static final int TEXT_MAX = 60_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String conversationId;

    /** REPORT, MESSAGE, ALARM, BACKGROUND or USER_TALK - see AgentInbox.Kind. */
    @Column(nullable = false, length = 16)
    private String kind;

    /** The agent it is from, when it is from one. */
    private UUID fromAgentId;

    /** Who or what it is from, as the agent reads it: an agent's title, an alarm's note, a tool. */
    @Column(length = 200)
    private String fromTitle;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant deliveredAt;

    public UUID getId() { return id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public UUID getFromAgentId() { return fromAgentId; }
    public void setFromAgentId(UUID fromAgentId) { this.fromAgentId = fromAgentId; }
    public String getFromTitle() { return fromTitle; }
    public void setFromTitle(String fromTitle) { this.fromTitle = fromTitle; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
