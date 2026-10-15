package ai.proxyma.core.evaluation.repository;

import ai.proxyma.core.evaluation.model.ChatTurnMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatTurnMetricRepository extends JpaRepository<ChatTurnMetric, UUID> {

    /**
     * Everything System > Performance shows about chat turns since {@code since}, in ONE query over
     * the window rather than one per figure: the turn count, how many failed, and the average time
     * to first token, duration and retry count. One row, in that order.
     */
    @Query("SELECT COUNT(m), COALESCE(SUM(CASE WHEN m.success = false THEN 1 ELSE 0 END), 0), "
            + "COALESCE(AVG(m.timeToFirstTokenMs), 0), COALESCE(AVG(m.totalDurationMs), 0), "
            + "COALESCE(AVG(m.retryCount), 0) "
            + "FROM ChatTurnMetric m WHERE m.recordedAt >= :since")
    List<Object[]> summarySince(@Param("since") Instant since);

    // Retention. These are the highest-volume tables in the system - one row per chat turn and one
    // per search - and until now nothing ever removed one.
    /**
     * Bulk delete, and it carries its OWN transaction deliberately.
     *
     * Was a derived `long deleteByRecordedAtBefore(Instant)` called from inside a sweep annotated
     * with a bare @Transactional. Two things were wrong with that. Spring Data implements a derived
     * delete by SELECTing every matching row and calling em.remove() on each - on the
     * highest-volume tables in the deployment that is the whole retention window loaded into the
     * persistence context. And the bare @Transactional on the caller resolves the @PRIMARY
     * transaction manager, which on desktop is localTransactionManager, while this repository is
     * bound to a different EntityManagerFactory entirely - so the caller's transaction was never
     * this repository's transaction. Both sweeps failed on every run with "Null return value from
     * advice does not match primitive return type" and had never deleted a row.
     *
     * @Transactional here rather than on the caller because the repository proxy's transaction
     * interceptor is already wired to the manager named in this package's @EnableJpaRepositories -
     * so an UNQUALIFIED annotation gets the right manager in both editions, with no bean whose name
     * only exists on one of them. int, not long: @Modifying supports int/Integer/void only.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from ChatTurnMetric m where m.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);
}
