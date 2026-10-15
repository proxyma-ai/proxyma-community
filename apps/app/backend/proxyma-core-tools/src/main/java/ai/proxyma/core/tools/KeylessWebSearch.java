package ai.proxyma.core.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web search with NO API key and no configuration, the way a person opening a search box gets it.
 *
 * <p>Queries DuckDuckGo's HTML endpoint over ordinary HTTP and reads the result list out of the
 * markup. That is scraping, and the honest summary of the trade is: this removes a setup step and
 * a per-query bill, and accepts that it can break when the markup changes and can be rate-limited
 * or served a bot check. The rate limit is not hypothetical and it is not brief - see
 * {@link #isBotCheck} for what was measured. A configured Google Custom Search key still wins when
 * one is present - see WebSearchTool, which prefers it.
 *
 * <p><b>Why DuckDuckGo rather than Google.</b> The request was "like a human opening Google", but
 * Google is the one engine that cannot be done this way: it detects non-browser clients quickly,
 * answers with a consent wall or a CAPTCHA, and its terms prohibit automated result retrieval
 * outright. DuckDuckGo publishes this endpoint as plain server-rendered HTML with no key and no
 * JavaScript, which is why every keyless implementation of this feature uses it.
 *
 * <p><b>Why plain HTTP rather than the headless browser.</b> Playwright is bundled on DESKTOP,
 * where the installer stages a Chromium and js-engine defaults to "auto". Server mode defaults to
 * htmlunit and its image ships no browser, so a browser-driven search would work on one edition
 * and fail on the other - and the whole point of this change is that search works with nothing
 * configured. One HTTP GET through the existing proxy-aware client works identically on both.
 */
@Component
class KeylessWebSearch {

    private static final Logger log = LoggerFactory.getLogger(KeylessWebSearch.class);

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String LITE_ENDPOINT = "https://lite.duckduckgo.com/lite/";

    // The same identity the rendered fetch path presents. Without a browser User-Agent this
    // endpoint serves a reduced page, so it is load-bearing rather than cosmetic.
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private final RestClient externalRestClient;

    KeylessWebSearch(@Qualifier("externalRestClientBuilder") RestClient.Builder builder) {
        this.externalRestClient = builder.build();
    }

    /**
     * @return up to {@code limit} results, each with title, link and snippet.
     *
     * <p>TWO ENDPOINTS, since 2026-09-30. The HTML one is asked first; when it serves its bot check,
     * the LITE one - the same engine's plainer page, a table of links - is asked once before giving
     * up. The maintainer's self test was served the check by the first. Whether the two pages are
     * limited separately is not something this side can know; asking the second costs one request
     * and can turn a failure into an answer. Still one request each and no retry loop, for the
     * reason {@link #isBotCheck} gives.
     */
    List<Map<String, Object>> search(String query, int limit) {
        try {
            return searchHtml(query, limit);
        } catch (BotCheckException blocked) {
            log.info("[WebSearch] the HTML endpoint served a bot check; asking the lite one");
            try {
                return searchLite(query, limit);
            } catch (BotCheckException alsoBlocked) {
                throw new IllegalStateException(
                        "Web search was rate-limited - the search engine served a bot check instead "
                                + "of results. This clears on its own; searching again in a few minutes "
                                + "is the fix, and searching repeatedly while it is blocked is not.");
            }
        }
    }

    /** The bot check, told apart from every other failure so the second endpoint is tried for it alone. */
    static final class BotCheckException extends RuntimeException {
        BotCheckException() {
            super("bot check");
        }
    }

    private Document fetch(String endpoint, String query) {
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        ResponseEntity<String> response = externalRestClient.get()
                .uri(URI.create(url))
                .header(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                    throw new IllegalStateException(
                            "Web search returned HTTP " + res.getStatusCode().value()
                                    + ". The search endpoint may be rate-limiting this host.");
                })
                .toEntity(String.class);
        String html = response.getBody();
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("Web search returned an empty page.");
        }
        Document doc = Jsoup.parse(html, endpoint);
        if (isBotCheck(response.getStatusCode().value(), doc)) throw new BotCheckException();
        return doc;
    }

    private List<Map<String, Object>> searchHtml(String query, int limit) {
        List<Map<String, Object>> results = parseHtmlResults(fetch(ENDPOINT, query), limit);
        if (results.isEmpty()) log.debug("[WebSearch] no matches for '{}'", query);
        return results;
    }

    private List<Map<String, Object>> searchLite(String query, int limit) {
        List<Map<String, Object>> results = parseLiteResults(fetch(LITE_ENDPOINT, query), limit);
        if (results.isEmpty()) log.debug("[WebSearch] no matches for '{}' (lite)", query);
        return results;
    }

    /** The HTML endpoint's result list. Package-private for its test. */
    static List<Map<String, Object>> parseHtmlResults(Document doc, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Element link : doc.select("a.result__a")) {
            if (results.size() >= limit) break;
            Element block = link.closest(".result");

            // ADS ARE DROPPED. The endpoint returns sponsored results in the same list, marked with
            // result--ad, and their href is a duckduckgo.com/y.js click tracker rather than the
            // destination - so an unfiltered list hands the model an advertiser's landing page as
            // though it were the best answer, behind a URL that says nothing about where it goes.
            // Observed live on the very first query run against this: a Udemy ad outranked the
            // Spring documentation being searched for.
            if (block != null && block.className().contains("result--ad")) continue;
            if (link.closest(".results_links_deep.result--ad") != null) continue;

            String href = unwrap(link.attr("href"));
            if (href == null || href.isBlank()) continue;
            // Belt and braces for the same problem: anything still pointing back at the search
            // engine is a redirector or tracker this did not manage to unwrap, and is never a
            // result worth returning.
            if (isSearchEngineUrl(href)) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", link.text());
            entry.put("link", href);
            // The snippet is a sibling within the same result block, not a child of the anchor.
            Element snippet = block == null ? null : block.selectFirst(".result__snippet");
            entry.put("snippet", snippet == null ? "" : snippet.text());
            results.add(entry);
        }

        if (results.isEmpty()) {
            // Distinguished from "no matches" by the absence of the result container entirely: a
            // genuine zero-result page still renders one. The bot check was ruled out above, so
            // reaching here means the markup actually moved - and the selectors above are then
            // worth inspecting, which is exactly the wrong advice to give for a rate limit.
            if (doc.selectFirst(".result") == null && doc.selectFirst("#links") == null) {
                throw new IllegalStateException(
                        "Web search returned a page with no result list - the search endpoint "
                                + "appears to have changed its markup.");
            }
        }
        return results;
    }

    /**
     * The lite endpoint's table: each result is a row holding {@code a.result-link}, and its
     * snippet is {@code td.result-snippet} in the row after it. Links go through the same
     * redirector, and ads through the same tracker, as on the HTML endpoint.
     */
    static List<Map<String, Object>> parseLiteResults(Document doc, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Element link : doc.select("a.result-link")) {
            if (results.size() >= limit) break;
            Element row = link.closest("tr");
            if (row != null && row.className().contains("sponsored")) continue;
            String href = unwrap(link.attr("href"));
            if (href == null || href.isBlank() || isSearchEngineUrl(href)) continue;
            Element next = row == null ? null : row.nextElementSibling();
            Element snippet = next == null ? null : next.selectFirst("td.result-snippet");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", link.text());
            entry.put("link", href);
            entry.put("snippet", snippet == null ? "" : snippet.text());
            results.add(entry);
        }
        if (results.isEmpty() && doc.selectFirst("table") == null) {
            throw new IllegalStateException(
                    "Web search returned a page with no result list - the search endpoint "
                            + "appears to have changed its markup.");
        }
        return results;
    }

    /**
     * The bot check does NOT arrive as an error status - the endpoint answers <b>HTTP 202</b> with a
     * challenge page, which {@code is2xxSuccessful()} waves through. Confirmed live on 2026-08-22:
     * roughly a dozen queries locked this host out for over 25 minutes, and a fully quiet 10-minute
     * gap did not clear it, so a retry loop cannot rescue this and is not attempted.
     *
     * <p>Kept separate from the markup-change branch deliberately. Both produce a page with no
     * results, but they call for opposite responses - wait, versus go and fix these selectors - and
     * reporting the wrong one sends whoever is debugging it at the only clue that would have helped.
     */
    static boolean isBotCheck(int status, Document doc) {
        // Matched on the class PREFIX: the challenge page carries anomaly-modal__mask,
        // anomaly-modal__box and a dozen more siblings, and pinning any single one of them invites
        // this check to rot silently the next time the page is restyled.
        return doc.selectFirst("[class*=anomaly-modal]") != null
                || (status == 202 && doc.selectFirst(".result") == null);
    }

    /**
     * DuckDuckGo hands back redirector links - {@code //duckduckgo.com/l/?uddg=<encoded target>} -
     * rather than the destination. Unwrapped here so a caller gets a URL it can actually fetch,
     * and so the model is not shown a hostname that says nothing about the source.
     */
    /** True for a link back into the search engine itself - a redirector, a tracker, or a
     *  "more results" control. Never a destination worth returning. */
    private static boolean isSearchEngineUrl(String href) {
        try {
            String host = URI.create(href).getHost();
            return host != null && host.toLowerCase().endsWith("duckduckgo.com");
        } catch (Exception e) {
            // Unparseable means it is not a URL the caller could fetch either.
            return true;
        }
    }

    private static String unwrap(String href) {
        if (href == null) return null;
        String h = href.startsWith("//") ? "https:" + href : href;
        int marker = h.indexOf("uddg=");
        if (marker < 0) return h;
        String encoded = h.substring(marker + "uddg=".length());
        int amp = encoded.indexOf('&');
        if (amp >= 0) encoded = encoded.substring(0, amp);
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return h;
        }
    }
}
