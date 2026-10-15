package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.PartialSyncAware;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The same page under another address is not another page.
 *
 * <p>A wiki answers every members-only link with one notice, and a dozen variants of one address
 * with one page. Each was listed, counted against the page limit and indexed, and each one's
 * links were followed in turn - so a crawl pointed at one wiki page spent its whole budget on
 * copies. Served over a real loopback server for the reason WebCrawlPartialSyncTest gives: this
 * is a property of the walk, not of one fetch.
 *
 * <p>The page repeated here is NOT a sign-in page, which is never listed at all - see
 * WebCrawlSignInPageTest.
 */
class WebCrawlSamePageTest {

    private static HttpServer server;
    private static String base;
    private static final List<String> requested = new CopyOnWriteArrayList<>();

    private static final String MEMBERS_ONLY = page("Members only", "<p>This part of the wiki is for members.</p>"
            + "<a href=\"/forgot\">Forgot password</a><a href=\"/signup\">Sign up</a>");

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            String body = switch (path) {
                case "/wiki" -> page("Home", "<p>The wiki's front page.</p>"
                        + "<a href=\"/private/1\">one</a><a href=\"/private/2\">two</a>"
                        + "<a href=\"/private/3\">three</a><a href=\"/open\">open</a>");
                // Everything private answers with the one notice.
                case "/private/1", "/private/2", "/private/3" -> MEMBERS_ONLY;
                case "/open" -> page("Open page", "<p>Something anyone may read.</p>");
                case "/forgot", "/signup" -> page("Account " + path, "<p>Account help for " + path + "</p>");
                default -> null;
            };
            if (body == null) {
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

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><title>" + title + "</title></head><body><main>"
                + body + "</main></body></html>";
    }

    private static Iterator<ItemMetadata> crawl(int maxPages, List<ItemMetadata> into) {
        WebCrawlConnector connector = new WebCrawlConnector(
                RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());
        Iterator<ItemMetadata> it = connector.open(SourceContext.of(ConnectorType.WEB_PAGE,
                Map.of(
                "seedUrl", base + "/wiki",
                "maxPages", String.valueOf(maxPages),
                "renderJs", "false"), null, null))
                .list(ListRequest.full());
        while (it.hasNext()) into.add(it.next());
        return it;
    }

    @Test
    @DisplayName("a page served under three addresses is listed once")
    void copiesAreListedOnce() {
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = crawl(50, items);

        List<String> listed = items.stream().map(i -> i.getId().substring(base.length())).toList();
        assertEquals(List.of("/wiki", "/private/1", "/open", "/forgot", "/signup"), listed);
        // Nothing failed, so the crawl is a complete one - a copy is not a page that would not load.
        assertFalse(assertInstanceOf(PartialSyncAware.class, it).isPartial());
    }

    @Test
    @DisplayName("copies do not use up the page limit")
    void copiesDoNotCountAgainstTheLimit() {
        List<ItemMetadata> items = new ArrayList<>();
        crawl(3, items);

        // Three pages: the front page, the notice once, and the open page - which three copies of
        // the notice used to crowd out.
        List<String> listed = items.stream().map(i -> i.getId().substring(base.length())).toList();
        assertEquals(List.of("/wiki", "/private/1", "/open"), listed);
    }
}
