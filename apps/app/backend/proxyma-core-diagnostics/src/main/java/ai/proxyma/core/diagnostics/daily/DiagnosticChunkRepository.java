package ai.proxyma.core.diagnostics.daily;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * The modifying queries carry their own unqualified {@code @Transactional}, on the REPOSITORY and
 * not on a service: the repository proxy is bound to the diagnostics transaction manager its
 * {@code @EnableJpaRepositories} names (its own H2 file on desktop), while an unqualified
 * annotation on a service would resolve the {@code @Primary} manager - a different database.
 * See recurring-failure-classes section 13.
 */
@Repository
public interface DiagnosticChunkRepository extends JpaRepository<DiagnosticChunk, Long> {

    List<DiagnosticChunk> findAllByOrderByIdAsc();

    List<DiagnosticChunk> findByDayLessThanOrderByIdAsc(LocalDate day);

    @Modifying
    @Transactional
    @Query("delete from DiagnosticChunk c where c.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query("delete from DiagnosticChunk c where c.day < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDate cutoff);

    @Modifying
    @Transactional
    @Query("delete from DiagnosticChunk c")
    int deleteEverything();
}
