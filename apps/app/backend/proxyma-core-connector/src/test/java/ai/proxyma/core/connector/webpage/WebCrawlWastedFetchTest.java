package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A crawl does not keep asking an address that has said what it is.
 *
 * <p>A wiki page links to its sign-in address, its history, its page information and its export,
 * one of each per page and each with the page in the query string. A crawl of one wiki page fetched
 * every one of them - the sign-in page again, a refusal asked for three times, a Word document
 * downloaded to learn it is not a page - and spent most of its time on addresses that could never
 * become an item. Measured against a public wiki on 2026-10-02: 25 pages listed, 18 copies and 7
 * refusals fetched beside them.
 */
class WebCrawlWastedFetchTest {

    private static HttpServer server;
    private static String base;
    private static final List<String> requested = new CopyOnWriteArrayList<>();

    private static final String SIGN_IN = page("Log in", "<p>Log in to continue.</p>");

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            requested.add(path);
            switch (path) {
                case "/wiki" -> html(exchange, page("Home", "<p>The wiki's front page.</p>"
                        + links("/login?dest=", 6) + links("/info?id=", 5) + links("/export?id=", 5)
                        + links("/page?id=", 4)));
                case "/login" -> html(exchange, SIGN_IN);
                case "/info" -> status(exchange, 403);
                case "/export" -> send(exchange, 200, "application/msword", "not a page");
                case "/page" -> html(exchange, page("Page " + query, "<p>What page " + query + " says.</p>"));
                // A site that answers every address with its sign-in page.
                case "/walled" -> html(exchange, page("Walled", "<p>A front page.</p>" + links("/room/", 20)));
                default -> {
                    if (path.startsWith("/room/")) html(exchange, SIGN_IN);
                    else status(exchange, 404);
                }
            }
        });
        server.start();
        base = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    private static String links(String prefix, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= count; i++) out.append("<a href=\"").append(prefix).append(i).append("\">").append(i).append("</a>");
        return out.toString();
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><title>" + title + "</title></head><body><main>"
                + body + "</main></body></html>";
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "text/html; charset=utf-8", body);
    }

    private static void status(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Iterator<ItemMetadata> crawl(String seed, int maxPages, List<ItemMetadata> into) {
        WebCrawlConnector connector = new WebCrawlConnector(
                RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());
        Iterator<ItemMetadata> it = connector.open(SourceContext.of(ConnectorType.WEB_PAGE,
                Map.of(
                "seedUrl", base + seed,
                "maxPages", String.valueOf(maxPages),
                "renderJs", "false"), null, null))
                .list(ListRequest.full());
        while (it.hasNext()) into.add(it.next());
        return it;
    }

    // One crawl of the wiki, read by the four cases below: every fetch waits out the crawl's own
    // pacing, so four crawls would be twenty seconds of the suite for the same evidence.
    private static List<ItemMetadata> wikiItems;
    private static List<String> wikiRequests;

    private static synchronized void crawlWikiOnce() {
        if (wikiItems != null) return;
        requested.clear();
        List<ItemMetadata> items = new ArrayList<>();
        crawl("/wiki", 50, items);
        wikiItems = items;
        wikiRequests = List.copyOf(requested);
    }

    private static long asked(String path) {
        crawlWikiOnce();
        return wikiRequests.stream().filter(path::equals).count();
    }

    @Test
    @DisplayName("an address whose variants keep answering with the sign-in page is asked three times, not for every variant")
    void repeatingVariantsAreLeftAlone() {
        // The sign-in page three times - never a page, since 2026-10-02 - and the last three not at all.
        assertEquals(3, asked("/login"));
        assertEquals(0, wikiItems.stream().filter(i -> i.getId().contains("/login")).count());
    }

    @Test
    @DisplayName("a refusal is asked for once, and three refusals settle the address")
    void refusalsAreNotRetried() {
        // Five links, three asked, each ONCE - it was three attempts apiece with a growing pause.
        assertEquals(3, asked("/info"));
    }

    @Test
    @DisplayName("an address that serves downloads is left alone after three")
    void downloadsAreLeftAlone() {
        assertEquals(3, asked("/export"));
    }

    @Test
    @DisplayName("an address whose variants are pages is asked for every one")
    void realVariantsAreAllListed() {
        assertEquals(4, asked("/page"));
        assertEquals(4, wikiItems.stream().filter(i -> i.getId().contains("/page?id=")).count());
    }

    @Test
    @DisplayName("a site answering everything with its sign-in page stops after as many copies as its page limit")
    void copiesHaveABudget() {
        crawlWikiOnce();
        requested.clear();
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = crawl("/walled", 5, items);

        // The front page is the one item; five sign-in pages end the crawl, with fifteen rooms
        // never asked for.
        assertEquals(1, items.size());
        assertEquals(5, requested.stream().filter(p -> p.startsWith("/room/")).count());
        PartialSyncAware partial = assertInstanceOf(PartialSyncAware.class, it);
        assertTrue(partial.isPartial());
        assertTrue(partial.getPartialReason().contains("sign-in page"), partial.getPartialReason());
    }
}
