package ai.proxyma.core.settings.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * One page of a tab, narrowed by whatever the caller supplied - "null means all" for every
     * filter, as {@code LlmCallRepository.search} does it, so one query serves every combination.
     * {@code text} is a lower-cased LIKE pattern escaped by the controller. The order comes from the
     * pageable, and the controller passes only an order it has whitelisted.
     */
    @Query("""
            select e from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to
              and e.category in :categories
              and (:action is null or e.action = :action)
              and (:outcome is null or e.outcome = :outcome)
              and (:target is null or e.target = :target)
              and (:actorId is null or e.actorId = :actorId)
              and (:conversationId is null or e.conversationId = :conversationId)
              and (:text is null
                   or lower(e.action) like :text escape '\\'
                   or lower(coalesce(e.target, '')) like :text escape '\\'
                   or lower(coalesce(e.summary, '')) like :text escape '\\'
                   or lower(coalesce(e.actorName, '')) like :text escape '\\')
            """)
    Page<AuditEvent> search(@Param("from") Instant from,
                            @Param("to") Instant to,
                            @Param("categories") Collection<String> categories,
                            @Param("action") String action,
                            @Param("outcome") String outcome,
                            @Param("target") String target,
                            @Param("actorId") String actorId,
                            @Param("conversationId") String conversationId,
                            @Param("text") String text,
                            Pageable pageable);

    /** [category, action, count] in the window - the summary's tiles and the Action filter's choices. */
    @Query("""
            select e.category, e.action, count(e) from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to and e.category in :categories
            group by e.category, e.action order by count(e) desc
            """)
    List<Object[]> countByAction(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("categories") Collection<String> categories);

    /** [outcome, count] in the window. */
    @Query("""
            select e.outcome, count(e) from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to and e.category in :categories
            group by e.outcome order by count(e) desc
            """)
    List<Object[]> countByOutcome(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("categories") Collection<String> categories);

    /**
     * [target, count, failed, total ms] in the window - which tools, which rules, which sources.
     * Failed is FAILED alone: a write the user refused did not fail.
     */
    @Query("""
            select e.target, count(e),
                   sum(case when e.outcome = 'FAILED' then 1 else 0 end),
                   sum(coalesce(e.durationMs, 0))
            from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to and e.category in :categories
              and e.target is not null
            group by e.target order by count(e) desc
            """)
    List<Object[]> countByTarget(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("categories") Collection<String> categories,
                                 Pageable limit);

    /** [actorKind, actorId, actorName, count] - who, for an administrator. */
    @Query("""
            select e.actorKind, e.actorId, max(e.actorName), count(e) from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to and e.category in :categories
            group by e.actorKind, e.actorId order by count(e) desc
            """)
    List<Object[]> countByActor(@Param("from") Instant from, @Param("to") Instant to,
                                @Param("categories") Collection<String> categories,
                                Pageable limit);

    /**
     * Durations in the window, newest first and capped by the pageable - the percentiles are taken
     * from a recent sample in Java, since neither H2 nor JPQL offers a percentile function both
     * editions share.
     */
    @Query("""
            select e.durationMs from AuditEvent e
            where e.occurredAt >= :from and e.occurredAt < :to and e.category in :categories
              and e.durationMs is not null
            order by e.occurredAt desc
            """)
    List<Long> recentDurations(@Param("from") Instant from, @Param("to") Instant to,
                               @Param("categories") Collection<String> categories, Pageable limit);

    /**
     * Retention. Its OWN transaction, as LlmCallRepository's: a bare @Transactional on the sweep
     * would resolve the @PRIMARY manager, which on Desktop belongs to another database.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from AuditEvent e where e.occurredAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);

    /** A tab's Clear, on Desktop and Home Server. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from AuditEvent e where e.category in :categories")
    int deleteByCategories(@Param("categories") Collection<String> categories);
}
