package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.WorkflowGraphRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowGraphRunRepository extends JpaRepository<WorkflowGraphRun, UUID> {

    List<WorkflowGraphRun> findTop50ByOrderByStartedAtDesc();

    List<WorkflowGraphRun> findTop50ByUserIdOrderByStartedAtDesc(String userId);

    List<WorkflowGraphRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    /** Runs still marked live. Read at startup by the reconciler: a run is an in-memory thread, so
     *  a row left RUNNING by a restart is a spinner that never resolves - the same stale-state
     *  problem WorkflowRunReconciler already solves for the markdown runner. */
    List<WorkflowGraphRun> findByStatusIn(List<String> statuses);

    /** A conversation's runs must go when the conversation does. Free if this hung off a chat
     *  message, deliberate work here - the same cost the markdown run table already pays. */
    void deleteByConversationId(String conversationId);
}
