package ai.proxyma.core.chat.workflow.run;

import java.util.List;
import java.util.Map;

/**
 * Everything the interpreter needs from the outside world, as interfaces.
 *
 * <p>WHY PORTS AND NOT SERVICES. The interpreter is the piece where the engine's promises live -
 * order, branching, what a node saw, what happens when someone presses Stop - and every one of
 * those is decided by code that touches no provider, no database and no HTTP. Injecting the four
 * things that DO touch them keeps the interesting half runnable against stubs, which is the only
 * way it gets verified in a workspace with no test suite. It is also the seam the real
 * implementations attach to without the interpreter knowing they exist.
 */
public final class WorkflowPorts {

    private WorkflowPorts() {}

    /** Thrown by a port when a node cannot proceed. Fails the node, and with it the run. */
    public static class NodeFailedException extends RuntimeException {
        public NodeFailedException(String message) { super(message); }
        public NodeFailedException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Invokes one registered tool with arguments an author fixed.
     *
     * <p>THE IMPLEMENTATION MUST STILL APPLY THE WRITE GUARD. Removing the model from the
     * invocation removes who CHOSE the arguments; it does not remove what the call does. A Tool
     * node naming a write tool has to pass {@code WriteToolRegistry} and
     * {@code McpGuard.checkWriteAllowed} exactly as a model-issued call does - an author is as
     * capable of writing {@code deleteRepository} as a model is.
     */
    public interface ToolPort {
        Map<String, Object> invoke(String toolName, Map<String, Object> arguments);
    }

    /** One model call. Deliberately not a conversation: an LLM node's memory is its own, so there
     *  is no thread to carry and nothing to condense. */
    public record ModelCall(
            String nodeId,
            String instruction,
            /** Tool names this node may use, empty for the toolless default. */
            List<String> tools,
            /** Provider instance id and model resolved node -> workflow -> chat role, both nullable
             *  meaning "the chat role's". */
            String provider,
            String model,
            int maxIterations) {}

    public record ModelReply(String text, long inputTokens, long outputTokens, double costUsd) {
        public static ModelReply of(String text) { return new ModelReply(text, 0, 0, 0); }
    }

    public interface ModelPort {
        ModelReply complete(ModelCall call);
    }

    public enum ApprovalDecision { APPROVED, DENIED }

    /**
     * Asks the person who started the run, and blocks until they answer.
     *
     * <p>Blocking is correct here and is not a design smell: an Approval node exists to stop the
     * procedure, the run has its own thread, and the alternative - suspending and resuming an
     * interpreter mid-graph - is the resumability question the design deliberately deferred.
     */
    public interface ApprovalPort {
        ApprovalDecision awaitDecision(String nodeId, String prompt);
    }

    /**
     * Checked before every model-backed node, not once per run.
     *
     * <p>Per run is not enough once loops exist: a bounded loop with an LLM node in it is N calls
     * admitted by one check. Per node is what actually bounds the bill. Implementations throw to
     * refuse, mirroring {@code globalQuotaGate.checkOrRefuse} which already works that way.
     */
    public interface SpendGate {
        void checkOrRefuse(String what);

        SpendGate ALLOW = what -> { };
    }
}
