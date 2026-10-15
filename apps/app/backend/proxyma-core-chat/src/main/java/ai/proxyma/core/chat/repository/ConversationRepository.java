package ai.proxyma.core.chat.repository;
import ai.proxyma.core.chat.model.Conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    // This column alone - see Conversation.activeServers for why never a whole-row save.
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.activeServers = :value WHERE c.id = :id")
    int updateActiveServers(@Param("id") String id, @Param("value") String value);

    // The generated title, written from a thread that is not the turn's - see
    // ConversationTitleService for why not a whole-row save either.
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.title = :title WHERE c.id = :id")
    int updateTitle(@Param("id") String id, @Param("title") String title);

    // THE COUNTERS AND FLAGS BELOW ARE EACH WRITTEN BY A STATEMENT OF ITS OWN, since 2026-10-14.
    // They were read, changed and saved as a whole row, from threads that run side by side - the
    // turn on every step, each background sub-agent, the title, a search's own model calls - so of
    // two that read the same row one increment was lost, and each whole-row save also wrote back a
    // stale title over the generated one (chat-agent-06). An increment done by the database cannot
    // be lost, and a statement that names one column cannot overwrite another.

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.inputTokens = c.inputTokens + :input, c.outputTokens = c.outputTokens + :output, "
            + "c.cacheReadTokens = c.cacheReadTokens + :cacheRead, c.cacheWriteTokens = c.cacheWriteTokens + :cacheWrite, "
            + "c.estimatedCostUsd = c.estimatedCostUsd + :cost WHERE c.id = :id")
    int addUsage(@Param("id") String id, @Param("input") long input, @Param("output") long output,
                 @Param("cacheRead") long cacheRead, @Param("cacheWrite") long cacheWrite, @Param("cost") double cost);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.contextTokens = :tokens WHERE c.id = :id")
    int updateContextTokens(@Param("id") String id, @Param("tokens") int tokens);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.contextTokens = CASE WHEN c.contextTokens > :freed "
            + "THEN c.contextTokens - :freed ELSE 0 END WHERE c.id = :id")
    int reduceContextTokens(@Param("id") String id, @Param("freed") int freed);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.messageCount = c.messageCount + 1, c.updatedAt = :at WHERE c.id = :id")
    int recordExchange(@Param("id") String id, @Param("at") Instant at);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.messageCount = :count WHERE c.id = :id")
    int updateMessageCount(@Param("id") String id, @Param("count") int count);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.pinned = :pinned WHERE c.id = :id")
    int updatePinned(@Param("id") String id, @Param("pinned") boolean pinned);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.enabledSkillIds = :value WHERE c.id = :id")
    int updateEnabledSkillIds(@Param("id") String id, @Param("value") String value);

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("UPDATE Conversation c SET c.unlockedSkillNames = :value WHERE c.id = :id")
    int updateUnlockedSkillNames(@Param("id") String id, @Param("value") String value);

    // EVERY conversation, whatever its surface - what deleting a user's data walks
    // (ChatService.deleteAllByUserId). The history list is the four below.
    Page<Conversation> findAllByOrderByPinnedDescUpdatedAtDesc(Pageable pageable);

    Page<Conversation> findAllByUserIdOrderByPinnedDescUpdatedAtDesc(String userId, Pageable pageable);

    // Paginated - backs the conversation history modal's lazy-loaded list, since a
    // long-running session can accumulate hundreds of conversations. Pinned conversations sort
    // to the top regardless of recency. The chat page's own (no surface), or one surface's - see
    // ConversationSurface; a conversation another page holds stays out of the chat's history.
    @Query("SELECT c FROM Conversation c WHERE c.surface IS NULL ORDER BY c.pinned DESC, c.updatedAt DESC")
    Page<Conversation> pageOfChat(Pageable pageable);

    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId AND c.surface IS NULL "
            + "ORDER BY c.pinned DESC, c.updatedAt DESC")
    Page<Conversation> pageOfChatOfUser(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT c FROM Conversation c WHERE c.surface = :surface ORDER BY c.pinned DESC, c.updatedAt DESC")
    Page<Conversation> pageOfSurface(@Param("surface") String surface, Pageable pageable);

    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId AND c.surface = :surface "
            + "ORDER BY c.pinned DESC, c.updatedAt DESC")
    Page<Conversation> pageOfSurfaceOfUser(@Param("userId") String userId, @Param("surface") String surface,
                                           Pageable pageable);

    // One user's conversations among the given ids, whatever their surface - a page that keeps its
    // own record of which conversations are its (the key-account panel's bindings) lists them here.
    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId AND c.id IN :ids "
            + "ORDER BY c.pinned DESC, c.updatedAt DESC")
    Page<Conversation> pageAmongOfUser(@Param("userId") String userId,
                                       @Param("ids") java.util.Collection<String> ids, Pageable pageable);

    // Retention sweep target set - see ConversationRetentionSweep.
    List<Conversation> findAllByUpdatedAtBeforeAndPinnedFalse(Instant cutoff);

    @Query("SELECT c.id FROM Conversation c")
    List<String> findAllIds();

    // searchConversations' title half - see ChatMessageRepository.searchContent for the two-query
    // shape and the one pattern per term. The chat page's conversations only: one another page holds
    // (a surface) is that page's.
    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId AND c.id <> :exclude AND c.surface IS NULL "
            + "AND (" + TITLE_MATCHES + ") ORDER BY c.updatedAt DESC")
    List<Conversation> searchTitlesOfUser(@Param("userId") String userId, @Param("exclude") String exclude,
                                          @Param("p1") String p1, @Param("p2") String p2, @Param("p3") String p3,
                                          @Param("p4") String p4, @Param("p5") String p5, @Param("p6") String p6,
                                          Pageable page);

    @Query("SELECT c FROM Conversation c WHERE c.id <> :exclude AND c.surface IS NULL "
            + "AND (" + TITLE_MATCHES + ") ORDER BY c.updatedAt DESC")
    List<Conversation> searchTitles(@Param("exclude") String exclude,
                                    @Param("p1") String p1, @Param("p2") String p2, @Param("p3") String p3,
                                    @Param("p4") String p4, @Param("p5") String p5, @Param("p6") String p6,
                                    Pageable page);

    String TITLE_MATCHES = "LOWER(c.title) LIKE :p1 ESCAPE '!' OR LOWER(c.title) LIKE :p2 ESCAPE '!' "
            + "OR LOWER(c.title) LIKE :p3 ESCAPE '!' OR LOWER(c.title) LIKE :p4 ESCAPE '!' "
            + "OR LOWER(c.title) LIKE :p5 ESCAPE '!' OR LOWER(c.title) LIKE :p6 ESCAPE '!'";

    @Query("SELECT c.id FROM Conversation c WHERE c.userId = :userId")
    List<String> findIdsByUserId(@Param("userId") String userId);

    void deleteByUserId(String userId);

    // Lifetime (not date-windowed, unlike the other Admin Activity metrics) per-user token/cost
    // totals across every conversation - backs the Admin Activity tab's per-user usage table (see
    // proxyma-core-auth's AdminAnalyticsService, which is allowed to call into this module: auth
    // depends on chat via the proxyma-core aggregator, never the reverse). Rows with a null
    // userId (anonymous/desktop conversations) are excluded - there is no user to attribute them
    // to. Row shape: [userId, sumInputTokens, sumOutputTokens, sumCacheReadTokens, sumCacheWriteTokens, sumEstimatedCostUsd].
    @Query("SELECT c.userId, SUM(c.inputTokens), SUM(c.outputTokens), SUM(c.cacheReadTokens), SUM(c.cacheWriteTokens), SUM(c.estimatedCostUsd) "
            + "FROM Conversation c WHERE c.userId IS NOT NULL GROUP BY c.userId")
    List<Object[]> sumTokenUsageByUser();

    // Same aggregate shape as sumTokenUsageByUser() above but for a single user (no GROUP BY, so
    // always exactly one row - all-null aggregates if that user has zero conversations, per JPQL
    // aggregate semantics) - backs the Admin user detail modal without pulling every user's totals
    // just to show one. List<Object[]> (not a bare Object[]) deliberately, matching
    // sumTokenUsageByUser()'s already-proven return shape - no other repository method anywhere in
    // this codebase returns a bare Object[], so this sticks to the shape Spring Data JPA is known
    // to handle correctly here rather than a novel one.
    // Row shape: [sumInputTokens, sumOutputTokens, sumCacheReadTokens, sumCacheWriteTokens, sumEstimatedCostUsd].
    @Query("SELECT SUM(c.inputTokens), SUM(c.outputTokens), SUM(c.cacheReadTokens), SUM(c.cacheWriteTokens), SUM(c.estimatedCostUsd) "
            + "FROM Conversation c WHERE c.userId = :userId")
    List<Object[]> sumTokenUsageForUser(@Param("userId") String userId);

    // Cross-user Evaluation Mode overview (see proxyma-core-evaluation's AdminEvaluationController)
    // - unscoped by user like sumTokenUsageByUser() above, but windowed by :since instead of
    // lifetime, matching the ?since= query param every /api/v1/admin/evaluation/** endpoint takes.
    long countByCreatedAtGreaterThanEqual(Instant since);

    @Query("SELECT COALESCE(SUM(c.estimatedCostUsd), 0) FROM Conversation c WHERE c.createdAt >= :since")
    double sumEstimatedCostSince(@Param("since") Instant since);
}
