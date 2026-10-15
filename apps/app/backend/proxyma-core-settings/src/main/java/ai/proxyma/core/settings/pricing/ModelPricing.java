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

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
