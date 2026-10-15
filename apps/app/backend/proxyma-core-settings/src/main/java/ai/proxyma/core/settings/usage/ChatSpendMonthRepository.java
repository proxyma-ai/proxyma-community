package ai.proxyma.core.settings.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSpendMonthRepository extends JpaRepository<ChatSpendMonth, UUID> {

    Optional<ChatSpendMonth> findByYearMonthAndProviderAndModel(String yearMonth, String provider, String model);

    /** One month's per-model rows, dearest first - the order the breakdown is read in. */
    List<ChatSpendMonth> findByYearMonthOrderByCostUsdDescModelAsc(String yearMonth);

    /**
     * COALESCE, not a nullable Double: an empty table is a legitimate state (nothing has been
     * chatted yet) and it means zero spend, not unknown spend. Without it every caller has to
     * null-check a figure that is never genuinely absent.
     */
    @Query("select coalesce(sum(c.costUsd), 0) from ChatSpendMonth c where c.yearMonth = :yearMonth")
    double totalCostForMonth(@Param("yearMonth") String yearMonth);

    @Query("select coalesce(sum(c.costUsd), 0) from ChatSpendMonth c")
    double totalCostAllTime();

    @Query("select coalesce(sum(c.callCount), 0) from ChatSpendMonth c where c.yearMonth = :yearMonth")
    long totalCallsForMonth(@Param("yearMonth") String yearMonth);

    @Query("select coalesce(sum(c.callCount), 0) from ChatSpendMonth c")
    long totalCallsAllTime();
}
