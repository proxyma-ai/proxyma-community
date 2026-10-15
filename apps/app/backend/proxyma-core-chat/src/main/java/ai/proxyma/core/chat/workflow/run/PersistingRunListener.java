package ai.proxyma.core.chat.workflow.run;

import ai.proxyma.core.chat.model.WorkflowRunEvent;
import ai.proxyma.core.chat.repository.WorkflowRunEventRepository;
import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes a run's events to the log table and pushes them to whoever is watching.
 *
 * <p>ONE LISTENER FOR BOTH, on purpose. What a viewer sees live and what a reader opening the run
 * tomorrow sees have to be the same events; two code paths producing them separately drift, and the
 * live one always wins the drift because it is the one people look at.
 *
 * <p>PERSIST FIRST, THEN EMIT. The table is the record and the stream is an optimisation over
 * polling it, so an event that reaches a browser but not the database would be a run whose history
 * disagrees with what somebody watched happen.
 *
 * <p>Everything crossing to a client goes through {@link SensitivePaths#scrub}. A run log holds
 * tool arguments and tool results, which is precisely where an absolute server path leaks - and
 * `CLAUDE.md` puts the screen at the boundary rather than at the producer for exactly this kind of
 * new surface.
 */
public class PersistingRunListener implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(PersistingRunListener.class);

    /** A payload longer than this is dropped rather than stored: one node returning a 10 MB
     *  document would otherwise put it in the log, in the stream, and in every reload of the run. */
    private static final int MAX_PAYLOAD_CHARS = 8000;
    private static final int MAX_DETAIL_CHARS = 2000;

    private final UUID runId;
    private final WorkflowRunEventRepository events;
    private final RunEventSink sink;
    private final ObjectMapper mapper;
    private final AtomicLong seq = new AtomicLong(0);
    private final Runnable onStateChanged;

    public PersistingRunListener(UUID runId, WorkflowRunEventRepository events, RunEventSink sink,
                                 ObjectMapper mapper, Runnable onStateChanged) {
        this.runId = runId;
        this.events = events;
        this.sink = sink;
        this.mapper = mapper;
        this.onStateChanged = onStateChanged == null ? () -> { } : onStateChanged;
    }

    @Override
    public void nodeStarted(String nodeId, int iteration) {
        record("NODE_STARTED", nodeId, iteration, null, null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void nodeCompleted(String nodeId, int iteration, Map<String, Object> output,
                              List<String> read, long millis) {
        String detail = read.isEmpty() ? null : "read " + String.join(", ", read);
        record("NODE_COMPLETED", nodeId, iteration, detail, output, millis, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void nodeFailed(String nodeId, int iteration, String reason) {
        record("NODE_FAILED", nodeId, iteration, reason, null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void nodesSkipped(List<String> nodeIds) {
        record("NODES_SKIPPED", null, 0, String.join(", ", nodeIds), null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void modelCall(String nodeId, String provider, String model,
                          long inputTokens, long outputTokens, double costUsd) {
        record("LLM_CALL", nodeId, 0, model, null, null, inputTokens, outputTokens, costUsd);
    }

    @Override
    public void toolCall(String nodeId, String toolName, Map<String, Object> arguments) {
        record("TOOL_CALL", nodeId, 0, toolName, arguments, null, 0, 0, 0);
    }

    @Override
    public void approvalRequested(String nodeId, String prompt) {
        record("APPROVAL_REQUESTED", nodeId, 0, prompt, null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void approvalResolved(String nodeId, WorkflowPorts.ApprovalDecision decision) {
        record("APPROVAL_RESOLVED", nodeId, 0, decision.name(), null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void paused() {
        record("PAUSED", null, 0, null, null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void resumed() {
        record("RESUMED", null, 0, null, null, null, 0, 0, 0);
        onStateChanged.run();
    }

    @Override
    public void halted(String nodeId, String reason) {
        record("HALTED", nodeId, 0, reason, null, null, 0, 0, 0);
    }

    @Override
    public void runFinished(RunOutcome outcome) {
        record("RUN_FINISHED", outcome.failedNode(), 0,
                outcome.status() + (outcome.reason() == null ? "" : ": " + outcome.reason()),
                null, null, outcome.inputTokens(), outcome.outputTokens(), outcome.costUsd());
        onStateChanged.run();
    }

    private void record(String kind, String nodeId, int iteration, String detail,
                        Object payload, Long millis, long inputTokens, long outputTokens, double costUsd) {
        long n = seq.getAndIncrement();
        WorkflowRunEvent row = new WorkflowRunEvent();
        row.setRunId(runId);
        row.setSeq(n);
        row.setAt(Instant.now());
        row.setNodeId(nodeId);
        row.setIteration(iteration);
        row.setKind(kind);
        row.setDetail(truncate(scrub(detail), MAX_DETAIL_CHARS));
        row.setPayloadJson(truncate(scrub(json(payload)), MAX_PAYLOAD_CHARS));
        row.setMillis(millis);
        row.setInputTokens(inputTokens);
        row.setOutputTokens(outputTokens);
        row.setCostUsd(costUsd);
        try {
            events.save(row);
        } catch (RuntimeException e) {
            // A log write must never take a run down with it. The run is the work; this is the
            // account of it, and losing a line of the account is not worth losing the work.
            log.warn("[WorkflowRun] {} could not record {}: {}", runId, kind, e.toString());
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", n);
        event.put("at", row.getAt().toString());
        event.put("kind", kind);
        if (nodeId != null) event.put("nodeId", nodeId);
        if (iteration > 1) event.put("iteration", iteration);
        if (row.getDetail() != null) event.put("detail", row.getDetail());
        if (row.getPayloadJson() != null) event.put("payload", row.getPayloadJson());
        if (millis != null) event.put("millis", millis);
        if (inputTokens > 0 || outputTokens > 0 || costUsd > 0) {
            event.put("inputTokens", inputTokens);
            event.put("outputTokens", outputTokens);
            event.put("costUsd", costUsd);
        }
        sink.emit(runId, event);
    }

    private String json(Object payload) {
        if (payload == null) return null;
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private static String scrub(String value) {
        return value == null ? null : SensitivePaths.scrub(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
