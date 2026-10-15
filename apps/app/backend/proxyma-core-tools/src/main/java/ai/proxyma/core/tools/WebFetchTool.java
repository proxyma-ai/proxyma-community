package ai.proxyma.core.tools;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.ingest.service.DocumentConversionService;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Fetches any given http(s) URL - not restricted to the public internet. Always routes through
// the same proxy-aware externalRestClientBuilder every connector (Jira/Confluence/etc.) already
// uses (see RestClientConfig in proxyma-core-network), so Settings > Network / HTTPS_PROXY is
// respected with no tool-specific proxy code, meaning it can reach intranet/internal URLs too as
// long as the deployment's network/proxy config permits it. WebMediaStorage's RestClient sends a
// real browser User-Agent/Accept headers (see its own docs) - a meaningful share of real sites
// otherwise reject or degrade responses for the JDK HttpClient's default identifying header alone,
// independent of any deeper TLS-fingerprinting-level bot detection headers can't defeat anyway.
// HTML pages are parsed with Jsoup (charset sniffed from the response/document, never assumed
// UTF-8) and converted to Markdown by flexmark's jsoup-based FlexmarkHtmlConverter (not regex -
// the only prior HTML->text conversion in this codebase, ConfluenceConnector.htmlToMarkdown, is
// hand-rolled regex over trusted Confluence body.view output and isn't shaped for arbitrary/
// malformed third-party HTML) into structured
// Markdown (headings, lists, tables, inline links/images), preferring a semantic main-content
// landmark over the whole <body> and stripping nav/header/footer chrome first (readability-lite -
// see mainContentRoot) so real articles aren't diluted by site furniture. A separate deduped
// `links` list (extracted from the UNSTRIPPED document) lets an agent see page layout and navigate
// to sub-pages, not just a flattened text blob. Any img/video/audio/source URLs found are also
// downloaded (bounded) and saved via WebMediaStorage; a direct media URL (Content-Type already
// image/video/audio) is downloaded as the sole item, and a direct PDF URL is run through the same
// pdfplumber-based table-aware extraction ConvertDocumentTool uses for uploaded PDFs, not returned
// as raw bytes decoded as text.
//
// Two tools, so the AGENT chooses the fetch strategy instead of the code guessing "does this page
// need JS?" (a doc-generator shell carries hundreds of chars of chrome and reads as content-rich,
// so any body-text threshold misfires):
//  - webFetch (light, the default): static fetch only, no browser. For HTML it additionally recovers
//    framed / hash-deep-linked content deterministically over plain HTTP (resolveShellContent -
//    fetches the sub-document behind a <frame>/<iframe> or a hash deep link into a doc generator,
//    e.g. MadCap Flare's product.htm#Path/Topic.htm). Fast; handles most pages.
//  - renderWebFetch (heavy): drives the page in a real headless browser (runs its JS, follows the
//    hash route, renders SPAs, stitches iframe content), returning the same result shape. The agent
//    uses it when a webFetch result looks like a JS shell / is thin. adoptIfNotWorse keeps the static
//    DOM if the render is blocked/empty and yields less, so a render never loses content.
// Rendering (renderWebFetch only) is gated by proxyma.web-fetch.js-rendering (auto|off) and the
// engine by proxyma.web-fetch.js-engine (htmlunit|playwright|auto, default htmlunit): Playwright is a
// real Chromium needing an installed browser binary (opt-in, self-disables where absent - see
// PlaywrightRenderService), HtmlUnit is the pure-Java fallback used on the desktop build.
@Component
public class WebFetchTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    // Bounds the primary page fetch itself, independent of WebMediaStorage's own per-item cap on
    // the media it downloads afterward - a 20MB HTML/text response is already pathological.
    private static final long MAX_FETCH_BYTES = 20L * 1024 * 1024;
    // Sanity bound on extracted markdown so a single degenerate page can't build a multi-megabyte
    // string in memory - large results still go through ChatService's normal >8000-token
    // offload-to-file wrapper regardless, this just guards the extraction step itself.
    private static final int MAX_TEXT_CHARS = 500_000;

    private final WebMediaStorage webMediaStorage;
    private final DocumentConversionService documentConversionService;
    // Shared render engine (Playwright/HtmlUnit) and HTML->Markdown/links extraction, both in
    // proxyma-core-webcontent so the web-page crawl connector reuses the exact same code.
    private final WebRenderService webRenderService;
    private final HtmlContentExtractor htmlContentExtractor;

    public WebFetchTool(WebMediaStorage webMediaStorage,
                        DocumentConversionService documentConversionService,
                        WebRenderService webRenderService,
                        HtmlContentExtractor htmlContentExtractor) {
        this.webMediaStorage = webMediaStorage;
        this.documentConversionService = documentConversionService;
        this.webRenderService = webRenderService;
        this.htmlContentExtractor = htmlContentExtractor;
    }

    @Tool(description = "Fetch an http(s) URL without a browser (fast, no JavaScript). HTML becomes Markdown in "
            + "textContent, with title, excerpt, links and media saved to a mediaUrl; PDFs get text and tables. "
            + "truncated:true is a size cap, not a failure. Near-empty JavaScript shell: retry with renderWebFetch.")
    @ToolResult({
            @ToolResultField(name = "url", type = ToolFieldType.STRING, always = true,
                    description = "The URL fetched."),
            @ToolResultField(name = "title", type = ToolFieldType.STRING, always = true, nullable = true,
                    description = "Page title, null for a PDF or image which has none."),
            @ToolResultField(name = "textContent", type = ToolFieldType.STRING, always = true, nullable = true,
                    description = "Extracted text as markdown, null when the response was an image."),
            @ToolResultField(name = "links", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Links found on the page. Empty for non-HTML responses."),
            @ToolResultField(name = "media", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Stored media, each with type, mediaUrl, originalUrl and sizeBytes."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "True when textContent is incomplete: the download hit 20 MB or the text "
                            + "passed 500,000 characters. Absent for an image response."),
            @ToolResultField(name = "excerpt", type = ToolFieldType.STRING,
                    description = "Short summary, present only for an article-like HTML page."),
            @ToolResultField(name = "byline", type = ToolFieldType.STRING, nullable = true,
                    description = "Author line, present only for an article-like HTML page."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the response was a media type that is not saved.")
    })
    public Map<String, Object> webFetch(@ToolParam(description = "Full http(s) URL") String url) {
        return fetchToMap(url, false);
    }

    @Tool(description = "Fetch an http(s) URL in a headless browser: runs JavaScript, hash routes, single-page "
            + "apps, iframes. Same result as webFetch but much slower - only after webFetch returned a JavaScript "
            + "shell or near-empty page.")
    @ToolResult({
            @ToolResultField(name = "url", type = ToolFieldType.STRING, always = true,
                    description = "The URL fetched."),
            @ToolResultField(name = "title", type = ToolFieldType.STRING, always = true, nullable = true,
                    description = "Page title, null for a PDF or image which has none."),
            @ToolResultField(name = "textContent", type = ToolFieldType.STRING, always = true, nullable = true,
                    description = "Extracted text as markdown, null when the response was an image."),
            @ToolResultField(name = "links", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Links found on the page. Empty for non-HTML responses."),
            @ToolResultField(name = "media", type = ToolFieldType.OBJECT_ARRAY, always = true,
                    description = "Stored media, each with type, mediaUrl, originalUrl and sizeBytes."),
            @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN,
                    description = "True when textContent is incomplete: the download hit 20 MB or the text "
                            + "passed 500,000 characters. Absent for an image response."),
            @ToolResultField(name = "excerpt", type = ToolFieldType.STRING,
                    description = "Short summary, present only for an article-like HTML page."),
            @ToolResultField(name = "byline", type = ToolFieldType.STRING, nullable = true,
                    description = "Author line, present only for an article-like HTML page."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Present when the response was a media type that is not saved.")
    })
    public Map<String, Object> renderWebFetch(@ToolParam(description = "Full http(s) URL") String url) {
        return fetchToMap(url, true);
    }

    // Shared body for both @Tool methods. render=false -> light (static + deterministic frame/hash
    // resolution, no browser); render=true -> heavy (real-browser render, see processBody).
    private Map<String, Object> fetchToMap(String url, boolean render) {
        if (url == null || !url.matches("(?i)^https?://.+")) {
            return Map.of("success", false, "error", "url must be a full http:// or https:// address");
        }
        try {
            // Refused up front on a deployment that blocks private addresses (the public sandbox),
            // so the model is told why rather than handed a timeout. Redirects are checked per hop
            // by the shared client; see OutboundAddressPolicy.
            OutboundAddressPolicy.check(url);
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
        try {
            WebMediaStorage.FetchedBody body = webMediaStorage.fetchCapped(url, MAX_FETCH_BYTES);
            if (body == null) {
                return Map.of("success", false, "error", "Server returned an empty response body");
            }
            return processBody(url, body, render);
        } catch (WebMediaStorage.FetchException e) {
            log.warn("[WebFetchTool] failed to fetch {}: {}", url, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", Failures.describe(e));
            if (e.statusCode() != null) result.put("statusCode", e.statusCode());
            return result;
        } catch (Exception e) {
            log.warn("[WebFetchTool] failed to fetch {}: {}", url, e.getMessage());
            return Map.of("success", false, "error", Failures.describe(e, "fetch failed"));
        }
    }

    // --- MCP entry point -------------------------------------------------------------

    // Either raw image bytes (imageBytes/imageMimeType set, textResult null) or the normal
    // structured result (textResult set, imageBytes null) - never both. See webFetchForMcp.
    public record McpFetchResult(byte[] imageBytes, String imageMimeType, Map<String, Object> textResult) {
        public boolean isImage() { return imageBytes != null; }
    }

    // MCP-protocol variant of webFetch (light) and renderWebFetch (heavy). An MCP client has no
    // conversation folder to save media into and renders images from an MCP ImageContent block, not
    // from a conversation media URL, so a direct-image URL comes back here as RAW BYTES + mime for the
    // caller (WebFetchMcpToolConfig) to wrap as ImageContent - instead of the saveDirect() path, which
    // returns an empty `media` for a null conversation id (i.e. silently drops the image over MCP).
    // Everything else (HTML/PDF/text, and any failure) flows through the exact same processBody() as
    // the chat path and is returned as the normal structured map. Fetches once.
    public McpFetchResult webFetchForMcp(String url) {
        return fetchForMcp(url, false);
    }

    public McpFetchResult renderWebFetchForMcp(String url) {
        return fetchForMcp(url, true);
    }

    private McpFetchResult fetchForMcp(String url, boolean render) {
        if (url == null || !url.matches("(?i)^https?://.+")) {
            return new McpFetchResult(null, null,
                    Map.of("success", false, "error", "url must be a full http:// or https:// address"));
        }
        try {
            OutboundAddressPolicy.check(url);
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            return new McpFetchResult(null, null, Map.of("success", false, "error", e.getMessage()));
        }
        try {
            WebMediaStorage.FetchedBody body = webMediaStorage.fetchCapped(url, MAX_FETCH_BYTES);
            if (body == null) {
                return new McpFetchResult(null, null,
                        Map.of("success", false, "error", "Server returned an empty response body"));
            }
            String contentType = body.contentType() == null ? "" : body.contentType().toLowerCase();
            // Only still images map to MCP ImageContent - video/audio have no inline MCP content
            // block here, so they fall through to processBody() and its normal (text) handling.
            if (contentType.startsWith("image/") && !body.truncated()) {
                return new McpFetchResult(body.bytes(), cleanImageMime(body.contentType()), null);
            }
            return new McpFetchResult(null, null, processBody(url, body, render));
        } catch (WebMediaStorage.FetchException e) {
            log.warn("[WebFetchTool] failed to fetch {}: {}", url, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", Failures.describe(e));
            if (e.statusCode() != null) result.put("statusCode", e.statusCode());
            return new McpFetchResult(null, null, result);
        } catch (Exception e) {
            log.warn("[WebFetchTool] failed to fetch {}: {}", url, e.getMessage());
            return new McpFetchResult(null, null,
                    Map.of("success", false, "error", Failures.describe(e, "fetch failed")));
        }
    }

    // Strips any charset/parameters from an image Content-Type so ImageContent carries a clean
    // "image/png" style mime. Only ever called on an already image/*-prefixed value.
    private static String cleanImageMime(String contentType) {
        if (contentType == null || contentType.isBlank()) return "image/png";
        int semi = contentType.indexOf(';');
        String mime = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? "image/png" : mime;
    }

    // Everything the primary fetch does once the response bytes are in hand: dispatch on
    // Content-Type (direct media -> saveDirect; PDF -> table-aware extraction; plain text -> as-is;
    // HTML -> Jsoup parse + link/media/markdown extraction). `render` selects the HTML content path:
    // false = light (static + deterministic frame/hash resolution, no browser - the webFetch tool);
    // true = heavy (real-browser render - the renderWebFetch tool). Non-HTML dispatch is identical
    // for both. Shared by the @Tool/chat methods and the MCP methods; may throw (PDF extraction) -
    // all callers wrap it in the same try/catch.
    private Map<String, Object> processBody(String url, WebMediaStorage.FetchedBody body, boolean render) throws Exception {
        String contentType = body.contentType() == null ? "" : body.contentType().toLowerCase();

        if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("audio/")) {
            if (body.truncated()) {
                // A partial media file is corrupt, not merely incomplete - unlike HTML/text below,
                // there is no usable "prefix" to hand back, so this genuinely is a failure.
                return Map.of("success", false, "error",
                        "File exceeded the " + (MAX_FETCH_BYTES / (1024 * 1024)) + "MB fetch limit");
            }
            WebMediaStorage.SavedMedia saved = webMediaStorage.saveDirect(url, body.contentType(), body.bytes());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("url", url);
            result.put("title", null);
            result.put("textContent", null);
            result.put("links", List.of());
            result.put("media", saved == null ? List.of() : List.of(toMap(saved)));
            if (WebMediaStorage.savedExtension(body.contentType()) == null) {
                result.put("note", "This file type is not saved or shown; only common raster images, "
                        + "MP4/WebM video and common audio formats are.");
            }
            return result;
        }

        // Content-Type is the primary signal, but some servers mislabel a PDF as
        // application/octet-stream (or omit the header) - fall back to the "%PDF-" magic bytes
        // so those still get real extraction instead of being decoded as garbage text below.
        if (contentType.contains("pdf") || looksLikePdf(body.bytes())) {
            if (body.truncated()) {
                return Map.of("success", false, "error",
                        "PDF exceeded the " + (MAX_FETCH_BYTES / (1024 * 1024)) + "MB fetch limit");
            }
            String pdfText = extractPdfText(body.bytes());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("url", url);
            result.put("title", null);
            result.put("textContent", cap(pdfText));
            result.put("links", List.of());
            result.put("media", List.of());
            result.put("truncated", overCap(pdfText));
            return result;
        }

        String charset = extractCharset(body.contentType());

        if (!contentType.contains("html")) {
            // Plain text / JSON / etc - return as-is (bounded), no structure/media extraction attempted.
            Charset cs = charset != null ? Charset.forName(charset) : StandardCharsets.UTF_8;
            String text = new String(body.bytes(), cs);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("url", url);
            result.put("title", null);
            result.put("textContent", cap(text));
            result.put("links", List.of());
            result.put("media", List.of());
            result.put("truncated", body.truncated() || overCap(text));
            return result;
        }

        // charset=null lets Jsoup sniff the real charset from the document's own <meta> tags
        // (HTTP header takes priority when present above) instead of assuming UTF-8, which
        // otherwise mojibake's every page served in a different encoding.
        Document doc = Jsoup.parse(new ByteArrayInputStream(body.bytes()), charset, url);
        Document shell = doc;

        // Which content path, decided by the CALLER'S tool choice - never by guessing whether this
        // page "looks like a shell" from its body-text length (a doc-generator shell carries hundreds
        // of chars of chrome and reads as content-rich, so any such threshold misfires):
        //  - render=true (renderWebFetch): drive the page in a real headless browser - it runs the
        //    JS, follows the hash route (the '#...' a server GET drops) and via stitched frames
        //    captures navigation + iframe content. adoptIfNotWorse keeps the static DOM if the render
        //    is blocked/empty and yields less, so a render never loses content; if no browser is
        //    available/rendered nothing, fall through to the same deterministic recovery as the light
        //    path.
        //  - render=false (webFetch): light - just recover framed/hash-deep-linked content
        //    deterministically over plain HTTP (resolveShellContent), no browser.
        boolean rendered = false;
        if (render) {
            Document r = adoptIfNotWorse(webRenderService.render(url), url, doc);
            if (r != doc) { doc = r; rendered = true; }
        }
        if (!rendered) {
            Document resolved = resolveShellContent(url, doc);
            if (resolved != null) doc = resolved;
        }
        // HTML->Markdown + link/media extraction via the shared HtmlContentExtractor. When a shell was
        // resolved to a content doc, the original shell is also passed so its nav/logo chrome still
        // surfaces in links/media (deduped).
        Document supplemental = doc == shell ? null : shell;
        HtmlContentExtractor.ExtractedContent extracted = htmlContentExtractor.extract(doc, supplemental);
        List<WebMediaStorage.SavedMedia> saved = webMediaStorage.downloadAndSave(extracted.mediaUrls());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("url", url);
        result.put("title", extracted.title());
        result.put("textContent", cap(extracted.markdown()));
        result.put("excerpt", extracted.excerpt());
        result.put("byline", extracted.byline());
        result.put("links", extracted.links());
        result.put("media", saved.stream().map(WebFetchTool::toMap).toList());
        result.put("truncated", body.truncated() || overCap(extracted.markdown()));
        return result;
    }

    // Parses rendered HTML and adopts it over `current` only if it has at least as much visible text
    // (>=, so an equally-sized render still wins - the browser DOM is the more faithful source). A
    // null render (engine disabled/unavailable) or a headless-blocked render that came back with LESS
    // than the static page is rejected, so rendering never loses content. Returns `current` unchanged
    // in those cases (callers use identity to detect whether a render was adopted).
    private static Document adoptIfNotWorse(String renderedHtml, String url, Document current) {
        if (renderedHtml == null || renderedHtml.isBlank()) return current;
        Document r = Jsoup.parse(renderedHtml, url);
        if (r.body() == null) return current;
        int renderedLen = r.body().text().length();
        int currentLen = current.body() != null ? current.body().text().length() : 0;
        if (renderedLen >= currentLen) {
            log.debug("[WebFetchTool] adopted rendered DOM for {} ({} chars vs {} static)",
                    url, renderedLen, currentLen);
            return r;
        }
        return current;
    }

    // --- Shell / frame content resolution (Layer 1, no headless browser) --------------

    // Below this much visible body text a page may be a content-light shell whose real content is
    // hosted elsewhere (a frame, or a hash-routed sub-document). Higher than JS_EMPTY_TEXT_THRESHOLD
    // because a shell can still carry boilerplate chrome (a comment/login widget, cookie banner).
    private static final int SHELL_TEXT_THRESHOLD = 500;
    // Bounds the extra GETs a single shell page can trigger while resolving its content.
    private static final int MAX_SHELL_CANDIDATES = 4;
    private static final int MAX_FRAMESET_FRAMES = 5;

    // When the served page is a content-light shell, fetch the sub-document that holds its real
    // content and return it (else null -> keep the shell). Two shapes are handled: a classic
    // <frameset> (no <body>, content split across <frame> docs -> merged), and a content-light body
    // that hosts an <iframe>/<frame> or was reached via a hash deep link naming a topic document.
    // Every candidate is fetched over plain HTTP (bounded, same-origin) and adopted only if it
    // yields more visible text than the shell, so a wrong guess can never replace real content.
    private Document resolveShellContent(String pageUrl, Document doc) {
        if (doc.body() == null || doc.selectFirst("frameset") != null) {
            return resolveFrameset(pageUrl, doc);
        }
        List<String> candidates = shellContentCandidates(pageUrl, doc);
        if (candidates.isEmpty()) return null;
        Document best = null;
        int bestLen = doc.body().text().length();
        for (String candidate : candidates) {
            Document cd = fetchDoc(candidate);
            if (cd == null || cd.body() == null) continue;
            int len = cd.body().text().length();
            if (len > bestLen) { best = cd; bestLen = len; }
        }
        return best;
    }

    // Same-origin content URLs to try for a shell page. A hash fragment that names a document is
    // resolved UNCONDITIONALLY (that deep link is itself the shell signal - a Flare shell carries
    // hundreds of chars of chrome, so a body-text gate would wrongly skip it): directly against the
    // shell's directory, and with a "Content/" prefix that MadCap Flare and similar generators
    // insert between the output root and topic paths. A <frame>/<iframe> src is only chased when the
    // host body is genuinely content-light, so an ordinary article carrying an embed isn't replaced
    // by the embed. Returns empty (-> no resolution) when neither signal is present.
    private static List<String> shellContentCandidates(String pageUrl, Document doc) {
        Set<String> out = new LinkedHashSet<>();
        String frag = connectorLikeFragment(pageUrl);
        if (frag != null) {
            String base = dropFragment(pageUrl);
            addIfFetchable(out, pageUrl, resolveAgainstBase(base, frag));
            addIfFetchable(out, pageUrl, resolveAgainstBase(base, "Content/" + frag));
        }
        if (doc.body() != null && doc.body().text().length() < SHELL_TEXT_THRESHOLD) {
            for (Element frame : doc.select("iframe[src], frame[src]")) {
                if (out.size() >= MAX_SHELL_CANDIDATES) break;
                addIfFetchable(out, pageUrl, frame.absUrl("src"));
            }
        }
        return new ArrayList<>(out);
    }

    // Classic frameset (or a body that is essentially only frames): fetch each same-origin frame
    // document and merge their bodies into one synthetic document so the combined content/links are
    // extracted downstream. Each frame's relative URLs are absolutized against the FRAME's own base
    // before the nodes are moved (their owner document, and thus baseUri, changes on move).
    private Document resolveFrameset(String pageUrl, Document doc) {
        List<Element> frames = doc.select("frame[src], iframe[src]");
        if (frames.isEmpty()) return null;
        Document merged = Document.createShell(dropFragment(pageUrl));
        int count = 0;
        for (Element frame : frames) {
            if (count >= MAX_FRAMESET_FRAMES) break;
            String src = frame.absUrl("src");
            if (!isFetchableHtmlCandidate(pageUrl, src)) continue;
            Document fd = fetchDoc(src);
            if (fd == null || fd.body() == null) continue;
            HtmlContentExtractor.absolutizeUrls(fd.body());
            merged.body().appendChildren(fd.body().childNodes());
            count++;
        }
        return count > 0 && !merged.body().text().isBlank() ? merged : null;
    }

    // Bounded GET + jsoup parse of a candidate content URL. Returns null on any failure (404, non-2xx
    // -> FetchException, network error) or when the response clearly isn't HTML/XML, so the caller
    // simply skips that candidate. The fragment is dropped by fetchCapped's request-URI builder.
    private Document fetchDoc(String candidateUrl) {
        try {
            WebMediaStorage.FetchedBody body = webMediaStorage.fetchCapped(candidateUrl, MAX_FETCH_BYTES);
            if (body == null || body.bytes().length == 0) return null;
            String ct = body.contentType() == null ? "" : body.contentType().toLowerCase(Locale.ROOT);
            if (!ct.isEmpty() && !ct.contains("html") && !ct.contains("xml")) return null;
            return Jsoup.parse(new ByteArrayInputStream(body.bytes()), extractCharset(body.contentType()), candidateUrl);
        } catch (Exception e) {
            log.debug("[WebFetchTool] shell content fetch failed for {}: {}", candidateUrl, e.getMessage());
            return null;
        }
    }

    private static void addIfFetchable(Set<String> out, String pageUrl, String candidate) {
        if (out.size() < MAX_SHELL_CANDIDATES && isFetchableHtmlCandidate(pageUrl, candidate)) out.add(candidate);
    }

    // A candidate is worth fetching only if it is an absolute http(s) URL on the SAME host as the
    // shell (never follow a shell off to a third-party origin) and is not the shell page itself.
    private static boolean isFetchableHtmlCandidate(String pageUrl, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String low = candidate.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
        if (dropFragment(candidate).equals(dropFragment(pageUrl))) return false;
        return sameHost(pageUrl, candidate);
    }

    // The URL fragment, returned only when it names a document (contains .htm/.html) rather than a
    // plain in-page anchor id - a strong signal the server returned a shell instead of that topic.
    // Handles a doubled '#' (e.g. product.htm#Topic.htm#) by keeping only the first fragment segment.
    private static String connectorLikeFragment(String url) {
        int hash = url.indexOf('#');
        if (hash < 0 || hash == url.length() - 1) return null;
        String frag = url.substring(hash + 1);
        int second = frag.indexOf('#');
        if (second >= 0) frag = frag.substring(0, second);
        frag = frag.trim();
        if (frag.isEmpty()) return null;
        String low = frag.toLowerCase(Locale.ROOT);
        return (low.contains(".htm") || low.contains(".html")) ? frag : null;
    }

    private static String dropFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    private static boolean sameHost(String a, String b) {
        try {
            String ha = URI.create(dropFragment(a)).getHost();
            String hb = URI.create(dropFragment(b)).getHost();
            return ha != null && ha.equalsIgnoreCase(hb);
        } catch (Exception e) {
            return false;
        }
    }

    // --- PDF extraction --------------------------------------------------------------

    // "%PDF-" file signature. Standard PDFs put it at offset 0, but the spec tolerates leading
    // junk, so scan a small window from the start rather than only checking byte 0.
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final int PDF_MAGIC_SCAN_WINDOW = 1024;

    private static boolean looksLikePdf(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) return false;
        int limit = Math.min(bytes.length, PDF_MAGIC_SCAN_WINDOW) - PDF_MAGIC.length;
        for (int i = 0; i <= limit; i++) {
            int j = 0;
            while (j < PDF_MAGIC.length && bytes[i + j] == PDF_MAGIC[j]) j++;
            if (j == PDF_MAGIC.length) return true;
        }
        return false;
    }

    // Writes the downloaded bytes to a throwaway temp file - every converter behind
    // DocumentConversionService (pdfplumber, markitdown, OCR) drives a Python or CLI process that
    // needs a real file path, not an in-memory byte array. Without this branch a PDF at a URL fell
    // into the plain-text path and got `new String(pdfBytes, UTF_8)`'d - raw binary decoded as text
    // - which is silent garbage, not an error, so it never surfaced as a failure on its own.
    //
    // Routed through the ATTACHMENT surface's configured method, the same as a file the user
    // uploaded: a PDF someone hands over as a link is the same kind of document as one they attach,
    // including the scanned ones, which now reach OCR here too instead of returning a blank page.
    private String extractPdfText(byte[] pdfBytes) throws Exception {
        Path temp = Files.createTempFile("webfetch-", ".pdf");
        try {
            Files.write(temp, pdfBytes);
            return documentConversionService.convert(temp, DocumentConversionService.Surface.ATTACHMENT).content();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // --- Charset detection -----------------------------------------------------------

    // HTTP's own charset signal (Content-Type's charset= param) takes priority over guessing -
    // returns null (not a default) when absent/unrecognized so callers can fall back appropriately
    // (Jsoup sniffs <meta> tags itself when given null; the plain-text path falls back to UTF-8).
    private static String extractCharset(String contentType) {
        if (contentType == null) return null;
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) return null;
        String value = contentType.substring(idx + "charset=".length()).trim();
        int semi = value.indexOf(';');
        if (semi >= 0) value = value.substring(0, semi);
        value = value.replace("\"", "").trim();
        if (value.isEmpty()) return null;
        try {
            return Charset.isSupported(value) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Resolves a possibly-relative URL against a base (used by Layer-1 shell-content candidate
    // building). Kept here (not in HtmlContentExtractor) because it serves the tool's own fetch
    // orchestration, not the shared HTML extraction.
    private static String resolveAgainstBase(String baseUri, String rel) {
        if (baseUri == null || baseUri.isBlank()) return rel;
        try {
            return java.net.URI.create(baseUri).resolve(rel).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cap(String text) {
        if (text == null) return null;
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }

    // `truncated` covers BOTH cuts. It reported only the download cap, so a page whose text was cut
    // here at MAX_TEXT_CHARS - including every PDF, hardcoded false - said it was complete.
    private static boolean overCap(String text) {
        return text != null && text.length() > MAX_TEXT_CHARS;
    }

    private static Map<String, Object> toMap(WebMediaStorage.SavedMedia media) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", media.type());
        m.put("mediaUrl", media.mediaUrl());
        m.put("originalUrl", media.originalUrl());
        m.put("sizeBytes", media.sizeBytes());
        return m;
    }
}
