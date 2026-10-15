package ai.proxyma.core.chat.workflow.run;

import ai.proxyma.core.chat.model.WorkflowGraphRun;
import ai.proxyma.core.chat.model.WorkflowRunEvent;
import ai.proxyma.core.chat.repository.WorkflowGraphRunRepository;
import ai.proxyma.core.chat.repository.WorkflowRunEventRepository;
import ai.proxyma.core.chat.workflow.WorkflowGraph;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts runs, keeps their state, and answers the controls.
 *
 * <p>A RUN IS A THREAD AND A ROW. The row is written before any work starts - so a client can
 * subscribe to something that already exists, and so a run that dies in its first node still has a
 * record - and the thread walks the graph with a {@link RunControl} this class hands out to
 * whoever presses Pause.
 *
 * <p>ONE PROCESS, like {@code WorkflowRunReconciler} before it. The live map below is in memory, so
 * pause/stop/approve reach a run only on the instance running it, and a restart ends every run it
 * was running. That is a real limit and it is the same one the markdown runner already has; sharing
 * runs across instances is a distributed-state problem this build does not take on.
 */
@Service
public class WorkflowGraphRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphRunService.class);

    /** Runs live only as long as the process does, so this is a plain unbounded pool rather than
     *  something tuned: the bound that matters is spend, and the gate applies per node. */
    private final ExecutorService runners = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "workflow-run");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<UUID, Live> live = new ConcurrentHashMap<>();

    private final WorkflowGraphRunRepository runs;
    private final WorkflowRunEventRepository events;
    private final RunEventSink sink;
    private final ObjectMapper mapper;
    private final ToolCallbackProvider toolCallbackProvider;
    private final McpGuardService guard;
    private final LlmGateway llmGateway;
    private final SettingsService settings;
    private final ModelPricingService pricing;
    private final GlobalQuotaGate quotaGate;

    public WorkflowGraphRunService(WorkflowGraphRunRepository runs, WorkflowRunEventRepository events,
                              RunEventSink sink, ObjectMapper mapper,
                              ToolCallbackProvider toolCallbackProvider, McpGuardService guard,
                              LlmGateway llmGateway, SettingsService settings,
                              ModelPricingService pricing, GlobalQuotaGate quotaGate) {
        this.runs = runs;
        this.events = events;
        this.sink = sink;
        this.mapper = mapper;
        this.toolCallbackProvider = toolCallbackProvider;
        this.guard = guard;
        this.llmGateway = llmGateway;
        this.settings = settings;
        this.pricing = pricing;
        this.quotaGate = quotaGate;
    }

    /** What is held in memory for a run in flight, and lost when the process ends. */
    private static final class Live {
        final RunControl control = new RunControl();
        final WorkflowRunPorts.LatchApprovalPort approvals = new WorkflowRunPorts.LatchApprovalPort(control);
        final AtomicReference<Map<String, NodeStatus>> nodeStatus = new AtomicReference<>(Map.of());
        final String userId;
        volatile RunStatus status = RunStatus.RUNNING;

        Live(String userId) { this.userId = userId; }
    }

    /**
     * Writes the run row, then starts the walk on its own thread.
     *
     * <p>Returns as soon as the row exists rather than when the run finishes: a run is minutes of
     * work, and the caller is an HTTP request. The id it returns is what a client subscribes to.
     */
    public UUID start(WorkflowGraph graph, Map<String, Object> inputs, String userId,
                      String conversationId) {
        // Refused before anything is written, so an exhausted budget costs nothing and leaves no
        // half-started run. The per-node gate below is what bounds a loop; this is what stops a
        // doomed run beginning at all.
        quotaGate.checkOrRefuse("this workflow run");

        UUID runId = UUID.randomUUID();
        Map<String, NodeStatus> initial = new LinkedHashMap<>();
        graph.nodes().forEach(n -> initial.put(n.id(), NodeStatus.PENDING));

        WorkflowGraphRun row = new WorkflowGraphRun();
        row.setId(runId);
        row.setWorkflowId(graph.id());
        row.setWorkflowName(graph.name());
        row.setUserId(userId);
        row.setConversationId(conversationId);
        row.setStatus(RunStatus.RUNNING.name());
        row.setNodeStatusJson(json(initial));
        row.setInputsJson(json(inputs));
        row.setStartedAt(Instant.now());
        runs.save(row);

        Live state = new Live(userId);
        state.nodeStatus.set(initial);
        live.put(runId, state);
        sink.open(runId);

        // The run's owner in the spend context on the runner thread, for the model calls its TOOL
        // nodes make (a search's query expansion, reranking and embedding). Its own model nodes
        // pass the owner to the gateway explicitly - see ProviderModelPort.
        runners.submit(() -> SpendContext.run(SpendSubject.owner(userId), () -> execute(runId, graph, inputs, state)));
        log.info("[WorkflowRun] {} started '{}' ({} nodes){}", runId, graph.name(), graph.nodes().size(),
                conversationId == null ? "" : " from conversation " + conversationId);
        return runId;
    }

    private void execute(UUID runId, WorkflowGraph graph, Map<String, Object> inputs, Live state) {
        PersistingRunListener listener = new PersistingRunListener(runId, events, sink, mapper,
                () -> pushSnapshot(runId, state));
        // Node statuses are tracked here as well as inside the interpreter, because the snapshot a
        // client refreshes onto has to be available WHILE the run is in flight - the interpreter's
        // own map is not visible until it returns.
        RunListener tracking = new RunListener() {
            @Override public void nodeStarted(String nodeId, int iteration) {
                mutate(state, nodeId, NodeStatus.RUNNING);
                listener.nodeStarted(nodeId, iteration);
            }
            @Override public void nodeCompleted(String nodeId, int iteration, Map<String, Object> output,
                                                List<String> read, long millis) {
                mutate(state, nodeId, NodeStatus.COMPLETED);
                listener.nodeCompleted(nodeId, iteration, output, read, millis);
            }
            @Override public void nodeFailed(String nodeId, int iteration, String reason) {
                mutate(state, nodeId, NodeStatus.FAILED);
                listener.nodeFailed(nodeId, iteration, reason);
            }
            @Override public void nodesSkipped(List<String> nodeIds) {
                nodeIds.forEach(id -> mutate(state, id, NodeStatus.SKIPPED));
                listener.nodesSkipped(nodeIds);
            }
            @Override public void modelCall(String n, String p, String m, long in, long out, double c) {
                listener.modelCall(n, p, m, in, out, c);
            }
            @Override public void toolCall(String n, String t, Map<String, Object> a) { listener.toolCall(n, t, a); }
            @Override public void approvalRequested(String n, String p) {
                state.status = RunStatus.AWAITING_APPROVAL;
                listener.approvalRequested(n, p);
            }
            @Override public void approvalResolved(String n, WorkflowPorts.ApprovalDecision d) {
                state.status = RunStatus.RUNNING;
                listener.approvalResolved(n, d);
            }
            @Override public void paused() { state.status = RunStatus.PAUSED; listener.paused(); }
            @Override public void resumed() { state.status = RunStatus.RUNNING; listener.resumed(); }
            @Override public void halted(String n, String r) { listener.halted(n, r); }
            @Override public void runFinished(RunOutcome outcome) { listener.runFinished(outcome); }
        };

        // A workflow node runs unattended - it has nobody to ask.
        List<ToolCallback> callbacks = java.util.Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(cb -> !ai.proxyma.core.chat.question.ChatOnlyTools.ALL.contains(cb.getToolDefinition().name()))
                .toList();
        WorkflowInterpreter interpreter = new WorkflowInterpreter(
                new WorkflowRunPorts.RegistryToolPort(callbacks, guard, mapper, state.userId),
                new WorkflowRunPorts.ProviderModelPort(llmGateway, pricing, state.userId),
                state.approvals,
                what -> quotaGate.checkOrRefuse(what));

        RunOutcome outcome;
        try {
            outcome = interpreter.run(graph, inputs, state.control, tracking);
        } catch (RuntimeException e) {
            // The interpreter turns node failures into an outcome rather than throwing, so reaching
            // here means the machinery itself broke. The row must still be closed, or the run shows
            // a spinner until a restart reconciles it.
            log.error("[WorkflowRun] {} died outside the interpreter", runId, e);
            outcome = new RunOutcome(RunStatus.FAILED, null, state.nodeStatus.get(), Map.of(),
                    e.getMessage() == null ? e.toString() : e.getMessage(), null, 0, 0, 0);
        }
        state.status = outcome.status();
        // The live entry and the stream go whatever closing the row does: a database that fails
        // here left both for the two-hour reaper.
        try {
            finish(runId, outcome);
        } finally {
            live.remove(runId);
            sink.close(runId);
        }
    }

    private void mutate(Live state, String nodeId, NodeStatus status) {
        Map<String, NodeStatus> next = new LinkedHashMap<>(state.nodeStatus.get());
        next.put(nodeId, status);
        state.nodeStatus.set(next);
    }

    // No @Transactional: execute() calls this on `this`, past the proxy, so one here would never
    // apply. Its one save is the repository's own transaction.
    protected void finish(UUID runId, RunOutcome outcome) {
        runs.findById(runId).ifPresent(row -> {
            row.setStatus(outcome.status().name());
            row.setNodeStatusJson(json(outcome.nodeStatus()));
            row.setAnswerJson(json(outcome.answer()));
            row.setFinishedAt(Instant.now());
            row.setFailureMessage(truncate(outcome.reason(), 2000));
            row.setFailedNode(outcome.failedNode());
            row.setInputTokens(outcome.inputTokens());
            row.setOutputTokens(outcome.outputTokens());
            row.setCostUsd(outcome.costUsd());
            runs.save(row);
        });
    }

    /** The small whole-snapshot half of the stream, resent complete on every transition - which is
     *  what lets a refresh mid-run redraw the true state. The unbounded half is the log. */
    private void pushSnapshot(UUID runId, Live state) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "SNAPSHOT");
        event.put("status", state.status.name());
        event.put("nodes", state.nodeStatus.get());
        event.put("pauseRequested", state.control.pauseRequested());
        sink.emit(runId, event);
        // Persisted too, so a client that reloads rather than reconnects reads the same statuses.
        try {
            runs.findById(runId).ifPresent(row -> {
                row.setStatus(state.status.name());
                row.setNodeStatusJson(json(state.nodeStatus.get()));
                runs.save(row);
            });
        } catch (RuntimeException e) {
            log.debug("[WorkflowRun] {} snapshot not persisted: {}", runId, e.toString());
        }
    }

    // - controls -

    public boolean pause(UUID runId, String requesterId, boolean superAdmin) {
        Live state = requireControllable(runId, requesterId, superAdmin);
        if (state == null) return false;
        if (state.control.paused()) state.control.resume();
        else state.control.pause();
        return true;
    }

    public boolean resume(UUID runId, String requesterId, boolean superAdmin) {
        Live state = requireControllable(runId, requesterId, superAdmin);
        if (state == null) return false;
        state.control.resume();
        return true;
    }

    public boolean stop(UUID runId, String requesterId, boolean superAdmin) {
        Live state = requireControllable(runId, requesterId, superAdmin);
        if (state == null) return false;
        state.control.stop();
        // Stop wakes only the pause monitor; a run waiting on an approval held its thread until
        // the two-hour wait ran out (chat-api-data-11).
        state.approvals.cancelAll();
        return true;
    }

    public boolean approve(UUID runId, String nodeId, boolean approved, String requesterId,
                           boolean superAdmin) {
        Live state = requireControllable(runId, requesterId, superAdmin);
        if (state == null) return false;
        return state.approvals.resolve(nodeId, approved
                ? WorkflowPorts.ApprovalDecision.APPROVED : WorkflowPorts.ApprovalDecision.DENIED);
    }

    /** Null when the run is not on this instance or has already finished - the caller answers 404,
     *  which is also the honest answer for a run belonging to somebody else. */
    private Live requireControllable(UUID runId, String requesterId, boolean superAdmin) {
        Live state = live.get(runId);
        if (state == null) return null;
        // Controlling a run is the owner or a Super Admin, never any viewer who can see it. Desktop
        // has one user and no auth, where both sides are null and this is trivially true.
        boolean mine = state.userId == null || state.userId.equals(requesterId);
        return (mine || superAdmin) ? state : null;
    }

    // - reads -

    public Optional<WorkflowGraphRun> find(UUID runId) {
        return runs.findById(runId);
    }

    public List<WorkflowGraphRun> recent(String userId) {
        return userId == null ? runs.findTop50ByOrderByStartedAtDesc()
                : runs.findTop50ByUserIdOrderByStartedAtDesc(userId);
    }

    public List<WorkflowRunEvent> logAfter(UUID runId, long afterSeq) {
        return events.findTop500ByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, afterSeq);
    }

    /** True while this instance is running it - which is what the controls need, and what a client
     *  needs to know before offering a Pause button that would 404. */
    public boolean isLive(UUID runId) {
        return live.containsKey(runId);
    }

    public boolean visibleTo(WorkflowGraphRun row, String requesterId, boolean superAdmin) {
        return superAdmin || row.getUserId() == null || row.getUserId().equals(requesterId);
    }

    public Map<String, Object> snapshotOf(WorkflowGraphRun row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", row.getId().toString());
        out.put("workflowId", row.getWorkflowId());
        out.put("workflowName", row.getWorkflowName());
        out.put("status", row.getStatus());
        out.put("live", isLive(row.getId()));
        out.put("nodes", parseMap(row.getNodeStatusJson()));
        out.put("startedAt", row.getStartedAt() == null ? null : row.getStartedAt().toString());
        out.put("finishedAt", row.getFinishedAt() == null ? null : row.getFinishedAt().toString());
        out.put("failureMessage", row.getFailureMessage());
        out.put("failedNode", row.getFailedNode());
        out.put("inputTokens", row.getInputTokens());
        out.put("outputTokens", row.getOutputTokens());
        out.put("costUsd", row.getCostUsd());
        out.put("conversationId", row.getConversationId());
        return out;
    }

    /**
     * Fails runs a restart left marked live.
     *
     * <p>A run is an in-memory thread, so a row still saying RUNNING after a restart is a spinner
     * that never resolves - the same stale-state problem the markdown runner's reconciler already
     * fixes, and invisible in testing because nothing restarts mid-run there.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional("chatTransactionManager")
    public void failRunsLeftByRestart() {
        List<WorkflowGraphRun> stale = runs.findByStatusIn(List.of(
                RunStatus.RUNNING.name(), RunStatus.PAUSED.name(), RunStatus.AWAITING_APPROVAL.name()));
        for (WorkflowGraphRun row : stale) {
            row.setStatus(RunStatus.FAILED.name());
            row.setFinishedAt(Instant.now());
            row.setFailureMessage("Proxyma restarted while this run was in progress.");
            runs.save(row);
        }
        if (!stale.isEmpty()) {
            log.info("[WorkflowRun] failed {} run(s) left in progress by a restart", stale.size());
        }
    }

    // - helpers -

    private String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(value, Map.class);
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /** For the controller's list view, kept here so the JSON shape is defined in one place. */
    public List<Map<String, Object>> snapshots(List<WorkflowGraphRun> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        rows.forEach(row -> out.add(snapshotOf(row)));
        return out;
    }
}
