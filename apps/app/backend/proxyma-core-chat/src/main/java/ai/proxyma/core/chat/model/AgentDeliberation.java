package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A question put to a panel of agents (AgentDeliberations): each panelist argues and votes, sees the
 * others' arguments in the next round and may change its vote, and the agent that asked decides - its
 * decision, its reason and every panelist who voted otherwise kept on record.
 *
 * <p>{@code options} and {@code panel} are JSON arrays; {@code rounds} holds every round's arguments
 * and votes, as JSON ({@code [[{panelist, vote, argument}]]}).
 */
@Entity
@Table(name = "agent_deliberations", indexes = {
        @Index(name = "idx_agent_deliberations_root", columnList = "rootConversationId"),
})
public class AgentDeliberation {

    public static final String RUNNING = "RUNNING";
    /** Every round is in; the agent that asked has the tally and decides. */
    public static final String AWAITING_DECISION = "AWAITING_DECISION";
    public static final String DECIDED = "DECIDED";
    public static final String STOPPED = "STOPPED";

    public static final int QUESTION_MAX = 4_000;
    public static final int ROUNDS_MAX = 3;
    public static final int PANEL_MAX = 7;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String parentConversationId;

    @Column(nullable = false, length = 64)
    private String rootConversationId;

    /** The person the tree works for. */
    @Column(length = 128)
    private String userId;

    /** The panelists' model, when one was asked for. */
    @Column(length = 200)
    private String model;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(columnDefinition = "text")
    private String options;

    @Column(nullable = false, columnDefinition = "text")
    private String panel;

    @Column(nullable = false)
    private int roundsPlanned;

    @Column(nullable = false)
    private int round;

    @Column(columnDefinition = "text")
    private String rounds;

    @Column(nullable = false, length = 24)
    private String status = RUNNING;

    @Column(length = 500)
    private String decision;

    @Column(columnDefinition = "text")
    private String rationale;

    /** The panelists whose final vote was not the decision, as JSON [{panelist, vote, argument}]. */
    @Column(columnDefinition = "text")
    private String dissent;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(String parentConversationId) { this.parentConversationId = parentConversationId; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public String getPanel() { return panel; }
    public void setPanel(String panel) { this.panel = panel; }
    public int getRoundsPlanned() { return roundsPlanned; }
    public void setRoundsPlanned(int roundsPlanned) { this.roundsPlanned = roundsPlanned; }
    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public String getRounds() { return rounds; }
    public void setRounds(String rounds) { this.rounds = rounds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String getDissent() { return dissent; }
    public void setDissent(String dissent) { this.dissent = dissent; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
