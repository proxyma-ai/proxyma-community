package ai.proxyma.core.webcontent;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Shared HTML -> structured-content extraction (jsoup + flexmark + readability), used by both the
// webFetch tool (proxyma-core-tools) and the web-page crawl connector (proxyma-core-connector).
// Turns an already-parsed jsoup Document into Markdown (headings, lists, tables, inline links/images
// preserved) plus the page title, a Readability excerpt/byline for articles, a deduped list of
// distinct absolute links (for navigation / crawl discovery) and a deduped list of media URLs.
// Pure of any fetch/render concern - the caller has already produced the Document (static parse or
// a headless render) before calling here.
@Component
public class HtmlContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(HtmlContentExtractor.class);

    private static final int MAX_LINK_CANDIDATES = 50;
    private static final int MAX_MEDIA_CANDIDATES = 20;

    // Embedded non-content stripped from the conversion root before flexmark runs.
    private static final String STRIP_BEFORE_CONVERT = "script, style, noscript, template, svg, iframe";
    // Nav/header/footer chrome stripped when picking a markdown conversion root (readability-lite).
    private static final String NOISE_SELECTOR =
            "nav, header, footer, aside, form, [role=navigation], [role=banner], [role=contentinfo], [role=complementary]";
    // Preferred conversion root, in order - first match's subtree becomes the markdown body.
    private static final String MAIN_CONTENT_SELECTOR = "main, article, [role=main]";
    // Plain + lazy-loading src attributes, in priority order.
    private static final String[] SRC_ATTRS = {"src", "data-src", "data-original", "data-lazy-src", "data-lazy"};

    // Reusable, thread-safe HTML->Markdown converter (jsoup-based). ATX ('# ') headings so h3-h6
    // survive; GFM tables, nested lists, inline links/images, code, bold/italic on by default.
    private static final FlexmarkHtmlConverter HTML_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)).build();

    public record ExtractedContent(String markdown, String title, String excerpt, String byline,
                                   List<Map<String, Object>> links, List<String> mediaUrls) {}

    // Extracts from contentDoc; links/media additionally drawn from supplementalDoc when non-null
    // (e.g. a frame-resolved content doc plus the original shell, so shell nav/logo chrome still
    // surfaces). The document's baseUri (set at parse time) is used for Readability and to absolutize
    // relative URLs.
    public ExtractedContent extract(Document contentDoc, Document supplementalDoc) {
        String url = contentDoc.baseUri();
        List<Map<String, Object>> links = extractLinks(contentDoc, supplementalDoc);
        List<String> mediaUrls = extractMediaUrls(contentDoc, supplementalDoc);
        Article article = extractArticle(url, contentDoc);
        String markdown = articleMarkdown(article, contentDoc);
        String title = contentDoc.title();
        if ((title == null || title.isBlank()) && article != null) title = article.getTitle();
        return new ExtractedContent(markdown, title,
                article != null ? article.getExcerpt() : null,
                article != null ? article.getByline() : null,
                links, mediaUrls);
    }

    // Absolutizes a[href]/img[src] under the given root against its owner-document baseUri. Public so
    // a caller merging frame documents can fix relative URLs before moving nodes into another doc.
    public static void absolutizeUrls(Element root) {
        for (Element a : root.select("a[href]")) {
            String abs = a.absUrl("href");
            if (!abs.isBlank()) a.attr("href", abs);
        }
        for (Element img : root.select("img[src]")) {
            String abs = img.absUrl("src");
            if (!abs.isBlank()) img.attr("src", abs);
        }
    }

    // --- Main content extraction (Readability, with readability-lite fallback) --------

    private static Article extractArticle(String url, Document doc) {
        try {
            return new Readability4JExtended(url, doc.clone()).parse();
        } catch (Exception e) {
            log.debug("[HtmlContentExtractor] Readability extraction failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String articleMarkdown(Article article, Document doc) {
        String contentHtml = article != null ? article.getContent() : null;
        if (contentHtml != null && !contentHtml.isBlank()) {
            Element root = Jsoup.parse(contentHtml, doc.baseUri()).body();
            if (root != null && !root.text().isBlank()) return htmlToMarkdown(root);
        }
        return doc.body() != null ? htmlToMarkdown(mainContentRoot(doc)) : doc.text();
    }

    private static Element mainContentRoot(Document doc) {
        doc.select(NOISE_SELECTOR).remove();
        Element main = doc.selectFirst(MAIN_CONTENT_SELECTOR);
        return main != null ? main : doc.body();
    }

    // --- HTML to Markdown conversion (flexmark, jsoup-based) ---------------------

    private static String htmlToMarkdown(Element root) {
        root.select(STRIP_BEFORE_CONVERT).remove();
        absolutizeUrls(root);
        return normalizeMarkdown(HTML_CONVERTER.convert(root.outerHtml()));
    }

    private static String normalizeMarkdown(String md) {
        String result = md.replaceAll("(?m)[ \\t]+$", "");
        result = result.replaceAll("\n{3,}", "\n\n");
        return result.trim();
    }

    // --- Link extraction -----------------------------------------------------------

    // Distinct absolute links across the given document(s), deduped by url; null docs skipped.
    public static List<Map<String, Object>> extractLinks(Document... docs) {
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Document doc : docs) {
            if (doc == null) continue;
            for (Element a : doc.select("a[href]")) {
                if (links.size() >= MAX_LINK_CANDIDATES) return links;
                String href = a.absUrl("href");
                if (href.isBlank() || href.toLowerCase(Locale.ROOT).startsWith("javascript:") || !seen.add(href)) continue;
                String text = a.text();
                if (text.isBlank()) text = href;
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("text", text.length() > 200 ? text.substring(0, 200) : text);
                link.put("url", href);
                links.add(link);
            }
        }
        return links;
    }

    // --- Media extraction ------------------------------------------------------------

    private static List<String> extractMediaUrls(Document... docs) {
        Set<String> urls = new LinkedHashSet<>();
        for (Document doc : docs) {
            if (doc == null) continue;
            for (Element el : doc.select("img, video, audio, source")) {
                if (urls.size() >= MAX_MEDIA_CANDIDATES) return new ArrayList<>(urls);
                String abs = resolveMediaUrl(el);
                if (abs != null) urls.add(abs);
            }
        }
        return new ArrayList<>(urls);
    }

    private static String resolveMediaUrl(Element el) {
        for (String attr : SRC_ATTRS) {
            if (!el.hasAttr(attr)) continue;
            String abs = el.absUrl(attr);
            if (!abs.isBlank() && !abs.startsWith("data:")) return abs;
        }
        for (String attr : new String[]{"srcset", "data-srcset"}) {
            String rel = firstSrcsetUrl(el.attr(attr));
            if (rel == null || rel.startsWith("data:")) continue;
            String abs = resolveAgainstBase(el.baseUri(), rel);
            if (abs != null && !abs.isBlank()) return abs;
        }
        return null;
    }

    private static String firstSrcsetUrl(String srcset) {
        if (srcset == null || srcset.isBlank()) return null;
        String first = srcset.split(",", 2)[0].trim();
        if (first.isEmpty()) return null;
        int ws = first.indexOf(' ');
        return ws > 0 ? first.substring(0, ws) : first;
    }

    private static String resolveAgainstBase(String baseUri, String rel) {
        if (baseUri == null || baseUri.isBlank()) return rel;
        try {
            return URI.create(baseUri).resolve(rel).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
