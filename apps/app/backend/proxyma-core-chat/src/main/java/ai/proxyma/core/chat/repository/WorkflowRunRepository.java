package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    /** Oldest first: a conversation's runs read as a sequence of things that happened, and the
     *  step table for each is rendered in that order. */
    List<WorkflowRun> findByConversationIdOrderByStartedAtAsc(String conversationId);

    /**
     * Runs still marked RUNNING. Read once at startup by WorkflowRunReconciler - after a restart
     * every one of these is a lie, because the thread driving it died with the process.
     */
    List<WorkflowRun> findByStatus(WorkflowRun.Status status);

    /**
     * A conversation's runs have to go when the conversation does. Hanging this state off
     * ChatMessage would have got that for free; a separate table makes it explicit work, and
     * forgetting it leaves rows pointing at a conversation that no longer exists.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorkflowRun r WHERE r.conversationId = :conversationId")
    void deleteByConversationId(String conversationId);
}
