package ai.proxyma.core.registry.repository;

import ai.proxyma.core.registry.model.IngestToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestTokenRepository extends JpaRepository<IngestToken, UUID> {

    Optional<IngestToken> findByTokenHash(String tokenHash);

    List<IngestToken> findByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    long countByConnectorId(UUID connectorId);

    // The use stamp alone, never a save of the row read at authentication - a revoke between the
    // two must not be undone by the stamp.
    @Modifying
    @Transactional
    @Query("update IngestToken t set t.lastUsedAt = :at where t.id = :id")
    int markUsed(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("delete from IngestToken t where t.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);
}
