package ai.proxyma.core.chat.workflow;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link WorkflowGraph} back to YAML.
 *
 * <p>THE POINT OF THIS CLASS IS THE DIFF. Giving up markdown gave up something `workflows.md` and
 * `ChatSkillService` both valued out loud - a definition as diffable as a plain text file - and
 * what is recoverable of that has to be engineered rather than hoped for:
 *
 * <ul>
 *   <li><b>Fixed key order</b>, top level and per node, so a graph never reorders itself between
 *       saves.
 *   <li><b>Nodes sorted by id and edges sorted by (from, port, to)</b>, so moving a node on the
 *       canvas - which changes only x/y - does not reshuffle the file, and a two-node edit is a
 *       two-node diff.
 *   <li><b>Multi-line strings as literal blocks</b> rather than one long double-quoted line with
 *       \n escapes in it. An instruction is the thing an author most often edits and most wants to
 *       read in a review; the default representation makes both painful.
 *   <li><b>Empty and null fields omitted.</b> A workflow that inherits the chat model says nothing
 *       about a model at all, exactly as the markdown format omitted the keys - see #181.
 * </ul>
 */
public final class WorkflowGraphWriter {

    private WorkflowGraphWriter() {}

    public static String write(WorkflowGraph graph) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", WorkflowGraph.FORMAT_VERSION);
        root.put("name", graph.name() == null ? "" : graph.name());
        root.put("shortDescription", graph.shortDescription() == null ? "" : graph.shortDescription());
        if (graph.userId() != null) root.put("userId", graph.userId());
        root.put("shared", graph.shared());
        if (!graph.tags().isEmpty()) root.put("tags", graph.tags());
        // Provider only travels with a model, for the reason WorkflowService.normalizeModelChoice
        // records: an instance cannot be called without naming what to call on it.
        if (graph.model() != null) {
            if (graph.provider() != null) root.put("provider", graph.provider());
            root.put("model", graph.model());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        graph.nodes().stream()
                .sorted(Comparator.comparing(WorkflowGraph.Node::id))
                .forEach(n -> nodes.add(nodeMap(n)));
        root.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        graph.edges().stream()
                .sorted(Comparator.comparing(WorkflowGraph.Edge::from)
                        .thenComparing(e -> e.port() == null ? "" : e.port())
                        .thenComparing(WorkflowGraph.Edge::to))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", e.from());
                    if (e.port() != null) m.put("port", e.port());
                    m.put("to", e.to());
                    edges.add(m);
                });
        root.put("edges", edges);

        return yaml().dump(root);
    }

    private static Map<String, Object> nodeMap(WorkflowGraph.Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type().wire());
        if (n.title() != null && !n.title().isBlank()) m.put("title", n.title());
        m.put("x", n.x());
        m.put("y", n.y());
        if (n.model() != null) {
            if (n.provider() != null) m.put("provider", n.provider());
            m.put("model", n.model());
        }
        // Config keys keep the order they were parsed or built in. They are the node's own
        // vocabulary and there is no natural cross-type ordering to impose; what matters for the
        // diff is that the order is preserved rather than being re-derived each save.
        n.config().forEach((k, v) -> { if (v != null) m.put(k, detach(v)); });
        return m;
    }

    /**
     * Returns a structurally identical value that shares no object identity with the original.
     *
     * <p>NO ANCHORS. SnakeYAML emits {@code &id001} / {@code *id001} whenever the same INSTANCE is
     * dumped twice, and it decides that by identity, not equality. Two nodes handed the same tools
     * list - which a migration does, and any editor sharing an immutable list will do - come out as
     * an anchor and a reference. The file is still valid and still round-trips, but it is
     * unreadable and its diff is meaningless, which is the whole property this class exists for.
     * SnakeYAML 2.4 exposes no hook to turn aliasing off, so the fix is to hand it nothing it can
     * alias.
     *
     * <p>Strings are copied too, deliberately: two nodes holding the same interned literal are the
     * same instance, and that is enough to produce an anchor on a scalar. Only paid on save.
     */
    private static Object detach(Object value) {
        if (value instanceof String s) {
            return new String(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), detach(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(detach(v)));
            return copy;
        }
        return value;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(100);
        options.setSplitLines(false);
        return new Yaml(new LiteralBlockRepresenter(options), options);
    }

    /**
     * Emits any string containing a newline as a literal block ({@code |-}) instead of a quoted
     * one-liner with escapes.
     *
     * <p>Trailing whitespace on a line is stripped first: YAML cannot represent it in a literal
     * block, and SnakeYAML silently falls back to the quoted form for the whole scalar if it finds
     * any - so one stray trailing space on one line of a prompt would put the entire instruction
     * back on one unreadable line.
     */
    private static final class LiteralBlockRepresenter extends Representer {
        LiteralBlockRepresenter(DumperOptions options) {
            super(options);
        }


        @Override
        protected org.yaml.snakeyaml.nodes.Node representScalar(Tag tag, String value,
                                                                DumperOptions.ScalarStyle style) {
            if (tag.equals(Tag.STR) && value != null && value.contains("\n")) {
                String cleaned = String.join("\n",
                        java.util.Arrays.stream(value.split("\n", -1)).map(WorkflowGraphWriter::stripTrailing).toList());
                return super.representScalar(tag, cleaned, DumperOptions.ScalarStyle.LITERAL);
            }
            return super.representScalar(tag, value, style);
        }
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) end--;
        return line.substring(0, end);
    }
}
