package ai.proxyma.core.chat.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a graph may be saved, and says why not in words written for its author.
 *
 * <p>WHY THIS EXISTS AT ALL. A workflow is a program, and the editor is the only place its
 * mistakes can be reported to somebody who can still fix them cheaply. Every rule below earns its
 * place by being a failure that is otherwise invisible until a run is half-finished - and a run
 * that has already filed an issue cannot be un-run.
 *
 * <p>The rule that catches the most real mistakes is the ancestor rule: a binding may only
 * reference a node that is guaranteed to have run first. Breaking it does not throw at run time,
 * it silently resolves to nothing, and the model downstream writes a confident paragraph about an
 * empty list.
 *
 * <p>ERROR refuses the save; WARNING does not. The split is not about severity of consequence but
 * about certainty: a node whose output nothing reads may be a write step that is supposed to be a
 * dead end, so refusing it would be the validator overruling an author about their own intent.
 */
public final class WorkflowGraphValidator {

    /** A cap on nodes, inherited in spirit from the markdown format's MAX_STEPS = 20 - which was a
     *  SPEND ceiling, not a storage one. A graph needs a bigger number because Input, Result,
     *  Decision and Halt nodes cost nothing to run, and a separate cap on model-backed nodes is
     *  what actually bounds the bill. */
    public static final int MAX_NODES = 60;

    /** Model-backed nodes (LLM, CLASSIFY) in one graph. Each is a provider call per visit, and a
     *  loop multiplies it - which is why the run-time interpreter must ALSO gate per node rather
     *  than trusting this number. */
    public static final int MAX_MODEL_NODES = 20;

    /** {{ nodeId }} or {{ nodeId.output.something }}. The id is captured; the path is not
     *  interpreted here, deliberately - see the note on declared outputs in checkBindings. */
    private static final Pattern BINDING = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)([^}]*)}}");

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private WorkflowGraphValidator() {}

    public enum Severity { ERROR, WARNING }

    /** @param nodeId the node at fault, or null for a whole-graph problem. */
    public record Problem(Severity severity, String nodeId, String message) {}

    /**
     * What the validator needs to know about the tool registry, as an interface rather than a
     * dependency on it: the rules below are the interesting part and they are testable against a
     * handful of fixtures only if this class does not drag the whole Spring tool layer in with it.
     * The real implementation reads ToolCallbackProvider and ToolResultSchemaRegistry - the same
     * pair GET /api/v1/mcp/tools already serves to the editor.
     */
    public interface ToolCatalog {
        boolean hasTool(String name);
        /** Parameter names the tool requires. Empty for an unknown tool. */
        Set<String> requiredParameters(String name);

        /** A catalogue that knows nothing. Every tool-specific rule below is SKIPPED rather than
         *  failed - a validator with no registry must not report every tool as missing. */
        ToolCatalog UNKNOWN = new ToolCatalog() {
            @Override public boolean hasTool(String name) { return true; }
            @Override public Set<String> requiredParameters(String name) { return Set.of(); }
        };
    }

    public static List<Problem> validate(WorkflowGraph graph) {
        return validate(graph, ToolCatalog.UNKNOWN);
    }

    public static List<Problem> validate(WorkflowGraph graph, ToolCatalog tools) {
        List<Problem> problems = new ArrayList<>();
        if (graph == null) return problems;

        checkNodeIdentity(graph, problems);
        checkTerminals(graph, problems);
        checkEdgeEndpoints(graph, problems);
        checkPorts(graph, problems);

        Map<String, Set<String>> ancestors = ancestors(graph);
        checkReachability(graph, ancestors, problems);
        checkBindings(graph, ancestors, problems);
        checkCycles(graph, problems);
        checkTools(graph, tools, problems);
        checkCaps(graph, problems);
        checkAdvisories(graph, problems);

        return List.copyOf(problems);
    }

    public static boolean saveable(List<Problem> problems) {
        return problems.stream().noneMatch(p -> p.severity() == Severity.ERROR);
    }

    // - rules -

    private static void checkNodeIdentity(WorkflowGraph g, List<Problem> out) {
        Set<String> seen = new HashSet<>();
        for (WorkflowGraph.Node n : g.nodes()) {
            if (!seen.add(n.id())) {
                out.add(new Problem(Severity.ERROR, n.id(),
                        "Two nodes share the id '" + n.id() + "'. A binding to it could mean either."));
            }
            if (!ID.matcher(n.id()).matches()) {
                out.add(new Problem(Severity.ERROR, n.id(),
                        "Node id '" + n.id() + "' must be lowercase letters, digits, - or _, starting "
                                + "with a letter or digit. Bindings refer to it by name."));
            }
        }
    }

    private static void checkTerminals(WorkflowGraph g, List<Problem> out) {
        int inputs = g.nodesOfType(NodeType.INPUT).size();
        int results = g.nodesOfType(NodeType.RESULT).size();
        if (inputs != 1) {
            out.add(new Problem(Severity.ERROR, null,
                    inputs == 0 ? "This workflow has no Input node, so nothing says where a run starts."
                                : "This workflow has " + inputs + " Input nodes; it can have one."));
        }
        if (results != 1) {
            out.add(new Problem(Severity.ERROR, null,
                    results == 0 ? "This workflow has no Result node, so a run has no answer to give back."
                                 : "This workflow has " + results + " Result nodes; it can have one. A graph "
                                   + "can end on several paths, which is exactly why the answer is named "
                                   + "rather than taken from whatever ran last."));
        }
        for (WorkflowGraph.Node n : g.nodes()) {
            if (n.type().terminal() && g.edges().stream().anyMatch(e -> e.from().equals(n.id()))) {
                out.add(new Problem(Severity.ERROR, n.id(),
                        "Nothing can follow a " + n.type().wire() + " node - it ends the run."));
            }
        }
    }

    private static void checkEdgeEndpoints(WorkflowGraph g, List<Problem> out) {
        for (WorkflowGraph.Edge e : g.edges()) {
            if (g.node(e.from()) == null) {
                out.add(new Problem(Severity.ERROR, null,
                        "An edge starts at '" + e.from() + "', which is not a node in this workflow."));
            }
            if (g.node(e.to()) == null) {
                out.add(new Problem(Severity.ERROR, null,
                        "An edge ends at '" + e.to() + "', which is not a node in this workflow."));
            }
        }
    }

    private static void checkPorts(WorkflowGraph g, List<Problem> out) {
        for (WorkflowGraph.Node n : g.nodes()) {
            List<String> required = expectedPorts(n);
            if (required.isEmpty()) continue;
            Set<String> connected = new LinkedHashSet<>();
            for (WorkflowGraph.Edge e : g.edges()) {
                if (e.from().equals(n.id()) && e.port() != null) connected.add(e.port());
            }
            for (String port : required) {
                if (!connected.contains(port)) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "The '" + port + "' branch of '" + label(n) + "' is not connected. A run "
                                    + "reaching it would stop for no stated reason."));
                }
            }
            for (String port : connected) {
                if (!required.contains(port)) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "'" + label(n) + "' has no '" + port + "' branch. It has: "
                                    + String.join(", ", required) + "."));
                }
            }
        }
    }

    /** CLASSIFY's ports are its declared labels, so they are read per node rather than from the enum. */
    private static List<String> expectedPorts(WorkflowGraph.Node n) {
        if (n.type() != NodeType.CLASSIFY) return n.type().ports();
        List<String> labels = new ArrayList<>();
        for (Object raw : n.configList("labels")) {
            if (raw == null) continue;
            String label = String.valueOf(raw).trim();
            if (!label.isEmpty()) labels.add(label);
        }
        return labels;
    }

    private static void checkReachability(WorkflowGraph g, Map<String, Set<String>> ancestors,
                                          List<Problem> out) {
        List<WorkflowGraph.Node> inputs = g.nodesOfType(NodeType.INPUT);
        if (inputs.size() != 1) return; // already reported; reachability from "somewhere" is meaningless
        String start = inputs.get(0).id();
        for (WorkflowGraph.Node n : g.nodes()) {
            if (n.id().equals(start)) continue;
            if (!ancestors.getOrDefault(n.id(), Set.of()).contains(start)) {
                out.add(new Problem(Severity.ERROR, n.id(),
                        "'" + label(n) + "' cannot be reached from the Input node, so it would be "
                                + "drawn and saved and never run."));
            }
        }
    }

    private static void checkBindings(WorkflowGraph g, Map<String, Set<String>> ancestors,
                                      List<Problem> out) {
        Set<String> inputNames = new HashSet<>();
        g.declaredInputs().forEach(i -> inputNames.add(i.name()));

        for (WorkflowGraph.Node n : g.nodes()) {
            for (String reference : referencesIn(n)) {
                // A declared input is in scope everywhere: it exists before the run starts.
                if (inputNames.contains(reference)) continue;
                if (g.node(reference) == null) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "'" + label(n) + "' binds to '" + reference + "', which is neither a "
                                    + "declared input nor a node in this workflow."));
                    continue;
                }
                if (reference.equals(n.id())) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "'" + label(n) + "' binds to its own output."));
                    continue;
                }
                // THE RULE THAT MATTERS. Not "is it somewhere in the graph" but "is it guaranteed
                // to have run BEFORE this node, on every path that reaches this node". Anything
                // else resolves to nothing at run time and says so to nobody.
                if (!ancestors.getOrDefault(n.id(), Set.of()).contains(reference)) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "'" + label(n) + "' binds to '" + reference + "', which does not always "
                                    + "run before it. The value would be empty on some paths, with "
                                    + "nothing said about it."));
                }
            }
        }
        // The path INSIDE a binding is deliberately not checked here. Declared output schemas cover
        // top-level keys only (#177): a binding to `issue` is checkable and `issue.fields.status`
        // is not, because the value is passthrough. The editor marks the deep ones unchecked rather
        // than refusing them - refusing would make every passthrough value unusable.
    }

    private static void checkCycles(WorkflowGraph g, List<Problem> out) {
        for (List<String> cycle : cycles(g)) {
            boolean bounded = cycle.stream().anyMatch(id -> {
                WorkflowGraph.Node n = g.node(id);
                return n != null && (n.type() == NodeType.FOREACH || n.config().containsKey("maxIterations"));
            });
            if (!bounded) {
                out.add(new Problem(Severity.ERROR, cycle.get(0),
                        "The loop " + String.join(" -> ", cycle) + " -> " + cycle.get(0)
                                + " has nothing bounding it. Route it through a For each, or give a "
                                + "node in it a maxIterations - a backward edge costs the guarantee "
                                + "that the run terminates."));
            }
        }
    }

    private static void checkTools(WorkflowGraph g, ToolCatalog tools, List<Problem> out) {
        for (WorkflowGraph.Node n : g.nodesOfType(NodeType.TOOL)) {
            String tool = n.configString("tool");
            if (tool == null || tool.isBlank()) {
                out.add(new Problem(Severity.ERROR, n.id(), "'" + label(n) + "' does not name a tool."));
                continue;
            }
            if (!tools.hasTool(tool)) {
                out.add(new Problem(Severity.ERROR, n.id(),
                        "'" + tool + "' is not a registered tool in this build."));
                continue;
            }
            Map<String, Object> args = n.configMap("arguments");
            for (String required : tools.requiredParameters(tool)) {
                Object value = args.get(required);
                if (value == null || String.valueOf(value).isBlank()) {
                    out.add(new Problem(Severity.ERROR, n.id(),
                            "'" + tool + "' requires '" + required + "', which is not set. A Tool node "
                                    + "has no model to fill it in - that is the point of one."));
                }
            }
        }
    }

    private static void checkCaps(WorkflowGraph g, List<Problem> out) {
        if (g.nodes().size() > MAX_NODES) {
            out.add(new Problem(Severity.ERROR, null,
                    "This workflow has " + g.nodes().size() + " nodes; the limit is " + MAX_NODES + "."));
        }
        long modelNodes = g.nodes().stream().filter(n -> !n.type().deterministic()).count();
        if (modelNodes > MAX_MODEL_NODES) {
            out.add(new Problem(Severity.ERROR, null,
                    "This workflow has " + modelNodes + " model-backed nodes; the limit is "
                            + MAX_MODEL_NODES + ". Each one is a provider call every time it is "
                            + "visited, and a loop multiplies that."));
        }
    }

    private static void checkAdvisories(WorkflowGraph g, List<Problem> out) {
        Set<String> referenced = new HashSet<>();
        for (WorkflowGraph.Node n : g.nodes()) referenced.addAll(referencesIn(n));

        for (WorkflowGraph.Node n : g.nodes()) {
            if (!n.type().deterministic() && !n.configList("tools").isEmpty()) {
                out.add(new Problem(Severity.WARNING, n.id(),
                        "'" + label(n) + "' is given tools, which makes it a small agent choosing its "
                                + "own arguments inside a workflow built to stop exactly that. Legal, "
                                + "and worth being deliberate about."));
            }
            boolean produces = n.type() != NodeType.RESULT && n.type() != NodeType.HALT
                    && n.type() != NodeType.INPUT && n.type() != NodeType.APPROVAL;
            if (produces && !referenced.contains(n.id())) {
                out.add(new Problem(Severity.WARNING, n.id(),
                        "Nothing reads the output of '" + label(n) + "'. Fine for a step that exists "
                                + "to have an effect; a mistake if you meant to use what it found."));
            }
        }
    }

    // - graph helpers -

    /** For every node, the set of nodes that run before it on EVERY path from the start. Computed
     *  as a dominator-style intersection rather than plain reachability, because "it can run
     *  before this" is not the guarantee a binding needs - a value produced on only one branch of a
     *  Decision is absent on the other. */
    private static Map<String, Set<String>> ancestors(WorkflowGraph g) {
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (WorkflowGraph.Node n : g.nodes()) {
            incoming.put(n.id(), new ArrayList<>());
            outgoing.put(n.id(), new ArrayList<>());
        }
        for (WorkflowGraph.Edge e : g.edges()) {
            if (incoming.containsKey(e.to()) && outgoing.containsKey(e.from())) {
                incoming.get(e.to()).add(e.from());
                outgoing.get(e.from()).add(e.to());
            }
        }

        List<WorkflowGraph.Node> inputs = g.nodesOfType(NodeType.INPUT);
        Map<String, Set<String>> dom = new LinkedHashMap<>();
        if (inputs.size() != 1) return dom;
        String start = inputs.get(0).id();

        Set<String> all = new LinkedHashSet<>(incoming.keySet());
        for (String id : all) dom.put(id, id.equals(start) ? new LinkedHashSet<>() : new LinkedHashSet<>(all));
        dom.get(start).clear();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : all) {
                if (id.equals(start)) continue;
                List<String> preds = incoming.get(id);
                Set<String> next = new LinkedHashSet<>();
                boolean first = true;
                for (String p : preds) {
                    Set<String> candidate = new LinkedHashSet<>(dom.getOrDefault(p, Set.of()));
                    candidate.add(p);
                    if (first) { next = candidate; first = false; }
                    else next.retainAll(candidate);
                }
                if (!next.equals(dom.get(id))) { dom.put(id, next); changed = true; }
            }
        }
        return dom;
    }

    /** Every simple cycle, found by DFS. Returned as node id lists in traversal order. */
    private static List<List<String>> cycles(WorkflowGraph g) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (WorkflowGraph.Node n : g.nodes()) outgoing.put(n.id(), new ArrayList<>());
        for (WorkflowGraph.Edge e : g.edges()) {
            if (outgoing.containsKey(e.from()) && outgoing.containsKey(e.to())) outgoing.get(e.from()).add(e.to());
        }

        List<List<String>> found = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String id : outgoing.keySet()) {
            if (!done.contains(id)) walk(id, outgoing, done, onStack, stack, found);
        }
        return found;
    }

    private static void walk(String id, Map<String, List<String>> outgoing, Set<String> done,
                             Set<String> onStack, Deque<String> stack, List<List<String>> found) {
        onStack.add(id);
        stack.push(id);
        for (String next : outgoing.getOrDefault(id, List.of())) {
            if (onStack.contains(next)) {
                List<String> cycle = new ArrayList<>();
                for (String s : stack) {
                    cycle.add(0, s);
                    if (s.equals(next)) break;
                }
                found.add(cycle);
            } else if (!done.contains(next)) {
                walk(next, outgoing, done, onStack, stack, found);
            }
        }
        stack.pop();
        onStack.remove(id);
        done.add(id);
    }

    /** Every {@code {{...}}} reference in a node's config, by referenced id. */
    static Set<String> referencesIn(WorkflowGraph.Node n) {
        Set<String> found = new LinkedHashSet<>();
        collect(n.config(), found);
        return found;
    }

    private static void collect(Object value, Set<String> found) {
        if (value instanceof String s) {
            Matcher m = BINDING.matcher(s);
            while (m.find()) found.add(m.group(1));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, found));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collect(v, found));
        }
    }

    private static String label(WorkflowGraph.Node n) {
        if (n.title() != null && !n.title().isBlank()) return n.title();
        return n.type().wire().toUpperCase(Locale.ROOT) + " " + n.id();
    }
}
