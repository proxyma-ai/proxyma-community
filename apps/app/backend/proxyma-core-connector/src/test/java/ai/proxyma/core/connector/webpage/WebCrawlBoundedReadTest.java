package ai.proxyma.core.connector.webpage;

import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.webcontent.HtmlContentExtractor;
import ai.proxyma.core.webcontent.WebRenderService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * What the crawler reads of a response. The whole body was read into memory before the page and
 * PDF ceilings were applied, so a multi-gigabyte download behind a ".pdf" link could exhaust the
 * server's heap; and every page was decoded as UTF-8 whatever its charset said.
 */
@org.junit.jupiter.api.Timeout(60)
class WebCrawlBoundedReadTest {

    private HttpServer server;
    private String base;
    private final WebCrawlConnector crawler = new WebCrawlConnector(
            // The JDK client, as production uses (RestClientConfig).
            RestClient.builder().requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()),
            mock(WebRenderService.class), new HtmlContentExtractor());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/huge.pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            // Declares 300 MB and sends a sliver: the crawler must refuse on the header alone.
            exchange.sendResponseHeaders(200, 300L * 1024 * 1024);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
            } catch (Exception closedEarly) {
                // The client hangs up once it has read the header, which is the point.
            }
        });
        server.createContext("/latin.html", exchange -> {
            byte[] page = ("<html><head><title>Menu</title></head><body><main><p>Café crème brûlée "
                    + "on the terrace every afternoon.</p></main></body></html>").getBytes(Charset.forName("windows-1252"));
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=windows-1252");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // A session of a source on this server - a fetch is always a session's.
    private ConnectorSession session() {
        return crawler.open(SourceContext.of(ConnectorType.WEB_PAGE, java.util.Map.of("seedUrl", base + "/"), null, null));
    }

    private static ItemMetadata item(String url, String type) {
        ItemMetadata meta = new ItemMetadata();
        meta.setId(url);
        meta.setContentType(type);
        return meta;
    }

    @Test
    @DisplayName("a PDF declaring more than the limit is refused before its body is read")
    void oversizedPdfIsRefused() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> session().fetch(item(base + "/huge.pdf", "application/pdf")));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    @Test
    @DisplayName("a page is decoded in the charset it declares")
    void pageCharsetIsHonored() throws Exception {
        try (InputStream in = session().fetch(item(base + "/latin.html", "text/html"))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("Café crème brûlée"), text);
        }
    }
}
