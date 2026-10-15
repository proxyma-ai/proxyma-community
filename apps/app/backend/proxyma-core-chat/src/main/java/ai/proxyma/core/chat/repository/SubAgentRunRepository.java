package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.SubAgentRun;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SubAgentRunRepository extends JpaRepository<SubAgentRun, String> {

    /** A conversation's sub-agents, oldest first - the order their panels appear in. */
    List<SubAgentRun> findByConversationIdOrderByStartedAtAsc(String conversationId);

    /** Reports the main agent has not been given yet, in the order the sub-agents started. */
    List<SubAgentRun> findByConversationIdAndDeliveredFalseAndStatusNotOrderByStartedAtAsc(
            String conversationId, String status);

    /** Rows still marked RUNNING. Read once at startup - after a restart every one is stale. */
    List<SubAgentRun> findByStatus(String status);

    /** Whether anything at all is owed in any conversation - one cheap query at startup. */
    List<SubAgentRun> findByDeliveredFalseAndStatusNot(String status);

    /**
     * A conversation's sub-agents have to go when the conversation does - see SubAgentRun for why
     * that is explicit work here.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SubAgentRun r WHERE r.conversationId = :conversationId")
    void deleteByConversationId(String conversationId);
}
