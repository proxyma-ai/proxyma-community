package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A crawl that stopped at its page cap is not a crawl that found the whole site.
 *
 * <p>The iterator finished cleanly, so nothing was errored and the source was right to stay ACTIVE -
 * and SyncService then purged every page past the cap, because a page missing from a complete
 * listing is a page that was deleted upstream. On a 400-page site with the Community 100-page
 * ceiling that deleted pages 101-400 on every single run, re-crawled the same first 100, and
 * reported a clean sync each time. {@link PartialSyncAware} is the difference, and this pins that
 * the crawl iterator reports it - and, just as importantly, that a crawl which really did reach
 * every page does NOT, since that one must stay purge-eligible.
 *
 * <p>Served over a real loopback HTTP server rather than a mocked fetch: the cap is a property of
 * the breadth-first walk, the queue and the visited set together, and stubbing the fetch would test
 * the counter instead of the crawl.
 */
class WebCrawlPartialSyncTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = pageFor(path);
            if (body == null) {
                // robots.txt and sitemap.xml included: the crawler asks for both and treats a
                // refusal as "no rules, no seeds", which is the ordinary case for a small site.
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    // A hub with three leaves, and a second hub with one, so a test can choose between a site
    // bigger than its cap and a site the cap comfortably covers.
    private static String pageFor(String path) {
        return switch (path) {
            case "/" -> page("Hub", "<a href=\"/a\">a</a><a href=\"/b\">b</a><a href=\"/c\">c</a>");
            case "/solo" -> page("Solo", "<a href=\"/only\">only</a>");
            case "/dead" -> page("Dead links", "<a href=\"/only\">only</a><a href=\"/gone1\">1</a><a href=\"/gone2\">2</a>");
            case "/a", "/b", "/c", "/only" -> page("Leaf " + path, "<p>Leaf content for " + path + "</p>");
            default -> null;
        };
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><title>" + title + "</title></head><body><main>"
                + body + "</main></body></html>";
    }

    private static Iterator<ItemMetadata> crawl(String seedPath, int maxPages, List<ItemMetadata> into) {
        WebCrawlConnector connector = new WebCrawlConnector(
                RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());
        Iterator<ItemMetadata> it = connector.open(SourceContext.of(ConnectorType.WEB_PAGE,
                Map.of(
                "seedUrl", base + seedPath,
                "maxPages", String.valueOf(maxPages),
                "renderJs", "false"), null, null))
                .list(ListRequest.full());
        while (it.hasNext()) into.add(it.next());
        return it;
    }

    @Test
    @DisplayName("a crawl that hits its page cap reports isPartial, and is not an error")
    void cappedCrawlIsPartial() {
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = crawl("/", 2, items);

        assertEquals(2, items.size(), "the cap is what stopped it, so it must have emitted exactly that many");
        PartialSyncAware partial = assertInstanceOf(PartialSyncAware.class, it,
                "a crawl iterator must be able to say it did not cover the whole site");
        assertTrue(partial.isPartial());
        assertNotNull(partial.getPartialReason(), "the reason is the log line explaining the skipped purge");

        // Not an error: nothing broke, the ceiling did exactly what it was configured to do, and
        // putting the source in ERROR for that would be wrong.
        assertFalse(it instanceof SyncErrorAware aware && aware.hasErrored());
    }

    @Test
    @DisplayName("a crawl that reached every page is complete, so purge-by-absence stays on")
    void exhaustedCrawlIsNotPartial() {
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = crawl("/solo", 50, items);

        assertEquals(2, items.size());
        assertFalse(((PartialSyncAware) it).isPartial(),
                "a complete listing must stay purge-eligible, or a page deleted upstream is never removed");
    }

    @Test
    @DisplayName("pages that would not load are reported with WHY, not only how many")
    void skippedPagesSayWhy() {
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = crawl("/dead", 50, items);

        assertEquals(2, items.size(), "the seed and the one live leaf");
        PartialSyncAware partial = (PartialSyncAware) it;
        assertTrue(partial.isPartial(), "a listing that skipped pages is not proof they were deleted");
        String reason = partial.getPartialReason();
        // The count, the cause the server gave, and which pages - "2 pages could not be loaded"
        // alone told the reader there was a problem and nothing they could do about it.
        assertTrue(reason.contains("2 pages could not be loaded"), reason);
        assertTrue(reason.contains("404"), reason);
        assertTrue(reason.contains("the page no longer exists"), reason);
        assertTrue(reason.contains(base + "/gone1") && reason.contains("and 1 more"), reason);
    }
}
