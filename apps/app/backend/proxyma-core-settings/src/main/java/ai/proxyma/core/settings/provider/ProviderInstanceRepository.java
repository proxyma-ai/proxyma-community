package ai.proxyma.core.settings.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderInstanceRepository extends JpaRepository<ProviderInstance, UUID> {

    List<ProviderInstance> findAllByOrderByNameAsc();

    // Name uniqueness is enforced by the column too; this is what turns a duplicate into a message
    // the user can read instead of a constraint-violation stack trace.
    Optional<ProviderInstance> findByNameIgnoreCase(String name);
}
