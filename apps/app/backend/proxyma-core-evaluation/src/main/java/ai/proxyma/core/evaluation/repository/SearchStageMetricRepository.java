package ai.proxyma.core.evaluation.repository;

import ai.proxyma.core.evaluation.model.SearchStageMetric;
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
public interface SearchStageMetricRepository extends JpaRepository<SearchStageMetric, UUID> {

    /**
     * Everything System > Performance shows about searches since {@code since}, in ONE query over
     * the window rather than nine: the search count; the average expansion, dense, keyword,
     * rerank and total times; and the graph channel set beside searches without it - how many
     * searches ran it, its own average cost, and the whole search's average with and without it.
     * One row, in that order.
     */
    @Query("SELECT COUNT(m), COALESCE(AVG(m.expansionMs), 0), COALESCE(AVG(m.denseMs), 0), "
            + "COALESCE(AVG(m.bm25Ms), 0), COALESCE(AVG(m.rerankMs), 0), COALESCE(AVG(m.totalMs), 0), "
            + "COALESCE(SUM(CASE WHEN m.graphChannel = true THEN 1 ELSE 0 END), 0), "
            + "COALESCE(AVG(CASE WHEN m.graphChannel = true THEN m.graphMs ELSE NULL END), 0), "
            + "COALESCE(AVG(CASE WHEN m.graphChannel = true THEN m.totalMs ELSE NULL END), 0), "
            + "COALESCE(AVG(CASE WHEN m.graphChannel = true THEN NULL ELSE m.totalMs END), 0) "
            + "FROM SearchStageMetric m WHERE m.recordedAt >= :since")
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
    @Query("delete from SearchStageMetric m where m.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);
}
