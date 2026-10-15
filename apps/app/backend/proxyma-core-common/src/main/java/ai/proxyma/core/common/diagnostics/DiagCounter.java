package ai.proxyma.core.common.diagnostics;

/**
 * Every event the diagnostics report counts. Closed for the same reason as {@link DiagOp}.
 */
public enum DiagCounter {
    APP_START("app.start"),
    /** A clean shutdown. A crash or a kill sends nothing, so this is a lower bound. */
    APP_STOP("app.stop"),
    /** Minutes of uptime, added on a clean shutdown. */
    APP_UPTIME_MINUTES("app.uptime_minutes"),
    LICENSE_ACTIVATED("license.activated"),
    /** Labels: connector. */
    SOURCE_REGISTERED("source.registered"),
    /** Labels: connector. */
    SOURCE_REMOVED("source.removed"),
    CHAT_TURN("chat.turn"),
    /** Labels: expansion, rerank. */
    SEARCH("search.query"),
    /** One reconcile pass. Labels: connector, mode, outcome (complete/partial/incomplete/failed), error. */
    SYNC_OUTCOME("sync.outcome"),
    /** One item's processing. Labels: connector, fileClass, outcome, error. */
    INGEST_OUTCOME("ingest.outcome"),
    /** A model call that failed. Labels: role, protocol, error. */
    LLM_FAILURE("llm.failure"),
    /** An updater event. Labels: event (checked, found, downloaded, applied, failed, cancelled). */
    UPDATE_EVENT("update.event"),
    /** One guardrail decision. Labels: stage, verdict, enforced. */
    GUARDRAIL_DECISION("guardrail.decision"),
    /**
     * One call of a knowledge-graph tool - whether the graph earns its cost. Labels: tool (search,
     * path, people, timeline, themes), found (on/off), question (on/off - whether the user's
     * question was passed to link by meaning). Never a name, a term or a result.
     */
    GRAPH_TOOL("graph.tool"),
    /** A classifier call that failed and was let through. */
    GUARDRAIL_CLASSIFIER_FAILURE("guardrail.classifier_failure");

    private final String wireName;

    DiagCounter(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
