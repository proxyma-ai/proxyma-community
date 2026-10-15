package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.FailedSyncIterator;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crawls a website starting from a seed URL and exposes each reachable page as an indexable item.
 *
 * <p>Unlike the API-backed connectors (Jira/Confluence/...) a website has no page-list endpoint, so
 * a session's listing performs a bounded breadth-first crawl to discover pages itself: it fetches each
 * page (statically, or through the shared {@link WebRenderService} headless browser when renderJs is
 * on - the SAME rendering webFetch uses), records it as an item, and enqueues its same-site links up
 * to the configured depth/page limits. A visited-set prevents cycles; robots.txt is honoured; a
 * politeness delay throttles requests; sitemap.xml (when present) seeds discovery.
 *
 * <p>HTML pages are returned as pre-rendered Markdown (contentType text/markdown - our jsoup +
 * readability extraction, better for web pages than the generic office converter). Linked PDFs are
 * returned as raw bytes (contentType application/pdf) for the ingest pipeline to convert with the
 * same table-aware extraction it uses for uploaded PDFs. Other binaries are skipped.
 *
 * <p>The plugin of the WEB_PAGE type: each source's crawl runs in a session of its own, over that
 * source's configuration - its fields as JSON: seedUrl (required), maxDepth, maxPages,
 * sameDomainOnly, renderJs, includePattern (optional regex) - and its owner. This bean holds only
 * what every crawl shares: the HTTP client, the renderer, and the discovery cache partitioned by
 * owner.
 */
@Service
public class WebCrawlConnector implements ConnectorPlugin {

    private static final Logger log = LoggerFactory.getLogger(WebCrawlConnector.class);

    // The ONE definition of what a crawl may be asked for. ConnectorService validates every
    // register/update against these and rejects anything outside them with a message naming the
    // real maximum. They used to disagree in three places - the registration modal
    // imposed no bounds at all, the edit modal allowed depth 0-20 and up to 100000 pages, and this
    // class silently clamped to 20/2000 - so asking for depth 300 quietly got you depth 20 and
    // nothing anywhere said so.
    //
    // Clamped again when a session opens (config()), as defence in depth for a configuration
    // stored before the validation existed: a row with larger bounds cannot crawl past them.
    // Small defaults, so the out-of-the-box crawl finishes while someone is still watching it. Anyone
    // can raise them, on every tier, up to the ceilings below; nobody has to lower them to start.
    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int DEFAULT_MAX_PAGES = 100;
    public static final int MAX_CRAWL_DEPTH = 50;
    // Ceiling on pages a single crawl may be asked to index. At the politeness delay below this is
    // already many hours of crawling, so it bounds a runaway crawl rather than any real request.
    public static final int MAX_CRAWL_PAGES = 20_000;
    // The least time between the START of one request and the start of the next. It was a pause
    // added after every answer, so a site that takes a second to answer was given 1.4 - a third of
    // a crawl's time spent waiting on a server that had already finished. One request at a time
    // and never more than this many a second is what the site sees either way.
    private static final long POLITENESS_DELAY_MS = 400;
    // How many times one address may answer its variants (the same path, another query string)
    // with nothing new - a page already listed, a refusal, something that is not a page - before
    // its remaining variants are no longer asked for. See CrawlIterator.fruitless().
    private static final int FRUITLESS_AFTER = 3;
    private static final long MAX_PAGE_BYTES = 5L * 1024 * 1024;
    // PDFs are raw binary, not truncatable like HTML text - a truncated PDF has no valid EOF
    // trailer/xref table and always fails conversion (confirmed live, 2026-07-28: markitdown's
    // PdfConverter threw "PSEOF: Unexpected EOF" on a large user-guide PDF crawled from
    // a vendor docs portal that exceeded MAX_PAGE_BYTES and got silently truncated by fetchBytes()).
    // Matches FileController.MAX_FILE_SIZE_BYTES so a crawled PDF gets the same ceiling as one
    // uploaded directly, instead of inheriting the much smaller HTML-page cap above.
    private static final long MAX_PDF_BYTES = 200L * 1024 * 1024;
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; ProxymaCrawler/1.0; +https://proxyma.ai/bot)";
    // Binary/asset extensions never enqueued for crawling or indexed as HTML. PDFs are handled
    // separately (indexed as raw bytes), so they are NOT in this skip set.
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
            "css", "js", "json", "xml", "zip", "gz", "tar", "rar", "7z",
            "mp4", "webm", "mov", "avi", "mp3", "wav", "ogg", "woff", "woff2", "ttf", "eot",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "exe", "dmg", "iso");

    private final RestClient restClient;
    private final WebRenderService webRenderService;
    private final HtmlContentExtractor htmlContentExtractor;

    // One source's crawl, as its session holds it - built from that source's own configuration
    // when the session opens, and handed to every crawl and fetch it makes. It used to be a
    // ThreadLocal that auth() set before each call, so a configuration a registration probe left on
    // a pooled thread was the one the next crawl on that thread ran.
    // ownerUserId partitions the discovery-content cache below; null in desktop mode (single user,
    // no accounts), which is its own partition like any other.
    record CrawlConfig(String seedUrl, int maxDepth, int maxPages, boolean sameDomainOnly,
                       boolean renderJs, Pattern includePattern, String ownerUserId,
                       String problem) {}

    // Discovery already fetches, renders and parses every page in order to read its title and
    // follow its links - and then getContent() used to fetch and render the exact same URL all over
    // again when the drain loop got round to indexing it. With renderJs=true that second pass costs
    // another full headless-browser navigation, and every render in the whole application
    // serializes on PlaywrightRenderService's single ReentrantLock, so a 200-page crawl paid for
    // ~400 sequential renders rather than 200. Measured 2026-07-29,
    // crawling a vendor docs portal with renderJs=true, maxPages=200: 17m45s to discover (5.3s/page) and a
    // further ~7s/item to index, the bulk of both being that render.
    //
    // Caching the markdown discovery already had in hand removes the second pass entirely. Markdown
    // (not raw HTML) because it is what getContent() returns anyway and is several times smaller,
    // and extraction is pure CPU next to a multi-second render.
    //
    // Strictly an optimization: every miss (evicted, oversized, restarted process, retried item)
    // falls through to the original fetch path, so correctness never depends on an entry surviving.
    // Total budget across every cached page, and the bound that is meant to bind. Deliberately not
    // an entry count: one connector bean is shared by every WEB_PAGE connector on the whole
    // deployment, so with a low entry cap concurrent crawls belonging to different users evict each
    // other long before the cache uses the memory it is allowed to - typical documentation-page
    // markdown is a few KB, so this holds well over a thousand pages.
    private static final long DISCOVERY_CACHE_MAX_TOTAL_CHARS = 8_000_000;
    // Per-entry ceiling - an oversized page simply isn't cached and re-fetches as before.
    private static final int DISCOVERY_CACHE_MAX_ENTRY_CHARS = 256_000;
    // Backstop only, for the degenerate case of very many tiny pages: per-entry map/String overhead
    // is real but isn't expressible in the character budget above. Set high enough that the budget
    // is what normally evicts, so this should not bind in practice.
    private static final int DISCOVERY_CACHE_MAX_ENTRIES = 5_000;

    // Partitioned per owning user: one connector bean serves every WEB_PAGE connector on the
    // deployment, so entries are keyed by owner identity as well as URL and a user can only ever be
    // served content their own crawl fetched. Today's fetches carry no per-user identity at all
    // (a session takes only crawl shape and never a credential, one RestClient with a fixed User-Agent,
    // one shared browser with no session), so cross-user sharing would in fact yield byte-identical
    // content - but that is a property of the current implementation, not something this cache
    // should depend on. Partitioning means adding per-connector cookies/auth later cannot silently
    // turn this into a cross-tenant leak of one user's authenticated view of a page.
    //
    // Key is owner + '\n' + url. A raw newline cannot appear in either component (URLs cannot
    // contain one, and the owner is a UUID or null), so the delimiter is unambiguous and no two
    // distinct pairs can collide.
    //
    // Insertion-ordered; entries are written once by discovery and read at most once by
    // getContent(), so eviction order only matters for entries nothing ever claims (a re-crawl where
    // SyncService finds the page unchanged and so never queues it for indexing).
    // Guarded by its own monitor, along with cachedChars.
    private final LinkedHashMap<String, String> discoveredMarkdown = new LinkedHashMap<>();

    // Null owner (desktop mode - single user, no accounts) is its own partition, distinct from any
    // real user id rather than a wildcard that matches all of them.
    private static String cacheKey(String ownerUserId, String url) {
        return (ownerUserId == null ? "" : ownerUserId) + "\n" + url;
    }
    // Key lengths counted as well as values - URLs on documentation sites run to 100+ chars, and a
    // budget that silently ignored them would not be the bound it claims to be.
    private long cachedChars;

    // Extraction is deliberately done here on the crawl thread rather than stashing the Document:
    // a parsed Jsoup Document holds the entire DOM, far larger than the markdown it renders down to.
    // Returns the extracted markdown (null when extraction failed) for the item's content hash.
    private String cacheDiscoveredMarkdown(String ownerUserId, String url, Document doc) {
        String markdown = null;
        try {
            String key = cacheKey(ownerUserId, url);
            markdown = htmlContentExtractor.extract(doc, null).markdown();
            // Blank extraction is left uncached on purpose - getContent() must then take its normal
            // path so an empty page still surfaces through the usual empty-content handling rather
            // than being silently served as a valid, empty cache hit. Oversized likewise re-fetches.
            boolean cacheable = markdown != null && !markdown.isBlank()
                    && markdown.length() <= DISCOVERY_CACHE_MAX_ENTRY_CHARS;
            synchronized (discoveredMarkdown) {
                // Drop any entry left by an earlier crawl of this same URL before deciding whether
                // to store the new one. Discovery has just re-fetched the page, so an older entry
                // that nothing ever consumed may now be stale - and on the not-cacheable paths above
                // there is no put() to overwrite it, so without this an unchanged-looking cache hit
                // could serve pre-change content for a page this crawl saw grow past the per-entry
                // ceiling (or start extracting blank).
                String stale = discoveredMarkdown.remove(key);
                if (stale != null) cachedChars -= key.length() + stale.length();
                if (!cacheable) return markdown;

                discoveredMarkdown.put(key, markdown);
                cachedChars += key.length() + markdown.length();
                // Evict oldest-first until back inside both bounds. The entry just added is last in
                // insertion order, so it can only be evicted here if it alone breaks the budget -
                // impossible given the per-entry ceiling above is a fraction of the total.
                Iterator<Map.Entry<String, String>> it = discoveredMarkdown.entrySet().iterator();
                while ((cachedChars > DISCOVERY_CACHE_MAX_TOTAL_CHARS
                        || discoveredMarkdown.size() > DISCOVERY_CACHE_MAX_ENTRIES) && it.hasNext()) {
                    Map.Entry<String, String> eldest = it.next();
                    cachedChars -= eldest.getKey().length() + eldest.getValue().length();
                    it.remove();
                }
            }
        } catch (Exception e) {
            // Never let a caching failure break discovery - the page is still a perfectly good item,
            // it just costs a re-fetch later.
            log.debug("[WebCrawl] could not cache discovered content for {}: {}", url, e.getMessage());
        }
        return markdown;
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Removes on read: an item is indexed once per sync, so holding the entry afterwards only
    // wastes memory. A later auto-retry of a failed item misses and re-fetches, which is the
    // behavior a retry should have anyway.
    private String takeDiscoveredMarkdown(String ownerUserId, String url) {
        String key = cacheKey(ownerUserId, url);
        synchronized (discoveredMarkdown) {
            String markdown = discoveredMarkdown.remove(key);
            if (markdown != null) cachedChars -= key.length() + markdown.length();
            return markdown;
        }
    }

    public WebCrawlConnector(
            @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder,
            WebRenderService webRenderService,
            HtmlContentExtractor htmlContentExtractor) {
        this.restClient = externalRestClientBuilder
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.webRenderService = webRenderService;
        this.htmlContentExtractor = htmlContentExtractor;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.WEB_PAGE);
    }

    @Override
    public ConnectorSession open(SourceContext context) {
        return new Session(config(context.config(), context.ownerId()));
    }

    /** A website is the address its crawl starts from - never its title, which is its name. */
    @Override
    public String identity(Map<String, String> config) {
        String seed = config.get("seedUrl");
        return seed == null ? null : seed.strip();
    }

    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        return identity(config);
    }

    /**
     * One source's crawl, from its configuration: null when it has no start address that can be
     * crawled; a problem (said by the listing and the probe, never read as "no filter") when its
     * include pattern does not compile. The product's ceilings apply here as well as at
     * registration. The owner is the source's, never a configuration value - a crafted
     * "ownerUserId" field could otherwise claim another user's cached pages.
     */
    static CrawlConfig config(Map<String, String> c, String ownerUserId) {
        String seed = c.get("seedUrl");
        if (seed == null || !seed.matches("(?i)^https?://.+")) {
            log.warn("[WebCrawl] invalid or missing seedUrl");
            return null;
        }
        String includePattern = c.get("includePattern");
        Pattern compiled = compilePattern(includePattern);
        return new CrawlConfig(
                seed.trim(),
                parseInt(c.get("maxDepth"), DEFAULT_MAX_DEPTH, 0, MAX_CRAWL_DEPTH),
                parseInt(c.get("maxPages"), DEFAULT_MAX_PAGES, 1, MAX_CRAWL_PAGES),
                !"false".equalsIgnoreCase(c.get("sameDomainOnly")),
                "true".equalsIgnoreCase(c.get("renderJs")),
                compiled,
                ownerUserId,
                // A pattern that does not compile is the source's error, not "no filter": read as
                // no filter, a crawl asked to stay inside one manual walked the whole site.
                includePattern != null && !includePattern.isBlank() && compiled == null
                        ? "The include pattern of this source is not a valid regular expression - edit it in the source's settings."
                        : null);
    }

    /**
     * One source's crawl and fetches. Two sessions crawl side by side - a crawl runs for minutes,
     * and nothing one reads is anything another can write.
     */
    private final class Session implements ConnectorSession {
        private final CrawlConfig cfg;

        Session(CrawlConfig cfg) {
            this.cfg = cfg;
        }

        /**
         * What a registration can learn without crawling: a start address that can be crawled, and
         * an include pattern that compiles.
         */
        @Override
        public AccessCheck verifyAccess() {
            if (cfg == null) return AccessCheck.failed("The start address is not a web address.");
            if (cfg.problem() != null) return AccessCheck.failed(cfg.problem());
            return AccessCheck.unsupported();
        }

        @Override
        public Listing list(ListRequest request) {
            return Listings.of(crawl(cfg));
        }

        @Override
        public InputStream fetch(ItemMetadata item) {
            return getContent(item, cfg);
        }
    }

    // A full re-crawl every pass (websites have no incremental-change API here): the descriptor does not
    // say incremental, so every listing is a FULL one and SyncService purges pages that vanished.

    // A thrown-not-swallowed signal that a fetch hit a CAPTCHA/bot-challenge page - see
    // fetchHtml()'s throwOnFailure parameter and getContent() below for how it's used.
    private static final class ChallengePageException extends RuntimeException {
        ChallengePageException(String url) {
            super("Site returned a CAPTCHA/bot-challenge page instead of content for " + url);
        }
    }

    // The address answered, with something that is not a web page - an export, a download, a feed.
    // Its own type so the crawl can tell "this address does not give pages" from a fetch that
    // failed, without reading the sentence.
    private static final class NotAPageException extends ConnectorException {
        NotAPageException(String message) {
            super(message);
        }
    }

    private Iterator<ItemMetadata> crawl(CrawlConfig cfg) {
        if (cfg == null) {
            log.warn("[WebCrawl] a crawl was asked for with no usable start address");
            // Not an empty iterator: nothing was crawled because nothing could be, and reporting
            // that as a clean empty listing would purge every page this source has indexed. Spec 00
            // section 3.4.
            return new FailedSyncIterator(
                    "This source has no usable start address - check its URL in the connector's settings.");
        }
        if (cfg.problem() != null) return new FailedSyncIterator(cfg.problem());
        return new CrawlIterator(cfg);
    }

    private InputStream getContent(ItemMetadata metadata, CrawlConfig cfg) {
        String url = metadata.getId();
        String ct = metadata.getContentType();
        try {
            if (ct != null && ct.contains("pdf")) {
                // Raw PDF bytes - the ingest pipeline converts it (same path as an uploaded PDF).
                // fetchBytes() throws rather than returning null now: a PDF that 404s or is too
                // large is a fact about that item, and handing back an empty stream recorded it as
                // "no extractable text" - the same answer a genuinely blank page gets.
                return new ByteArrayInputStream(fetchBytes(url));
            }
            // Served straight from what discovery already fetched and rendered, when still cached -
            // see discoveredMarkdown. No challenge-page re-check is needed on this path: discovery
            // discards a challenge page instead of emitting an item for it (fetchHtml with
            // throwOnFailure=false returns null there), so a cached entry can never be one.
            // cfg is null only for a source with no usable configuration - then there is no owner
            // identity to match on, so no cache entry can be claimed and the fetch below runs as it
            // always did. Never falls back to an unpartitioned lookup.
            String cached = cfg != null ? takeDiscoveredMarkdown(cfg.ownerUserId(), url) : null;
            if (cached != null) {
                return new ByteArrayInputStream(cached.getBytes(StandardCharsets.UTF_8));
            }

            // throwOnFailure=true here (unlike the discovery path in CrawlIterator, which passes
            // false) - see ChallengePageException's catch clause below, and fetchHtml's own comment,
            // for why this call site reports what discovery steps over. fetchHtml() therefore never
            // returns null on this path.
            FetchedPage fetched = fetchHtml(url, cfg != null && cfg.renderJs(), true);
            Document doc = Jsoup.parse(fetched.html(), url);
            String title = doc.title();
            boolean asksForPassword = asksForPassword(doc);
            HtmlContentExtractor.ExtractedContent extracted = htmlContentExtractor.extract(doc, null);
            String markdown = extracted.markdown() == null ? "" : extracted.markdown();
            if (looksLikeSignInPage(title, asksForPassword, markdown)) {
                throw new ConnectorException("The page at " + url + " now answers with a sign-in page - "
                        + "it needs a login this crawler does not have.");
            }
            return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
        } catch (ChallengePageException e) {
            // Deliberately NOT swallowed like every other getContent() failure below - a CAPTCHA/
            // bot-challenge block deserves a distinct, visible FAILED item (confirmed live,
            // 2026-07-29: a vendor docs portal's landing page indexed the challenge page's own
            // boilerplate text as if it were real content) rather than silently completing with
            // zero content, indistinguishable from a page that's genuinely just always been empty.
            // SyncService.processItem()'s catch already turns any thrown exception here into
            // markFailed(doc, e.getMessage()) - which applies TrackedDocument's existing
            // auto-retry window (3 attempts, 5 minutes apart) with no extra retry logic needed on
            // this connector's side; this connector also fully re-crawls every sync regardless.
            throw e;
        } catch (ConnectorException e) {
            // The page could not be fetched, or is not there any more. Also deliberately NOT
            // swallowed: an empty stream reaches the pipeline as "this page has no extractable
            // text", which is what a genuinely empty page looks like, so a whole site gone behind a
            // login used to index as a set of perfectly healthy blank documents. Thrown instead, so
            // SyncService.processItem() records it on the item with the reason visible, and
            // TrackedDocument's existing 3-attempt retry window covers the transient case.
            // Spec 00 section 3.5.
            log.warn("[WebCrawl] getContent failed for {}: {}", url, e.getMessage());
            throw e;
        } catch (Exception e) {
            // Anything else - a parse or extraction failure on a page we did fetch. Still reported
            // rather than returned as empty content, for the same reason.
            log.warn("[WebCrawl] getContent failed for {}: {}", url, e.getMessage());
            throw new ConnectorException(
                    "Could not read the page at " + url + ": " + SyncErrorAware.summarize(e), e);
        }
    }

    // --- Crawl ---------------------------------------------------------------------

    // Lazily performs the bounded BFS crawl one page at a time, on demand from hasNext()/next(),
    // instead of eagerly walking the whole crawl (previously up to maxPages pages, each with its
    // own network fetch) inside a single blocking call before sync() returned anything at all.
    // SyncService.reconcile() pulls this iterator in batches and persists/publishes each item as
    // it's reconciled. (It took a full hundred per batch until 2026-10-02, which for a crawl of a
    // hundred pages was all of it - see SyncService.pull, which now stops on a clock too.)
    // Confirmed live, 2026-07-29: a renderJs=true, maxPages=200 crawl of a vendor docs portal showed
    // nothing in the UI and nothing began downloading/converting/embedding until all 200 pages had
    // already been fetched by the old eager crawl() - several minutes of dead time up front. Making
    // discovery lazy lets the first item reach the UI (and the drain loop start on it) after just
    // one page fetch, and interleaves the rest of discovery with that processing as it goes.
    //
    // IMPLEMENTS PartialSyncAware because a crawl routinely ends without having seen the whole site
    // and without anything having gone wrong: it stops at maxPages, and it skips a page that will
    // not load rather than abandoning every other queued page. Both leave pages this source has
    // indexed before missing from the listing, and SyncService purges by absence - so a 100-page cap
    // on a 400-page site used to delete pages 101-400 on every single run, then re-crawl the same
    // first 100 and report a clean sync. The cap is not an error (nothing broke, and marking the
    // source ERROR for doing exactly what it was configured to do would be wrong); it is a success
    // that covered part of the site. Spec 00 section 3.4.
    private final class CrawlIterator implements Iterator<ItemMetadata>, PartialSyncAware {
        private final CrawlConfig cfg;
        private final String origin;
        private final RobotsTxt robots;
        private final Set<String> visited = new HashSet<>();
        private final Deque<Node> queue = new ArrayDeque<>();
        private int resultCount = 0;
        private boolean finished = false;
        private ItemMetadata pending;
        // Set when the crawl ran out of its page budget with URLs still queued - i.e. the site is
        // bigger than this source was configured to index.
        private boolean cappedAtMaxPages = false;
        // Pages that answered with nothing usable (dead link, timeout, non-HTML, bot challenge) and
        // were stepped over. A transient one of these is the common case, and it must not be read
        // as "that page was deleted upstream".
        private int skippedPages = 0;
        // Why each skipped page did not load, and which pages - grouped, since one cause (a login
        // wall, a dead section of the site) usually accounts for most of them.
        private final Map<String, List<String>> skipped = new java.util.LinkedHashMap<>();
        // Discovered links dropped because the pending-URL queue was already at its ceiling.
        private boolean queueCeilingHit = false;
        // What each listed page says, by hash - see the duplicate check in advance().
        private final Set<String> contentSeen = new HashSet<>();
        private int duplicatePages = 0;
        // Set when as many addresses as the page limit have repeated a page already listed.
        private boolean cappedAtRepeats = false;
        // Per address without its query string: [0] pages it has given, [1] times it gave nothing
        // new. See fruitless().
        private final Map<String, int[]> answersByPath = new java.util.HashMap<>();
        private int notAsked = 0;
        private int signInPages = 0;
        // Addresses this crawl looked at and found are not pages of their own - see getGoneItemIds.
        private final Set<String> gone = new HashSet<>();
        // When the last request was sent - see pace().
        private long lastRequestAt = System.nanoTime() - POLITENESS_DELAY_MS * 1_000_000;
        // Bound on discovered-but-not-yet-visited URLs held in memory. Sized from what this crawl
        // can actually consume rather than from the global page ceiling: a maxPages=200 crawl has
        // no use for 20000 queued URLs, and now that MAX_CRAWL_PAGES is 20000 rather than 2000 a
        // flat ceiling would let every small crawl retain a hundred times the URLs it will ever
        // pop. The 4x headroom covers queued URLs that turn out to be already-visited,
        // robots-disallowed or non-HTML and so never produce an item.
        private final int queueCeiling;

        CrawlIterator(CrawlConfig cfg) {
            this.cfg = cfg;
            this.queueCeiling = Math.min(MAX_CRAWL_PAGES * 4, Math.max(1_000, cfg.maxPages() * 4));
            this.origin = origin(cfg.seedUrl());
            this.robots = RobotsTxt.fetch(restClient, origin);
            queue.add(new Node(canonical(cfg.seedUrl()), 0));
            for (String s : sitemapSeeds(origin, cfg)) {
                queue.add(new Node(canonical(s), 0));
            }
        }

        @Override
        public boolean hasNext() {
            if (pending == null) pending = advance();
            return pending != null;
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            ItemMetadata item = pending;
            pending = null;
            return item;
        }

        // Performs BFS steps - fetching a page, enqueueing its links - until either one new item is
        // ready to return or the crawl is genuinely done (queue exhausted, maxPages reached, or
        // this iterator already reported done once before).
        private ItemMetadata advance() {
            if (finished) return null;
            while (!queue.isEmpty() && resultCount < cfg.maxPages() && !cappedAtRepeats) {
                Node node = queue.poll();
                String url = node.url;
                if (url == null || !visited.add(url)) continue;
                if (!robots.allowed(url)) continue;
                String path = withoutQuery(url);
                if (fruitless(url, path)) {
                    notAsked++;
                    gone.add(url);
                    log.debug("[WebCrawl] {} not asked for - that address has given nothing new", url);
                    continue;
                }

                // A PDF is a leaf content item - record it, never crawl into it.
                //
                // CHECKED FIRST, since 2026-10-01. It was listed unseen and fetched later, when
                // it is indexed - so a dead link to a PDF became an item that could only fail,
                // while the same dead link to a page was a skipped page. A vendor's docs portal
                // linking a manual it no longer hosts left "The page at ...pdf no longer exists
                // (404 Not Found)" on the source as a failed document, three retries and all.
                // Now it is the skipped page it is, with the reason beside the others.
                if (isPdfUrl(url)) {
                    PdfProbe probe = probePdf(url);
                    if (probe.gone() != null) {
                        if (url.equals(cfg.seedUrl())) {
                            throw new IllegalStateException("Could not fetch " + url + " because "
                                    + probe.gone() + ", so nothing was crawled.");
                        }
                        skippedPages++;
                        skipped.computeIfAbsent(probe.gone(), k -> new java.util.ArrayList<>()).add(url);
                        gone.add(url);
                        continue;
                    }
                    resultCount++;
                    return pdfItem(url, probe.lastModified());
                }

                pace();
                // A single blocked page during discovery must not abort reconciling every other
                // already-queued page - so the failure is caught here and the page skipped. It is
                // fetched WITH throwOnFailure since 2026-09-29, though, because the exception is
                // the only thing that knows WHY: "3 pages could not be loaded" told the reader
                // there was a problem and nothing they could do about it.
                FetchedPage fetched;
                String failure = null;
                long fetchStarted = System.nanoTime();
                try {
                    fetched = fetchHtml(url, cfg.renderJs(), true);
                } catch (RuntimeException e) {
                    fetched = null;
                    failure = whyNotLoaded(url, e);
                    // A refusal and a download are the address's own answer, and count towards
                    // leaving its other variants alone. A timeout or a server error is not.
                    if (e instanceof NotAPageException || refused(e)) gaveNothingNew(path);
                    if (e instanceof NotAPageException || notThere(e)) gone.add(url);
                    log.debug("[WebCrawl] skipping {}: {}", url, failure);
                }
                log.debug("[WebCrawl] {} answered in {} ms", url, (System.nanoTime() - fetchStarted) / 1_000_000);
                if (fetched == null) {
                    // A page that will not load DURING discovery is skipped - one dead link must
                    // not abandon every other queued page. But the SEED is different: if that one
                    // cannot be fetched there is nothing to crawl, and continuing here empties the
                    // queue and reports a perfectly successful sync that indexed zero items.
                    //
                    // That is what a geo-restricted site looks like from the wrong country: the
                    // server gets 403/451 or a reset, every failure is swallowed at debug level,
                    // and the connector lands in the list marked indexed with nothing in it. The
                    // same URL crawled from a machine inside the permitted region works, which
                    // makes it look like Proxyma silently lost the pages.
                    if (url.equals(cfg.seedUrl())) {
                        throw new IllegalStateException(
                                "Could not fetch " + url + " because " + failure + ", so nothing was "
                                + "crawled. The page may be unreachable from this deployment, behind a "
                                + "login, or restricted to certain regions. If the site is reachable "
                                + "from your own network but not from here, configure an HTTP proxy in "
                                + "Settings > Network.");
                    }
                    skippedPages++;
                    skipped.computeIfAbsent(failure != null ? failure : "it returned nothing usable",
                            k -> new java.util.ArrayList<>()).add(url);
                    continue;
                }
                Document doc = Jsoup.parse(fetched.html(), url);
                // Read before the text is extracted, like the links below.
                boolean asksForPassword = asksForPassword(doc);

                // SyncService.reconcileItem() dereferences getLastModified() unconditionally -
                // every item here MUST carry a non-null Instant (confirmed live
                // , 2026-07-28: every crawled page was silently dropped via a caught NPE,
                // "0 items" shown for a crawl that had genuinely fetched 82 pages). Prefer the
                // page's real HTTP Last-Modified header (python -m http.server and most real web
                // servers send one, derived from the file's mtime) so unchanged pages still get the
                // SyncService "upToDate" fast path on the next daily re-crawl instead of being
                // re-embedded for no reason; fall back to observation time only when the server
                // didn't send one (e.g. Playwright-rendered pages have no HTTP response to read a
                // header from).
                ItemMetadata md = new ItemMetadata(url, titleOf(doc, url), ConnectorType.WEB_PAGE,
                        fetched.lastModified() != null ? fetched.lastModified() : Instant.now(), "text/markdown");

                // Read off the page BEFORE its text is extracted, so nothing extract() might do to
                // the Document can affect which links this crawl discovers.
                List<String> links = new ArrayList<>();
                if (node.depth < cfg.maxDepth()) {
                    for (Map<String, Object> link : HtmlContentExtractor.extractLinks(doc)) {
                        links.add(String.valueOf(link.get("url")));
                    }
                }
                String markdown = cacheDiscoveredMarkdown(cfg.ownerUserId(), url, doc);
                String contentHash = markdown != null && !markdown.isBlank() ? sha256(markdown) : null;

                // A SIGN-IN PAGE - see looksLikeSignInPage. Not an item, not a page of the budget,
                // and its links are the identity provider's, not the site's. Like a copy, it is not
                // a "skipped page": every page of a wiki links to its own sign-in address, and
                // counting those would mark every crawl of a public wiki incomplete. It does count
                // towards the budget for copies, which is what stops a site that answers
                // everything this way.
                if (looksLikeSignInPage(md.getTitle(), asksForPassword, markdown)) {
                    if (url.equals(cfg.seedUrl())) {
                        throw new IllegalStateException("Could not crawl " + url + " because the site "
                                + "answered with its sign-in page, so nothing was crawled. The site needs "
                                + "a login this crawler does not have.");
                    }
                    takeDiscoveredMarkdown(cfg.ownerUserId(), url);
                    signInPages++;
                    duplicatePages++;
                    gaveNothingNew(path);
                    gone.add(url);
                    log.debug("[WebCrawl] {} answered with a sign-in page - not listed", url);
                    if (duplicatePages >= cfg.maxPages()) cappedAtRepeats = true;
                    continue;
                }

                // THE SAME PAGE UNDER ANOTHER ADDRESS is not another page. A site behind a login
                // answers every link with its sign-in page; a wiki answers ?focusedCommentId=,
                // ?src= and a dozen other variants of one address with one page. Each was listed,
                // counted against the page limit and indexed - a hundred copies of a login form -
                // and each one's links were followed in turn. A page that says exactly what one
                // already listed says is dropped here: not an item, not a page of the budget, and
                // its links (the first copy's links, again) are not followed.
                //
                // Not a "skipped page" either: nothing failed, and counting it as one would mark
                // every crawl of an ordinary site as incomplete.
                if (contentHash != null && !contentSeen.add(contentHash)) {
                    duplicatePages++;
                    gaveNothingNew(path);
                    gone.add(url);
                    log.debug("[WebCrawl] {} says what a page already listed says - not listed again", url);
                    // Not a page of the budget, but not free either: every copy was fetched to
                    // find out. A site that answers everything with its sign-in page would be
                    // walked to the end of its queue for one item, so the copies have a budget of
                    // their own, the same size.
                    if (duplicatePages >= cfg.maxPages()) cappedAtRepeats = true;
                    continue;
                }
                resultCount++;
                answersByPath.computeIfAbsent(path, k -> new int[2])[0]++;

                for (String link : links) {
                    if (resultCount + queue.size() >= queueCeiling) {
                        queueCeilingHit = true;
                        break;
                    }
                    String next = canonical(link);
                    if (next != null && !visited.contains(next) && shouldEnqueue(next, origin, cfg)) {
                        queue.add(new Node(next, node.depth + 1));
                    }
                }
                // What the page SAYS, so a re-crawl can tell an unchanged page from a changed one
                // without the server's Last-Modified. Most sites send none, so each page was
                // stamped "now" and every re-crawl re-embedded every page it already had - which is
                // why raising a site's page limit took so long to reach the new pages.
                if (contentHash != null) md.putExtra(ItemMetadata.CONTENT_HASH, contentHash);
                return md;
            }
            finished = true;
            // The loop exits on an empty queue (the site is exhausted - a complete listing), on the
            // page budget running out with URLs still waiting, or on the budget for copies running
            // out (both incomplete). What is left in the queue tells the first from the other two.
            cappedAtMaxPages = !cappedAtRepeats && !queue.isEmpty();
            log.info("[WebCrawl] crawled {} page(s) from {} (maxDepth={}, maxPages={}, renderJs={}, "
                            + "{} more were copies of a listed page or sign-in pages ({} sign-in), "
                            + "{} not asked for)",
                    resultCount, cfg.seedUrl(), cfg.maxDepth(), cfg.maxPages(), cfg.renderJs(),
                    duplicatePages, signInPages, notAsked);
            if (isPartial()) {
                log.info("[WebCrawl] partial crawl of {} - {}", cfg.seedUrl(), getPartialReason());
            }
            return null;
        }

        // Whether this address should be left alone: a VARIANT (it carries a query string) of an
        // address whose variants have answered FRUITLESS_AFTER times with nothing new, and with at
        // most one page between them.
        //
        // A wiki page links to its own sign-in address (?os_destination=<this page>), its history,
        // its page information, its attachments, its export to Word and to PDF - one of each per
        // page, so a crawl of a hundred pages was asked for a thousand addresses, each fetched to
        // learn it was the sign-in page again or a 403 (measured 2026-10-02 on a public wiki: 25
        // pages listed, 18 copies and 7 refusals fetched beside them). After three such answers
        // the address has said what it is. ONE page is allowed for, because the first copy of a
        // sign-in page is a page; an address that has given two is never left alone - /page?id=7
        // and /page?id=9 are pages, however many of their siblings repeat.
        private boolean fruitless(String url, String path) {
            if (path.equals(url)) return false;
            int[] answers = answersByPath.get(path);
            return answers != null && answers[0] <= 1 && answers[1] >= FRUITLESS_AFTER;
        }

        private void gaveNothingNew(String path) {
            answersByPath.computeIfAbsent(path, k -> new int[2])[1]++;
        }

        // Waits out what is left of POLITENESS_DELAY_MS since the last request was sent.
        private void pace() {
            long waitNanos = POLITENESS_DELAY_MS * 1_000_000 - (System.nanoTime() - lastRequestAt);
            if (waitNanos > 0) {
                try {
                    Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.nanoTime();
        }

        @Override
        public boolean isPartial() {
            return cappedAtMaxPages || cappedAtRepeats || queueCeilingHit || skippedPages > 0;
        }

        // What this crawl can vouch for: an address the server said nothing is at (404, 410), one
        // that is not a web page, a sign-in page, a copy of a page already listed, and a variant
        // left unasked because its address had answered that way three times. An earlier crawl -
        // or an earlier build - may have listed any of them. Never a page that timed out, was
        // refused or was never reached.
        @Override
        public Set<String> getGoneItemIds() {
            return java.util.Collections.unmodifiableSet(gone);
        }

        @Override
        public String getPartialReason() {
            if (!isPartial()) return null;
            StringBuilder reason = new StringBuilder();
            if (cappedAtMaxPages) {
                reason.append("stopped at the ").append(cfg.maxPages())
                        .append("-page limit with ").append(queue.size()).append(" link(s) still to visit");
            }
            if (cappedAtRepeats) {
                reason.append("stopped after ").append(duplicatePages)
                        .append(signInPages > 0
                                ? " addresses answered with a sign-in page or a page already listed - the "
                                        + "site needs a login this crawler does not have"
                                : " addresses repeated a page already listed");
            }
            if (queueCeilingHit) {
                if (reason.length() > 0) reason.append("; ");
                reason.append("more links were found than this crawl can queue");
            }
            if (skippedPages > 0) {
                if (reason.length() > 0) reason.append("; ");
                reason.append(skippedPages).append(skippedPages == 1 ? " page" : " pages")
                        .append(" could not be loaded: ");
                int shown = 0;
                for (Map.Entry<String, List<String>> e : skipped.entrySet()) {
                    if (shown == 3) {
                        reason.append("; and other reasons");
                        break;
                    }
                    if (shown > 0) reason.append("; ");
                    // The cause first, then which pages: "the server answered 404 Not Found - the
                    // page no longer exists (https://x/a and 2 more)".
                    List<String> urls = e.getValue();
                    reason.append(e.getKey()).append(" (").append(urls.get(0));
                    if (urls.size() > 1) reason.append(" and ").append(urls.size() - 1).append(" more");
                    reason.append(")");
                    shown++;
                }
            }
            return reason.toString();
        }
    }

    private boolean shouldEnqueue(String url, String origin, CrawlConfig cfg) {
        String low = url.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
        if (cfg.sameDomainOnly() && !sameHost(url, cfg.seedUrl())) return false;
        if (SKIP_EXTENSIONS.contains(extensionOf(url))) return false;
        if (cfg.includePattern() != null && !cfg.includePattern().matcher(url).find()) return false;
        return true;
    }

    // html() is never null when a FetchedPage is returned; lastModified() is null when the
    // server sent no Last-Modified header (or the page came from the headless renderer, which
    // has no HTTP response to read one from) - callers must supply their own fallback.
    private record FetchedPage(String html, Instant lastModified) {}

    // A CAPTCHA/bot-challenge page (reCAPTCHA, hCaptcha, Cloudflare's interstitial, ...) still
    // arrives as an ordinary successful response - same status code, real HTML body - so nothing
    // downstream can tell it apart from real content without checking for it explicitly. Confirmed
    // live, 2026-07-29: a vendor docs portal started serving a reCAPTCHA challenge page mid-crawl (this
    // connector's own request volume against a site with active bot protection is a plausible
    // trigger), and without this check the challenge page's own boilerplate text ("protected by
    // reCAPTCHA") got embedded and indexed as if it were real product documentation. Deliberately just
    // detects and skips - this connector does not attempt to solve or evade the challenge; doing so
    // would mean defeating an access control the site owner put there on purpose. If a connector
    // keeps hitting this, the real fix is getting this crawler's outbound IP/User-Agent allow-listed
    // with whoever administers the target site's bot protection.
    private static final Pattern CHALLENGE_PAGE_PATTERN = Pattern.compile(
            "(?i)g-recaptcha|recaptcha-accessible-status|hcaptcha|cf-turnstile|cf_captcha|"
            + "Attention Required! \\| Cloudflare|Just a moment\\.\\.\\.|Checking your browser");
    // Challenge interstitials are small boilerplate wrappers, not real documentation - a few KB at
    // most. Requiring this keeps the check from firing on a legitimate page that merely mentions
    // "captcha" in passing (a security how-to, say) without also being suspiciously short.
    private static final int CHALLENGE_PAGE_MAX_BYTES = 20_000;

    private static boolean looksLikeChallengePage(String html) {
        return html != null && html.length() <= CHALLENGE_PAGE_MAX_BYTES && CHALLENGE_PAGE_PATTERN.matcher(html).find();
    }

    // A SIGN-IN PAGE IS NOT A PAGE OF THE SITE. A wiki behind single sign-on answers a private
    // address by sending the visitor to its identity provider, and what comes back - under the
    // address that was asked for - is that provider's sign-in page. The same-page check does not
    // catch it: each copy carries its own request id and return address in its links, so no two
    // say exactly the same thing, and seven of them were listed, titled "Sign in to your account"
    // and indexed as documents of the wiki (maintainer's feedback, 2026-10-02).
    //
    // Recognized by what it is rather than by who serves it: a page that says little and is titled
    // as a sign-in, or says next to nothing and asks for a password. A long page is never one,
    // however it is titled - a manual's "Sign in to your account" chapter is documentation - and a
    // short article on a site with a login box on every page is still an article.
    private static final Pattern SIGN_IN_TITLE = Pattern.compile(
            "(?i)^\\s*(sign[ -]?in|log[ -]?in|log[ -]?on|sign[ -]?on|single sign[ -]?on)\\b");
    private static final int SIGN_IN_MAX_CHARS = 1_500;
    private static final int PASSWORD_FORM_MAX_CHARS = 400;

    private static boolean asksForPassword(Document doc) {
        return doc.selectFirst("input[type=password]") != null;
    }

    private static boolean looksLikeSignInPage(String title, boolean asksForPassword, String markdown) {
        int said = markdown == null ? 0 : markdown.length();
        if (title != null && SIGN_IN_TITLE.matcher(title).find()) return said <= SIGN_IN_MAX_CHARS;
        return asksForPassword && said <= PASSWORD_FORM_MAX_CHARS;
    }

    // Fetches page HTML: through the headless browser when render is requested AND rendering is
    // available (falls back to a static GET when the render yields nothing), else a static GET.
    //
    // throwOnFailure separates the two call sites, which want opposite things from a page that will
    // not load. DISCOVERY (false) skips it and returns null, because one dead link must not abandon
    // every other queued page - the skip is counted instead, and CrawlIterator reports it as a
    // partial crawl. getContent() (true) reports it, because there the page IS the work: returning
    // null there produced an empty stream, the pipeline recorded "no extractable text", and a page
    // that had 404'd became indistinguishable from one that is genuinely blank. Spec 00 section 3.5.
    // Never returns null when throwOnFailure is true.
    private FetchedPage fetchHtml(String url, boolean render, boolean throwOnFailure) {
        if (render && webRenderService.isRenderingEnabled()) {
            String rendered = webRenderService.render(url);
            if (rendered != null && !rendered.isBlank() && !looksLikeChallengePage(rendered)) {
                return new FetchedPage(rendered, null);
            }
            if (rendered != null && looksLikeChallengePage(rendered)) {
                // Falls through to the static GET below rather than failing immediately - same
                // idea as the existing blank-render fallback, on the chance the two request paths
                // don't trigger the target's bot protection identically.
                log.warn("[WebCrawl] {} returned a CAPTCHA/bot-challenge page via rendering - retrying with a static GET", url);
            }
        }
        try {
            Fetched resp = getWithRetry(url, MAX_PAGE_BYTES, false);
            String contentType = resp.headers().getContentType() != null
                    ? resp.headers().getContentType().toString().toLowerCase(Locale.ROOT) : "";
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                if (throwOnFailure) {
                    throw new ConnectorException("The page at " + url + " returned nothing - it may have "
                            + "been removed.");
                }
                return null;
            }
            if (!contentType.isEmpty() && !contentType.contains("html") && !contentType.contains("xml")) {
                if (throwOnFailure) {
                    throw new NotAPageException("The address " + url + " no longer serves a web page - "
                            + "it now returns " + contentType + ".");
                }
                return null;
            }
            // Decoded in the page's own charset - the header's, else its <meta charset> - rather than
            // always as UTF-8, which turned windows-1252, Shift_JIS or GBK text into mojibake.
            String html = new String(body, charsetOf(resp.headers(), body));
            if (looksLikeChallengePage(html)) {
                if (throwOnFailure) throw new ChallengePageException(url);
                log.warn("[WebCrawl] {} returned a CAPTCHA/bot-challenge page - skipping", url);
                return null;
            }
            long headerMillis = resp.headers().getLastModified();
            Instant lastModified = headerMillis >= 0 ? Instant.ofEpochMilli(headerMillis) : null;
            return new FetchedPage(html, lastModified);
        } catch (ChallengePageException | ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.debug("[WebCrawl] fetch failed for {}: {}", url, e.getMessage());
            if (throwOnFailure) throw notFetchable(url, e);
            return null;
        }
    }

    // WHY a page did not load, as a clause that reads after "because" and names no URL - so that
    // pages failing for one reason can be counted together. The cause, not the symptom: a status
    // code the server gave, a CAPTCHA, a timeout, a page that is not HTML.
    private static String whyNotLoaded(String url, RuntimeException e) {
        if (e instanceof ChallengePageException) {
            return "the site answered with a CAPTCHA or bot check instead of the page";
        }
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            int status = httpEx.getStatusCode().value();
            String answer = "the server answered " + SyncErrorAware.summarize(httpEx);
            if (status == 401 || status == 403) return answer + " - it needs a login or blocks this crawler";
            if (status == 404 || status == 410) return answer + " - the page no longer exists";
            if (status == 429) return answer + " - it is limiting how fast it may be crawled";
            if (status >= 500) return answer + " - the site had an error of its own";
            return answer;
        }
        if (cause != e) return SyncErrorAware.summarize(cause instanceof Exception ex ? ex : e);
        String message = e.getMessage() == null ? "" : e.getMessage();
        // fetchHtml's own sentences name the page; the grouped report names it once, before this.
        return message.isBlank() ? SyncErrorAware.summarize(e)
                : message.replace("The page at " + url + " ", "it ")
                         .replace("The address " + url + " ", "it ")
                         .replaceAll("\\.$", "");
    }

    // The one sentence getContent() puts on a failed item. A 404 or a 410 is worth saying plainly -
    // the page is gone, and re-crawling will not bring it back - while anything else is a fetch that
    // failed this time and may not next time. summarize() rather than the raw message because an
    // expired single-sign-on session answers with a whole HTML login page as its body, and that
    // string would otherwise land on the source's card.
    private static ConnectorException notFetchable(String url, Exception cause) {
        if (cause instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            int status = httpEx.getStatusCode().value();
            if (status == 404 || status == 410) {
                return new ConnectorException("The page at " + url + " no longer exists ("
                        + SyncErrorAware.summarize(httpEx) + ").", cause);
            }
        }
        return new ConnectorException(
                "Could not fetch " + url + ": " + SyncErrorAware.summarize(cause), cause);
    }

    // Throws rather than returning null on every failure: its only caller is getContent(), where a
    // null became an empty stream that the pipeline recorded as "no extractable text" - the same
    // thing it records for a PDF with no text layer. Spec 00 section 3.5.
    private byte[] fetchBytes(String url) {
        Fetched resp;
        try {
            resp = getWithRetry(url, MAX_PDF_BYTES, true);
        } catch (TooLarge e) {
            // Never truncate - unlike HTML text, a partial PDF has no valid trailer and is
            // guaranteed unparseable. Refuse the file outright rather than hand markitdown a byte
            // array that looks plausible but corrupt.
            log.warn("[WebCrawl] skipping PDF fetch for {}: over the {}MB limit", url, MAX_PDF_BYTES / (1024 * 1024));
            throw new ConnectorException("The PDF at " + url + " is larger than the "
                    + (MAX_PDF_BYTES / (1024 * 1024)) + " MB limit and was not indexed.");
        } catch (Exception e) {
            log.debug("[WebCrawl] byte fetch failed for {}: {}", url, e.getMessage());
            throw notFetchable(url, e);
        }
        byte[] body = resp.body();
        if (body == null || body.length == 0) {
            throw new ConnectorException("The PDF at " + url + " returned nothing - it may have been removed.");
        }
        return body;
    }

    private static final int FETCH_MAX_ATTEMPTS = 3;
    private static final long FETCH_RETRY_BASE_DELAY_MS = 500;

    // A small bounded retry around every GET this connector makes, independent of and
    // complementary to externalRestClientBuilder's own staleConnectionRetryInterceptor (which
    // only retries a raw connection-level IOException, before Spring's status-code error handling
    // even runs, so it can never see an HTTP-level error response). Confirmed live, 2026-07-28: a
    // connector whose seed URL is reachable via the corporate proxy to an internal (non-internet)
    // host intermittently got a genuine, well-formed 404 response for pages that had just been
    // fetched successfully seconds earlier during the same crawl's discovery phase - not
    // reproducible via curl or a fresh short-lived HttpClient hitting the exact same proxy path,
    // so most likely a connection-reuse/routing quirk in the proxy chain specific to this
    // long-lived, heavily-shared connection pool (also used for Jira/Confluence/LLM calls for the
    // app's entire uptime) rather than anything about the target page or request itself. A retry
    // (observed empirically to have a real, if partial, success rate on the same failed items)
    // is the pragmatic mitigation - the alternative would require diagnosing the corporate
    // proxy infrastructure itself, which is out of reach from here.
    //
    // NOT for a 401 or a 403. Those are the server deciding, and it decides the same way a second
    // later: a wiki's history and page-information links are refused to a visitor who is not
    // signed in, there is one of each on every page, and asking three times with a growing pause
    // made each cost over two seconds instead of half of one.
    //
    // READ NO FURTHER THAN maxBytes. The whole body used to be read into memory and the page and
    // PDF ceilings applied afterwards, so a hostile or mistaken response - a multi-gigabyte file
    // behind a .pdf link, an endless HTML stream - exhausted the server's heap on a sync thread.
    // A body past the bound is cut (a page) or refused (refuseOver: a PDF, which cut is corrupt),
    // and a Content-Length already past it is refused before anything is read.
    private Fetched getWithRetry(String url, long maxBytes, boolean refuseOver) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= FETCH_MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get().uri(URI.create(url)).exchange((request, response) -> {
                    org.springframework.http.HttpStatusCode status = response.getStatusCode();
                    if (status.isError()) {
                        byte[] errorBody;
                        try (InputStream in = response.getBody()) {
                            errorBody = in.readNBytes(8192);
                        }
                        if (status.is4xxClientError()) {
                            throw org.springframework.web.client.HttpClientErrorException.create(status,
                                    response.getStatusText(), response.getHeaders(), errorBody, StandardCharsets.UTF_8);
                        }
                        throw org.springframework.web.client.HttpServerErrorException.create(status,
                                response.getStatusText(), response.getHeaders(), errorBody, StandardCharsets.UTF_8);
                    }
                    if (refuseOver && response.getHeaders().getContentLength() > maxBytes) {
                        // Closed here, unread: on close Spring drains what is left of a body to
                        // reuse the connection, which for this one is the whole download.
                        try {
                            response.getBody().close();
                        } catch (RuntimeException | java.io.IOException notOpened) {
                            // A client that had not opened the body has nothing to drain either.
                        }
                        throw new TooLarge();
                    }
                    try (InputStream in = response.getBody()) {
                        byte[] body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                        if (body.length > maxBytes) {
                            if (refuseOver) throw new TooLarge();
                            body = java.util.Arrays.copyOf(body, (int) maxBytes);
                        }
                        return new Fetched(response.getHeaders(), body);
                    }
                });
            } catch (RuntimeException e) {
                last = e;
                if (e instanceof TooLarge || refused(e)) break;
                if (attempt < FETCH_MAX_ATTEMPTS) {
                    log.debug("[WebCrawl] fetch attempt {}/{} failed for {}: {}",
                            attempt, FETCH_MAX_ATTEMPTS, url, e.getMessage());
                    try {
                        Thread.sleep(FETCH_RETRY_BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /** One fetched response: its headers and at most its bound of body. */
    private record Fetched(org.springframework.http.HttpHeaders headers, byte[] body) {}

    /** A body past a bound that is refused rather than cut - see getWithRetry. */
    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("The response is larger than this crawler reads");
        }
    }

    // robots.txt and sitemap.xml are read no further than this - both were read whole, unbounded.
    private static final long MAX_SITE_FILE_BYTES = 512 * 1024;

    // A small site file as text, or null when it cannot be had.
    private static String siteFile(RestClient client, String url) {
        return client.get().uri(URI.create(url)).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) return null;
            try (InputStream in = response.getBody()) {
                return new String(in.readNBytes((int) MAX_SITE_FILE_BYTES), StandardCharsets.UTF_8);
            }
        });
    }

    // The page's charset: the Content-Type header's, else a <meta charset> near the top, else UTF-8.
    static java.nio.charset.Charset charsetOf(org.springframework.http.HttpHeaders headers, byte[] body) {
        org.springframework.http.MediaType type = headers.getContentType();
        if (type != null && type.getCharset() != null) return type.getCharset();
        String head = new String(body, 0, Math.min(body.length, 2048), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = META_CHARSET.matcher(head);
        if (m.find()) {
            try {
                return java.nio.charset.Charset.forName(m.group(1));
            } catch (RuntimeException unknown) {
                // An unknown name reads as UTF-8, as before.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static final Pattern META_CHARSET =
            Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)");

    // Best-effort sitemap seeding: pull <loc> URLs from /sitemap.xml when present (bounded).
    private List<String> sitemapSeeds(String origin, CrawlConfig cfg) {
        List<String> seeds = new ArrayList<>();
        try {
            String xml = siteFile(restClient, origin + "/sitemap.xml");
            if (xml == null) return seeds;
            java.util.regex.Matcher m = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.DOTALL).matcher(xml);
            while (m.find() && seeds.size() < 100) {
                String loc = m.group(1).trim();
                if (loc.matches("(?i)^https?://.+") && (!cfg.sameDomainOnly() || sameHost(loc, cfg.seedUrl()))) seeds.add(loc);
            }
        } catch (Exception e) {
            log.debug("[WebCrawl] no usable sitemap at {}: {}", origin, e.getMessage());
        }
        return seeds;
    }

    // See CrawlIterator.advance()'s comment on FetchedPage.lastModified() for why the date can't be
    // null - a cheap HEAD request (no body) is worth it here since getContent() defers the real byte
    // fetch to the later drain phase, so this is the only point where discovery could ever learn the
    // PDF's real modified date.
    private ItemMetadata pdfItem(String url, Instant lastModified) {
        return new ItemMetadata(url, lastSegment(url), ConnectorType.WEB_PAGE,
                lastModified != null ? lastModified : Instant.now(), "application/pdf");
    }

    // What a HEAD said about a linked PDF: when it last changed, or - `gone` - why it is not there,
    // in whyNotLoaded's wording. Both null when the server would not say either.
    private record PdfProbe(Instant lastModified, String gone) {}

    // Only 404 and 410 make a PDF `gone`: the server's own statement that nothing is at that
    // address. Anything else - a server that refuses HEAD, a login wall, a timeout - says nothing
    // about whether a GET would work, so the PDF is listed as before and getContent() decides.
    // Asked FETCH_MAX_ATTEMPTS times before it is believed, for the reason getWithRetry gives.
    private PdfProbe probePdf(String url) {
        for (int attempt = 1; ; attempt++) {
            try {
                long headerMillis = restClient.head().uri(URI.create(url)).retrieve()
                        .toBodilessEntity().getHeaders().getLastModified();
                return new PdfProbe(headerMillis >= 0 ? Instant.ofEpochMilli(headerMillis) : null, null);
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status != 404 && status != 410) {
                    log.debug("[WebCrawl] HEAD failed for {}: {}", url, e.getMessage());
                    return new PdfProbe(null, null);
                }
                if (attempt >= FETCH_MAX_ATTEMPTS) return new PdfProbe(null, whyNotLoaded(url, e));
                try {
                    Thread.sleep(FETCH_RETRY_BASE_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new PdfProbe(null, null);
                }
            } catch (Exception e) {
                log.debug("[WebCrawl] HEAD failed for {}: {}", url, e.getMessage());
                return new PdfProbe(null, null);
            }
        }
    }

    private static String titleOf(Document doc, String url) {
        String t = doc.title();
        return (t == null || t.isBlank()) ? lastSegment(url) : t;
    }

    // --- URL helpers ---------------------------------------------------------------

    private record Node(String url, int depth) {}

    // A fragment is normally dropped (never part of server identity, so #a and #b of one page
    // aren't two crawls) - EXCEPT WebHelp tools (MadCap Flare, RoboHelp, ...) commonly encode a
    // relative document path in the fragment instead (e.g. "product.htm#../Subsystems/core/Content/
    // Topics/Foo.htm?TocPath=..."), client-side-only routing that never reaches the server; the
    // frameset shell itself has no content of its own. Confirmed live, 2026-07-29 against
    // a vendor docs portal (MadCap Flare TriPane skin): stripping every such fragment collapsed
    // ~500 distinct topics into one already-visited shell URL, so nothing but the empty shell was
    // ever indexed. resolveFragmentDocPath() below resolves the path form into its real URL
    // instead; only a plain same-page anchor (no ".htm"/".html" in the fragment) still gets
    // stripped as before.
    private static String canonical(String url) {
        if (url == null || url.isBlank() || "null".equals(url)) return null;
        url = sanitizeUrl(url);
        if (url == null) return null;
        int hash = url.indexOf('#');
        if (hash < 0) return url;
        String base = url.substring(0, hash);
        String resolved = resolveFragmentDocPath(base, url.substring(hash + 1));
        return resolved != null ? resolved : base;
    }

    // Repairs a URL string containing characters illegal in a URI (most commonly a raw,
    // un-percent-encoded space) into a properly escaped absolute URI string, without double-encoding
    // a URL that is already well-formed. Needed because jsoup's Element.absUrl() (used by
    // HtmlContentExtractor.extractLinks()) resolves relative hrefs against the page's base URL but
    // does not percent-encode illegal characters left in the source markup - confirmed live,
    // 2026-07-29: a vendor docs portal's landing page links to "Getting Started Overview.htm" with a
    // literal space in the href, and the target page is real and reachable (HTTP 200 once the space
    // is escaped to %20 - browsers do this silently when following the link). Every URI.create()
    // call in this class (sameHost(), getWithRetry(), fetchLastModified(), RobotsTxt.allowed())
    // throws IllegalArgumentException on the raw string; shouldEnqueue()'s sameHost() check swallows
    // that exception and reports "different host", silently dropping an otherwise valid, same-domain
    // page from the crawl instead. Tries the strict parse first (the common case, left byte-for-byte
    // untouched) and only falls back to the java.net.URL-based repair - which re-encodes each
    // component independently - on failure, so an already-percent-encoded URL is never
    // double-encoded (confirmed: an existing "%20" would otherwise become "%2520").
    // URL(String) is deprecated (JDK 20+) in favor of URI-based factories, but only because it can
    // implicitly load a third-party stream handler for the scheme - it does no I/O of its own and is
    // used here purely as a lenient component parser (scheme/host/port/path/query/fragment) for a
    // string java.net.URI's own strict constructor already rejected, so that risk doesn't apply.
    @SuppressWarnings("deprecation")
    private static String sanitizeUrl(String raw) {
        try {
            new URI(raw);
            return raw;
        } catch (URISyntaxException e) {
            try {
                URL u = new URL(raw);
                return new URI(u.getProtocol(), u.getUserInfo(), u.getHost(), u.getPort(),
                        u.getPath(), u.getQuery(), u.getRef()).toASCIIString();
            } catch (Exception repairFailed) {
                return null;
            }
        }
    }

    // Matches a fragment shaped like a relative document path (contains ".htm"/".html", optionally
    // followed by a query string or another fragment) rather than a plain in-page anchor id.
    private static final Pattern FRAGMENT_DOC_PATTERN = Pattern.compile("(?i)\\.html?($|[?#])");

    // Resolves a WebHelp-style path fragment against the shell page's own "Content/" subdirectory
    // (one level deeper than the shell page itself) and returns the resulting absolute URL, or
    // null if `fragment` doesn't look like a document path (caller falls back to stripping it as
    // an ordinary anchor). "Content/" as the resolution base - not the shell page's own directory -
    // was determined empirically against the live server: MadCap's root-project topics resolve
    // directly under "<shellDir>/Content/" (e.g. fragment "Topics/Overview.htm" -> "<shellDir>/
    // Content/Topics/Overview.htm", verified 200), while merged-sub-project topics use exactly one
    // leading "../" to escape back out to "<shellDir>/" before descending into their own "Subsystems/
    // <name>/Content/Topics/..." (e.g. fragment "../Subsystems/dbm/Content/Topics/Foo.htm" ->
    // "<shellDir>/Subsystems/dbm/Content/Topics/Foo.htm", also verified 200) - both are ordinary
    // RFC 3986 relative-URI resolution (java.net.URI already implements the dot-segment removal
    // this needs) once "Content/" is used as the base directory instead of the shell's own directory.
    private static String resolveFragmentDocPath(String base, String fragment) {
        if (fragment == null || fragment.isBlank() || !FRAGMENT_DOC_PATTERN.matcher(fragment).find()) return null;
        // Drop the TocPath breadcrumb (?...) and any further embedded "#" (a few entries on this
        // site have a stray trailing "#", e.g. "...LandingPage.htm#") - neither is part of the path.
        String path = fragment.split("[?#]", 2)[0];
        try {
            URI baseUri = URI.create(base);
            String baseDir = baseUri.getPath();
            int slash = baseDir.lastIndexOf('/');
            baseDir = slash >= 0 ? baseDir.substring(0, slash + 1) : "/";
            URI contentBase = new URI(baseUri.getScheme(), baseUri.getAuthority(), baseDir + "Content/", null, null);
            return contentBase.resolve(path).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean sameHost(String a, String b) {
        try {
            String ha = URI.create(a).getHost();
            String hb = URI.create(b).getHost();
            return ha != null && ha.equalsIgnoreCase(hb);
        } catch (Exception e) {
            return false;
        }
    }

    // The server's own statement that nothing is at the address - 404 or 410 - anywhere in the
    // cause chain.
    private static boolean notThere(Throwable e) {
        org.springframework.web.client.HttpStatusCodeException http = SyncErrorAware.httpStatusIn(e);
        if (http == null) return false;
        int status = http.getStatusCode().value();
        return status == 404 || status == 410;
    }

    // The server's own refusal - 401 or 403 - anywhere in the cause chain.
    private static boolean refused(Throwable e) {
        org.springframework.web.client.HttpStatusCodeException http = SyncErrorAware.httpStatusIn(e);
        if (http == null) return false;
        int status = http.getStatusCode().value();
        return status == 401 || status == 403;
    }

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static boolean isPdfUrl(String url) {
        return "pdf".equals(extensionOf(url));
    }

    private static String extensionOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        return dot >= 0 ? last.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String lastSegment(String url) {
        String path = canonical(url);
        if (path == null) return url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
        return last.isBlank() ? url : last;
    }

    private static int parseInt(String value, int def, int min, int max) {
        if (value == null || value.isBlank()) return def;
        try {
            int v = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Pattern compilePattern(String regex) {
        if (regex == null || regex.isBlank()) return null;
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
    }

    // Minimal robots.txt: honour Disallow prefixes for User-agent: * (and our own agent). Enough to
    // respect a site's crawl wishes without a full RFC 9309 implementation.
    private static final class RobotsTxt {
        private final List<String> disallow;

        private RobotsTxt(List<String> disallow) {
            this.disallow = disallow;
        }

        static RobotsTxt fetch(RestClient client, String origin) {
            List<String> disallow = new ArrayList<>();
            try {
                String txt = siteFile(client, origin + "/robots.txt");
                if (txt != null) {
                    boolean applies = false;
                    for (String raw : txt.split("\n")) {
                        String line = raw.trim();
                        int c = line.indexOf('#');
                        if (c >= 0) line = line.substring(0, c).trim();
                        if (line.isEmpty()) continue;
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("user-agent:")) {
                            String ua = line.substring(11).trim();
                            applies = ua.equals("*") || ua.toLowerCase(Locale.ROOT).contains("proxyma");
                        } else if (applies && lower.startsWith("disallow:")) {
                            String path = line.substring(9).trim();
                            if (!path.isEmpty()) disallow.add(path);
                        }
                    }
                }
            } catch (Exception e) {
                // No robots.txt (or unreachable) -> crawl is allowed.
            }
            return new RobotsTxt(disallow);
        }

        boolean allowed(String url) {
            if (disallow.isEmpty()) return true;
            String path;
            try {
                path = URI.create(url).getPath();
            } catch (Exception e) {
                return true;
            }
            if (path == null || path.isEmpty()) path = "/";
            for (String rule : disallow) {
                if (rule.equals("/")) return false;
                if (path.startsWith(rule)) return false;
            }
            return true;
        }
    }
}
