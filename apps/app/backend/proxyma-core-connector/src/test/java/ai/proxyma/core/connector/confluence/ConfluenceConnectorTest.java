package ai.proxyma.core.connector.confluence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What a Confluence page body converts to - every case here is a bug the converter shipped with. What
 * a sync asks Confluence for, and when, is the native session's, tested in proxyma-connectors.
 */
class ConfluenceConnectorTest {

    // --- HTML to Markdown, spec 02 acceptance test 4 -----------------------------

    @Test
    @DisplayName("a nested list keeps its outer item and indents the inner one")
    void nestedList() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<ul><li>Install<ul><li>Windows</li><li>Linux</li></ul></li><li>Configure</li></ul>");
        assertEquals("- Install\n  - Windows\n  - Linux\n- Configure", markdown);
    }

    @Test
    @DisplayName("a table becomes GFM, with a pipe inside a cell escaped")
    void tableWithPipe() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<table><tr><th>Name</th><th>Pattern</th></tr>"
                + "<tr><td>Pipe</td><td>a | b</td></tr></table>");
        assertEquals("| Name | Pattern |\n| --- | --- |\n| Pipe | a \\| b |", markdown);
    }

    @Test
    @DisplayName("a nested table flattens into its cell instead of tearing the outer row in half")
    void nestedTable() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<table><tr><td>Outer</td><td><table><tr><td>Inner</td></tr></table></td></tr></table>");
        assertEquals("| Outer | Inner |\n| --- | --- |", markdown);
    }

    @Test
    @DisplayName("a code block containing markup comes back byte for byte")
    void codeBlockIsNotRewritten() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<p>Before</p>"
                + "<pre><code>&lt;b&gt;not bold&lt;/b&gt; and *stars* and [link](x)</code></pre>"
                + "<p>After</p>");
        assertEquals("Before\n\n```\n<b>not bold</b> and *stars* and [link](x)\n```\n\nAfter", markdown);
    }

    @Test
    @DisplayName("inline markup outside a code block still converts")
    void inlineMarkup() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<p><strong>Bold</strong> and <em>italic</em> and "
                + "<a href=\"https://wiki.example.com/x\">a link</a>.</p>");
        assertEquals("**Bold** and *italic* and [a link](https://wiki.example.com/x).", markdown);
    }

    @Test
    @DisplayName("headings, entities and images follow the spec's shapes")
    void headingsEntitiesAndImages() {
        String markdown = ConfluenceHtmlToMarkdown.convert(
                "<h2>Title &amp; subtitle</h2><p>a&nbsp;b</p>"
                + "<p><img src=\"/x.png\" alt=\"Diagram\"><img src=\"/y.png\"></p>");
        assertEquals("## Title & subtitle\n\na b\n\n[Diagram]", markdown);
        assertNotNull(ConfluenceHtmlToMarkdown.convert(""));
        assertEquals("", ConfluenceHtmlToMarkdown.convert(null));
    }
}
