package ai.proxyma.core.chat.workflow.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What every node in a run has produced, and the resolution of {@code {{...}}} against it.
 *
 * <p>THIS IS THE ENGINE'S ANSWER TO "WHAT DID THIS STEP SEE". In the markdown runner a step saw
 * whatever was in the conversation thread, which is unauditable by construction - you cannot show
 * someone a paraphrase. Here a node's inputs are values in a map, and the map is a thing a person
 * can be shown after the fact. That is the property, and it is why bindings are explicit rather
 * than inherited.
 *
 * <p>Two resolution modes, and the difference matters for tool arguments:
 * <ul>
 *   <li>A string that is EXACTLY one binding resolves to the typed value - a number stays a number,
 *       a list stays a list. {@code maxResults: "{{count}}"} must not arrive at a tool as "42".
 *   <li>A binding embedded in text interpolates, because the surrounding characters mean the author
 *       wanted a string.
 * </ul>
 */
public final class RunScope {

    /** {{ ref }} or {{ ref.path.to.value }} or {{ ref.output.list[0].key }}. */
    private static final Pattern BINDING = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)((?:\\.[A-Za-z0-9_-]+|\\[\\d+])*)\\s*}}");

    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();

    public RunScope(Map<String, Object> inputs) {
        if (inputs != null) this.inputs.putAll(inputs);
    }

    public void put(String nodeId, Map<String, Object> output) {
        outputs.put(nodeId, output == null ? Map.of() : new LinkedHashMap<>(output));
    }

    public Map<String, Object> outputOf(String nodeId) {
        return outputs.getOrDefault(nodeId, Map.of());
    }

    /** Every node output so far, for the run's Values view and for a caller assembling a report. */
    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        outputs.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        return copy;
    }

    public Map<String, Object> inputs() {
        return Map.copyOf(inputs);
    }

    /**
     * Resolves a value that may be a binding, a string containing bindings, or a structure
     * containing either. Maps and lists are walked, so a tool's whole argument map resolves in one
     * call.
     */
    public Object resolve(Object value) {
        if (value instanceof String s) return resolveString(s);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolve(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(resolve(v)));
            return out;
        }
        return value;
    }

    /** Always a string - for an instruction or a prompt, where the point is the prose. */
    public String resolveText(Object value) {
        Object resolved = resolve(value);
        return resolved == null ? "" : String.valueOf(resolved);
    }

    private Object resolveString(String s) {
        Matcher m = BINDING.matcher(s);
        if (!m.find()) return s;
        // Exactly one binding and nothing else: keep the type.
        if (m.start() == 0 && m.end() == s.length()) {
            return lookup(m.group(1), m.group(2));
        }
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(m.group(1), m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : stringify(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves one reference.
     *
     * <p>A missing value is NULL rather than an exception, deliberately, and the validator is what
     * stops that mattering: it refuses a graph whose binding does not always run first, so a null
     * here means either an optional input nobody supplied or a hand-edited file that never went
     * through the editor. Failing the run on it would make an optional input impossible to express.
     */
    private Object lookup(String reference, String path) {
        Object base;
        if (outputs.containsKey(reference)) {
            base = outputs.get(reference);
        } else if (inputs.containsKey(reference)) {
            base = inputs.get(reference);
        } else {
            return null;
        }
        if (path == null || path.isEmpty()) return base;

        Object current = base;
        // `.key` and `[0]` in one walk. An input referenced as {{version}} has no path; a node
        // output is reached as {{collect.output.total}} - "output" being a literal segment, so the
        // shape a reader sees in the file is the shape the engine walks.
        Matcher seg = Pattern.compile("\\.([A-Za-z0-9_-]+)|\\[(\\d+)]").matcher(path);
        while (seg.find()) {
            if (current == null) return null;
            if (seg.group(1) != null) {
                String key = seg.group(1);
                if ("output".equals(key) && current == base && base instanceof Map<?, ?> m
                        && !m.containsKey("output")) {
                    // `output` is the conventional first segment of a node reference and the node's
                    // map IS its output, so it is transparent - unless a node genuinely produced a
                    // key called "output", in which case that wins and nothing is hidden.
                    continue;
                }
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(key);
            } else {
                int index = Integer.parseInt(seg.group(2));
                if (!(current instanceof List<?> list) || index >= list.size()) return null;
                current = list.get(index);
            }
        }
        return current;
    }

    /** Lists and maps interpolated into prose are rendered readably rather than as Java toString
     *  noise - what lands here is going into a model's instruction. */
    private static String stringify(Object v) {
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(item -> parts.add(String.valueOf(item)));
            return String.join(", ", parts);
        }
        return String.valueOf(v);
    }

    /** Every reference in a value, for the log's record of what a node actually read. */
    public static List<String> referencesIn(String s) {
        List<String> found = new ArrayList<>();
        if (s == null) return found;
        Matcher m = BINDING.matcher(s);
        while (m.find()) found.add(m.group(1) + m.group(2));
        return found;
    }
}
