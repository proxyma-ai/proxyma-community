package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttributedSpendRepository extends JpaRepository<AttributedSpendMonth, UUID> {

    /** The row to fold a call onto, if this month has seen this exact combination before. */
    Optional<AttributedSpendMonth> findByYearMonthAndSubjectKindAndSubjectIdAndRoleAndProviderAndModel(
            String yearMonth, SpendSubject.Kind subjectKind, String subjectId,
            String role, String provider, String model);

    /**
     * What the whole deployment spent in a month. COALESCE because SUM over no rows is null, not
     * zero - a fresh deployment would otherwise NPE the first time anyone opened the page.
     */
    @Query("select coalesce(sum(a.costUsd), 0) from AttributedSpendMonth a where a.yearMonth = :month")
    double totalCost(@Param("month") String month);

    /** Per subject, biggest first - "who is this costing us". */
    @Query("""
            select a.subjectKind, a.subjectId, coalesce(sum(a.costUsd), 0),
                   coalesce(sum(a.inputTokens), 0), coalesce(sum(a.outputTokens), 0), coalesce(sum(a.callCount), 0)
            from AttributedSpendMonth a
            where a.yearMonth = :month
            group by a.subjectKind, a.subjectId
            order by coalesce(sum(a.costUsd), 0) desc
            """)
    List<Object[]> bySubject(@Param("month") String month);

    /** Per role - "what is costing us", and the list an include/exclude policy is chosen from. */
    @Query("""
            select a.role, coalesce(sum(a.costUsd), 0),
                   coalesce(sum(a.inputTokens), 0), coalesce(sum(a.outputTokens), 0), coalesce(sum(a.callCount), 0)
            from AttributedSpendMonth a
            where a.yearMonth = :month
            group by a.role
            order by coalesce(sum(a.costUsd), 0) desc
            """)
    List<Object[]> byRole(@Param("month") String month);

    /**
     * One subject's spend, split by role.
     *
     * <p>Split rather than summed, because the sum is a policy question: whether a user's ingest
     * belongs in "what this user costs" is for the reader to decide, and they can only decide it if
     * the parts arrive separately.
     */
    @Query("""
            select a.role, coalesce(sum(a.costUsd), 0),
                   coalesce(sum(a.inputTokens), 0), coalesce(sum(a.outputTokens), 0), coalesce(sum(a.callCount), 0)
            from AttributedSpendMonth a
            where a.yearMonth = :month and a.subjectKind = :kind and a.subjectId = :subjectId
            group by a.role
            order by coalesce(sum(a.costUsd), 0) desc
            """)
    List<Object[]> forSubjectByRole(@Param("month") String month,
                                    @Param("kind") SpendSubject.Kind kind,
                                    @Param("subjectId") String subjectId);

    /** Everything anonymous visitors cost this month, which is one row of bySubject() by design. */
    @Query("""
            select coalesce(sum(a.costUsd), 0) from AttributedSpendMonth a
            where a.yearMonth = :month and a.subjectKind = ai.proxyma.core.common.spend.SpendSubject$Kind.ANONYMOUS
            """)
    double anonymousCost(@Param("month") String month);
}
