package ai.proxyma.core.network;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.server.reactive.HttpHandler;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE SERVER RUNS ON TOMCAT, so forwarded headers are Tomcat's RemoteIpValve's to read (2026-11-12): the Netty
 * customizer in TrustedForwardedHeaders never ran. This pins that the valve trusts exactly the peers the rate limiter
 * trusts - not Boot's default list, which also trusted 100.64.0.0/10 - and, on a real Tomcat, that a request through
 * a trusted proxy is the LAST X-Forwarded-For address, never the first one a client wrote (SEC-48).
 */
class TrustedForwardedHeadersTomcatTest {

    @Test
    @DisplayName("the valve's internal proxies are exactly the rate limiter's proxy peers")
    void sameRuleAsTheRateLimiter() throws Exception {
        List<String> addresses = List.of(
                "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.50.20", "169.254.10.20",
                "::1", "fe80::1", "fd12:3456::1", "fc00::1",
                "8.8.8.8", "203.0.113.9", "172.15.0.1", "172.32.0.1", "100.64.0.1", "100.100.100.100",
                "11.0.0.1", "2001:db8::1", "fec0::1");
        for (String a : addresses) {
            InetAddress address = InetAddress.getByName(a);
            // Tomcat matches the address as getRemoteAddr writes it - Java's own text form.
            boolean valve = TrustedForwardedHeadersTomcat.INTERNAL.matcher(address.getHostAddress()).matches();
            boolean limiter = ApiRateLimitFilter.isProxyPeer(address);
            assertThat(valve).as(a + " (" + address.getHostAddress() + ")").isEqualTo(limiter);
        }
        assertThat(TrustedForwardedHeadersTomcat.INTERNAL.matcher("100.64.0.1").matches())
                .as("carrier-grade NAT is not ours").isFalse();
    }

    @Test
    @DisplayName("on a real Tomcat, through a trusted proxy, the client is the last X-Forwarded-For address")
    void lastEntryOnARealTomcat() throws Exception {
        TomcatReactiveWebServerFactory factory = new TomcatReactiveWebServerFactory(0);
        RemoteIpValve boots = new RemoteIpValve();   // as Spring Boot adds it under forward-headers-strategy=native
        boots.setRemoteIpHeader("X-Forwarded-For");
        boots.setProtocolHeader("X-Forwarded-Proto");
        factory.addEngineValves(boots);
        new TrustedForwardedHeadersTomcat().trustedForwardedHeadersTomcatCustomizer().customize(factory);
        assertThat(boots.getInternalProxies()).isEqualTo(TrustedForwardedHeadersTomcat.INTERNAL_PROXIES);

        HttpHandler echo = (request, response) -> {
            String who = request.getRemoteAddress() == null ? "?" : request.getRemoteAddress().getAddress().getHostAddress();
            return response.writeWith(Mono.just(response.bufferFactory().wrap((who + " " + request.getURI().getScheme())
                    .getBytes(StandardCharsets.UTF_8))));
        };
        WebServer server = factory.getWebServer(echo);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + server.getPort() + "/");
            // The test connects from loopback, which is a proxy peer - as cloudflared or Caddy is.
            String spoofed = client.send(HttpRequest.newBuilder(uri)
                    .header("X-Forwarded-For", "8.8.8.8, 203.0.113.9")
                    .header("X-Forwarded-Proto", "https").build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(spoofed).as("the address the proxy appended, not the one the client wrote")
                    .isEqualTo("203.0.113.9 https");

            String plain = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(plain).isEqualTo("127.0.0.1 http");
        } finally {
            server.stop();
        }
    }
}
