package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.spend.SpendSubject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    /**
     * One page of calls, newest first, narrowed by whatever the caller supplied.
     *
     * <p>Every filter is optional and expressed as "null means all", so one query serves the whole
     * detail view instead of a method per combination - and, more to the point, so the SUBJECT
     * filter cannot be forgotten. On the server edition a user may see only their own calls, and
     * that is enforced by the controller passing a subjectId it derived from the token rather than
     * one the client sent.
     *
     * <p>{@code text} is a lower-cased LIKE pattern ({@code %...%}, with its own wildcards escaped
     * by the controller) matched against the service, provider, model and subject - the log's
     * search box. The ORDER comes from the pageable, never from here, so the table's sortable
     * columns sort the whole filtered window on the server rather than one page of it in the
     * browser; the controller only ever passes a sort it has whitelisted.
     */
    @Query("""
            select c from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
              and (:subjectId is null or c.subjectId = :subjectId)
              and (:role is null or c.role = :role)
              and (:provider is null or c.provider = :provider)
              and (:model is null or c.model = :model)
              and (:text is null
                   or lower(c.role) like :text escape '\\'
                   or lower(c.provider) like :text escape '\\'
                   or lower(c.model) like :text escape '\\'
                   or lower(c.subjectId) like :text escape '\\')
            """)
    Page<LlmCall> search(@Param("from") Instant from,
                         @Param("to") Instant to,
                         @Param("subjectId") String subjectId,
                         @Param("role") String role,
                         @Param("provider") String provider,
                         @Param("model") String model,
                         @Param("text") String text,
                         Pageable pageable);

    /**
     * The same window, grouped. Each returns
     * [key, calls, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, cost] - except
     * {@link #totalsByModel}, whose key is TWO columns (provider, model), so the controller can ask
     * whether that pair has a rate without splitting a display string back apart.
     */
    @Query("""
            select c.provider, count(c), sum(c.inputTokens), sum(c.outputTokens),
                   sum(c.cacheReadTokens), sum(c.cacheWriteTokens), sum(c.costUsd)
            from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
              and (:subjectId is null or c.subjectId = :subjectId)
            group by c.provider order by sum(c.costUsd) desc
            """)
    List<Object[]> totalsByProvider(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("subjectId") String subjectId);

    @Query("""
            select c.provider, c.model, count(c), sum(c.inputTokens), sum(c.outputTokens),
                   sum(c.cacheReadTokens), sum(c.cacheWriteTokens), sum(c.costUsd)
            from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
              and (:subjectId is null or c.subjectId = :subjectId)
            group by c.provider, c.model order by sum(c.costUsd) desc
            """)
    List<Object[]> totalsByModel(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("subjectId") String subjectId);

    @Query("""
            select c.role, count(c), sum(c.inputTokens), sum(c.outputTokens),
                   sum(c.cacheReadTokens), sum(c.cacheWriteTokens), sum(c.costUsd)
            from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
              and (:subjectId is null or c.subjectId = :subjectId)
            group by c.role order by sum(c.costUsd) desc
            """)
    List<Object[]> totalsByRole(@Param("from") Instant from, @Param("to") Instant to,
                                @Param("subjectId") String subjectId);

    /**
     * [recordedAt, cost, subjectKind, role] for every call in the window, for the cost-over-time
     * chart.
     *
     * <p>Bucketed in Java rather than grouped here, because the buckets are the READER's calendar
     * days: a date function in JPQL groups in the database session's zone, which differs between H2
     * on desktop and PostgreSQL on server and is neither the reader's. The kind and role are there
     * so each bucket can be split into what users were charged and what the platform paid, by the
     * same cost-center rule the global quota counts with.
     */
    @Query("""
            select c.recordedAt, c.costUsd, c.subjectKind, c.role
            from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
              and (:subjectId is null or c.subjectId = :subjectId)
            """)
    List<Object[]> costPoints(@Param("from") Instant from, @Param("to") Instant to,
                              @Param("subjectId") String subjectId);

    /** Who spent it - the dimension only an admin sees. */
    @Query("""
            select c.subjectKind, c.subjectId, count(c), sum(c.inputTokens), sum(c.outputTokens), sum(c.costUsd)
            from LlmCall c
            where c.recordedAt >= :from and c.recordedAt < :to
            group by c.subjectKind, c.subjectId order by sum(c.costUsd) desc
            """)
    List<Object[]> totalsBySubject(@Param("from") Instant from, @Param("to") Instant to);

    long countBySubjectKindAndSubjectId(SpendSubject.Kind kind, String subjectId);

    /**
     * Retention, and it carries its OWN transaction - see PlatformSpendRepository's note, which is
     * the same trap written out in full: a bare @Transactional on the sweep resolves the @PRIMARY
     * transaction manager, which on desktop belongs to a different EntityManagerFactory than this
     * repository, so both sweeps failed on every run for months and deleted nothing. Unqualified
     * here is correct, because the repository proxy is already wired to the manager this package's
     * @EnableJpaRepositories names. int, not long: @Modifying supports int/Integer/void only.
     */
    /**
     * Spend since {@code since}, split by whose it was: [subjectKind, cost]. What the global quota
     * counts, since 2026-09-29 - every model call the gateway or the embedding bean makes lands
     * here, so the ceiling counts exactly what was spent, in every edition.
     */
    @Query("""
            select c.subjectKind, c.role, sum(c.costUsd) from LlmCall c
            where c.recordedAt >= :since
            group by c.subjectKind, c.role
            """)
    List<Object[]> costBySubjectKindAndRoleSince(@Param("since") Instant since);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from LlmCall c where c.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);

    /** Every call - the log's Clear (LlmCallsController.clear). Its own transaction, as above. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from LlmCall c")
    int deleteAllCalls();
}
