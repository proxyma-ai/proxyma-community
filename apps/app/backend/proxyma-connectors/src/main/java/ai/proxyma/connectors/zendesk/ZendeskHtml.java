package ai.proxyma.connectors.zendesk;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Help Center article's body - HTML - as markdown: headings, list items, paragraphs, line breaks,
 * links with their address, table cells apart, and the common entities decoded. Not a converter of
 * arbitrary pages: an article's body is an editor's output, and this keeps what a search and a reader
 * need from it - the words, where a heading or an item starts, and where a link goes.
 */
final class ZendeskHtml {

    private static final Pattern DROPPED = Pattern.compile("(?is)<(script|style|head)[^>]*>.*?</\\1>|<!--.*?-->");
    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>");
    private static final Pattern ITEM = Pattern.compile("(?i)<li[^>]*>");
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|ul|ol|table|blockquote|pre|section)>|<(p|div|ul|ol|table|blockquote|pre|section)(\\s[^>]*)?>");
    private static final Pattern ROW_END = Pattern.compile("(?i)</tr>");
    private static final Pattern CELL_END = Pattern.compile("(?i)</t[dh]>");
    private static final Pattern STRONG = Pattern.compile("(?is)<(strong|b)(\\s[^>]*)?>(.*?)</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private ZendeskHtml() {}

    static String toMarkdown(String html) {
        if (html == null || html.isBlank()) return "";
        String s = DROPPED.matcher(html).replaceAll("");
        s = replace(HEADING, s, m -> "\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " "
                + inline(m.group(2)) + "\n\n");
        s = replace(LINK, s, m -> {
            String text = inline(m.group(2));
            String href = m.group(1).strip();
            if (!href.startsWith("http://") && !href.startsWith("https://") || href.equals(text)) return text;
            return text.isEmpty() ? href : "[" + text + "](" + href + ")";
        });
        s = replace(STRONG, s, m -> {
            String text = inline(m.group(3));
            return text.isEmpty() ? "" : "**" + text + "**";
        });
        s = ITEM.matcher(s).replaceAll("\n- ");
        s = BREAK.matcher(s).replaceAll("\n");
        s = BLOCK_END.matcher(s).replaceAll("\n\n");
        s = ROW_END.matcher(s).replaceAll("\n");
        s = CELL_END.matcher(s).replaceAll(" | ");
        s = TAG.matcher(s).replaceAll("");
        s = entities(s);
        s = s.replace("\r\n", "\n").replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f]+\\n", "\n")
                .replaceAll("\\n[ \\t]+-", "\n-")
                .replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    // The text inside a heading or a link: its tags dropped, its lines joined. Its entities are
    // decoded with the rest of the body, once.
    private static String inline(String html) {
        return TAG.matcher(html).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    private static String entities(String s) {
        Matcher m = NUMERIC.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String decoded;
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                decoded = new String(Character.toChars(code));
            } catch (RuntimeException e) {
                decoded = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(out);
        return out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String replace(Pattern pattern, String s, Function<Matcher, String> with) {
        Matcher m = pattern.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(with.apply(m)));
        m.appendTail(out);
        return out.toString();
    }
}
