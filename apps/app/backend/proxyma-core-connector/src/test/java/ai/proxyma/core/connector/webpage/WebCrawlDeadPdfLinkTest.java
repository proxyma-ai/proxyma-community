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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A dead link to a PDF is a skipped page, not a document that fails to index.
 *
 * <p>A PDF used to be listed as an item the moment its link was seen and fetched only when it was
 * indexed. So a link the site itself had left dangling - a vendor's docs portal pointing at a
 * manual it no longer hosts, 404 for a browser too - became a failed document on the source,
 * retried three times and then left there, reading as "PDF pages do not work" (2026-10-01). The
 * same dead link to an HTML page was already skipped and reported with its reason.
 *
 * <p>A real loopback server, as in {@link WebCrawlPartialSyncTest}: what is under test is which
 * requests the crawl makes for a PDF and what it does with each answer.
 */
class WebCrawlDeadPdfLinkTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            byte[] body = null;
            String type = "text/html; charset=utf-8";
            switch (path) {
                case "/" -> body = page("Manuals", "<a href=\"/manuals/live.pdf\">live</a>"
                        + "<a href=\"/manuals/dead.pdf\">dead</a>").getBytes(StandardCharsets.UTF_8);
                case "/picky" -> body = page("Picky", "<a href=\"/manuals/no-head.pdf\">no head</a>")
                        .getBytes(StandardCharsets.UTF_8);
                case "/manuals/live.pdf" -> {
                    body = "%PDF-1.4 live".getBytes(StandardCharsets.UTF_8);
                    type = "application/pdf";
                }
                case "/manuals/no-head.pdf" -> {
                    // A server that answers GET and refuses HEAD says nothing about the file.
                    if (head) {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                        return;
                    }
                    body = "%PDF-1.4 no-head".getBytes(StandardCharsets.UTF_8);
                    type = "application/pdf";
                }
                default -> { /* 404 below: /manuals/dead.pdf, robots.txt, sitemap.xml */ }
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            if (head) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
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

    private static Iterator<ItemMetadata> crawl(String seedPath, List<ItemMetadata> into) {
        WebCrawlConnector connector = new WebCrawlConnector(
                RestClient.builder(), mock(WebRenderService.class), new HtmlContentExtractor());
        Iterator<ItemMetadata> it = connector.open(SourceContext.of(ConnectorType.WEB_PAGE,
                Map.of(
                "seedUrl", base + seedPath, "maxPages", "50", "renderJs", "false"), null, null))
                .list(ListRequest.full());
        while (it.hasNext()) into.add(it.next());
        return it;
    }

    private static List<String> ids(List<ItemMetadata> items) {
        return items.stream().map(ItemMetadata::getId).toList();
    }

    @Test
    @DisplayName("a PDF the server says is not there is not listed as an item")
    void aDeadPdfIsNotAnItem() {
        List<ItemMetadata> items = new ArrayList<>();
        crawl("/", items);

        assertEquals(List.of(base + "/", base + "/manuals/live.pdf"), ids(items));
        assertEquals("application/pdf", items.get(1).getContentType());
    }

    @Test
    @DisplayName("it is reported with the other skipped pages, with the server's answer and its address")
    void itIsReportedAsASkippedPage() {
        List<ItemMetadata> items = new ArrayList<>();
        PartialSyncAware partial = (PartialSyncAware) crawl("/", items);

        assertTrue(partial.isPartial(), "a link that would not load is not proof the page was deleted");
        String reason = partial.getPartialReason();
        assertTrue(reason.contains("1 page could not be loaded"), reason);
        assertTrue(reason.contains("404"), reason);
        assertTrue(reason.contains(base + "/manuals/dead.pdf"), reason);
    }

    @Test
    @DisplayName("a server that refuses HEAD has said nothing about the PDF, so it is still listed")
    void aRefusedHeadIsNotADeadLink() {
        List<ItemMetadata> items = new ArrayList<>();
        PartialSyncAware partial = (PartialSyncAware) crawl("/picky", items);

        assertEquals(List.of(base + "/picky", base + "/manuals/no-head.pdf"), ids(items));
        assertFalse(partial.isPartial());
    }
}
