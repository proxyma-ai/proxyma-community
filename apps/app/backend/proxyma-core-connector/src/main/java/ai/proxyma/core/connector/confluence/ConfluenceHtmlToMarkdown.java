package ai.proxyma.core.connector.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Confluence rendered HTML (body.view) to Markdown, over a parsed DOM.
 *
 * <p>This used to be a stack of regular expressions inside ConfluenceConnector, and a regex cannot
 * see nesting or containment - the two things that broke. A non-greedy {@code <li>...</li>} match
 * ends at the FIRST closing tag, so a list with a sub-list lost its outer item and a table inside a
 * cell ended the row early; and the bold/italic/link rewrites ran over the whole document, so a
 * {@code <pre>} block containing text that looks like markup came back edited - code altered by the
 * indexer is worse than code not indexed, because it still reads as the file.
 *
 * <p>jsoup is already on this module's classpath (the Web Page crawl connector parses with it), so
 * this adds no dependency. The traversal is deliberately its own small walker rather than the shared
 * HtmlContentExtractor: spec 02 section 5.3 pins an output shape that differs from the web-page one
 * (images become {@code [alt]} rather than an image link, every list marker is {@code -}), and the
 * whole point of this class is that the conversion is pinned by tests.
 */
final class ConfluenceHtmlToMarkdown {

    private ConfluenceHtmlToMarkdown() {
    }

    static String convert(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        StringBuilder out = new StringBuilder();
        renderNodes(doc.body().childNodes(), out, 0);
        return tidy(out.toString());
    }

    private static void renderNodes(List<Node> nodes, StringBuilder out, int listDepth) {
        for (Node node : nodes) {
            renderNode(node, out, listDepth);
        }
    }

    private static void renderNode(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode text) {
            out.append(collapseWhitespace(text.text()));
            return;
        }
        if (!(node instanceof Element el)) return;   // comments and doctypes carry nothing to index

        switch (el.tagName().toLowerCase(Locale.ROOT)) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = el.tagName().charAt(1) - '0';
                out.append("\n\n").append("#".repeat(level)).append(' ')
                   .append(inline(el, listDepth)).append("\n\n");
            }
            // Terminal on purpose: wholeText() keeps the whitespace and drops the highlighter's
            // markup, and nothing inside is rewritten, so code survives the round trip intact.
            case "pre" -> out.append("\n\n```\n").append(stripTrailingNewlines(el.wholeText()))
                             .append("\n```\n\n");
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                renderNodes(el.childNodes(), inner, listDepth);
                out.append("\n\n").append(prefixLines(tidy(inner.toString()))).append("\n\n");
            }
            case "table" -> out.append("\n\n").append(table(el)).append("\n\n");
            case "ul", "ol" -> {
                out.append('\n');
                renderList(el, out, listDepth);
                out.append('\n');
            }
            case "li" -> renderListItem(el, out, listDepth);   // an item outside any list
            case "p" -> {
                out.append("\n\n");
                renderNodes(el.childNodes(), out, listDepth);
                out.append("\n\n");
            }
            case "br" -> out.append('\n');
            case "hr" -> out.append("\n\n---\n\n");
            case "strong", "b" -> emphasis(el, out, listDepth, "**");
            case "em", "i" -> emphasis(el, out, listDepth, "*");
            case "code" -> {
                String code = collapseWhitespace(el.wholeText()).trim();
                if (!code.isEmpty()) out.append('`').append(code).append('`');
            }
            case "a" -> {
                String text = inline(el, listDepth);
                String href = el.attr("href").trim();
                if (text.isEmpty()) return;
                if (href.isEmpty()) out.append(text);
                else out.append('[').append(text).append("](").append(href).append(')');
            }
            case "img" -> {
                String alt = el.attr("alt").trim();
                if (!alt.isEmpty()) out.append('[').append(alt).append(']');   // no alt: dropped
            }
            case "script", "style" -> {
                // Never text a reader wants, and a macro's inline script would otherwise be indexed.
            }
            case "div", "section", "article", "header", "footer" -> {
                renderNodes(el.childNodes(), out, listDepth);
                out.append('\n');
            }
            default -> renderNodes(el.childNodes(), out, listDepth);   // tags removed, text kept
        }
    }

    private static void emphasis(Element el, StringBuilder out, int listDepth, String marker) {
        String inner = inline(el, listDepth);
        if (inner.isEmpty()) return;   // Confluence emits empty <strong> around macros; **** is noise
        out.append(marker).append(inner).append(marker);
    }

    private static String inline(Element el, int listDepth) {
        StringBuilder inner = new StringBuilder();
        renderNodes(el.childNodes(), inner, listDepth);
        return inner.toString().replaceAll("\\s+", " ").trim();
    }

    private static void renderList(Element list, StringBuilder out, int depth) {
        for (Element child : list.children()) {
            if (child.tagName().equalsIgnoreCase("li")) renderListItem(child, out, depth);
            else renderNode(child, out, depth);
        }
    }

    /**
     * One item per line, its sub-lists indented two spaces per level. The item's own text is
     * flattened onto that line: a Markdown list item that spans lines needs continuation indents
     * that nothing downstream would agree on, and an indexed paragraph reads the same either way.
     */
    private static void renderListItem(Element li, StringBuilder out, int depth) {
        StringBuilder line = new StringBuilder();
        StringBuilder nested = new StringBuilder();
        for (Node child : li.childNodes()) {
            if (child instanceof Element ce
                    && (ce.tagName().equalsIgnoreCase("ul") || ce.tagName().equalsIgnoreCase("ol"))) {
                renderList(ce, nested, depth + 1);
            } else {
                renderNode(child, line, depth);
            }
        }
        String text = line.toString().replaceAll("\\s+", " ").trim();
        out.append('\n').append("  ".repeat(depth)).append("- ").append(text);
        out.append(nested);
    }

    private static String table(Element table) {
        StringBuilder md = new StringBuilder();
        boolean headerDone = false;
        for (Element row : rowsOf(table)) {
            List<String> cells = new ArrayList<>();
            for (Element cell : cellsOf(row)) {
                // text() strips the inner tags and collapses the whitespace; a nested table
                // flattens into its cell rather than tearing the outer row in half.
                cells.add(cell.text().replace("|", "\\|").trim());
            }
            if (cells.isEmpty()) continue;
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (!headerDone) {
                md.append("| ").append(cells.stream().map(c -> "---").collect(Collectors.joining(" | ")))
                  .append(" |\n");
                headerDone = true;
            }
        }
        return md.toString().trim();
    }

    /** Rows of THIS table only - a nested table's rows belong to the cell they sit in. */
    private static List<Element> rowsOf(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element tr : table.getElementsByTag("tr")) {
            if (nearestAncestor(tr, "table") == table) rows.add(tr);
        }
        return rows;
    }

    private static List<Element> cellsOf(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element cell : row.children()) {
            String tag = cell.tagName().toLowerCase(Locale.ROOT);
            if (tag.equals("td") || tag.equals("th")) cells.add(cell);
        }
        return cells;
    }

    private static Element nearestAncestor(Element el, String tagName) {
        for (Element parent = el.parent(); parent != null; parent = parent.parent()) {
            if (parent.tagName().equalsIgnoreCase(tagName)) return parent;
        }
        return null;
    }

    private static String prefixLines(String text) {
        if (text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append("> ").append(lines[i]);
        }
        return sb.toString();
    }

    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        return text.substring(0, end);
    }

    /** HTML collapses runs of whitespace, and a decoded &nbsp; is one of them. */
    private static String collapseWhitespace(String text) {
        return text.replaceAll("[\\s\\u00a0]+", " ");
    }

    private static String tidy(String markdown) {
        return markdown.replace("\r\n", "\n")
                       .replaceAll("[ \t]+\n", "\n")
                       .replaceAll("\n{3,}", "\n\n")
                       .trim();
    }
}
