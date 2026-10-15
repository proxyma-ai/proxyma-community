package ai.proxyma.core.chat.workflow;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a workflow file into a {@link WorkflowGraph}.
 *
 * <p>SnakeYAML rather than a hand-rolled reader, because it is already on the classpath (Spring
 * Boot brings it for application.yml) and because a YAML parser is the wrong thing to write
 * yourself - the escaping and block-scalar rules are where a hand-rolled one silently mangles an
 * author's prompt text.
 *
 * <p>{@link SafeConstructor}, not the default: a workflow file is user-authored content that this
 * process reads, and SnakeYAML's default constructor can instantiate arbitrary classes named in the
 * document. That is a deserialization gadget, and "the file came from our own directory" is not a
 * defence once export/import can put one there.
 *
 * <p>THIS PARSER DOES NOT VALIDATE. It refuses only what it cannot represent - a file that is not a
 * mapping, a node with no id, an unknown node type - and everything else is
 * {@link WorkflowGraphValidator}'s job. The split is deliberate: a graph with a dangling edge must
 * still LOAD, or the editor cannot open the file to fix it, and the author is left with a workflow
 * they can neither run nor repair.
 */
public final class WorkflowGraphParser {

    /** Guards against a malicious or accidental file exhausting memory before validation sees it. */
    private static final int MAX_ALIASES = 64;
    private static final int MAX_FILE_CHARS = 512_000;

    private WorkflowGraphParser() {}

    /** Thrown only for a file this class cannot turn into a graph at all. */
    public static class MalformedWorkflowException extends RuntimeException {
        public MalformedWorkflowException(String message) { super(message); }
        public MalformedWorkflowException(String message, Throwable cause) { super(message, cause); }
    }

    public static WorkflowGraph parse(String id, String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new MalformedWorkflowException("Workflow '" + id + "' is empty.");
        }
        if (yaml.length() > MAX_FILE_CHARS) {
            throw new MalformedWorkflowException(
                    "Workflow '" + id + "' is " + yaml.length() + " characters; the limit is "
                            + MAX_FILE_CHARS + ".");
        }

        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(MAX_ALIASES);
            options.setAllowDuplicateKeys(false);
            loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            // The message SnakeYAML produces names the line and column, which is the one thing an
            // author needs, so it is carried through rather than replaced.
            throw new MalformedWorkflowException(
                    "Workflow '" + id + "' is not valid YAML: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new MalformedWorkflowException(
                    "Workflow '" + id + "' should be a mapping of fields, not a "
                            + (loaded == null ? "blank document" : loaded.getClass().getSimpleName()) + ".");
        }

        Map<String, Object> map = asStringMap(root);
        return new WorkflowGraph(
                id,
                str(map.get("name"), ""),
                str(map.get("shortDescription"), ""),
                blankToNull(str(map.get("userId"), null)),
                Boolean.parseBoolean(str(map.get("shared"), "false")),
                stringList(map.get("tags")),
                blankToNull(str(map.get("provider"), null)),
                blankToNull(str(map.get("model"), null)),
                parseNodes(id, map.get("nodes")),
                parseEdges(id, map.get("edges")));
    }

    private static List<WorkflowGraph.Node> parseNodes(String id, Object raw) {
        List<WorkflowGraph.Node> nodes = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return List.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> node = asStringMap(m);
            String nodeId = str(node.get("id"), "");
            if (nodeId.isBlank()) {
                throw new MalformedWorkflowException(
                        "Workflow '" + id + "' has a node with no id. Every node needs one - it is "
                                + "what bindings refer to.");
            }
            NodeType type = NodeType.fromWire(str(node.get("type"), ""));
            if (type == null) {
                throw new MalformedWorkflowException(
                        "Workflow '" + id + "' node '" + nodeId + "' has an unknown type '"
                                + str(node.get("type"), "") + "'. This build understands: "
                                + String.join(", ", java.util.Arrays.stream(NodeType.values())
                                        .map(NodeType::wire).toList()) + ".");
            }
            // Everything not a known top-level field is the node's config. Reading it as "the
            // remainder" rather than from a nested `config:` key keeps the file readable - a tool
            // node's arguments sit directly under it - and means a key this build does not know is
            // carried rather than dropped, so a file written by a newer build round-trips.
            Map<String, Object> config = new LinkedHashMap<>(node);
            config.keySet().removeAll(List.of("id", "type", "title", "x", "y", "provider", "model"));
            nodes.add(new WorkflowGraph.Node(
                    nodeId,
                    type,
                    str(node.get("title"), ""),
                    intOf(node.get("x")),
                    intOf(node.get("y")),
                    blankToNull(str(node.get("provider"), null)),
                    blankToNull(str(node.get("model"), null)),
                    config));
        }
        return List.copyOf(nodes);
    }

    private static List<WorkflowGraph.Edge> parseEdges(String id, Object raw) {
        List<WorkflowGraph.Edge> edges = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return List.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> edge = asStringMap(m);
            String from = str(edge.get("from"), "");
            String to = str(edge.get("to"), "");
            if (from.isBlank() || to.isBlank()) {
                throw new MalformedWorkflowException(
                        "Workflow '" + id + "' has an edge missing its from or to.");
            }
            edges.add(new WorkflowGraph.Edge(from, blankToNull(str(edge.get("port"), null)), to));
        }
        return List.copyOf(edges);
    }

    // - helpers -

    private static Map<String, Object> asStringMap(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int intOf(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            String s = String.valueOf(item).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }
}
