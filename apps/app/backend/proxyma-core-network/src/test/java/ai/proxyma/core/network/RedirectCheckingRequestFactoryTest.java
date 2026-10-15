package ai.proxyma.core.network;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The redirect half of the sandbox's private-address rule. What it pins was measured, not assumed:
 * the JDK client asks its ProxySelector about the first request only, so a public URL answering
 * 302 to a private address walked straight past the first-hop check.
 *
 * <p>The server here is on loopback, reached as "localhost" - which the test ALLOWS - and redirects
 * to "127.0.0.1", which it does not. Same machine, two names, one allowed: that is the whole case.
 */
class RedirectCheckingRequestFactoryTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/to-private", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/secret");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/to-allowed", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/ok", ex -> {
            byte[] b = "fine".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        OutboundAddressPolicy.setAllowedHosts(List.of("localhost"));
        OutboundAddressPolicy.setEnabled(true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        OutboundAddressPolicy.setEnabled(false);
        OutboundAddressPolicy.setAllowedHosts(null);
    }

    private RedirectCheckingRequestFactory factory() {
        return new RedirectCheckingRequestFactory(new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
    }

    @Test
    @DisplayName("a redirect from an allowed host to a private one is refused at the hop")
    void refusesARedirectToAPrivateAddress() {
        URI start = URI.create("http://localhost:" + port + "/to-private");
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> factory().createRequest(start, HttpMethod.GET).execute());
    }

    @Test
    @DisplayName("a redirect that stays on an allowed host is followed as the JDK would")
    void followsAnAllowedRedirect() throws Exception {
        URI start = URI.create("http://localhost:" + port + "/to-allowed");
        try (ClientHttpResponse response = factory().createRequest(start, HttpMethod.GET).execute()) {
            assertEquals(200, response.getStatusCode().value());
            assertEquals("fine", new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
