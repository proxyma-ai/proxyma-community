package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened in an agent tree, when it happened - the record a timeline and its replay
 * are drawn from (AgentEvents). An agent deployed, its status changing, a report or a message between
 * two agents, a write waiting for a person and its decision, a budget or deadline stopping a subtree, a
 * verifier's scorecard. Kept beside the tree, deleted with its root conversation.
 *
 * <p>{@code detail} is a short line for a person, never what an agent was asked or answered in full:
 * the transcript is the conversation's own, and its owner's.
 */
@Entity
@Table(name = "agent_events", indexes = {
        @Index(name = "idx_agent_events_root", columnList = "rootConversationId, at"),
        @Index(name = "idx_agent_events_conversation", columnList = "conversationId"),
})
public class AgentEvent {

    public static final String DEPLOYED = "DEPLOYED";
    public static final String STATUS = "STATUS";
    public static final String REPORT = "REPORT";
    public static final String MESSAGE = "MESSAGE";
    public static final String USER_TALK = "USER_TALK";
    public static final String APPROVAL_ASKED = "APPROVAL_ASKED";
    public static final String APPROVAL_DECIDED = "APPROVAL_DECIDED";
    public static final String LIMIT = "LIMIT";
    public static final String FAN_OUT = "FAN_OUT";
    public static final String VERIFIED = "VERIFIED";
    public static final String DELIBERATION = "DELIBERATION";
    public static final String FILE = "FILE";

    public static final int DETAIL_MAX = 300;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String rootConversationId;

    /** The conversation of the agent it is about - the root's own for the agent the user opened. */
    @Column(nullable = false, length = 64)
    private String conversationId;

    /** The agent it is about, or null for the root. */
    private UUID agentId;

    /** For a report or a message: the agent it went to, or null for the root. */
    private UUID toAgentId;

    @Column(nullable = false, length = 24)
    private String kind;

    /** For STATUS: the status it moved to. */
    @Column(length = 16)
    private String status;

    @Column(length = DETAIL_MAX)
    private String detail;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public UUID getToAgentId() { return toAgentId; }
    public void setToAgentId(UUID toAgentId) { this.toAgentId = toAgentId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
