package ai.proxyma.core.chat.workflow.run;

/**
 * Per-node state. The same five the markdown step table already uses, kept deliberately identical
 * so the run view stays one component whether it is drawing a graph or a list.
 *
 * <p>SKIPPED means never attempted - a branch went the other way, or the run ended first. It is not
 * a failure, and drawing it as one is the same class of lie as narrating a refused tool call in the
 * past tense.
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
