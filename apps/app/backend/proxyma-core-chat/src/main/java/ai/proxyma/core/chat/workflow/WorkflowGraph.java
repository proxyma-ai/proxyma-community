package ai.proxyma.core.chat.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow as a typed graph: the format that replaces the markdown step list.
 *
 * <p>See proxyma-docs/proposals/workflow-design-page.md. Three things about this shape are
 * decisions rather than convenience, and changing any of them changes what the engine can promise:
 *
 * <ul>
 *   <li><b>An edge is ORDER, never data.</b> {@link Edge} carries no value. A node reads an earlier
 *       node's result by reference - {@code {{collect.output.total}}} - resolved against the run
 *       scope. Data therefore does not need an edge, and drawing one would encode the same fact in
 *       two places that can disagree.
 *   <li><b>Input and Result are NODES</b>, not top-level fields. Everything on a canvas needs an id
 *       and a position, and making the workflow's signature a pair of ordinary nodes means
 *       "exactly one Input" is a plain validation rule rather than a special case, and the file
 *       does not state the same thing twice.
 *   <li><b>A node's type-specific settings live in an untyped {@code config} map.</b> Nine node
 *       types with nine record shapes would need nine parse branches and a sealed hierarchy before
 *       a single one of them can execute. The keys each type reads are documented on
 *       {@link NodeType}, and the validator is what enforces them - which is also the only place
 *       that can report a missing key to the author with a message worth reading.
 * </ul>
 *
 * <p>Records, so a parsed graph is immutable: the interpreter will hold one for the length of a run
 * while the author may be editing the file underneath it, and a run must keep executing the
 * definition it started with.
 */
public record WorkflowGraph(
        /** The file's basename, as with the markdown format. Not stored inside the file. */
        String id,
        String name,
        String shortDescription,
        /** Owner, or null for a workflow that predates users / belongs to the deployment. */
        String userId,
        boolean shared,
        List<String> tags,
        /** Workflow-wide provider instance id and model, both nullable, both meaning "inherit the
         *  chat role". A node may override either - see #181, which introduced the same pair one
         *  level up. */
        String provider,
        String model,
        List<Node> nodes,
        List<Edge> edges) {

    /** The format version, written into every file. Bumped only for a change an older build could
     *  not read past - the same rule ConfigExport.Meta.format follows. */
    public static final int FORMAT_VERSION = 1;

    /**
     * One node.
     *
     * @param id       stable, author-visible, and the name bindings refer to
     *                 ({@code {{collect.output.x}}}). Never positional, unlike the markdown format's
     *                 step index - a graph has no single position to derive one from.
     * @param x        canvas coordinates. Layout, not semantics: two graphs differing only in x/y
     *                 execute identically. Kept in the same file rather than a sidecar so a
     *                 workflow stays one portable artifact.
     * @param provider per-node provider instance id, or null to inherit the workflow's
     * @param model    per-node model, or null to inherit. These two are the per-STEP model the
     *                 engine proposal deferred; here they cost a field rather than a feature.
     * @param config   type-specific settings. See {@link NodeType} for the keys each type reads.
     */
    public record Node(
            String id,
            NodeType type,
            String title,
            int x,
            int y,
            String provider,
            String model,
            Map<String, Object> config) {

        public Node {
            config = config == null ? Map.of() : Map.copyOf(config);
        }

        public String configString(String key) {
            Object v = config.get(key);
            return v == null ? null : String.valueOf(v);
        }

        @SuppressWarnings("unchecked")
        public List<Object> configList(String key) {
            Object v = config.get(key);
            return v instanceof List<?> list ? (List<Object>) list : List.of();
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> configMap(String key) {
            Object v = config.get(key);
            return v instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        }
    }

    /**
     * One control-flow edge.
     *
     * @param port which of the source's out-ports this leaves by - {@code "true"}/{@code "false"} on
     *             a Decision, a label on a Classify, {@code "approved"}/{@code "denied"} on an
     *             Approval, {@code "body"}/{@code "after"} on a For each. Null for a node with one
     *             way out, which is most of them.
     */
    public record Edge(String from, String port, String to) {}

    /** A declared workflow input, held in the Input node's config under {@code inputs}. */
    public record Input(String name, String type, boolean required, String description) {}

    public Node node(String id) {
        for (Node n : nodes) if (n.id().equals(id)) return n;
        return null;
    }

    public List<Node> nodesOfType(NodeType type) {
        return nodes.stream().filter(n -> n.type() == type).toList();
    }

    /** The declared inputs, read off the Input node. Empty when there is no Input node - the
     *  validator reports that; this returns a usable answer rather than throwing on a broken graph,
     *  because every caller here runs BEFORE validation has necessarily passed. */
    public List<Input> declaredInputs() {
        List<Node> inputs = nodesOfType(NodeType.INPUT);
        if (inputs.isEmpty()) return List.of();
        List<Input> out = new java.util.ArrayList<>();
        for (Object raw : inputs.get(0).configList("inputs")) {
            if (!(raw instanceof Map<?, ?> m)) continue;
            Object name = m.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            out.add(new Input(
                    String.valueOf(name),
                    m.get("type") == null ? "string" : String.valueOf(m.get("type")),
                    Boolean.parseBoolean(String.valueOf(m.get("required"))),
                    m.get("description") == null ? "" : String.valueOf(m.get("description"))));
        }
        return List.copyOf(out);
    }
}
