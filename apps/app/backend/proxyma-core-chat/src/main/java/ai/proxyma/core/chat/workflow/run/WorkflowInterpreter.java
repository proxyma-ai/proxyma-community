package ai.proxyma.core.chat.workflow.run;

import ai.proxyma.core.chat.workflow.NodeType;
import ai.proxyma.core.chat.workflow.WorkflowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks a {@link WorkflowGraph} and runs it.
 *
 * <p>This class is the engine's promise. Everything it does is decided by code: which node runs
 * next, which branch is taken, what a node was given, and what happens when someone presses Pause.
 * The model is confined to the inside of an LLM or Classify node, and even Classify can only pick
 * from a declared set. That confinement IS the product - see the engine proposal's single test,
 * "is the decision made by code, or by the model?".
 *
 * <p>Four things are worth knowing before changing anything here:
 *
 * <ul>
 *   <li><b>It does not trust the validator.</b> A workflow file can be hand-edited or arrive by
 *       config import without ever passing through the editor, so every rule the validator enforces
 *       is also survivable at run time: a missing Result ends the run without an answer rather than
 *       throwing, an unbounded loop hits {@link #MAX_VISITS}, and a binding to nothing resolves to
 *       null. A validator is a courtesy to an author, never a guarantee to an interpreter.
 *   <li><b>Pause and Stop are checked between nodes only.</b> See {@link RunControl}.
 *   <li><b>A failed node fails the run</b>, and everything not attempted ends SKIPPED rather than
 *       FAILED. Per-node {@code onFailure: continue} and retry edges are deliberately not here -
 *       the engine proposal defers them, and a first version that continues past a failure is a
 *       first version whose green ticks mean nothing.
 *   <li><b>Spend is gated per model-backed node</b>, not once per run. A loop containing an LLM
 *       node is N calls admitted by one check, which is how a bounded loop becomes an unbounded
 *       bill.
 * </ul>
 */
public final class WorkflowInterpreter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInterpreter.class);

    /**
     * Hard ceiling on node executions in one run, whatever the graph says.
     *
     * <p>Defence in depth against a cycle the validator never saw - see the note above about files
     * that never went through the editor. Deliberately generous: it is a backstop against
     * non-termination, not a spend limit. Spend is bounded by the gate, which is checked per node.
     */
    public static final int MAX_VISITS = 1000;

    private final WorkflowPorts.ToolPort tools;
    private final WorkflowPorts.ModelPort models;
    private final WorkflowPorts.ApprovalPort approvals;
    private final WorkflowPorts.SpendGate spendGate;

    public WorkflowInterpreter(WorkflowPorts.ToolPort tools, WorkflowPorts.ModelPort models,
                               WorkflowPorts.ApprovalPort approvals, WorkflowPorts.SpendGate spendGate) {
        this.tools = tools;
        this.models = models;
        this.approvals = approvals;
        this.spendGate = spendGate == null ? WorkflowPorts.SpendGate.ALLOW : spendGate;
    }

    /** Everything one run needs that is not the graph: the values it was started with, the controls
     *  someone may press, and where its events go. */
    private static final class Run {
        final WorkflowGraph graph;
        final RunScope scope;
        final RunControl control;
        final RunListener listener;
        final Map<String, NodeStatus> status = new LinkedHashMap<>();
        final Map<String, Integer> visits = new LinkedHashMap<>();
        int totalVisits;
        long inputTokens;
        long outputTokens;
        double costUsd;
        String haltReason;
        String failure;
        String failedNode;

        Run(WorkflowGraph graph, RunScope scope, RunControl control, RunListener listener) {
            this.graph = graph;
            this.scope = scope;
            this.control = control;
            this.listener = listener;
            graph.nodes().forEach(n -> status.put(n.id(), NodeStatus.PENDING));
        }
    }

    /** Why a walk stopped, which is how a loop body knows the difference between "came back round"
     *  and "the run is over". */
    private enum Stop { REACHED_LIMIT, ENDED, HALTED, FAILED, PAUSED_OUT, STOPPED, NO_NEXT }

    public RunOutcome run(WorkflowGraph graph, Map<String, Object> inputs,
                          RunControl control, RunListener listener) {
        RunControl ctl = control == null ? new RunControl() : control;
        RunListener out = listener == null ? RunListener.NONE : listener;
        Run run = new Run(graph, new RunScope(inputs), ctl, out);

        out.runStarted(graph.id(), graph.nodes().stream().map(WorkflowGraph.Node::id).toList());

        List<WorkflowGraph.Node> starts = graph.nodesOfType(NodeType.INPUT);
        if (starts.isEmpty()) {
            // Survivable rather than fatal, for the reason in the class javadoc: a graph with no
            // Input never passed the validator, and the run says so instead of throwing.
            return finish(run, RunStatus.FAILED, null, "This workflow has no Input node, so there is "
                    + "nowhere for a run to start.");
        }

        Stop stop = walk(run, starts.get(0).id(), null);

        if (stop == Stop.STOPPED) return finish(run, RunStatus.STOPPED, null, "Stopped.");
        if (stop == Stop.FAILED) return finish(run, RunStatus.FAILED, null, run.failure);
        if (stop == Stop.HALTED) return finish(run, RunStatus.HALTED, null, run.haltReason);
        if (stop == Stop.REACHED_LIMIT) {
            return finish(run, RunStatus.FAILED, null, "This run executed " + MAX_VISITS
                    + " steps without finishing, so it was stopped. A loop somewhere is not bounded.");
        }

        // Ran off the end, or reached the Result node. Either way the answer is the Result's bound
        // value - a graph can end on several paths, so it is named rather than inferred.
        List<WorkflowGraph.Node> results = graph.nodesOfType(NodeType.RESULT);
        if (results.isEmpty()) {
            return finish(run, RunStatus.COMPLETED, null,
                    "This workflow has no Result node, so it produced no answer.");
        }
        WorkflowGraph.Node result = results.get(0);
        Object answer = run.status.get(result.id()) == NodeStatus.COMPLETED
                ? run.scope.outputOf(result.id()).get("value")
                : null;
        return finish(run, RunStatus.COMPLETED, answer, null);
    }

    /**
     * Follows edges from {@code from} until it reaches {@code stopAt}, the end of a path, or a
     * reason to stop entirely.
     *
     * @param stopAt the loop node a body returns to, or null for the main walk. Recursion is what
     *               makes nested loops work with no extra machinery.
     */
    private Stop walk(Run run, String from, String stopAt) {
        String current = from;
        while (current != null) {
            if (stopAt != null && current.equals(stopAt)) return Stop.ENDED;

            if (run.control.stopped()) return Stop.STOPPED;
            // The pause boundary. Requested during a node, applied here - between one and the next.
            if (run.control.pauseRequested()) {
                run.listener.paused();
                boolean carryOn = run.control.awaitResume();
                if (!carryOn) return Stop.STOPPED;
                run.listener.resumed();
            }
            if (++run.totalVisits > MAX_VISITS) return Stop.REACHED_LIMIT;

            WorkflowGraph.Node node = run.graph.node(current);
            if (node == null) {
                run.failure = "This workflow has an edge to '" + current + "', which is not a node.";
                run.failedNode = current;
                return Stop.FAILED;
            }

            int iteration = run.visits.merge(node.id(), 1, Integer::sum);
            run.status.put(node.id(), NodeStatus.RUNNING);
            run.listener.nodeStarted(node.id(), iteration);
            long began = System.nanoTime();

            Executed executed;
            try {
                executed = execute(run, node, iteration);
            } catch (WorkflowPorts.NodeFailedException e) {
                return fail(run, node, iteration, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("[Workflow] node {} threw: {}", node.id(), e.toString(), e);
                return fail(run, node, iteration, describe(e));
            }

            if (executed.stop == Stop.STOPPED) {
                run.status.put(node.id(), NodeStatus.SKIPPED);
                return Stop.STOPPED;
            }

            run.scope.put(node.id(), executed.output);
            run.status.put(node.id(), NodeStatus.COMPLETED);
            run.listener.nodeCompleted(node.id(), iteration, executed.output, executed.read,
                    (System.nanoTime() - began) / 1_000_000);

            if (executed.stop == Stop.HALTED) return Stop.HALTED;
            if (node.type() == NodeType.RESULT) return Stop.ENDED;

            current = next(run, node, executed.port);
            if (current == null) return Stop.NO_NEXT;
        }
        return Stop.NO_NEXT;
    }

    private Stop fail(Run run, WorkflowGraph.Node node, int iteration, String reason) {
        run.status.put(node.id(), NodeStatus.FAILED);
        run.failure = reason;
        run.failedNode = node.id();
        run.listener.nodeFailed(node.id(), iteration, reason);
        return Stop.FAILED;
    }

    /** What one node produced: its output, the port it chose, and what it read. */
    private record Executed(Map<String, Object> output, String port, List<String> read, Stop stop) {
        static Executed of(Map<String, Object> output, List<String> read) {
            return new Executed(output, null, read, null);
        }
        static Executed port(Map<String, Object> output, String port, List<String> read) {
            return new Executed(output, port, read, null);
        }
    }

    private Executed execute(Run run, WorkflowGraph.Node node, int iteration) {
        return switch (node.type()) {
            case INPUT -> Executed.of(new LinkedHashMap<>(run.scope.inputs()), List.of());
            case TOOL -> executeTool(run, node);
            case LLM -> executeLlm(run, node);
            case DECISION -> executeDecision(run, node);
            case CLASSIFY -> executeClassify(run, node);
            case APPROVAL -> executeApproval(run, node);
            case FOREACH -> executeForEach(run, node, iteration);
            case HALT -> {
                String reason = run.scope.resolveText(node.configString("reason"));
                run.haltReason = reason.isBlank() ? "Halted." : reason;
                run.listener.halted(node.id(), run.haltReason);
                yield new Executed(Map.of("reason", run.haltReason), null, List.of(), Stop.HALTED);
            }
            case RESULT -> {
                Object raw = node.config().get("value");
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("value", run.scope.resolve(raw));
                yield Executed.of(output, RunScope.referencesIn(String.valueOf(raw)));
            }
        };
    }

    private Executed executeTool(Run run, WorkflowGraph.Node node) {
        String tool = node.configString("tool");
        if (tool == null || tool.isBlank()) {
            throw new WorkflowPorts.NodeFailedException("This Tool node does not name a tool.");
        }
        Map<String, Object> declared = node.configMap("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) run.scope.resolve(declared);
        run.listener.toolCall(node.id(), tool, arguments);
        Map<String, Object> result = tools.invoke(tool, arguments);
        return Executed.of(result == null ? Map.of() : result, referencesOf(declared));
    }

    private Executed executeLlm(Run run, WorkflowGraph.Node node) {
        spendGate.checkOrRefuse("this workflow step");
        Object instruction = node.config().get("instruction");
        String prompt = run.scope.resolveText(instruction);
        List<String> tools = node.configList("tools").stream().map(String::valueOf).toList();
        WorkflowPorts.ModelReply reply = models.complete(new WorkflowPorts.ModelCall(
                node.id(), prompt, tools, node.provider() == null ? run.graph.provider() : node.provider(),
                node.model() == null ? run.graph.model() : node.model(),
                intConfig(node, "maxIterations", 6)));
        account(run, node, reply);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", reply.text());
        return Executed.of(output, referencesOf(instruction));
    }

    /**
     * Evaluates the author's predicate.
     *
     * <p>SpEL, with {@link SimpleEvaluationContext} and NEVER StandardEvaluationContext. The latter
     * resolves type references and constructors - {@code T(java.lang.Runtime).getRuntime().exec(..)}
     * is a working SpEL expression - and a workflow file is user-authored content that can arrive
     * by config import. Read-only data binding removes that entire surface.
     *
     * <p>Bound values become VARIABLES rather than being substituted into the expression text. That
     * keeps types intact (42 stays a number, so {@code > 0} means what it looks like) and makes
     * injection structurally impossible: a value containing an apostrophe cannot end a string
     * literal that never existed.
     */
    private Executed executeDecision(Run run, WorkflowGraph.Node node) {
        String raw = node.configString("expression");
        if (raw == null || raw.isBlank()) {
            throw new WorkflowPorts.NodeFailedException("This Decision node has no expression.");
        }
        List<String> read = RunScope.referencesIn(raw);
        StringBuilder expression = new StringBuilder();
        Map<String, Object> variables = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\\{\\s*([A-Za-z0-9_-]+)((?:\\.[A-Za-z0-9_-]+|\\[\\d+])*)\\s*}}").matcher(raw);
        int index = 0;
        int last = 0;
        while (m.find()) {
            expression.append(raw, last, m.start());
            String var = "v" + index++;
            variables.put(var, run.scope.resolve(m.group()));
            expression.append('#').append(var);
            last = m.end();
        }
        expression.append(raw.substring(last));

        Object value;
        try {
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            variables.forEach(context::setVariable);
            value = new SpelExpressionParser().parseExpression(expression.toString()).getValue(context);
        } catch (RuntimeException e) {
            throw new WorkflowPorts.NodeFailedException(
                    "This Decision could not be evaluated: " + raw + " - " + describe(e));
        }
        boolean truth = value instanceof Boolean b ? b : value != null;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("value", truth);
        return Executed.port(output, truth ? "true" : "false", read);
    }

    private Executed executeClassify(Run run, WorkflowGraph.Node node) {
        spendGate.checkOrRefuse("this workflow step");
        List<String> labels = node.configList("labels").stream().map(String::valueOf).toList();
        if (labels.isEmpty()) {
            throw new WorkflowPorts.NodeFailedException("This Classify node declares no labels.");
        }
        Object instruction = node.config().get("instruction");
        String prompt = run.scope.resolveText(instruction)
                + "\n\nAnswer with exactly one of these labels and nothing else: "
                + String.join(", ", labels) + ".";
        WorkflowPorts.ModelReply reply = models.complete(new WorkflowPorts.ModelCall(
                node.id(), prompt, List.of(),
                node.provider() == null ? run.graph.provider() : node.provider(),
                node.model() == null ? run.graph.model() : node.model(), 1));
        account(run, node, reply);

        String answer = reply.text() == null ? "" : reply.text().trim();
        String chosen = null;
        for (String label : labels) {
            if (label.equalsIgnoreCase(answer)) { chosen = label; break; }
        }
        if (chosen == null) {
            // Deliberately a failure rather than a guess. The bound choice is the only thing that
            // makes Classify safer than an open-ended model call, and picking the nearest label -
            // or the first - would quietly hand the branch decision back to chance.
            throw new WorkflowPorts.NodeFailedException(
                    "Classify expected one of [" + String.join(", ", labels) + "] and the model "
                            + "answered '" + truncate(answer) + "'.");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("label", chosen);
        return Executed.port(output, chosen, referencesOf(instruction));
    }

    private Executed executeApproval(Run run, WorkflowGraph.Node node) {
        String prompt = run.scope.resolveText(node.configString("prompt"));
        run.listener.approvalRequested(node.id(), prompt);
        WorkflowPorts.ApprovalDecision decision = approvals.awaitDecision(node.id(), prompt);
        run.listener.approvalResolved(node.id(), decision);
        if (run.control.stopped()) {
            return new Executed(Map.of(), null, List.of(), Stop.STOPPED);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", decision.name().toLowerCase(Locale.ROOT));
        return Executed.port(output,
                decision == WorkflowPorts.ApprovalDecision.APPROVED ? "approved" : "denied",
                referencesOf(node.configString("prompt")));
    }

    /**
     * Runs the body once per item, then continues on {@code after}.
     *
     * <p>The body is walked with this node as its stop point, so a backward edge from the last body
     * node is what closes the loop - the same edge the canvas draws. Nesting works by recursion.
     *
     * <p>During an iteration the loop's own output carries {@code item} and {@code index}, so a
     * body node binds to {@code {{loop.output.item}}}. Afterwards it carries {@code count} and
     * {@code results} - one entry per iteration, holding the output of the last node that ran in
     * it. A body node's own output is the LAST iteration's, which is a real limit worth knowing:
     * collecting per-iteration outputs per node is the wider question the engine proposal left open
     * about for-each semantics.
     */
    private Executed executeForEach(Run run, WorkflowGraph.Node node, int iteration) {
        Object itemsRaw = node.config().get("items");
        Object resolved = run.scope.resolve(itemsRaw);
        List<?> items = resolved instanceof List<?> list ? list
                : resolved == null ? List.of() : List.of(resolved);
        int max = intConfig(node, "maxIterations", 25);
        int count = Math.min(items.size(), max);

        String body = target(run, node, "body");
        List<Object> results = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Map<String, Object> cursor = new LinkedHashMap<>();
            cursor.put("item", items.get(i));
            cursor.put("index", i);
            cursor.put("count", count);
            run.scope.put(node.id(), cursor);

            if (body == null) break;
            Stop stop = walk(run, body, node.id());
            if (stop == Stop.STOPPED) return new Executed(Map.of(), null, List.of(), Stop.STOPPED);
            if (stop == Stop.HALTED) {
                return new Executed(Map.of("count", i), null, List.of(), Stop.HALTED);
            }
            if (stop == Stop.FAILED || stop == Stop.REACHED_LIMIT) {
                throw new WorkflowPorts.NodeFailedException(
                        run.failure == null ? "A step inside this loop failed." : run.failure);
            }
            results.add(lastBodyOutput(run, node, body));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("count", count);
        output.put("results", results);
        if (items.size() > max) {
            // Said out loud rather than silently truncated: a loop that quietly stops early is a
            // report about half the data that reads exactly like a report about all of it.
            output.put("truncated", items.size() - max);
        }
        return Executed.port(output, "after", referencesOf(itemsRaw));
    }

    /** The output of the last node that ran inside one iteration - found by walking back from the
     *  loop's body entry along whatever the run recorded, which is cheaper and more honest than
     *  asking the graph, since the path taken may have branched. */
    private Object lastBodyOutput(Run run, WorkflowGraph.Node loop, String bodyEntry) {
        Map<String, Object> latest = null;
        for (WorkflowGraph.Edge e : run.graph.edges()) {
            if (e.to().equals(loop.id()) && run.status.get(e.from()) == NodeStatus.COMPLETED) {
                latest = run.scope.outputOf(e.from());
            }
        }
        return latest != null ? latest : run.scope.outputOf(bodyEntry);
    }

    /** The node an edge leaving {@code node} by {@code port} points at. */
    private String target(Run run, WorkflowGraph.Node node, String port) {
        for (WorkflowGraph.Edge e : run.graph.edges()) {
            if (!e.from().equals(node.id())) continue;
            if (port == null ? e.port() == null : port.equals(e.port())) return e.to();
        }
        return null;
    }

    private String next(Run run, WorkflowGraph.Node node, String port) {
        String byPort = port == null ? null : target(run, node, port);
        if (byPort != null) return byPort;
        // A node with one way out, or a port whose edge is missing: fall back to the single unnamed
        // edge if there is one. The validator refuses an unconnected branch, so reaching here on a
        // ported node means a hand-edited file, and stopping is the honest answer.
        return port == null ? target(run, node, null) : null;
    }

    private void account(Run run, WorkflowGraph.Node node, WorkflowPorts.ModelReply reply) {
        run.inputTokens += reply.inputTokens();
        run.outputTokens += reply.outputTokens();
        run.costUsd += reply.costUsd();
        run.listener.modelCall(node.id(),
                node.provider() == null ? run.graph.provider() : node.provider(),
                node.model() == null ? run.graph.model() : node.model(),
                reply.inputTokens(), reply.outputTokens(), reply.costUsd());
    }

    private RunOutcome finish(Run run, RunStatus status, Object answer, String reason) {
        // Everything never attempted ends SKIPPED, not PENDING and not FAILED. Emitted once, here,
        // because until a run is over a node that has not run yet is simply pending.
        Set<String> skipped = new LinkedHashSet<>();
        run.status.forEach((id, s) -> {
            if (s == NodeStatus.PENDING || s == NodeStatus.RUNNING) skipped.add(id);
        });
        skipped.forEach(id -> run.status.put(id, NodeStatus.SKIPPED));
        if (!skipped.isEmpty()) run.listener.nodesSkipped(List.copyOf(skipped));

        RunOutcome outcome = new RunOutcome(status, answer, Map.copyOf(run.status), run.scope.snapshot(),
                reason, run.failedNode, run.inputTokens, run.outputTokens, run.costUsd);
        run.listener.runFinished(outcome);
        log.info("[Workflow] run of '{}' ended {} after {} step(s){}", run.graph.id(), status,
                run.totalVisits, reason == null ? "" : ": " + reason);
        return outcome;
    }

    private static List<String> referencesOf(Object value) {
        return value == null ? List.of() : RunScope.referencesIn(String.valueOf(value));
    }

    private static int intConfig(WorkflowGraph.Node node, String key, int fallback) {
        Object v = node.config().get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
