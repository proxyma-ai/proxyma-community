package ai.proxyma.core.chat.workflow.run;

import java.util.Map;

/**
 * Everything that happens during a run, as it happens.
 *
 * <p>This is the seam the persisted execution log and the SSE stream both attach to. It is one
 * interface rather than two because they must not diverge: what a viewer watching live sees and
 * what a reader opening the run tomorrow sees are the same events, and any pair of code paths
 * producing them separately drifts.
 *
 * <p>Note the shape of {@link #nodeCompleted}: it carries the node's OUTPUT and the references it
 * READ. A log of "step 4 finished" is a progress bar; a log of what a step was given and what it
 * produced is the auditable-inputs property the engine exists for, and it is only free if it is
 * recorded at the moment it is known.
 */
public interface RunListener {

    default void runStarted(String workflowId, java.util.List<String> nodeIds) {}

    default void nodeStarted(String nodeId, int iteration) {}

    /** @param read the bindings this node resolved, so the log can answer "where did that come
     *              from" without re-deriving it from the definition. */
    default void nodeCompleted(String nodeId, int iteration, Map<String, Object> output,
                               java.util.List<String> read, long millis) {}

    default void nodeFailed(String nodeId, int iteration, String reason) {}

    /** Never attempted, because a branch went elsewhere or the run ended first. Emitted once at the
     *  end, not per node, because until a run finishes a node is pending rather than skipped. */
    default void nodesSkipped(java.util.List<String> nodeIds) {}

    /** A model-backed node's spend, separated from nodeCompleted so the Cost view is not a scan of
     *  every event looking for the ones that happen to carry numbers. */
    default void modelCall(String nodeId, String provider, String model,
                           long inputTokens, long outputTokens, double costUsd) {}

    default void toolCall(String nodeId, String toolName, Map<String, Object> arguments) {}

    default void approvalRequested(String nodeId, String prompt) {}

    default void approvalResolved(String nodeId, WorkflowPorts.ApprovalDecision decision) {}

    default void paused() {}

    default void resumed() {}

    /** A Halt node ended the run cleanly. Not a failure - see NodeType.HALT. */
    default void halted(String nodeId, String reason) {}

    default void runFinished(RunOutcome outcome) {}

    RunListener NONE = new RunListener() {};
}
