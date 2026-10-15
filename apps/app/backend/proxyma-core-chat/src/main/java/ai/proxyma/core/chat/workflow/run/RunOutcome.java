package ai.proxyma.core.chat.workflow.run;

import java.util.Map;

/**
 * How a run ended, and everything it produced.
 *
 * @param status      see {@link RunStatus} - and note HALTED and COMPLETED are different answers.
 * @param answer      the Result node's resolved value, or null for a run that never reached one.
 * @param nodeStatus  final status per node id, including the ones never attempted.
 * @param scope       every node's output, which is the "what did this step actually see" record.
 * @param reason      the halt reason, the failure message, or null.
 * @param failedNode  the node that failed, or null.
 */
public record RunOutcome(
        RunStatus status,
        Object answer,
        Map<String, NodeStatus> nodeStatus,
        Map<String, Map<String, Object>> scope,
        String reason,
        String failedNode,
        long inputTokens,
        long outputTokens,
        double costUsd) {

    public boolean succeeded() {
        return status == RunStatus.COMPLETED;
    }
}
