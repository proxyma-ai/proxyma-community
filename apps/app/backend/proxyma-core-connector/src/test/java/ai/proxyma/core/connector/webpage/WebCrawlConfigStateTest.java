package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * What the crawler does with a configuration it cannot use - without crawling anything.
 *
 * <ul>
 *   <li>An include pattern that does not compile was read as "no filter", so a crawl asked to stay
 *       inside one manual walked the whole site. It is the source's error now.</li>
 *   <li>auth() returned on an invalid seed before clearing what an earlier source had left on the
 *       thread - one a registration probe set and nothing consumed - and the next sync on that
 *       thread crawled the earlier source's seed. A session holds its own configuration now; this
 *       pins that an invalid one is said, never filled from anywhere else.</li>
 *   <li>The bounds a row carries are clamped to the product's ceilings when a session opens, so a
 *       configuration stored past them cannot crawl past them.</li>
 * </ul>
 */
class WebCrawlConfigStateTest {

    private final WebCrawlConnector crawler = new WebCrawlConnector(
            RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());

    private ConnectorSession session(String seed, String includePattern) {
        Map<String, String> values = new HashMap<>();
        if (seed != null) values.put("seedUrl", seed);
        if (includePattern != null) values.put("includePattern", includePattern);
        return crawler.open(SourceContext.of(ConnectorType.WEB_PAGE, values, null, null));
    }

    @Test
    @DisplayName("an include pattern that does not compile fails the sync and the probe, never \"no filter\"")
    void invalidIncludePatternIsAnError() {
        assertTrue(session("https://docs.example.test/", "/docs/(v2|v3").verifyAccess().rejected());

        Iterator<ItemMetadata> listing = session("https://docs.example.test/", "/docs/(v2|v3").list(ListRequest.full());
        assertFalse(listing.hasNext());
        assertTrue(((SyncErrorAware) listing).hasErrored());
        assertTrue(((SyncErrorAware) listing).getErrorMessage().contains("include pattern"));
    }

    @Test
    @DisplayName("a source with an invalid seed never runs on the configuration an earlier one left behind")
    void invalidSeedClearsThePreviousConfiguration() {
        // An earlier source's session, opened for a probe and never used.
        session("https://first.example.test/", null);

        ConnectorSession invalid = session("not a web address", null);
        assertTrue(invalid.verifyAccess().rejected());
        Iterator<ItemMetadata> listing = invalid.list(ListRequest.full());
        assertFalse(listing.hasNext());
        assertTrue(((SyncErrorAware) listing).hasErrored(), "the first source's crawl must not run here");
    }

    @Test
    @DisplayName("bounds stored past the product's caps are capped when a session opens")
    void storedBoundsAreCapped() {
        WebCrawlConnector.CrawlConfig cfg = WebCrawlConnector.config(Map.of("seedUrl", "https://docs.example.test",
                "maxDepth", "500", "maxPages", "900000", "includePattern", ".*"), "ana");
        assertEquals(WebCrawlConnector.MAX_CRAWL_PAGES, cfg.maxPages());
        assertEquals(WebCrawlConnector.MAX_CRAWL_DEPTH, cfg.maxDepth());
        assertEquals("ana", cfg.ownerUserId());
    }
}
