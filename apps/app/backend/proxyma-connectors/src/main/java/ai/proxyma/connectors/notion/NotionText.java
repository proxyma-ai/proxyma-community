package ai.proxyma.connectors.notion;

import ai.proxyma.core.connector.spi.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Notion's JSON as text: rich text, a database row's property values, and - for a page the
 * page-as-markdown endpoint will not render - the page's blocks as markdown, read recursively
 * within a depth and a count bound.
 */
final class NotionText {

    private NotionText() {}

    /** A rich text array as plain text, links kept as markdown links. */
    static String plain(JsonNode richText) {
        StringBuilder out = new StringBuilder();
        for (JsonNode part : richText) {
            String text = part.path("plain_text").asText(part.path("text").path("content").asText(""));
            String href = part.path("href").asText("");
            if (href.isBlank() || href.equals(text) || text.isBlank()) out.append(text);
            else out.append('[').append(text).append("](").append(href).append(')');
        }
        return out.toString();
    }

    /**
     * One property value as text - empty for a value that says nothing. {@code people} names a user
     * object (null where its name cannot be had).
     */
    static String property(JsonNode value, Function<JsonNode, String> people) {
        String type = value.path("type").asText("");
        JsonNode v = value.path(type);
        return switch (type) {
            case "title", "rich_text" -> plain(v);
            case "number" -> v.isNull() || v.isMissingNode() ? "" : number(v);
            case "select", "status" -> v.path("name").asText("");
            case "multi_select" -> names(v, n -> n.path("name").asText(""));
            case "date" -> date(v);
            case "people" -> names(v, people);
            case "created_by", "last_edited_by" -> {
                String name = people.apply(v);
                yield name == null ? "" : name;
            }
            case "files" -> names(v, NotionText::file);
            case "checkbox" -> v.asBoolean(false) ? "Yes" : "No";
            case "url", "email", "phone_number", "created_time", "last_edited_time" -> v.isNull() ? "" : v.asText("");
            case "formula" -> formula(v);
            case "relation" -> {
                int count = v.size();
                yield count == 0 ? "" : count == 1 ? "1 linked page" : count + " linked pages";
            }
            case "rollup" -> rollup(v, people);
            case "unique_id" -> {
                if (v.path("number").isMissingNode() || v.path("number").isNull()) yield "";
                String prefix = v.path("prefix").asText("");
                yield (prefix.isBlank() || v.path("prefix").isNull() ? "" : prefix + "-") + v.path("number").asText();
            }
            case "verification" -> v.path("state").asText("");
            default -> "";
        };
    }

    private static String number(JsonNode n) {
        if (n.isIntegralNumber()) return n.asText();
        double d = n.asDouble();
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : n.asText();
    }

    private static String date(JsonNode v) {
        if (v.isNull() || v.isMissingNode()) return "";
        String start = v.path("start").asText("");
        String end = v.path("end").isNull() ? "" : v.path("end").asText("");
        return end.isBlank() ? start : start + " - " + end;
    }

    private static String file(JsonNode f) {
        String name = f.path("name").asText("");
        String url = f.path("external").path("url").asText("");
        // A file Notion hosts has a link that expires within the hour - its name alone is kept.
        return url.isBlank() ? name : name + " (" + url + ")";
    }

    private static String formula(JsonNode v) {
        String type = v.path("type").asText("");
        JsonNode r = v.path(type);
        return switch (type) {
            case "string" -> r.isNull() ? "" : r.asText("");
            case "number" -> r.isNull() || r.isMissingNode() ? "" : number(r);
            case "boolean" -> r.asBoolean(false) ? "Yes" : "No";
            case "date" -> date(r);
            default -> "";
        };
    }

    private static String rollup(JsonNode v, Function<JsonNode, String> people) {
        String type = v.path("type").asText("");
        JsonNode r = v.path(type);
        return switch (type) {
            case "number" -> r.isNull() || r.isMissingNode() ? "" : number(r);
            case "date" -> date(r);
            case "array" -> {
                List<String> parts = new ArrayList<>();
                for (JsonNode element : r) {
                    String text = property(element, people);
                    if (!text.isBlank()) parts.add(text);
                }
                yield String.join(", ", parts);
            }
            default -> "";
        };
    }

    private static String names(JsonNode array, Function<JsonNode, String> name) {
        List<String> out = new ArrayList<>();
        for (JsonNode element : array) {
            String n = name.apply(element);
            if (n != null && !n.isBlank()) out.add(n.strip());
        }
        return String.join(", ", out);
    }

    // -- blocks, for a page the markdown endpoint will not render ------------------------------

    /**
     * A page's blocks as markdown, read with {@code GET /blocks/{id}/children} - one request per
     * block that holds blocks, so bounded: {@code maxDepth} levels, {@code maxBlocks} blocks.
     */
    static String blocks(ApiClient api, String pageId, int maxDepth, int maxBlocks) {
        StringBuilder md = new StringBuilder();
        int[] count = {0};
        render(api, pageId, 0, maxDepth, maxBlocks, count, md);
        if (count[0] >= maxBlocks) md.append("\n(This page is longer than Proxyma reads; the rest is not indexed.)\n");
        return md.toString();
    }

    private static void render(ApiClient api, String parentId, int depth, int maxDepth, int maxBlocks, int[] count,
                               StringBuilder md) {
        String indent = "  ".repeat(depth);
        Iterator<JsonNode> children = NotionApi.getPages(api, "blocks/" + parentId + "/children");
        int numbered = 0;
        while (children.hasNext() && count[0] < maxBlocks) {
            JsonNode block = children.next();
            if (NotionSession.isTrashed(block)) continue;
            count[0]++;
            String type = block.path("type").asText("");
            JsonNode b = block.path(type);
            String text = plain(b.path("rich_text"));
            numbered = type.equals("numbered_list_item") ? numbered + 1 : 0;
            switch (type) {
                case "paragraph" -> md.append(indent).append(text).append("\n\n");
                case "heading_1" -> md.append("# ").append(text).append("\n\n");
                case "heading_2" -> md.append("## ").append(text).append("\n\n");
                case "heading_3" -> md.append("### ").append(text).append("\n\n");
                case "bulleted_list_item" -> md.append(indent).append("- ").append(text).append('\n');
                case "numbered_list_item" -> md.append(indent).append(numbered).append(". ").append(text).append('\n');
                case "to_do" -> md.append(indent).append(b.path("checked").asBoolean(false) ? "- [x] " : "- [ ] ")
                        .append(text).append('\n');
                case "toggle" -> md.append(indent).append("- ").append(text).append('\n');
                case "quote" -> md.append(indent).append("> ").append(text.replace("\n", "\n> ")).append("\n\n");
                case "callout" -> md.append(indent).append("> ").append(text.replace("\n", "\n> ")).append("\n\n");
                case "code" -> md.append("```").append(b.path("language").asText("").toLowerCase(Locale.ROOT))
                        .append('\n').append(text).append("\n```\n\n");
                case "equation" -> md.append("$$").append(b.path("expression").asText("")).append("$$\n\n");
                case "divider" -> md.append("---\n\n");
                case "child_page" -> md.append(indent).append("Page: ").append(b.path("title").asText("")).append("\n\n");
                case "child_database" -> md.append(indent).append("Database: ").append(b.path("title").asText(""))
                        .append("\n\n");
                case "bookmark", "embed", "link_preview" -> {
                    String url = b.path("url").asText("");
                    String caption = plain(b.path("caption"));
                    md.append(indent).append(caption.isBlank() ? url : "[" + caption + "](" + url + ")").append("\n\n");
                }
                case "image", "file", "pdf", "video", "audio" -> {
                    String name = b.path("name").asText("");
                    String caption = plain(b.path("caption"));
                    String said = !caption.isBlank() ? caption : name;
                    if (!said.isBlank()) md.append(indent).append('(').append(type).append(": ").append(said).append(")\n\n");
                }
                case "table_row" -> {
                    List<String> cells = new ArrayList<>();
                    for (JsonNode cell : b.path("cells")) cells.add(plain(cell).replace("|", "\\|").replace("\n", " "));
                    md.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
                default -> {
                    if (!text.isBlank()) md.append(indent).append(text).append("\n\n");
                }
            }
            boolean nested = block.path("has_children").asBoolean(false) && !type.equals("child_page")
                    && !type.equals("child_database");
            if (nested && depth + 1 < maxDepth) {
                boolean table = type.equals("table");
                render(api, block.path("id").asText(""), table ? depth : depth + 1, maxDepth, maxBlocks, count, md);
                if (table) md.append('\n');
            }
        }
    }
}
