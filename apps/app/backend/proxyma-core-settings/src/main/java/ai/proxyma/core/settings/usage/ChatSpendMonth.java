package ai.proxyma.core.settings.usage;

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
 * What chat has spent on one model in one calendar month.
 *
 * WHY THIS TABLE EXISTS. Chat cost was already being computed - SmartChatMemory prices every turn
 * through ModelPricingService - but the only place it landed was Conversation.estimatedCostUsd, a
 * running total on a row that has no provider/model column at all. So the deployment could say what
 * a conversation cost and could not say what any MODEL cost, nor what any MONTH cost. PlatformSpend
 * next door has both dimensions but deliberately covers only the LLM calls with no user behind them
 * (embedding, OCR, memory digest) - see PlatformService - so it could not answer it either.
 *
 * A ROLLUP, NOT A LEDGER. One row per (month, provider, model), accumulated in place, rather than
 * one row per turn. Three things follow from that and all of them are the point:
 *
 *  - It is bounded by months x models, not by how much the user chats, so it never needs a retention
 *    sweep. That matters because the figure above it is "lifetime": a swept ledger would make
 *    lifetime quietly mean "since the sweep", which is a number that looks precise and is wrong.
 *    Conversation.estimatedCostUsd already has that problem - retention.conversation-days defaults
 *    to 7, so conversation-derived totals decay after a week.
 *  - Both questions the view asks are a single indexed read: this month is one month's rows, the
 *    breakdown IS those rows, and lifetime is every row summed.
 *  - It stores no conversation id, no user id and no text - only a month, a model name and counters.
 *    There is nothing in here to attribute back to a person, which is what lets desktop keep it
 *    without any of the privacy questions a per-turn history would raise.
 *
 * The month is a plain "YYYY-MM" string in the SERVER'S OWN zone, resolved once at write time. Not
 * an Instant range computed at read time: "this calendar month" is a wall-clock question, and a
 * boundary recomputed on every read drifts against the rows already written on the other side of it.
 * Deliberately not the quota window either - that one honours quota-reset-day and is a billing
 * cycle, which is a server concept. This is the calendar.
 *
 * Local models land here too, priced at 0 because no ModelPricing row exists for them (see
 * ModelPricingService.estimateCostUsd, which treats an unconfigured pair as free rather than as an
 * error). Their token counts are real, so the breakdown can show a local model doing genuine work at
 * no cost rather than omitting it and looking like it was never used.
 */
@Entity
@Table(name = "chat_spend_month", indexes = {
        @Index(name = "idx_chat_spend_month_month", columnList = "yearMonth"),
        @Index(name = "idx_chat_spend_month_key", columnList = "yearMonth,provider,model", unique = true),
})
public class ChatSpendMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** "YYYY-MM" in the server's own zone - see the class javadoc for why it is stored, not derived. */
    @Column(nullable = false, length = 7)
    private String yearMonth;

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
     * Accumulated at WRITE time, against whichever ModelPricing row matched at the moment of each
     * call. Re-pricing later would rewrite history every time a rate is edited, which is exactly
     * what a spend record must not do.
     */
    @Column(nullable = false)
    private double costUsd;

    /** How many priced calls rolled into this row - lets the view say "over N turns" honestly. */
    @Column(nullable = false)
    private long callCount;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatSpendMonth() {}

    public ChatSpendMonth(String yearMonth, String provider, String model) {
        this.yearMonth = yearMonth;
        this.provider = provider;
        this.model = model;
        this.updatedAt = Instant.now();
    }

    /** Folds one priced call onto this row. */
    public void add(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                    double costUsd) {
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
        this.cacheReadTokens += cacheReadTokens;
        this.cacheWriteTokens += cacheWriteTokens;
        this.costUsd += costUsd;
        this.callCount++;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getYearMonth() { return yearMonth; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public double getCostUsd() { return costUsd; }
    public long getCallCount() { return callCount; }
    public Instant getUpdatedAt() { return updatedAt; }
}
