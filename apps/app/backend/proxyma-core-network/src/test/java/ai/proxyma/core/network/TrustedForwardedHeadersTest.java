package ai.proxyma.core.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * editions-assembly-02, through a REAL Netty server: the forwarded handler is Reactor Netty's
 * callback, and the whole defect was what Netty does with it before anything of ours runs.
 *
 * <p>With {@code forward-headers-strategy=native}, Netty believed X-Forwarded-For from anybody and
 * took its FIRST entry as the client - the one the client wrote. Behind Cloudflare, which appends
 * the real address, every made-up address was a new sign-in bucket. ClientIpTest calls the rate
 * limiter's arithmetic directly and could never see that.
 */
class TrustedForwardedHeadersTest {

    private DisposableServer server;

    @AfterEach
    void stop() {
        if (server != null) server.disposeNow();
    }

    // Answers with what the application would see: the client address, the scheme and the host.
    private String seen(Predicate<InetAddress> trustedPeer, String... headers) throws Exception {
        server = HttpServer.create().host("127.0.0.1").port(0)
                .forwarded((info, request) -> TrustedForwardedHeaders.apply(info, request, trustedPeer))
                .handle((req, res) -> res.sendString(Mono.just(
                        req.remoteAddress().getAddress().getHostAddress() + "|" + req.scheme() + "|" + req.hostName())))
                .bindNow();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void behindAProxyTheClientIsTheEntryTheProxyAppended() throws Exception {
        // Cloudflare / cloudflared on loopback: the client wrote 8.8.8.8, the proxy appended the truth.
        String seen = seen(ApiRateLimitFilter::isProxyPeer,
                "X-Forwarded-For", "8.8.8.8, 203.0.113.9", "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "enterprise-demo.proxyma.ai");
        assertThat(seen).isEqualTo("203.0.113.9|https|enterprise-demo.proxyma.ai");
    }

    @Test
    void fromAnyoneElseNothingForwardedIsBelieved() throws Exception {
        // The same request from a peer that is not a proxy: the TCP peer, as it arrived. (Loopback
        // stands in for a public address here by declaring nothing trusted.)
        String seen = seen(peer -> false,
                "X-Forwarded-For", "8.8.8.8", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example",
                "Forwarded", "for=9.9.9.9;proto=https");
        assertThat(seen).startsWith("127.0.0.1|http|");
    }

    @Test
    void theStandardForwardedHeaderIsReadTheSameWay() throws Exception {
        String seen = seen(ApiRateLimitFilter::isProxyPeer,
                "Forwarded", "for=8.8.8.8;proto=http, for=\"[2001:db8::7]:4711\";proto=https;host=nas.example.com");
        assertThat(seen).isEqualTo("2001:db8:0:0:0:0:0:7|https|nas.example.com");
    }

    @Test
    void aNameIsNeverLookedUp() {
        assertThat(TrustedForwardedHeaders.literalAddress("unknown")).isNull();
        assertThat(TrustedForwardedHeaders.literalAddress("_hidden")).isNull();
        assertThat(TrustedForwardedHeaders.literalAddress("cafe.be")).isNull();
        assertThat(TrustedForwardedHeaders.literalAddress("203.0.113.9:5555").getHostAddress()).isEqualTo("203.0.113.9");
        assertThat(TrustedForwardedHeaders.literalAddress("[::1]:80").isLoopbackAddress()).isTrue();
    }
}
