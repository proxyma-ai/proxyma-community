package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.ConnectorException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A sign-in page is not a page of the site.
 *
 * <p>A wiki behind single sign-on answered its private addresses with the identity provider's
 * sign-in page, and seven of them were listed, titled "Sign in to your account" and indexed as
 * documents of the wiki. The same-page check could not see them as copies: every one carries its
 * own request id in its links, which is what the page served here does.
 */
class WebCrawlSignInPageTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicInteger requests = new AtomicInteger();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/wiki" -> page("Home", "<p>The wiki's front page.</p>"
                        + "<a href=\"/private/1\">one</a><a href=\"/private/2\">two</a>"
                        + "<a href=\"/private/3\">three</a><a href=\"/open\">open</a>"
                        + "<a href=\"/guide\">guide</a><a href=\"/portal\">portal</a>"
                        + "<a href=\"/notes\">notes</a>");
                case "/private/1", "/private/2", "/private/3" -> signIn();
                case "/open" -> page("Open page", "<p>Something anyone may read.</p>");
                // Documentation ABOUT signing in: the title of a sign-in page, the length of a chapter.
                case "/guide" -> page("Sign in to your account", "<p>" + "How to sign in, step by step. ".repeat(80) + "</p>");
                // A sign-in page with no telling title: next to nothing said, and a password asked for.
                case "/portal" -> page("Portal", "<p>Members.</p>"
                        + "<form><input type=\"text\" name=\"u\"><input type=\"password\" name=\"p\"></form>");
                // An article on a site that puts a login box on every page.
                case "/notes" -> page("Release notes", "<header><form><input type=\"password\" name=\"p\"></form></header>"
                        + "<p>" + "What changed in this release. ".repeat(30) + "</p>");
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

    // What an identity provider answers: its own title, a line of text, and links that carry this
    // request's id - so no two copies say exactly the same thing.
    private static String signIn() {
        int n = requests.incrementAndGet();
        return page("Sign in to your account", "<p>Sign in</p>"
                + "<a href=\"https://login.example.com/reset?ctx=" + n + "\">Can't access your account?</a>"
                + "<a href=\"https://login.example.com/options?ctx=" + n + "\">Sign-in options</a>");
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><title>" + title + "</title></head><body><main>"
                + body + "</main></body></html>";
    }

    private static ConnectorSession connector(String seed) {
        WebCrawlConnector connector = new WebCrawlConnector(
                RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());
        return connector.open(SourceContext.of(ConnectorType.WEB_PAGE, Map.of(
                "seedUrl", base + seed,
                "maxPages", "50",
                "renderJs", "false"), null, null));
    }

    private static List<String> paths(List<ItemMetadata> items) {
        return items.stream().map(i -> i.getId().substring(base.length())).toList();
    }

    @Test
    @DisplayName("sign-in pages are not listed, however their links differ, and the crawl is a complete one")
    void signInPagesAreNotItems() {
        List<ItemMetadata> items = new ArrayList<>();
        Iterator<ItemMetadata> it = connector("/wiki").list(ListRequest.full());
        while (it.hasNext()) items.add(it.next());

        assertEquals(List.of("/wiki", "/open", "/guide", "/notes"), paths(items));
        PartialSyncAware listing = assertInstanceOf(PartialSyncAware.class, it);
        // Nothing failed: a wiki links to its sign-in address from every page.
        assertFalse(listing.isPartial(), String.valueOf(listing.getPartialReason()));
        // And an earlier crawl's copy of each is something this one can vouch is not a page.
        assertEquals(Set.of(base + "/private/1", base + "/private/2", base + "/private/3", base + "/portal"),
                listing.getGoneItemIds());
    }

    @Test
    @DisplayName("a site whose first page is its sign-in page fails the sync and says why")
    void aSeedThatIsASignInPage() {
        Iterator<ItemMetadata> it = connector("/private/1").list(ListRequest.full());
        // The crawl stops on the seed, and the listing carries why - the sentence the source shows.
        assertFalse(it.hasNext());
        SyncErrorAware failure = assertInstanceOf(SyncErrorAware.class, it);
        assertTrue(failure.hasErrored());
        assertTrue(failure.getErrorMessage().contains("sign-in page"), failure.getErrorMessage());
    }

    @Test
    @DisplayName("a page already tracked that now answers with a sign-in page fails, and is not indexed as one")
    void aTrackedPageThatBecameASignInPage() {
        ConnectorSession connector = connector("/wiki");
        ItemMetadata tracked = new ItemMetadata(base + "/private/2", "Design notes", ConnectorType.WEB_PAGE,
                Instant.now(), "text/markdown");
        ConnectorException failure = assertThrows(ConnectorException.class, () -> connector.fetch(tracked));
        assertTrue(failure.getMessage().contains("sign-in page"), failure.getMessage());
    }
}
