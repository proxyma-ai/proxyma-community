package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.WorkflowRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRunEventRepository extends JpaRepository<WorkflowRunEvent, Long> {

    /** The cursor read: everything a client has not seen. Bounded, because a run with a long loop
     *  can hold thousands of rows and a reconnecting browser must not be handed all of them. */
    List<WorkflowRunEvent> findTop500ByRunIdAndSeqGreaterThanOrderBySeqAsc(UUID runId, long afterSeq);

    List<WorkflowRunEvent> findByRunIdOrderBySeqAsc(UUID runId);

    void deleteByRunId(UUID runId);
}
