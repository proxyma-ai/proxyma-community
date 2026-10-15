package ai.proxyma.core.settings.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModelPricingRepository extends JpaRepository<ModelPricing, UUID> {
    Optional<ModelPricing> findByProviderAndModel(String provider, String model);
    List<ModelPricing> findAllByOrderByProviderAscModelAsc();
    // Rates are edited from one provider's own dialog now (Settings > Providers), so the common
    // read is "this provider's rates" rather than the whole table.
    List<ModelPricing> findAllByProviderOrderByModelAsc(String provider);
}
