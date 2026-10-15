package ai.proxyma.connectors.box;

import ai.proxyma.core.connector.ConnectorException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;

/**
 * A Box Note's text, as markdown. Box publishes no format for reading a note - its Notes API only
 * turns markdown INTO one - so this reads the file's own JSON, in the two shapes Box has written:
 *
 * <ul>
 *   <li>The current editor's: a document tree under {@code doc} - nodes with a {@code type}
 *       ({@code paragraph}, {@code heading} with {@code attrs.level}, {@code bullet_list},
 *       {@code ordered_list}, {@code list_item}, {@code blockquote}, {@code code_block}, {@code table}
 *       and its rows and cells) whose leaves are {@code text} nodes.
 *   <li>The older editor's: the whole text under {@code atext.text}, its formatting kept apart.
 * </ul>
 *
 * <p>A node of a type not named here contributes its text and nothing else, so a new kind of block
 * is still searchable; a file that is neither shape is refused with a sentence rather than indexed as
 * JSON.
 */
final class BoxNoteText {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Deep enough for any note a person writes; a deeper tree is cut off rather than overflowing.
    private static final int MAX_DEPTH = 64;

    private BoxNoteText() {}

    static String markdown(byte[] note) {
        JsonNode root;
        try {
            root = JSON.readTree(note);
        } catch (IOException e) {
            root = null;
        }
        if (root != null && root.path("doc").isObject()) {
            StringBuilder out = new StringBuilder();
            blocks(root.path("doc").path("content"), out, 0, "");
            return tidy(out.toString());
        }
        if (root != null && root.path("atext").path("text").isTextual()) {
            return tidy(root.path("atext").path("text").asText());
        }
        throw new ConnectorException("Box did not give this Box Note in a form Proxyma reads, so it was not indexed.");
    }

    // Block nodes, each ending its own line(s). `prefix` starts every line (a quote's "> ").
    private static void blocks(JsonNode nodes, StringBuilder out, int depth, String prefix) {
        if (depth > MAX_DEPTH) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            switch (type) {
                case "heading" -> {
                    int level = Math.max(1, Math.min(6, node.path("attrs").path("level").asInt(1)));
                    out.append(prefix).append("#".repeat(level)).append(' ').append(inline(node, depth)).append("\n\n");
                }
                case "bullet_list", "ordered_list", "check_list" -> {
                    boolean ordered = "ordered_list".equals(type);
                    int number = 1;
                    for (JsonNode item : node.path("content")) {
                        String marker = ordered ? (number++) + ". " : "- ";
                        listItem(item, out, depth + 1, prefix, marker);
                    }
                    out.append('\n');
                }
                case "blockquote" -> blocks(node.path("content"), out, depth + 1, prefix + "> ");
                case "code_block" -> {
                    out.append(prefix).append("```\n").append(inline(node, depth)).append('\n')
                            .append(prefix).append("```\n\n");
                }
                case "horizontal_rule" -> out.append(prefix).append("---\n\n");
                case "table" -> {
                    for (JsonNode row : node.path("content")) {
                        StringBuilder line = new StringBuilder(prefix).append('|');
                        for (JsonNode cell : row.path("content")) line.append(' ').append(inline(cell, depth).replace('\n', ' ')).append(" |");
                        out.append(line).append('\n');
                    }
                    out.append('\n');
                }
                case "text", "hard_break" -> out.append(prefix).append(inline(node, depth)).append("\n\n");
                default -> {
                    // A paragraph, or a block this reader does not know: its text, then its blocks.
                    if (hasBlocks(node)) {
                        blocks(node.path("content"), out, depth + 1, prefix);
                    } else {
                        String text = inline(node, depth);
                        if (!text.isBlank()) out.append(prefix).append(text).append("\n\n");
                    }
                }
            }
        }
    }

    private static void listItem(JsonNode item, StringBuilder out, int depth, String prefix, String marker) {
        boolean first = true;
        for (JsonNode child : item.path("content")) {
            String type = child.path("type").asText("");
            if (type.endsWith("_list")) {
                StringBuilder nested = new StringBuilder();
                blocks(JsonNodeFactory.instance.arrayNode().add(child), nested, depth + 1, prefix + "  ");
                out.append(nested.toString().stripTrailing()).append('\n');
                continue;
            }
            String text = inline(child, depth);
            if (text.isBlank()) continue;
            out.append(prefix).append(first ? marker : "  ").append(text).append('\n');
            first = false;
        }
    }

    private static boolean hasBlocks(JsonNode node) {
        for (JsonNode child : node.path("content")) {
            String type = child.path("type").asText("");
            if (!"text".equals(type) && !"hard_break".equals(type) && child.has("content")) return true;
        }
        return false;
    }

    // A node's text, its children's run together; a hard break is a line end.
    private static String inline(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) return "";
        String type = node.path("type").asText("");
        if ("text".equals(type)) return node.path("text").asText("");
        if ("hard_break".equals(type)) return "\n";
        StringBuilder out = new StringBuilder();
        for (JsonNode child : node.path("content")) out.append(inline(child, depth + 1));
        return out.toString();
    }

    private static String tidy(String text) {
        return text.replace("\r\n", "\n").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip() + "\n";
    }
}
