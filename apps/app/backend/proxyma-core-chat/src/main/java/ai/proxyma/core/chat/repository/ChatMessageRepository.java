package ai.proxyma.core.chat.repository;
import ai.proxyma.core.chat.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // EVERY ORDERED READ BREAKS TIES BY ID (2026-09-24). createdAt is stored to the MICROSECOND on
    // both H2 and PostgreSQL, so rows written in one batch can share a timestamp, and PostgreSQL's
    // sort is not stable - a condensation summary could reach the model after its own
    // acknowledgement, breaking the role alternation Anthropic requires. Ids are assigned in write
    // order, which within a tie is the order that was meant.

    /** Every row, condensed ones included - the TRANSCRIPT. */
    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    /** The rows the MODEL reads: everything not folded into a summary. */
    List<ChatMessage> findByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(String conversationId);

    /** The first of them - the system message, in a conversation that has one. */
    java.util.Optional<ChatMessage> findFirstByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(String conversationId);

    // Cross-user Evaluation Mode overview (see proxyma-core-evaluation's AdminEvaluationController).
    // Rows a condensation WROTE are not messages anybody sent; the rows it folded still are.
    long countByCreatedAtGreaterThanEqualAndSummaryFalse(Instant since);

    // Excludes ASSISTANT rows with blank content - intermediate ReAct-loop turns that only carry
    // tool-call metadata (no natural-language answer yet) are persisted as their own ASSISTANT
    // rows for chat-memory purposes, but have nothing a user could read or give feedback on, so
    // they must not surface as their own bubble in the display list.
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :conversationId " +
            "AND (m.role = ai.proxyma.core.chat.domain.MessageRole.USER " +
            "OR (m.role = ai.proxyma.core.chat.domain.MessageRole.ASSISTANT AND m.content IS NOT NULL AND TRIM(m.content) <> '')) " +
            "ORDER BY m.createdAt ASC, m.id ASC")
    List<ChatMessage> findDisplayMessagesByConversationId(String conversationId);

    // One statement. The derived delete this was loaded every row of the conversation, content and
    // all, and removed them one at a time - and conversation deletion ran it twice.
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

    // Rewind support, by TIME rather than by id since 2026-09-24. A condensation inserts its
    // summary rows after the messages that follow them in the conversation - newer ids, older
    // timestamps - so "id >= target" would take a summary with it; createdAt is the order both the
    // transcript and the model read in.
    // Tie-aware: a row sharing the target's microsecond but written before it is kept.
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.conversationId = :conversationId AND "
            + "(m.createdAt > :createdAt OR (m.createdAt = :createdAt AND m.id >= :id))")
    int deleteFromMessage(@Param("conversationId") String conversationId,
                          @Param("createdAt") Instant createdAt, @Param("id") Long id);

    void deleteByConversationIdAndSummaryTrue(String conversationId);

    @Modifying
    @Query("UPDATE ChatMessage m SET m.condensed = false WHERE m.conversationId = :conversationId")
    int restoreCondensed(@Param("conversationId") String conversationId);

    long countByConversationId(String conversationId);

    // The row a workflow run anchors its step table to - see WorkflowRun.triggerMessageId. Called
    // straight after the run's triggering USER message is appended, so the newest id IS that
    // message. MAX(id) rather than an ordered fetch of everything. Safe only because of WHEN it is
    // called: a condensation writes summary rows with newer ids and OLDER timestamps, so in general
    // the highest id is not the latest message.
    @Query("SELECT MAX(m.id) FROM ChatMessage m WHERE m.conversationId = :conversationId")
    Long findMaxIdByConversationId(String conversationId);

    @Query("SELECT DISTINCT m.conversationId FROM ChatMessage m")
    List<String> findDistinctConversationIds();

    // searchConversations: the user's and the assistant's words that contain ANY of the search's
    // terms, newest first - p1 to p6, one per term, a query with fewer terms repeating one. ONE
    // statement for all of them: a leading wildcard reads every row whatever the index, and a
    // statement per term read them six times over (chat-agent-14). Two queries rather than one with
    // ":userId IS NULL OR ...", which PostgreSQL cannot type when the parameter is null. The
    // patterns arrive lower-cased and escaped with '!'. Only the chat page's conversations (no
    // surface) - see ConversationSurface.
    @Query("SELECT m FROM ChatMessage m, Conversation c WHERE c.id = m.conversationId "
            + "AND c.userId = :userId AND c.surface IS NULL AND m.conversationId <> :exclude AND m.summary = false "
            + "AND m.role IN (ai.proxyma.core.chat.domain.MessageRole.USER, ai.proxyma.core.chat.domain.MessageRole.ASSISTANT) "
            + "AND (" + CONTENT_MATCHES + ") ORDER BY m.createdAt DESC")
    List<ChatMessage> searchContentOfUser(@Param("userId") String userId, @Param("exclude") String exclude,
                                          @Param("p1") String p1, @Param("p2") String p2, @Param("p3") String p3,
                                          @Param("p4") String p4, @Param("p5") String p5, @Param("p6") String p6,
                                          org.springframework.data.domain.Pageable page);

    @Query("SELECT m FROM ChatMessage m, Conversation c WHERE c.id = m.conversationId "
            + "AND c.surface IS NULL AND m.conversationId <> :exclude AND m.summary = false "
            + "AND m.role IN (ai.proxyma.core.chat.domain.MessageRole.USER, ai.proxyma.core.chat.domain.MessageRole.ASSISTANT) "
            + "AND (" + CONTENT_MATCHES + ") ORDER BY m.createdAt DESC")
    List<ChatMessage> searchContent(@Param("exclude") String exclude,
                                    @Param("p1") String p1, @Param("p2") String p2, @Param("p3") String p3,
                                    @Param("p4") String p4, @Param("p5") String p5, @Param("p6") String p6,
                                    org.springframework.data.domain.Pageable page);

    String CONTENT_MATCHES = "LOWER(m.content) LIKE :p1 ESCAPE '!' OR LOWER(m.content) LIKE :p2 ESCAPE '!' "
            + "OR LOWER(m.content) LIKE :p3 ESCAPE '!' OR LOWER(m.content) LIKE :p4 ESCAPE '!' "
            + "OR LOWER(m.content) LIKE :p5 ESCAPE '!' OR LOWER(m.content) LIKE :p6 ESCAPE '!'";
}
