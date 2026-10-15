package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KgAliasRepository extends JpaRepository<KgAlias, UUID> {

    // Resolution step 1, against the other names an entity goes by IN THIS SOURCE. At most one row
    // by construction - see KgAlias for why the constraint is per source.
    Optional<KgAlias> findByNormalizedAliasAndConnectorId(String normalizedAlias, UUID connectorId);

    boolean existsByNormalizedAliasAndConnectorId(String normalizedAlias, UUID connectorId);

    // A query's alias lookup: the entities this string names in any source the asker can read.
    @Query("select distinct a.entityId from KgAlias a "
            + "where a.normalizedAlias = :alias and a.connectorId in :connectorIds")
    List<UUID> entityIdsForAlias(@Param("alias") String alias,
                                 @Param("connectorIds") Collection<UUID> connectorIds);

    @Modifying
    @Query("delete from KgAlias a where a.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);

    // The aliases of entities that are gone - run after KgEntityRepository.deleteOrphansAmong, over
    // the same ids. An alias of a deleted entity would keep resolving lookups to a row that no
    // longer exists.
    @Modifying
    @Query("delete from KgAlias a where a.entityId in :ids "
            + "and not exists (select 1 from KgEntity e where e.id = a.entityId)")
    int deleteOfMissingEntities(@Param("ids") Collection<UUID> ids);
}
