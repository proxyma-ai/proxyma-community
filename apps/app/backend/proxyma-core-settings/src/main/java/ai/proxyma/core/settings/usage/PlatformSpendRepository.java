package ai.proxyma.core.settings.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlatformSpendRepository extends JpaRepository<PlatformSpendRecord, UUID> {

    /**
     * Total platform spend since an instant. COALESCE because SUM over no rows is null, not zero -
     * a fresh deployment would otherwise NPE the first time the global quota was evaluated.
     */
    @Query("select coalesce(sum(p.costUsd), 0) from PlatformSpendRecord p where p.recordedAt >= :since")
    double totalCostSince(@Param("since") Instant since);

    /** The same total split by activity, for the Usages page's breakdown. */
    @Query("""
            select p.service, coalesce(sum(p.costUsd), 0), coalesce(sum(p.inputTokens), 0),
                   coalesce(sum(p.outputTokens), 0), count(p)
            from PlatformSpendRecord p
            where p.recordedAt >= :since
            group by p.service
            """)
    List<Object[]> totalsByServiceSince(@Param("since") Instant since);

    /**
     * Platform spend per calendar day, for the cost-over-time view.
     *
     * Grouped in the DATABASE rather than by streaming rows into the JVM: a year of platform calls
     * on a busy deployment is millions of rows, and the answer is at most 365 numbers.
     */
    @Query("""
            select cast(p.recordedAt as date), coalesce(sum(p.costUsd), 0)
            from PlatformSpendRecord p
            where p.recordedAt >= :since
            group by cast(p.recordedAt as date)
            order by cast(p.recordedAt as date)
            """)
    List<Object[]> dailyCostSince(@Param("since") Instant since);

    /**
     * Retention: platform spend is an aggregate input, not a record anyone needs forever.
     *
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
    @Query("delete from PlatformSpendRecord p where p.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);
}
