package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One piece of work done over a list (AgentFanOuts): an agent per item, deployed as slots free up
 * rather than refused past the caps, each reporting to the fan-out, and one report back to the agent
 * that started it when the last item is in. A deliberation's round is one too.
 *
 * <p>{@code items} is a JSON array of {title, task}, each rendered when the fan-out was started.
 */
@Entity
@Table(name = "agent_fan_outs", indexes = {
        @Index(name = "idx_agent_fan_outs_parent", columnList = "parentConversationId"),
        @Index(name = "idx_agent_fan_outs_root", columnList = "rootConversationId"),
})
public class AgentFanOut {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String STOPPED = "STOPPED";

    public static final int ITEMS_MAX = 200;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String parentConversationId;

    @Column(nullable = false, length = 64)
    private String rootConversationId;

    @Column(length = 128)
    private String userId;

    @Column(nullable = false, length = AgentNode.TITLE_MAX)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String items;

    @Column(nullable = false)
    private int total;

    /** The next item to deploy. */
    @Column(nullable = false)
    private int nextIndex;

    @Column(nullable = false)
    private int maxParallel;

    @Column(nullable = false, length = 16)
    private String status = RUNNING;

    /** Each item agent's own budget and time, when given. */
    private Double budgetUsdEach;

    private Integer minutesEach;

    @Column(length = 64)
    private String providerId;

    @Column(length = 200)
    private String model;

    /** Whether each item's report is checked by a verifier. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verify;

    /** The deliberation this is a round of, or null. */
    private UUID deliberationId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant endedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(String parentConversationId) { this.parentConversationId = parentConversationId; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getNextIndex() { return nextIndex; }
    public void setNextIndex(int nextIndex) { this.nextIndex = nextIndex; }
    public int getMaxParallel() { return maxParallel; }
    public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getBudgetUsdEach() { return budgetUsdEach; }
    public void setBudgetUsdEach(Double budgetUsdEach) { this.budgetUsdEach = budgetUsdEach; }
    public Integer getMinutesEach() { return minutesEach; }
    public void setMinutesEach(Integer minutesEach) { this.minutesEach = minutesEach; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isVerify() { return verify; }
    public void setVerify(boolean verify) { this.verify = verify; }
    public UUID getDeliberationId() { return deliberationId; }
    public void setDeliberationId(UUID deliberationId) { this.deliberationId = deliberationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
