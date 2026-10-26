package ai.proxyma.core.settings.pricing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// Admin-configurable $ rate for one provider+model pair - one row per pair (unique index below),
// edited in place rather than duplicated. See ModelPricingService.estimateCostUsd for how these
// four per-million rates turn a TokenUsage into a USD estimate.
@Entity
@Table(name = "model_pricing", indexes = {
    @Index(name = "idx_model_pricing_provider_model", columnList = "provider,model", unique = true)
})
public class ModelPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private double inputPricePerMillion;

    @Column(nullable = false)
    private double outputPricePerMillion;

    @Column(nullable = false)
    private double cacheReadPricePerMillion;

    @Column(nullable = false)
    private double cacheWritePricePerMillion;

    // Since 2026-10-22, every one NULLABLE: ddl-auto adds them to a table that already has rows, and
    // a NOT NULL column there would fail every insert (recurring-failure-classes section 11). Null
    // means "not given" - see RateCard and CostCalculator for what each falls back to.

    /** A ONE-hour cache write; null for 1.6 times the five-minute rate (Anthropic's ratio). */
    private Double cacheWriteLongPricePerMillion;

    /** The prompt size above which the long* rates price the whole call; null for no long tier. */
    private Long longContextThresholdTokens;

    private Double longInputPricePerMillion;
    private Double longOutputPricePerMillion;
    private Double longCacheReadPricePerMillion;
    private Double longCacheWritePricePerMillion;

    /** A PricingSchedule name; null for one price at every hour. */
    @Column(length = 32)
    private String pricingSchedule;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPrePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getInputPricePerMillion() { return inputPricePerMillion; }
    public void setInputPricePerMillion(double inputPricePerMillion) { this.inputPricePerMillion = inputPricePerMillion; }

    public double getOutputPricePerMillion() { return outputPricePerMillion; }
    public void setOutputPricePerMillion(double outputPricePerMillion) { this.outputPricePerMillion = outputPricePerMillion; }

    public double getCacheReadPricePerMillion() { return cacheReadPricePerMillion; }
    public void setCacheReadPricePerMillion(double cacheReadPricePerMillion) { this.cacheReadPricePerMillion = cacheReadPricePerMillion; }

    public double getCacheWritePricePerMillion() { return cacheWritePricePerMillion; }
    public void setCacheWritePricePerMillion(double cacheWritePricePerMillion) { this.cacheWritePricePerMillion = cacheWritePricePerMillion; }

    public Double getCacheWriteLongPricePerMillion() { return cacheWriteLongPricePerMillion; }
    public void setCacheWriteLongPricePerMillion(Double value) { this.cacheWriteLongPricePerMillion = value; }

    public Long getLongContextThresholdTokens() { return longContextThresholdTokens; }
    public void setLongContextThresholdTokens(Long value) { this.longContextThresholdTokens = value; }

    public Double getLongInputPricePerMillion() { return longInputPricePerMillion; }
    public void setLongInputPricePerMillion(Double value) { this.longInputPricePerMillion = value; }

    public Double getLongOutputPricePerMillion() { return longOutputPricePerMillion; }
    public void setLongOutputPricePerMillion(Double value) { this.longOutputPricePerMillion = value; }

    public Double getLongCacheReadPricePerMillion() { return longCacheReadPricePerMillion; }
    public void setLongCacheReadPricePerMillion(Double value) { this.longCacheReadPricePerMillion = value; }

    public Double getLongCacheWritePricePerMillion() { return longCacheWritePricePerMillion; }
    public void setLongCacheWritePricePerMillion(Double value) { this.longCacheWritePricePerMillion = value; }

    public String getPricingSchedule() { return pricingSchedule; }
    public void setPricingSchedule(String value) { this.pricingSchedule = value; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
