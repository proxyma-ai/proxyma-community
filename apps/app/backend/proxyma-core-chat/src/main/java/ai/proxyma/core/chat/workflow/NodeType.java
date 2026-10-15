package ai.proxyma.core.chat.workflow;

import java.util.List;

/**
 * The kinds of node, and the out-ports each one has.
 *
 * <p>The port list is not decoration: it is what makes a node's control-flow behaviour visible on
 * the canvas without opening it, and it is what the validator checks against when it refuses to
 * save a graph with an unconnected branch.
 *
 * <p>{@code deterministic} is the property the whole engine exists to protect. DECISION and CLASSIFY
 * are two types rather than one for exactly this reason (see the engine proposal): a single node
 * that is sometimes code and sometimes a model is how a deterministic procedure stops being one
 * without anybody noticing. The editor renders the two groups differently, and this flag is what it
 * renders from.
 */
public enum NodeType {

    /** Declares the workflow's inputs. Config: {@code inputs} - a list of
     *  {name, type, required, description}. Exactly one per graph. */
    INPUT("input", true, List.of()),

    /** Invokes one registered tool directly, no model involved. Config: {@code tool} (the
     *  registered name) and {@code arguments} (a map of parameter name to literal or binding). */
    TOOL("tool", true, List.of()),

    /** One model call. Config: {@code instruction}, optional {@code tools} (a list of tool names -
     *  supplying any makes this a small ReAct loop, which the validator warns about), optional
     *  {@code maxIterations}, optional {@code outputSchema}. */
    LLM("llm", false, List.of()),

    /** A predicate over prior outputs, evaluated in Java. Config: {@code expression}. */
    DECISION("decision", true, List.of("true", "false")),

    /** A model picks one of the declared labels. Bounded - it can only choose from the set - but
     *  not deterministic. Config: {@code instruction} and {@code labels} (a list of strings); its
     *  out-ports are those labels, so they are resolved per node rather than from this enum. */
    CLASSIFY("classify", false, List.of()),

    /** Pauses the run and asks. Config: {@code prompt}. */
    APPROVAL("approval", true, List.of("approved", "denied")),

    /** Bounded iteration over a list from a prior output. Config: {@code items} (a binding) and
     *  {@code maxIterations}. The body runs once per item; `after` continues when it is done. */
    FOREACH("foreach", true, List.of("body", "after")),

    /** Ends the run cleanly, with a reason. Not a failure - a run that halts reads as HALTED, which
     *  is a different answer from COMPLETED and from FAILED. Config: {@code reason}. */
    HALT("halt", true, List.of()),

    /** The workflow's answer. Config: {@code value} - the binding whose resolved value IS the
     *  result. Exactly one per graph, because a graph can end on several paths and "the last thing
     *  that ran" is not an answer. */
    RESULT("result", true, List.of());

    private final String wire;
    private final boolean deterministic;
    private final List<String> ports;

    NodeType(String wire, boolean deterministic, List<String> ports) {
        this.wire = wire;
        this.deterministic = deterministic;
        this.ports = ports;
    }

    /** The lowercase name written to and read from the file. */
    public String wire() { return wire; }

    /** False for the two kinds whose behaviour a MODEL decides - LLM and CLASSIFY. */
    public boolean deterministic() { return deterministic; }

    /** Fixed out-ports. Empty means either one unnamed way out (INPUT, TOOL, LLM), or none at all
     *  (HALT, RESULT) - {@link #terminal()} separates those two cases. */
    public List<String> ports() { return ports; }

    /** Nothing may leave this node. */
    public boolean terminal() { return this == HALT || this == RESULT; }

    public static NodeType fromWire(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (NodeType t : values()) if (t.wire.equals(v)) return t;
        return null;
    }
}
